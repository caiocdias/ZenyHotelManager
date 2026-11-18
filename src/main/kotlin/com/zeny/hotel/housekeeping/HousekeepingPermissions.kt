package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `housekeeping` (RF-ACS-02, M6 E01). A configuração da governança (tipos de limpeza e, depois,
 * faxinas programadas) não tem dono, então só aceita o escopo `ALL`, e nenhuma é sensível. Sugestões aos modelos (SPEC
 * §3.1): o Gerente ("toda a operação") e a Governanta consultam e mantêm a configuração.
 */
object HousekeepingPermissions : ModulePermissions {
    override val module = "housekeeping"

    /** Consultar a configuração da governança: tipos de limpeza e créditos. */
    val CONFIG_READ = PermissionCode("housekeeping.config.read")

    /** Criar e editar a configuração da governança, desativar e reativar. */
    val CONFIG_MANAGE = PermissionCode("housekeeping.config.manage")

    private val managers = listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER).associateWith { TemplateGrant(ALL) }

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
        )
}
