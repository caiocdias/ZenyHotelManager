package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `housekeeping` (RF-ACS-02, M6 E01 a E03). A configuração da governança (tipos de limpeza e
 * faxinas programadas) e as tarefas não têm dono, então só aceitam o escopo `ALL`, e nenhuma é sensível. Sugestões aos
 * modelos (SPEC §3.1): o Gerente ("toda a operação") e a Governanta consultam e mantêm a configuração; o Gerente, a
 * Governanta e a Recepção consultam as tarefas e solicitam a avulsa; o Gerente e a Governanta distribuem as tarefas
 * (E03).
 */
object HousekeepingPermissions : ModulePermissions {
    override val module = "housekeeping"

    /** Consultar a configuração da governança: tipos de limpeza e créditos. */
    val CONFIG_READ = PermissionCode("housekeeping.config.read")

    /** Criar e editar a configuração da governança, desativar e reativar. */
    val CONFIG_MANAGE = PermissionCode("housekeeping.config.manage")

    /** Consultar as tarefas de governança. */
    val TASKS_READ = PermissionCode("housekeeping.tasks.read")

    /** Solicitar uma tarefa de governança avulsa. */
    val TASKS_REQUEST = PermissionCode("housekeeping.tasks.request")

    /** Sugerir, ajustar e publicar a distribuição das tarefas do dia (D10-E03). */
    val ASSIGNMENTS_MANAGE = PermissionCode("housekeeping.assignments.manage")

    private val managers = listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER).associateWith { TemplateGrant(ALL) }

    private val taskUsers =
        listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER, RoleTemplate.RECEPTIONIST)
            .associateWith { TemplateGrant(ALL) }

    override val permissions =
        listOf(
            PermissionDefinition(
                CONFIG_READ,
                "Consultar a configuração da governança: tipos de limpeza e créditos",
                setOf(ALL),
                templates = managers,
            ),
            PermissionDefinition(
                CONFIG_MANAGE,
                "Criar e editar a configuração da governança, desativar e reativar",
                setOf(ALL),
                templates = managers,
            ),
            PermissionDefinition(
                TASKS_READ,
                "Consultar as tarefas de governança",
                setOf(ALL),
                templates = taskUsers,
            ),
            PermissionDefinition(
                TASKS_REQUEST,
                "Solicitar uma tarefa de governança avulsa",
                setOf(ALL),
                templates = taskUsers,
            ),
            PermissionDefinition(
                ASSIGNMENTS_MANAGE,
                "Sugerir, ajustar e publicar a distribuição das tarefas de governança do dia",
                setOf(ALL),
                templates = managers,
            ),
        )
}
