package com.zeny.hotel.audit

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL

/**
 * Permissões do módulo `audit` (RF-ACS-11): consultar a trilha exige permissão própria. Só o
 * Administrador a recebe de saída (nenhum outro modelo de role a sugere); ele a concede a quem
 * precisar.
 */
object AuditPermissions : ModulePermissions {
    override val module = "audit"

    /** Consultar a trilha de auditoria (`GET /audit-entries`). */
    val READ = PermissionCode("audit.read")

    override val permissions =
        listOf(
            PermissionDefinition(READ, "Consultar a trilha de auditoria", setOf(ALL)),
        )
}
