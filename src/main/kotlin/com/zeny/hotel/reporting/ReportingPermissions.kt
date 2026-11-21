package com.zeny.hotel.reporting

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `reporting` (RF-ACS-02, M6 E09, D2), separadas por assunto. Os relatórios são do hotel todo,
 * sem dono, então só aceitam o escopo `ALL`, e nenhuma é sensível (nenhum relatório traz dado de hóspede nem texto
 * livre, RN-13). A [ARCHIVE_READ] (M6 E23, D5) abre a lista do arquivo por data e o download do PDF da data, anexo
 * sensível do `files` (sem nome de hóspede, mas com códigos de reserva, saldos e ids de operador); o download tem a
 * trilha do `files`.
 */
object ReportingPermissions : ModulePermissions {
    override val module = "reporting"

    /** O painel do dia (chegadas, saídas, ocupação e UHs). */
    val DASHBOARD_READ = PermissionCode("reporting.dashboard.read")

    /** Os relatórios de governança. */
    val HOUSEKEEPING_READ = PermissionCode("reporting.housekeeping.read")

    /** Os relatórios de manutenção. */
    val MAINTENANCE_READ = PermissionCode("reporting.maintenance.read")

    /** As pendências do fechamento do dia (contas, caixas e FNRH). */
    val PENDING_READ = PermissionCode("reporting.pending.read")

    /** O arquivo por data: a lista dos PDFs da data e o download deles (finalidade `reporting.day-report`). */
    val ARCHIVE_READ = PermissionCode("reporting.archive.read")

    private fun grantedTo(vararg templates: RoleTemplate) = templates.associateWith { TemplateGrant(ALL) }

    override val permissions =
        listOf(
            PermissionDefinition(
                DASHBOARD_READ,
                "Consultar o painel do dia: chegadas, saídas, ocupação e situação das UHs",
                setOf(ALL),
                templates = grantedTo(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.HOUSEKEEPER),
            ),
            PermissionDefinition(
                HOUSEKEEPING_READ,
                "Consultar os relatórios de governança: produtividade, tempo médio e reprovação",
                setOf(ALL),
                templates = grantedTo(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER),
            ),
            PermissionDefinition(
                MAINTENANCE_READ,
                "Consultar os relatórios de manutenção: ordens, tempo de atendimento e custos",
                setOf(ALL),
                templates = grantedTo(RoleTemplate.MANAGER, RoleTemplate.MAINTENANCE),
            ),
            PermissionDefinition(
                PENDING_READ,
                "Consultar as pendências do fechamento do dia: contas, caixas abertos e FNRH",
                setOf(ALL),
                templates = grantedTo(RoleTemplate.MANAGER, RoleTemplate.FINANCE, RoleTemplate.RECEPTIONIST),
            ),
            PermissionDefinition(
                ARCHIVE_READ,
                "Consultar o arquivo do fechamento: a lista e o download do PDF de cada data",
                setOf(ALL),
                templates = grantedTo(RoleTemplate.MANAGER, RoleTemplate.FINANCE),
            ),
        )
}
