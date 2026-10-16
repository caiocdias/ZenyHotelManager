package com.zeny.hotel.checklists

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `checklists` (RF-ACS-02, SPEC §5.15). Os modelos de checklist não têm dono, então só
 * aceitam o escopo `ALL`; nenhuma é sensível. Sugestões aos modelos (SPEC §3.1): o Gerente ("toda a operação")
 * e a Governanta ("modelos de checklist") consultam e mantêm os modelos. As permissões de execução entram na
 * E07 do M2.
 */
object ChecklistsPermissions : ModulePermissions {
    override val module = "checklists"

    /** Consultar os modelos de checklist, o rascunho e as versões publicadas. */
    val TEMPLATES_READ = PermissionCode("checklists.templates.read")

    /** Criar e editar modelos de checklist, publicar versões, desativar e reativar modelos. */
    val TEMPLATES_MANAGE = PermissionCode("checklists.templates.manage")

    private val all = TemplateGrant(ALL)

    override val permissions =
        listOf(
            PermissionDefinition(
                TEMPLATES_READ,
                "Consultar os modelos de checklist, o rascunho e as versões publicadas",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.HOUSEKEEPER to all),
            ),
            PermissionDefinition(
                TEMPLATES_MANAGE,
                "Criar e editar modelos de checklist, publicar versões, desativar e reativar modelos",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.HOUSEKEEPER to all),
            ),
        )
}
