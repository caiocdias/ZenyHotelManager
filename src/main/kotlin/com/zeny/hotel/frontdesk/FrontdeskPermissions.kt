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
 */
object FrontdeskPermissions : ModulePermissions {
    override val module = "frontdesk"

    /** Fazer o check-in de uma reserva confirmada. */
    val STAYS_CHECK_IN = PermissionCode("frontdesk.stays.checkin")

    /** Desfazer o check-in na mesma data operacional (sensível). */
    val STAYS_UNDO_CHECK_IN = PermissionCode("frontdesk.stays.undocheckin")

    private val all = TemplateGrant(ALL)

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
        )
}
