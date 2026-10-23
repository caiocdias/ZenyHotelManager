package com.zeny.hotel.frontdesk

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `frontdesk` (RF-ACS-02, SPEC §5.8, H20 do roadmap do M5). A hospedagem não tem dono, então só
 * aceita o escopo `ALL`.
 *
 * Check-in (E02): [STAYS_CHECK_IN] faz o check-in de uma reserva confirmada (Gerente e Recepcionista; não sensível);
 * [STAYS_UNDO_CHECK_IN] desfaz o check-in na mesma data operacional, **sensível** (RF-ACS-08, RN-12), sem limite, só
 * com o Gerente no modelo: a Recepcionista pede a aprovação de um superior. Desfazer exige também [STAYS_CHECK_IN].
 *
 * Check-out (E04): [STAYS_CHECK_OUT] faz o check-out de uma reserva hospedada (Gerente e Recepcionista; não sensível,
 * decisão D7): só com todas as contas da reserva em saldo zero, sem nenhuma permissão do `billing`.
 *
 * Troca de UH (E05): [STAYS_MOVE] troca a UH de uma reserva hospedada, com a tarifa mantida ou recalculada (Gerente e
 * Recepcionista; não sensível, decisão D7 da E05), sem `reservations.assignments.manage`.
 *
 * Recados (E06, decisão D2): [TRACES_READ] consulta os recados (Gerente, Recepcionista, Governanta, Manutenção e
 * Comercial/Eventos); [TRACES_MANAGE] cria e altera (Gerente e Recepcionista); [TRACES_RESOLVE] resolve (os mesmos da
 * leitura): o setor que cumpre o recado o resolve sem poder editá-lo. Nenhuma é sensível.
 */
object FrontdeskPermissions : ModulePermissions {
    override val module = "frontdesk"

    /** Fazer o check-in de uma reserva confirmada. */
    val STAYS_CHECK_IN = PermissionCode("frontdesk.stays.checkin")

    /** Desfazer o check-in na mesma data operacional (sensível). */
    val STAYS_UNDO_CHECK_IN = PermissionCode("frontdesk.stays.undocheckin")

    /** Fazer o check-out de uma reserva hospedada. */
    val STAYS_CHECK_OUT = PermissionCode("frontdesk.stays.checkout")

    /** Trocar a UH de um hóspede hospedado. */
    val STAYS_MOVE = PermissionCode("frontdesk.stays.move")

    /** Consultar os recados da recepção. */
    val TRACES_READ = PermissionCode("frontdesk.traces.read")

    /** Criar e alterar recados. */
    val TRACES_MANAGE = PermissionCode("frontdesk.traces.manage")

    /** Resolver um recado. */
    val TRACES_RESOLVE = PermissionCode("frontdesk.traces.resolve")

    private val all = TemplateGrant(ALL)

    /** Quem lê e resolve os recados: a recepção e os setores que os cumprem (D2). */
    private val traceDepartments =
        listOf(
            RoleTemplate.MANAGER,
            RoleTemplate.RECEPTIONIST,
            RoleTemplate.HOUSEKEEPER,
            RoleTemplate.MAINTENANCE,
            RoleTemplate.SALES_EVENTS,
        ).associateWith { all }

    override val permissions =
        listOf(
            PermissionDefinition(
                STAYS_CHECK_IN,
                "Fazer o check-in de uma reserva confirmada (com a caução opcional)",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                STAYS_UNDO_CHECK_IN,
                "Desfazer o check-in na mesma data operacional",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                STAYS_CHECK_OUT,
                "Fazer o check-out de uma reserva hospedada",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                STAYS_MOVE,
                "Trocar a UH de um hóspede hospedado",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                TRACES_READ,
                "Consultar os recados da recepção para os setores",
                setOf(ALL),
                templates = traceDepartments,
            ),
            PermissionDefinition(
                TRACES_MANAGE,
                "Criar e alterar recados da recepção",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                TRACES_RESOLVE,
                "Resolver um recado da recepção",
                setOf(ALL),
                templates = traceDepartments,
            ),
        )
}
