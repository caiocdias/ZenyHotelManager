package com.zeny.hotel.checklists

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `checklists` (RF-ACS-02, SPEC §5.15). Os modelos e as rotinas não têm dono, então só
 * aceitam o escopo `ALL`; as execuções aceitam `OWN` (o executor e, numa rotina, os candidatos escalados, H14) e
 * `ALL`. Nenhuma é sensível. Sugestões aos modelos (SPEC §3.1): o Gerente ("toda a operação") e a Governanta
 * ("modelos de checklist") consultam e mantêm modelos e rotinas e veem e executam todas as execuções; a
 * Camareira, a Manutenção e o Garçom executam e veem só as próprias.
 */
object ChecklistsPermissions : ModulePermissions {
    override val module = "checklists"

    /** Consultar os modelos de checklist, o rascunho e as versões publicadas. */
    val TEMPLATES_READ = PermissionCode("checklists.templates.read")

    /** Criar e editar modelos de checklist, publicar versões, desativar e reativar modelos. */
    val TEMPLATES_MANAGE = PermissionCode("checklists.templates.manage")

    /** Consultar as execuções de checklist, com as respostas e as fotos (o download das fotos também). */
    val EXECUTIONS_READ = PermissionCode("checklists.executions.read")

    /** Iniciar, responder e concluir execuções de checklist (online e offline) e enviar as fotos. */
    val EXECUTIONS_EXECUTE = PermissionCode("checklists.executions.execute")

    /** Consultar as rotinas (checklists recorrentes por setor e escala). */
    val ROUTINES_READ = PermissionCode("checklists.routines.read")

    /** Criar e editar rotinas, desativar e reativar. */
    val ROUTINES_MANAGE = PermissionCode("checklists.routines.manage")

    private val all = TemplateGrant(ALL)
    private val own = TemplateGrant(OWN)
    private val managers = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.HOUSEKEEPER to all)
    private val executors =
        managers +
            mapOf(RoleTemplate.ROOM_ATTENDANT to own, RoleTemplate.MAINTENANCE to own, RoleTemplate.WAITER to own)

    override val permissions =
        listOf(
            PermissionDefinition(
                TEMPLATES_READ,
                "Consultar os modelos de checklist, o rascunho e as versões publicadas",
                setOf(ALL),
                templates = managers,
            ),
            PermissionDefinition(
                TEMPLATES_MANAGE,
                "Criar e editar modelos de checklist, publicar versões, desativar e reativar modelos",
                setOf(ALL),
                templates = managers,
            ),
            PermissionDefinition(
                EXECUTIONS_READ,
                "Consultar as execuções de checklist, com as respostas e as fotos",
                setOf(OWN, ALL),
                templates = executors,
            ),
            PermissionDefinition(
                EXECUTIONS_EXECUTE,
                "Iniciar, responder e concluir execuções de checklist e enviar as fotos",
                setOf(OWN, ALL),
                templates = executors,
            ),
            PermissionDefinition(
                ROUTINES_READ,
                "Consultar as rotinas de checklist",
                setOf(ALL),
                templates = managers,
            ),
            PermissionDefinition(
                ROUTINES_MANAGE,
                "Criar e editar rotinas de checklist, desativar e reativar",
                setOf(ALL),
                templates = managers,
            ),
        )
}
