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
 * Comercial/Eventos e Financeiro); [RESERVATIONS_MANAGE] as cria e, desde a E08, as altera e cancela e, desde a E09,
 * confirma a pré-reserva com a garantia (Gerente e Recepcionista; não sensível). O desconto numa diária é a permissão
 * sensível `rates.discounts.apply` do `rates` (H7), com o limite `maxPercent`.
 *
 * Ciclo de vida (E08, H16): [PENALTIES_WAIVE] isenta a multa de cancelamento devida e [RESERVATIONS_REACTIVATE] reativa
 * uma reserva cancelada ou no-show, as duas **sensíveis** (RF-ACS-08), sem limite, só com o Gerente no modelo: a
 * Recepcionista pede a aprovação de um superior.
 *
 * Alocação de UH (E10, H16): [ASSIGNMENTS_MANAGE] aloca, realoca e desaloca a UH de uma reserva futura, marca "não
 * mover" e consulta as sugestões de UH (Gerente e Recepcionista; não sensível); quem a tem recebe a notificação de
 * realocação quando um OOO atinge reservas alocadas (H11). A lista das reservas que requerem realocação e o histórico
 * das alocações se consultam com [RESERVATIONS_READ].
 *
 * Grupos e bloqueios (E11, H16): [BLOCKS_READ] consulta os bloqueios com as unidades e o pick-up (Gerente,
 * Recepcionista, Comercial/Eventos e Financeiro); [BLOCKS_MANAGE] cria e altera bloqueios (o contrato com o grupo:
 * Gerente e Comercial/Eventos); [BLOCKS_PICK_UP] converte unidades do bloqueio em reservas, uma a uma ou pela rooming
 * list (Gerente, Recepcionista e Comercial/Eventos). Nenhuma é sensível.
 */
object ReservationsPermissions : ModulePermissions {
    override val module = "reservations"

    /** Consultar o inventário e a disponibilidade por tipo de UH e data. */
    val AVAILABILITY_READ = PermissionCode("reservations.availability.read")

    /** Configurar o limite de overbooking por tipo de UH e data e recontar o inventário. */
    val INVENTORY_MANAGE = PermissionCode("reservations.inventory.manage")

    /** Consultar reservas agrupadoras e reservas. */
    val RESERVATIONS_READ = PermissionCode("reservations.reservations.read")

    /** Criar, alterar, confirmar e cancelar reservas. */
    val RESERVATIONS_MANAGE = PermissionCode("reservations.reservations.manage")

    /** Isentar a multa de cancelamento (sensível). */
    val PENALTIES_WAIVE = PermissionCode("reservations.penalties.waive")

    /** Reativar uma reserva cancelada ou no-show (sensível). */
    val RESERVATIONS_REACTIVATE = PermissionCode("reservations.reservations.reactivate")

    /** Alocar, realocar e desalocar UH de reservas futuras e marcar "não mover". */
    val ASSIGNMENTS_MANAGE = PermissionCode("reservations.assignments.manage")

    /** Consultar bloqueios de grupo, com as unidades e o pick-up. */
    val BLOCKS_READ = PermissionCode("reservations.blocks.read")

    /** Criar e alterar bloqueios de grupo. */
    val BLOCKS_MANAGE = PermissionCode("reservations.blocks.manage")

    /** Converter unidades de um bloqueio em reservas (pick-up e rooming list). */
    val BLOCKS_PICK_UP = PermissionCode("reservations.blocks.pickup")

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
                "Criar, alterar, confirmar (com a garantia) e cancelar reservas",
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
            PermissionDefinition(
                ASSIGNMENTS_MANAGE,
                "Alocar, realocar e desalocar a UH de reservas futuras e marcar \"não mover\"",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                BLOCKS_READ,
                "Consultar bloqueios de grupo, com as unidades e o pick-up",
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
                BLOCKS_MANAGE,
                "Criar e alterar bloqueios de grupo",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.SALES_EVENTS to all),
            ),
            PermissionDefinition(
                BLOCKS_PICK_UP,
                "Converter unidades de um bloqueio de grupo em reservas (pick-up e rooming list)",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.SALES_EVENTS to all,
                    ),
            ),
        )
}
