package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamPermissions
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Consulta do catálogo de permissões vigente, para a tela de roles (exige `iam.permissions.read`). */
@Service
class PermissionCatalogQueries(
    private val store: PermissionStore,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun list(page: PageParams): CursorPage<PermissionDefinition> {
        authorization.require(IamPermissions.PERMISSIONS_READ)
        val rows = store.activePage(page.decodedCursor()?.string(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.code.value) }
    }
}

/** O usuário corrente, com as roles ativas e as permissões efetivas. */
data class CurrentUserView(
    val userId: UserId,
    val name: String,
    val login: String,
    val roles: List<AssignedRole>,
    val permissions: List<GrantedPermission>,
)

/** `GET /auth/me`: quem é o usuário corrente e o que ele pode fazer agora (sem permissão própria). */
@Service
class CurrentUserService(
    private val users: Users,
    private val data: AuthorizationData,
    private val permissions: EffectivePermissionService,
) {
    @Transactional(readOnly = true)
    fun describe(actor: Actor): CurrentUserView {
        val user = users.findById(actor.userId) ?: error("Usuário do token não existe: ${actor.userId}")
        return CurrentUserView(
            userId = user.id,
            name = user.name,
            login = user.login,
            roles = if (user.active) data.activeRolesOf(user.id) else emptyList(),
            permissions = permissions.of(user.id).all,
        )
    }
}
