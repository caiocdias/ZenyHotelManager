package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `housekeeping` (RF-ACS-02, M6 E01 a E05). A configuração da governança (tipos de limpeza e
 * faxinas programadas), a consulta, a solicitação e a distribuição das tarefas não têm dono, então só aceitam o escopo
 * `ALL`; a execução das tarefas (E04) aceita `OWN`, cuja dona é a camareira atribuída. Nenhuma é sensível. Sugestões
 * aos modelos (SPEC §3.1): o Gerente ("toda a operação") e a Governanta consultam e mantêm a configuração; o Gerente,
 * a Governanta e a Recepção consultam as tarefas e solicitam a avulsa; o Gerente e a Governanta distribuem as tarefas
 * (E03) e executam qualquer uma; a Camareira executa só as dela e **não** consulta as tarefas (D2-E04: ela as vê pela
 * resposta das ações e, na E19, pela fonte offline). A inspeção (E05, D1) só aceita `ALL` e fica com o Gerente e a
 * Governanta; a Camareira não a tem, e quem limpou só inspeciona se tiver a permissão.
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

    /** Executar as tarefas de governança: iniciar, pausar, retomar, recusar, tentar de novo e concluir (D1-E04). */
    val TASKS_EXECUTE = PermissionCode("housekeeping.tasks.execute")

    /** Inspecionar as UHs limpas: iniciar e concluir a inspeção, aprovando ou reprovando a limpeza (D1-E05). */
    val INSPECTIONS_PERFORM = PermissionCode("housekeeping.inspections.perform")

    private val managers = listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER).associateWith { TemplateGrant(ALL) }

    private val taskUsers =
        listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER, RoleTemplate.RECEPTIONIST)
            .associateWith { TemplateGrant(ALL) }

    private val executors =
        mapOf(
            RoleTemplate.MANAGER to TemplateGrant(ALL),
            RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
            RoleTemplate.ROOM_ATTENDANT to TemplateGrant(OWN),
        )

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
            PermissionDefinition(
                TASKS_EXECUTE,
                "Executar as tarefas de governança (as próprias, com o escopo OWN)",
                setOf(OWN, ALL),
                templates = executors,
            ),
            PermissionDefinition(
                INSPECTIONS_PERFORM,
                "Inspecionar as UHs limpas, aprovando ou reprovando a limpeza",
                setOf(ALL),
                templates = managers,
            ),
        )
}
