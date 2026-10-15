package com.zeny.hotel.hotel

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `hotel` (RF-ACS-02): consultar e editar a configuração do hotel (dados,
 * políticas, taxas e parâmetros). A data operacional não exige permissão: todo usuário autenticado
 * a vê (RF-CFG-05). Editar a configuração não está entre as ações sensíveis da RF-ACS-08 e fica
 * com o Administrador (SPEC §3.1: "configurações"); Gerente, Recepcionista e Financeiro consultam.
 */
object HotelPermissions : ModulePermissions {
    override val module = "hotel"

    /** Consultar dados, políticas, taxas e parâmetros do hotel. */
    val SETTINGS_READ = PermissionCode("hotel.settings.read")

    /** Editar dados, políticas, taxas e parâmetros do hotel. */
    val SETTINGS_MANAGE = PermissionCode("hotel.settings.manage")

    private val readers =
        listOf(
            RoleTemplate.MANAGER,
            RoleTemplate.RECEPTIONIST,
            RoleTemplate.FINANCE,
        ).associateWith { TemplateGrant(ALL) }

    override val permissions =
        listOf(
            PermissionDefinition(
                SETTINGS_READ,
                "Consultar dados, políticas, taxas e parâmetros do hotel",
                setOf(ALL),
                templates = readers,
            ),
            PermissionDefinition(
                SETTINGS_MANAGE,
                "Editar dados, políticas, taxas e parâmetros do hotel",
                setOf(ALL),
            ),
        )
}
