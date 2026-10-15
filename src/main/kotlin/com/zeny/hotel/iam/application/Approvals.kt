package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.Approval
import com.zeny.hotel.iam.domain.ApprovalBinding
import com.zeny.hotel.iam.domain.ApprovalId
import com.zeny.hotel.iam.domain.ApprovalRejection
import com.zeny.hotel.iam.domain.Logins
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant

/** Aprovações persistidas (só inserção e leitura: o papel da aplicação não altera nem apaga). */
interface ApprovalStore {
    fun add(approval: Approval)

    /** A aprovação cujo token tem este hash. */
    fun findByTokenHash(tokenHash: String): Approval?

    /** Se a aprovação já foi usada (por uma transação já confirmada). */
    fun isUsed(id: ApprovalId): Boolean

    /**
     * Registra o uso único da aprovação; devolve `false` se ela já foi usada. Com outra transação
     * usando a mesma aprovação ao mesmo tempo, espera ela terminar: se confirmou, `false`.
     */
    fun markUsed(
        id: ApprovalId,
        userId: UserId,
        sessionId: DeviceSessionId,
        at: Instant,
    ): Boolean
}

/** Recusa da aprovação de superior (`403`, código estável de [IamErrorCodes]). */
class ApprovalRefusedException(
    code: ErrorCode,
    message: String,
) : PermissionDeniedException(code, message)

/**
 * Pedido de aprovação: a operação recusada com `APPROVAL_REQUIRED`, o aprovador e o motivo. O PIN
 * nunca sai em `toString`.
 */
@Suppress("LongParameterList") // os campos do pedido (ARCHITECTURE §6.3)
class GrantApprovalCommand(
    val action: String,
    val permission: String,
    val resourceRef: String,
    val limit: LimitAmount?,
    val approverLogin: String,
    val approverPin: String,
    val reason: String,
) {
    override fun toString(): String = "GrantApprovalCommand(action=$action, permission=$permission)"
}

/** Uma aprovação concedida. O token nunca sai em `toString`. */
class GrantedApproval(
    val id: ApprovalId,
    val token: String,
    val approverId: UserId,
    val expiresAt: Instant,
) {
    override fun toString(): String = "GrantedApproval(id=$id, expiresAt=$expiresAt)"
}

/**
 * Concessão de aprovações de superior (RF-ACS-07, ARCHITECTURE §6.3): o solicitante (usuário
 * corrente) traz a operação recusada com `APPROVAL_REQUIRED`, o login e o PIN de outro usuário e o
 * motivo. Confere, nesta ordem: a permissão existe, é sensível e declara o limite pedido; o
 * aprovador existe e não é o solicitante; as aprovações dele não estão bloqueadas; o PIN (Argon2id)
 * confere; o aprovador, ativo, tem a permissão com limite para o valor pedido. O escopo `OWN` do
 * aprovador só é conferido no uso do token, quando o registro é conhecido.
 *
 * O PIN errado conta para um bloqueio progressivo **próprio** do aprovador (separado do login),
 * com a linha dele travada (as tentativas ficam em fila). As recusas confirmam a transação, para a
 * falha contada ficar gravada, e vão para a trilha como `REJECTED`.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do caso de uso
class ApprovalService(
    private val users: Users,
    private val catalog: PermissionStore,
    private val approvals: ApprovalStore,
    private val permissions: EffectivePermissionService,
    private val hasher: PasswordHasher,
    private val properties: IamProperties,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Hash de um PIN qualquer, para que login inexistente ou sem PIN leve o mesmo tempo que PIN errado. */
    private val decoyHash: String by lazy { hasher.hash(RefreshTokens.generate()) }

    @Audited("iam.approval.grant", recordRejections = true)
    @Transactional(noRollbackFor = [ApprovalRefusedException::class])
    fun grant(command: GrantApprovalCommand): GrantedApproval {
        val requester = currentActor.current()
        val now = clock.instant()
        val binding = bindingOf(command)
        val approver = Logins.normalize(command.approverLogin)?.let(users::findByLoginForUpdate)
        if (approver == null) {
            hasher.matches(command.approverPin, decoyHash)
            log.info("Aprovação recusada ao usuário {}: credenciais do aprovador inválidas", requester.userId)
            refuseApproval(IamErrorCodes.APPROVAL_CREDENTIALS_INVALID)
        }
        audit.track(IamAudit.USER, approver.id) { approver.auditState() }
        if (approver.id == requester.userId) refuseApproval(IamErrorCodes.APPROVER_IS_REQUESTER)
        if (approver.isPinLocked(now)) {
            log.info("Aprovação recusada: aprovações do usuário {} bloqueadas", approver.id)
            refuseApproval(IamErrorCodes.APPROVER_LOCKED)
        }
        val pinHash = approver.pinHash
        if (pinHash == null) {
            hasher.matches(command.approverPin, decoyHash)
            log.info("Aprovação recusada: o usuário {} não tem PIN", approver.id)
            refuseApproval(IamErrorCodes.APPROVAL_CREDENTIALS_INVALID)
        }
        if (!hasher.matches(command.approverPin, pinHash)) {
            approver.recordFailedPin(now, properties.approval.pinLockoutPolicy)
            log.info(
                "Aprovação recusada: PIN errado do usuário {} ({} falhas seguidas)",
                approver.id,
                approver.failedPinCount,
            )
            refuseApproval(IamErrorCodes.APPROVAL_CREDENTIALS_INVALID)
        }
        approver.recordSuccessfulPin(now)
        val granted = permissions.of(approver.id).of(binding.permission)
        if (granted == null || !granted.allows(command.limit)) {
            log.info("Aprovação recusada: o usuário {} não tem {} para aprovar", approver.id, binding.permission)
            refuseApproval(IamErrorCodes.APPROVER_NOT_ALLOWED)
        }
        val token = RefreshTokens.generate()
        val approval =
            Approval.grant(
                binding = binding,
                reason = command.reason,
                requesterId = requester.userId,
                requesterSessionId = requester.deviceSessionId,
                approverId = approver.id,
                tokenHash = RefreshTokens.hash(token),
                now = now,
            )
        approvals.add(approval)
        audit.created(IamAudit.APPROVAL, approval.id) { approval.auditState(use = null) }
        audit.approval(approval.id.value)
        log.info(
            "Aprovação {} de {} concedida pelo usuário {} ao usuário {}",
            approval.id,
            binding.action,
            approver.id,
            requester.userId,
        )
        return GrantedApproval(approval.id, token, approver.id, approval.expiresAt)
    }

    /** O vínculo pedido, se a permissão aceita aprovação (vigente, sensível e com o limite declarado). */
    private fun bindingOf(command: GrantApprovalCommand): ApprovalBinding {
        val definition =
            catalog.activeDefinitions().firstOrNull { it.code.value == command.permission }?.takeIf { it.sensitive }
                ?: notApplicable("Esta permissão não aceita aprovação de superior.")
        command.limit?.let { limit ->
            val declared = definition.limitOf(limit.key) ?: notApplicable("Esta permissão não tem o limite informado.")
            if (!declared.unit.accepts(limit.amount)) notApplicable("Valor inválido para o limite informado.")
        }
        return ApprovalBinding(
            action = command.action,
            permission = definition.code,
            resourceRef = command.resourceRef,
            limitKey = command.limit?.key,
            limitAmount = command.limit?.amount,
        )
    }

    private fun notApplicable(message: String): Nothing =
        throw BusinessRuleException(IamErrorCodes.APPROVAL_NOT_APPLICABLE, message)
}

/**
 * Consumo do token de aprovação pela porta `Authorization` (ARCHITECTURE §6.3), na transação do
 * caso de uso que pediu a ação sensível: confere o token (desconhecido, vínculo, validade, uso) e
 * que o aprovador ainda tem a permissão, no escopo do registro e com o limite do valor; registra o
 * uso único e marca a aprovação nas entradas de auditoria do caso de uso, com uma entrada da
 * própria aprovação (solicitante, aprovador, motivo e uso).
 */
@Component
class ApprovalTokenConsumer(
    private val approvals: ApprovalStore,
    private val permissions: EffectivePermissionService,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun consume(
        actor: Actor,
        action: SensitiveAction,
        token: String,
    ): SensitiveAuthorization.ByApproval {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "O token de aprovação de ${action.action} só é consumido numa transação de escrita (@Audited)" }
        val now = clock.instant()
        if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) refuseApproval(IamErrorCodes.APPROVAL_TOKEN_INVALID)
        val approval = approvals.findByTokenHash(RefreshTokens.hash(token))
        if (approval == null) {
            log.info("Token de aprovação desconhecido apresentado pelo usuário {}", actor.userId)
            refuseApproval(IamErrorCodes.APPROVAL_TOKEN_INVALID)
        }
        when (approval.rejectionFor(bindingOf(action), actor.userId, actor.deviceSessionId, now)) {
            ApprovalRejection.MISMATCH -> {
                log.info("Aprovação {} apresentada para outra operação pelo usuário {}", approval.id, actor.userId)
                refuseApproval(IamErrorCodes.APPROVAL_TOKEN_MISMATCH)
            }

            ApprovalRejection.EXPIRED -> {
                refuseApproval(IamErrorCodes.APPROVAL_TOKEN_EXPIRED)
            }

            null -> {
                Unit
            }
        }
        if (approvals.isUsed(approval.id)) refuseApproval(IamErrorCodes.APPROVAL_TOKEN_USED)
        val granted = permissions.of(approval.approverId).of(action.permission)
        if (granted == null || !granted.covers(action, approval.approverId)) {
            log.info(
                "Aprovação {} recusada: o aprovador {} não tem mais {}",
                approval.id,
                approval.approverId,
                action.permission,
            )
            refuseApproval(IamErrorCodes.APPROVER_NOT_ALLOWED)
        }
        if (!approvals.markUsed(approval.id, actor.userId, actor.deviceSessionId, now)) {
            refuseApproval(IamErrorCodes.APPROVAL_TOKEN_USED)
        }
        audit.approval(approval.id.value)
        audit.record(
            IamAudit.APPROVAL,
            approval.id,
            approval.auditState(use = null),
            approval.auditState(use = ApprovalUse(now, actor.deviceSessionId)),
        )
        log.info("Aprovação {} usada pelo usuário {} em {}", approval.id, actor.userId, action.action)
        return SensitiveAuthorization.ByApproval(approval.id.value, approval.approverId)
    }

    private companion object {
        const val MAX_TOKEN_LENGTH = 200
    }
}

/** O vínculo de uma ação sensível pedida. */
internal fun bindingOf(action: SensitiveAction) =
    ApprovalBinding(
        action = action.action,
        permission = action.permission,
        resourceRef = action.resourceRef,
        limitKey = action.limit?.key,
        limitAmount = action.limit?.amount,
    )

/**
 * Se a concessão cobre a ação de [userId]: o escopo alcança o registro (com `ALL`, ou com `OWN`
 * sendo dono; sem registro, qualquer escopo) e o limite admite o valor pedido.
 */
internal fun GrantedPermission.covers(
    action: SensitiveAction,
    userId: UserId,
): Boolean = reaches(action, userId) && allows(action.limit)

/** Se o escopo da concessão alcança o registro da ação. */
internal fun GrantedPermission.reaches(
    action: SensitiveAction,
    userId: UserId,
): Boolean = scope == PermissionScope.ALL || action.resource?.let { userId in it.ownerIds } ?: true

/** Se o limite da concessão admite o valor pedido (sem valor pedido, sempre). */
internal fun GrantedPermission.allows(requested: LimitAmount?): Boolean =
    requested == null || limit(requested.key).allows(requested.amount)

/** Recusa da aprovação com a mensagem padrão do código. */
internal fun refuseApproval(code: ErrorCode): Nothing =
    throw ApprovalRefusedException(code, APPROVAL_MESSAGES.getValue(code))

private val APPROVAL_MESSAGES: Map<ErrorCode, String> =
    mapOf(
        IamErrorCodes.APPROVAL_CREDENTIALS_INVALID to "Login ou PIN do aprovador incorretos.",
        IamErrorCodes.APPROVER_LOCKED to
            "As aprovações deste usuário estão bloqueadas temporariamente por PIN errado. Tente mais tarde.",
        IamErrorCodes.APPROVER_IS_REQUESTER to "A aprovação precisa ser de outro usuário.",
        IamErrorCodes.APPROVER_NOT_ALLOWED to "O aprovador não tem permissão para aprovar esta ação.",
        IamErrorCodes.APPROVAL_TOKEN_INVALID to "Aprovação inválida. Peça uma nova aprovação.",
        IamErrorCodes.APPROVAL_TOKEN_EXPIRED to "A aprovação venceu. Peça uma nova aprovação.",
        IamErrorCodes.APPROVAL_TOKEN_USED to "Esta aprovação já foi usada. Peça uma nova aprovação.",
        IamErrorCodes.APPROVAL_TOKEN_MISMATCH to "Esta aprovação é de outra operação. Peça uma nova aprovação.",
    )
