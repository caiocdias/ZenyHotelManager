package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.IamPermissions
import com.zeny.hotel.iam.domain.EffectivePermissions
import com.zeny.hotel.iam.domain.Grant
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/** Um limite pedido numa concessão: o teto ([max]) ou sem teto ([unlimited]), exatamente um dos dois. */
data class LimitCommand(
    val key: String,
    val unlimited: Boolean,
    val max: BigDecimal?,
)

/** Uma concessão pedida: a permissão do catálogo, o escopo e os limites. */
data class GrantCommand(
    val permission: String,
    val scope: PermissionScope,
    val limits: List<LimitCommand>,
)

/** Os dados de uma role: nome, descrição e o conjunto completo das concessões. */
data class RoleCommand(
    val name: String,
    val description: String,
    val grants: List<GrantCommand>,
)

/**
 * Gestão de roles pelo administrador (RF-ACS-03 e 09). Exige `iam.roles.manage`. Cada concessão
 * que passa a valer precisa estar coberta pelas permissões do ator ([DelegationRules]): as
 * acrescentadas ou mudadas numa edição (inclusive da própria role), todas as de uma role
 * criada, duplicada ou reativada. Na edição e na reativação, também a união resultante de cada
 * usuário que tem a role ([DelegationRules.requireResultingUnionsHeld]); a role duplicada nasce
 * sem usuários. Desativar e retirar concessões não concede nada. A role de
 * sistema Administrador não é editada, desativada nem reativada (`422 SYSTEM_ROLE_IMMUTABLE`).
 * Nada é apagado: a role desativada continua, sem conceder nada. Toda escrita começa pela
 * [AuthorizationLock].
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // os casos de uso da gestão de roles e os colaboradores deles
class RoleManagementService(
    private val roles: Roles,
    private val directory: RoleDirectory,
    private val rules: DelegationRules,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val lock: AuthorizationLock,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Audited("iam.role.create")
    @Transactional
    fun create(command: RoleCommand): RoleView {
        val actor = begin()
        val catalog = rules.catalog()
        val grants = parse(command.grants, catalog)
        rules.requireHeld(actor, grants, catalog)
        val now = clock.instant()
        val role = Role.create(command.name, command.description, now)
        grants.forEach { role.grant(definitionOf(it, catalog), it.scope, it.limits, now) }
        audit.created(IamAudit.ROLE, role.id) { role.auditState() }
        if (!roles.tryAdd(role)) throw nameInUse()
        log.info("Role {} criada pelo usuário {}", role.id, actor.userId)
        return view(role.id)
    }

    /**
     * Troca nome, descrição e concessões (as de permissões obsoletas ficam como estão). Só as
     * concessões acrescentadas ou mudadas passam pela regra de delegação.
     */
    @Audited("iam.role.update")
    @Transactional
    fun update(
        roleId: UUID,
        expectedVersion: Long,
        command: RoleCommand,
    ): RoleView {
        val id = RoleId(roleId)
        val actor = begin()
        val role = tracked(find(id))
        requireEditable(role)
        requireCurrentVersion(expectedVersion, checkNotNull(role.version))
        val catalog = rules.catalog()
        val requested = parse(command.grants, catalog).associateBy { it.permission }
        val current = role.grantValues().filter { it.permission in catalog }.associateBy { it.permission }
        val changed = requested.values.filter { current[it.permission] != it }
        rules.requireHeld(actor, changed, catalog)
        val holders = holdersBefore(id)
        val now = clock.instant()
        role.rename(command.name, command.description, now)
        (current.keys - requested.keys).forEach { role.revoke(it, now) }
        changed.forEach { role.grant(definitionOf(it, catalog), it.scope, it.limits, now) }
        // Ainda sem flush: as roles dos usuários lidas acima são o "antes", e a role em memória, o "depois".
        rules.requireResultingUnionsHeld(actor, holders.map { it.with(role) }, catalog)
        if (!roles.tryFlush()) throw nameInUse()
        log.info("Role {} editada pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /** Cria uma role nova com as concessões vigentes de outra (inclusive da role Administrador). */
    @Audited("iam.role.duplicate")
    @Transactional
    fun duplicate(
        roleId: UUID,
        name: String,
        description: String?,
    ): RoleView {
        val id = RoleId(roleId)
        val actor = begin()
        val source = find(id)
        val catalog = rules.catalog()
        val grants =
            EffectivePermissions
                .of(
                    source.grantValues(),
                    catalog,
                ).all
                .map { Grant(it.code, it.scope, it.limits) }
        rules.requireHeld(actor, grants, catalog)
        val now = clock.instant()
        val copy = Role.create(name, description ?: source.description, now)
        grants.forEach { copy.grant(definitionOf(it, catalog), it.scope, it.limits, now) }
        audit.created(IamAudit.ROLE, copy.id) { copy.auditState() }
        if (!roles.tryAdd(copy)) throw nameInUse()
        log.info("Role {} duplicada como {} pelo usuário {}", id, copy.id, actor.userId)
        return view(copy.id)
    }

    /** A role deixa de conceder permissão a quem a tem. Desativar de novo não muda nada. */
    @Audited("iam.role.deactivate")
    @Transactional
    fun deactivate(roleId: UUID): RoleView {
        val id = RoleId(roleId)
        val actor = begin()
        val role = tracked(find(id))
        requireEditable(role)
        role.deactivate(clock.instant())
        roles.tryFlush()
        log.info("Role {} desativada pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /** As concessões da role voltam a valer: o ator precisa cobri-las. */
    @Audited("iam.role.reactivate")
    @Transactional
    fun reactivate(roleId: UUID): RoleView {
        val id = RoleId(roleId)
        val actor = begin()
        val role = tracked(find(id))
        requireEditable(role)
        val catalog = rules.catalog()
        rules.requireHeld(actor, role.grantValues(), catalog)
        val holders = holdersBefore(id)
        role.reactivate(clock.instant())
        rules.requireResultingUnionsHeld(actor, holders.map { it.with(role) }, catalog)
        roles.tryFlush()
        log.info("Role {} reativada pelo usuário {}", id, actor.userId)
        return view(id)
    }

    /** As roles de cada usuário que tem a role, antes da operação. */
    private fun holdersBefore(id: RoleId): List<Map<RoleId, RoleGrants>> = rules.holdersOf(id).map(rules::rolesOf)

    /** A mudança de um usuário que tem a role: o "antes" e o mesmo com a role como está agora em memória. */
    private fun Map<RoleId, RoleGrants>.with(role: Role) =
        RolesChange(before = this, after = this + (role.id to RoleGrants(role.active, role.grantValues())))

    private fun begin(): Actor {
        lock.acquire()
        val actor = currentActor.current()
        authorization.require(IamPermissions.ROLES_MANAGE)
        return actor
    }

    /**
     * Converte e valida as concessões pedidas contra o catálogo vigente: a permissão existe, não
     * se repete, o escopo é suportado e cada limite é declarado, tem exatamente um teto ou a marca
     * "sem teto" e cabe na unidade.
     */
    private fun parse(
        commands: List<GrantCommand>,
        catalog: PermissionCatalog,
    ): List<Grant> {
        val seen = mutableSetOf<PermissionCode>()
        return commands.map { command ->
            val code = runCatching { PermissionCode(command.permission) }.getOrNull()
            val definition =
                code?.let(catalog::definitionOf)
                    ?: throw InvalidInputException(
                        IamErrorCodes.UNKNOWN_PERMISSION,
                        "A permissão ${command.permission} não existe no catálogo.",
                    )
            if (!seen.add(definition.code)) invalidGrant("A permissão ${definition.code} aparece mais de uma vez.")
            if (command.limits
                    .map { it.key }
                    .toSet()
                    .size != command.limits.size
            ) {
                invalidGrant("A permissão ${definition.code} tem um limite repetido.")
            }
            val limits = command.limits.associate { it.key to limitValue(it) }
            try {
                Role.validate(definition, command.scope, limits)
            } catch (e: IllegalArgumentException) {
                invalidGrant(e.message ?: "Concessão inválida para ${definition.code}.")
            }
            Grant(definition.code, command.scope, limits)
        }
    }

    private fun limitValue(command: LimitCommand): LimitValue =
        when {
            command.unlimited && command.max == null -> {
                LimitValue.Unlimited
            }

            !command.unlimited && command.max != null && command.max.signum() >= 0 -> {
                LimitValue.Max(command.max)
            }

            else -> {
                invalidGrant(
                    "O limite '${command.key}' precisa de um teto (max) ou da marca unlimited, não dos dois.",
                )
            }
        }

    private fun invalidGrant(message: String): Nothing =
        throw InvalidInputException(IamErrorCodes.INVALID_GRANT, message)

    private fun definitionOf(
        grant: Grant,
        catalog: PermissionCatalog,
    ) = checkNotNull(catalog.definitionOf(grant.permission)) { "Permissão fora do catálogo: ${grant.permission}" }

    private fun requireEditable(role: Role) {
        if (role.systemRole) {
            throw BusinessRuleException(
                IamErrorCodes.SYSTEM_ROLE_IMMUTABLE,
                "A role Administrador é mantida pelo sistema, com todas as permissões, e não pode ser alterada.",
            )
        }
    }

    private fun find(id: RoleId): Role = roles.findById(id) ?: throw roleNotFound()

    /** Acompanha a role na trilha: o retrato de agora é o "antes"; o do fim do caso de uso, o "depois". */
    private fun tracked(role: Role): Role = role.also { audit.track(IamAudit.ROLE, it.id) { it.auditState() } }

    private fun view(id: RoleId): RoleView = checkNotNull(directory.find(id))

    private fun nameInUse() =
        ConflictException(IamErrorCodes.ROLE_NAME_ALREADY_IN_USE, "Já existe uma role com este nome.")
}

/** Consulta de roles para a gestão (exige `iam.roles.read`). */
@Service
class RoleQueries(
    private val directory: RoleDirectory,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun list(page: PageParams): CursorPage<RoleView> {
        authorization.require(IamPermissions.ROLES_READ)
        val rows = directory.page(page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.value.toString()) }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): RoleView {
        authorization.require(IamPermissions.ROLES_READ)
        return directory.find(RoleId(id)) ?: throw roleNotFound()
    }
}

private fun roleNotFound() = NotFoundException(IamErrorCodes.ROLE_NOT_FOUND, "Role não encontrada.")
