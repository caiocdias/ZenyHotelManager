package com.zeny.hotel.iam

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `iam` (RF-ACS-02): catálogo, roles, usuários e sessões de outros
 * usuários. As próprias sessões, a troca da própria senha e `GET /auth/me` não exigem permissão.
 * Gerenciar usuários e roles é ação sensível (RF-ACS-08). O Gerente consulta usuários, roles e o
 * catálogo e gere sessões; criar e editar usuários e roles fica com o Administrador (SPEC §3.1).
 */
object IamPermissions : ModulePermissions {
    override val module = "iam"

    /** Consultar o catálogo de permissões (`GET /permissions`). */
    val PERMISSIONS_READ = PermissionCode("iam.permissions.read")

    /** Consultar roles. */
    val ROLES_READ = PermissionCode("iam.roles.read")

    /** Criar, editar, duplicar e desativar roles (M1 E03). */
    val ROLES_MANAGE = PermissionCode("iam.roles.manage")

    /** Consultar usuários. */
    val USERS_READ = PermissionCode("iam.users.read")

    /** Criar, editar, desativar e reativar usuários, redefinir senha e PIN e atribuir roles (M1 E03). */
    val USERS_MANAGE = PermissionCode("iam.users.manage")

    /** Consultar e revogar sessões de outros usuários (M1 E03). */
    val SESSIONS_MANAGE = PermissionCode("iam.sessions.manage")

    private val manager = mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL))

    override val permissions =
        listOf(
            PermissionDefinition(
                PERMISSIONS_READ,
                "Consultar o catálogo de permissões",
                setOf(ALL),
                templates = manager,
            ),
            PermissionDefinition(ROLES_READ, "Consultar roles", setOf(ALL), templates = manager),
            PermissionDefinition(
                ROLES_MANAGE,
                "Criar, editar, duplicar e desativar roles",
                setOf(ALL),
                sensitive = true,
            ),
            PermissionDefinition(USERS_READ, "Consultar usuários", setOf(ALL), templates = manager),
            PermissionDefinition(
                USERS_MANAGE,
                "Criar, editar, desativar e reativar usuários, redefinir senha e PIN e atribuir roles",
                setOf(ALL),
                sensitive = true,
            ),
            PermissionDefinition(
                SESSIONS_MANAGE,
                "Consultar e revogar sessões de outros usuários",
                setOf(ALL),
                templates = manager,
            ),
        )
}
