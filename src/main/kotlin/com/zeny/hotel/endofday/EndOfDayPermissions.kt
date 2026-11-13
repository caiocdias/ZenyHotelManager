package com.zeny.hotel.endofday

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `endofday` (RF-ACS-02, M6 E10). O fechamento do dia não tem dono, então só aceita o escopo
 * `ALL`.
 *
 * [RUNS_READ] acompanha execuções, etapas e pendências (Gerente, Recepcionista e Financeiro). [RUNS_EXECUTE] executa ou
 * retoma o fechamento manualmente: é sensível (RF-ACS-08), então quem não a tem precisa de aprovação de superior, e o
 * modelo é só o do Gerente.
 */
object EndOfDayPermissions : ModulePermissions {
    override val module = "endofday"

    /** Acompanhar o fechamento do dia: execuções, etapas e pendências. */
    val RUNS_READ = PermissionCode("endofday.runs.read")

    /** Executar ou retomar o fechamento do dia manualmente (sensível). */
    val RUNS_EXECUTE = PermissionCode("endofday.runs.execute")

    override val permissions =
        listOf(
            PermissionDefinition(
                RUNS_READ,
                "Acompanhar o fechamento do dia: execuções, etapas e pendências",
                setOf(ALL),
                templates =
                    listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.FINANCE)
                        .associateWith { TemplateGrant(ALL) },
            ),
            PermissionDefinition(
                RUNS_EXECUTE,
                "Executar ou retomar o fechamento do dia manualmente",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL)),
            ),
        )
}
