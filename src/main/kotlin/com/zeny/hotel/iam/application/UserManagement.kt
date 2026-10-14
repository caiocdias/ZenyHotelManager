package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.IamPermissions
import com.zeny.hotel.iam.domain.Logins
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Criação de um usuário pelo administrador. Senha e PIN nunca aparecem em `toString`. */
class CreateUserCommand(
    val name: String,
    val login: String,
    val password: String,
    val pin: String?,
    val roleIds: Set<UUID>,
) {
    override fun toString(): String = "CreateUserCommand(roleIds=$roleIds)"
}

/** Regras do PIN de aprovação (RF-ACS-01): só dígitos, de 4 a 8. */
object PinRules {
    const val PATTERN = "\\d{4,8}"
    private val VALID = Regex(PATTERN)

    fun isValid(pin: String): Boolean = VALID.matches(pin)
}

/**
 * Gestão de usuários pelo administrador (RF-ACS-01, 09, 10 e 12). Exige `iam.users.manage` e
 * aplica as [DelegationRules]: só administrador gere administrador e dá ou tira a role
 * Administrador; ninguém atribui role com permissão, escopo ou limite acima dos seus; redefinir
 * senha ou PIN e reativar exigem cobrir as permissões do usuário. O último administrador ativo
 * não é desativado nem perde a role. Nada é apagado: desativar revoga todas as sessões, e o
 * usuário perde as permissões já na requisição seguinte.
 *
 * As escritas que mexem em autorização ou dependem dela (criação, roles, desativação,
 * reativação, redefinição de senha e PIN) começam pela [AuthorizationLock]: uma edição de role
 * simultânea não amplia o usuário entre a conferência das regras e a gravação. As que travam o
 * usuário o travam antes das sessões dele, na mesma ordem do login. Só a senha definida aqui é
 * provisória (o usuário a troca no primeiro login); o PIN não.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // os casos de uso da gestão e os colaboradores deles
class UserManagementService(
    private val users: Users,
    private val roles: Roles,
    private val sessions: DeviceSessions,
    private val directory: UserDirectory,
    private val data: AuthorizationData,
    private val rules: DelegationRules,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val lock: AuthorizationLock,
    private val hasher: PasswordHasher,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Audited("iam.user.create")
    @Transactional
    fun create(command: CreateUserCommand): UserView {
        lock.acquire()
        val actor = actor()
        val login = Logins.normalize(command.login) ?: throw invalid("login", "Login inválido.")
        requirePasswordRules(command.password, "password")
        command.pin?.let { requirePinRules(it, "pin") }
        val now = clock.instant()
        val roleIds = command.roleIds.map(::RoleId).toSet()
        checkRoleChanges(actor, userId = null, requested = roleIds)
        val user =
            User.create(
                name = command.name,
                login = login,
                passwordHash = hasher.hash(command.password),
                passwordChangeRequired = true,
                now = now,
            )
        command.pin?.let { user.resetPin(hasher.hash(it), now) }
        roleIds.forEach { user.assignRole(it, now) }
        audit.created(IamAudit.USER, user.id) { user.auditState() }
        if (!users.tryAdd(user)) {
            throw ConflictException(IamErrorCodes.LOGIN_ALREADY_IN_USE, "Já existe um usuário com este login.")
        }
        log.info("Usuário {} criado pelo usuário {}", user.id, actor.userId)
        return view(user.id)
    }

    /** Edita o nome (o login não muda: identifica o usuário na auditoria). */
    @Audited("iam.user.rename")
    @Transactional
    fun rename(
        id: UserId,
        expectedVersion: Long,
        name: String,
    ): UserView {
        val actor = actor()
        val user = tracked(find(id))
        rules.requireCanManage(actor, id)
        requireCurrentVersion(expectedVersion, versionOf(user))
        user.rename(name, clock.instant())
        users.flush()
        return view(id)
    }

    /** Troca o conjunto de roles do usuário. */
    @Audited("iam.user.set-roles")
    @Transactional
    fun setRoles(
        id: UserId,
        expectedVersion: Long,
        requestedRoleIds: Set<UUID>,
    ): UserView {
        val roleIds = requestedRoleIds.map(::RoleId).toSet()
        lock.acquire()
        val actor = actor()
        val user = tracked(find(id))
        rules.requireCanManage(actor, id)
        requireCurrentVersion(expectedVersion, versionOf(user))
        val removesAdministrator = checkRoleChanges(actor, userId = id, requested = roleIds)
        if (removesAdministrator && user.active) requireAnotherActiveAdministrator()
        val now = clock.instant()
        (user.roleIds - roleIds).forEach { user.unassignRole(it, now) }
        (roleIds - user.roleIds).forEach { user.assignRole(it, now) }
        users.flush()
        log.info("Roles do usuário {} trocadas pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /** Desativa o usuário e revoga todas as sessões dele (RF-ACS-10). Desativar de novo não muda nada. */
    @Audited("iam.user.deactivate")
    @Transactional
    fun deactivate(id: UserId): UserView {
        lock.acquire()
        val actor = actor()
        val user = tracked(findForUpdate(id))
        rules.requireCanManage(actor, id)
        if (user.active && rules.isAdministrator(id)) requireAnotherActiveAdministrator()
        val now = clock.instant()
        user.deactivate(now)
        revokeAllSessions(user, SessionRevocationReason.USER_DISABLED)
        users.flush()
        log.info("Usuário {} desativado pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /** Reativa o usuário; as sessões revogadas não voltam (ele entra de novo). */
    @Audited("iam.user.reactivate")
    @Transactional
    fun reactivate(id: UserId): UserView {
        lock.acquire()
        val actor = actor()
        val user = tracked(findForUpdate(id))
        rules.requireCanManage(actor, id)
        rules.requireHoldsWhatUserHas(actor, id)
        user.reactivate(clock.instant())
        users.flush()
        log.info("Usuário {} reativado pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /**
     * Define uma senha provisória: o usuário a troca no próximo login, o bloqueio por tentativas
     * é zerado e todas as sessões dele caem.
     */
    @Audited("iam.user.reset-password")
    @Transactional
    fun resetPassword(
        id: UserId,
        password: String,
    ) {
        lock.acquire()
        val actor = actor()
        requirePasswordRules(password, "newPassword")
        val user = tracked(findForUpdate(id))
        rules.requireCanManage(actor, id)
        rules.requireHoldsWhatUserHas(actor, id)
        user.resetPassword(hasher.hash(password), clock.instant())
        revokeAllSessions(user, SessionRevocationReason.PASSWORD_RESET)
        log.info("Senha do usuário {} redefinida pelo usuário {}", id, actor.userId)
    }

    /** Define o PIN de aprovação do usuário (RF-ACS-01). */
    @Audited("iam.user.reset-pin")
    @Transactional
    fun resetPin(
        id: UserId,
        pin: String,
    ) {
        lock.acquire()
        val actor = actor()
        requirePinRules(pin, "newPin")
        val user = tracked(findForUpdate(id))
        rules.requireCanManage(actor, id)
        rules.requireHoldsWhatUserHas(actor, id)
        user.resetPin(hasher.hash(pin), clock.instant())
        log.info("PIN do usuário {} redefinido pelo usuário {}", id, actor.userId)
    }

    /** O ator corrente, com a permissão de gestão de usuários. */
    private fun actor(): Actor {
        val actor = currentActor.current()
        authorization.require(IamPermissions.USERS_MANAGE)
        return actor
    }

    /**
     * Confere a troca das roles do usuário [userId] (nulo: usuário novo, sem roles) para
     * [requested]: todas existem, a role Administrador só é dada ou tirada por administrador, as
     * roles acrescentadas não concedem nada acima do que o ator tem (ativas ou não: reativá-las
     * depois não pode virar atalho) e a união resultante não amplia nenhuma permissão além das do
     * ator ([DelegationRules.requireResultingUnionsHeld]). Devolve se a role Administrador sai.
     */
    private fun checkRoleChanges(
        actor: Actor,
        userId: UserId?,
        requested: Set<RoleId>,
    ): Boolean {
        val found = roles.findAllById(requested)
        if (found.size != requested.size) {
            throw InvalidInputException(IamErrorCodes.UNKNOWN_ROLE, "Uma das roles informadas não existe.")
        }
        val before = userId?.let(rules::rolesOf).orEmpty()
        val administrator = administratorRole().id
        val added = found.filter { it.id !in before.keys }
        val removesAdministrator = administrator in before.keys && administrator !in requested
        if (removesAdministrator || added.any(Role::systemRole)) rules.requireAdministrator(actor)
        val catalog = rules.catalog()
        rules.requireHeld(actor, added.flatMap(Role::grantValues), catalog)
        val after =
            before.filterKeys { it in requested } + added.associate { it.id to RoleGrants(it.active, it.grantValues()) }
        rules.requireResultingUnionsHeld(actor, listOf(RolesChange(before, after)), catalog)
        return removesAdministrator
    }

    private fun requireAnotherActiveAdministrator() {
        if (data.activeAdministratorCount() <= 1) {
            throw ConflictException(
                IamErrorCodes.LAST_ADMINISTRATOR,
                "Este é o último administrador ativo: o sistema ficaria sem quem o administre.",
            )
        }
    }

    private fun revokeAllSessions(
        user: User,
        reason: SessionRevocationReason,
    ) {
        val now = clock.instant()
        sessions.findNotRevokedOf(user.id).forEach {
            audit.track(IamAudit.SESSION, it.id) { it.auditState() }
            it.revoke(reason, now)
        }
    }

    /** Acompanha o usuário na trilha: o retrato de agora é o "antes"; o do fim do caso de uso, o "depois". */
    private fun tracked(user: User): User = user.also { audit.track(IamAudit.USER, it.id) { it.auditState() } }

    private fun administratorRole(): Role =
        checkNotNull(roles.findByTemplate(RoleTemplate.ADMINISTRATOR)) { "Role Administrador não encontrada" }

    private fun find(id: UserId): User = users.findById(id) ?: throw userNotFound()

    private fun findForUpdate(id: UserId): User = users.findByIdForUpdate(id) ?: throw userNotFound()

    private fun view(id: UserId): UserView = checkNotNull(directory.find(id)).at(clock.instant())

    private fun requirePasswordRules(
        password: String,
        field: String,
    ) {
        if (password.length !in PasswordRules.LENGTH) {
            throw invalid(
                field,
                "A senha precisa ter de ${PasswordRules.MIN_LENGTH} a ${PasswordRules.MAX_LENGTH} caracteres.",
            )
        }
    }

    private fun requirePinRules(
        pin: String,
        field: String,
    ) {
        if (!PinRules.isValid(pin)) throw invalid(field, "O PIN precisa ter de 4 a 8 dígitos.")
    }

    private fun invalid(
        field: String,
        message: String,
    ) = InvalidInputException(
        ErrorCodes.VALIDATION_FAILED,
        message,
        listOf(FieldViolation(field, FieldViolationCodes.INVALID_FORMAT, message)),
    )
}

/** Consulta de usuários para a gestão (exige `iam.users.read`). */
@Service
class UserQueries(
    private val directory: UserDirectory,
    private val authorization: Authorization,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(page: PageParams): CursorPage<UserView> {
        authorization.require(IamPermissions.USERS_READ)
        val rows = directory.page(page.decodedCursor()?.string(0), page.limit + 1)
        val now = clock.instant()
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.login) }.map { it.at(now) }
    }

    @Transactional(readOnly = true)
    fun get(id: UserId): UserView {
        authorization.require(IamPermissions.USERS_READ)
        return (directory.find(id) ?: throw userNotFound()).at(clock.instant())
    }
}

/** Versão de um agregado já carregado do banco. */
internal fun versionOf(user: User): Long = checkNotNull(user.version) { "Usuário sem versão: ${user.id}" }

internal fun userNotFound() = NotFoundException(IamErrorCodes.USER_NOT_FOUND, "Usuário não encontrado.")
