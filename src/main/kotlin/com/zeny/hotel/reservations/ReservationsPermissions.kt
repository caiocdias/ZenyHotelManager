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
 *
 * Reservas (E07, H16): [RESERVATIONS_READ] consulta reservas agrupadoras e reservas (Gerente, Recepcionista,
 * Comercial/Eventos e Financeiro); [RESERVATIONS_MANAGE] as cria e, desde a E08, as altera e cancela (Gerente e
 * Recepcionista; não sensível). O desconto numa diária é a permissão sensível `rates.discounts.apply` do `rates` (H7),
 * com o limite `maxPercent`.
 *
 * Ciclo de vida (E08, H16): [PENALTIES_WAIVE] isenta a multa de cancelamento devida e [RESERVATIONS_REACTIVATE]
 * reativa uma reserva cancelada ou no-show, as duas **sensíveis** (RF-ACS-08), sem limite, só com o Gerente no modelo:
 * a Recepcionista pede a aprovação de um superior.
 */
object ReservationsPermissions : ModulePermissions {
    override val module = "reservations"

    /** Consultar o inventário e a disponibilidade por tipo de UH e data. */
    val AVAILABILITY_READ = PermissionCode("reservations.availability.read")

    /** Configurar o limite de overbooking por tipo de UH e data e recontar o inventário. */
    val INVENTORY_MANAGE = PermissionCode("reservations.inventory.manage")

    /** Consultar reservas agrupadoras e reservas. */
    val RESERVATIONS_READ = PermissionCode("reservations.reservations.read")

    /** Criar, alterar e cancelar reservas. */
    val RESERVATIONS_MANAGE = PermissionCode("reservations.reservations.manage")

    /** Isentar a multa de cancelamento (sensível). */
    val PENALTIES_WAIVE = PermissionCode("reservations.penalties.waive")

    /** Reativar uma reserva cancelada ou no-show (sensível). */
    val RESERVATIONS_REACTIVATE = PermissionCode("reservations.reservations.reactivate")

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
            PermissionDefinition(
                RESERVATIONS_READ,
                "Consultar reservas agrupadoras e reservas",
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
                RESERVATIONS_MANAGE,
                "Criar, alterar e cancelar reservas",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                PENALTIES_WAIVE,
                "Isentar a multa de cancelamento de uma reserva",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                RESERVATIONS_REACTIVATE,
                "Reativar uma reserva cancelada ou no-show",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
        )
}
