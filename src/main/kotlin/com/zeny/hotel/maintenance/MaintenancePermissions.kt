package com.zeny.hotel.maintenance

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `maintenance` (RF-ACS-02, M6 E07, D1). Consultar e gerir as ordens de serviço aceitam `OWN`
 * (dona de uma ordem: quem a abriu e o usuário do colaborador responsável); abrir uma ordem só aceita `ALL`. Nenhuma é
 * sensível. Sugestões aos modelos (SPEC §3.1): o Gerente e a Manutenção têm as três com `ALL` (sem fila invisível: o
 * técnico vê e assume as ordens sem responsável); a Governanta e a Recepção abrem e acompanham (`request` e `read`
 * com `ALL`). O `OWN` existe para roles criadas pelo administrador.
 *
 * Os códigos usam `workorders` (sem hífen), porque o padrão do código de permissão só aceita letras e dígitos em cada
 * segmento.
 */
object MaintenancePermissions : ModulePermissions {
    override val module = "maintenance"

    /** Consultar as ordens de serviço (com `OWN`, só as que o usuário abriu ou de que é o responsável). */
    val READ = PermissionCode("maintenance.workorders.read")

    /** Abrir uma ordem de serviço. */
    val REQUEST = PermissionCode("maintenance.workorders.request")

    /** Editar, atribuir, agir e informar custos nas ordens (com `OWN`, só nas próprias). */
    val MANAGE = PermissionCode("maintenance.workorders.manage")

    private val technicians =
        listOf(
            RoleTemplate.MANAGER,
            RoleTemplate.MAINTENANCE,
        ).associateWith { TemplateGrant(ALL) }

    private val requesters =
        listOf(RoleTemplate.HOUSEKEEPER, RoleTemplate.RECEPTIONIST).associateWith {
            TemplateGrant(ALL)
        }

    override val permissions =
        listOf(
            PermissionDefinition(
                READ,
                "Consultar as ordens de serviço da manutenção (as próprias, com o escopo OWN)",
                setOf(OWN, ALL),
                templates = technicians + requesters,
            ),
            PermissionDefinition(
                REQUEST,
                "Abrir uma ordem de serviço da manutenção",
                setOf(ALL),
                templates = technicians + requesters,
            ),
            PermissionDefinition(
                MANAGE,
                "Editar, atribuir, agir e informar custos nas ordens de serviço (as próprias, com o escopo OWN)",
                setOf(OWN, ALL),
                templates = technicians,
            ),
        )
}
