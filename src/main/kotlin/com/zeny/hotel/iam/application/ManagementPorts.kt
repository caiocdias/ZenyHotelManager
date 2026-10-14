package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.util.UUID

/** Uma role atribuída a um usuário, como o administrador a vê. */
data class UserRoleView(
    val id: RoleId,
    val name: String,
    val active: Boolean,
)

/**
 * Um usuário como o administrador o vê (RF-ACS-01). Nunca leva senha nem PIN, só se há PIN.
 * [version] é a do agregado, para o `ETag`.
 */
data class UserView(
    val id: UserId,
    val name: String,
    val login: String,
    val active: Boolean,
    /** Se tem a role de sistema Administrador. */
    val administrator: Boolean,
    val hasPin: Boolean,
    val passwordChangeRequired: Boolean,
    /** Até quando o login está bloqueado por tentativas; nulo se não está. */
    val lockedUntil: Instant?,
    val roles: List<UserRoleView>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    /** O bloqueio só aparece enquanto vale em [now]. */
    fun at(now: Instant): UserView = copy(lockedUntil = lockedUntil?.takeIf { it.isAfter(now) })
}

/** Limites dos campos de usuário e role, para a validação da API. */
object ManagementLimits {
    const val USER_NAME_MAX_LENGTH = User.MAX_NAME_LENGTH
    const val ROLE_NAME_MAX_LENGTH = Role.MAX_NAME_LENGTH
    const val ROLE_DESCRIPTION_MAX_LENGTH = Role.MAX_DESCRIPTION_LENGTH
}

/** Leitura dos usuários para a gestão (`JdbcClient`, ARCHITECTURE §11). */
interface UserDirectory {
    fun find(id: UserId): UserView?

    /** Uma página em ordem de login, depois de [afterLogin]. */
    fun page(
        afterLogin: String?,
        limit: Int,
    ): List<UserView>
}

/**
 * Uma role como o administrador a vê (RF-ACS-03): só as concessões de permissões vigentes (as
 * de permissões obsoletas continuam gravadas, mas não concedem nada nem aparecem).
 */
data class RoleView(
    val id: RoleId,
    val name: String,
    val description: String,
    val active: Boolean,
    val template: RoleTemplate?,
    val systemRole: Boolean,
    val customized: Boolean,
    /** Em ordem de código da permissão. */
    val grants: List<GrantedPermission>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Leitura das roles para a gestão (`JdbcClient`, ARCHITECTURE §11). */
interface RoleDirectory {
    fun find(id: RoleId): RoleView?

    /** Uma página em ordem de criação (id UUIDv7), depois de [afterId]. */
    fun page(
        afterId: UUID?,
        limit: Int,
    ): List<RoleView>
}
