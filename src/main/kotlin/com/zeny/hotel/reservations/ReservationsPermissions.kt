package com.zeny.hotel.reservations

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `reservations` (RF-ACS-02, SPEC §5.7, H16 do roadmap do M4). O inventário não tem dono, então só
 * aceita o escopo `ALL`.
 *
 * Inventário (E05): [AVAILABILITY_READ] consulta os contadores por tipo de UH e data (a disponibilidade com o
 * calendário e a cotação vêm na E06); [INVENTORY_MANAGE] configura o limite de overbooking por tipo e data (RF-TAR-09,
 * Q-09: nasce zero) e pede a recontagem dos contadores (não sensível; só o Gerente no modelo). Sugestões aos modelos
 * (SPEC §3.1): o Gerente, a Recepcionista, o Comercial/Eventos e o Financeiro consultam.
 */
object ReservationsPermissions : ModulePermissions {
    override val module = "reservations"

    /** Consultar o inventário e a disponibilidade por tipo de UH e data. */
    val AVAILABILITY_READ = PermissionCode("reservations.availability.read")

    /** Configurar o limite de overbooking por tipo de UH e data e recontar o inventário. */
    val INVENTORY_MANAGE = PermissionCode("reservations.inventory.manage")

    private val all = TemplateGrant(ALL)

    override val permissions =
        listOf(
            PermissionDefinition(
                AVAILABILITY_READ,
                "Consultar o inventário e a disponibilidade por tipo de UH e data",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.SALES_EVENTS to all,
                        RoleTemplate.FINANCE to all,
                    ),
            ),
            PermissionDefinition(
                INVENTORY_MANAGE,
                "Configurar o limite de overbooking por tipo de UH e data e recontar o inventário",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
        )
}
