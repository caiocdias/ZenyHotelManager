package com.zeny.hotel.rooms

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `rooms` (RF-ACS-02, SPEC §5.4). O cadastro (tipos de UH, características, UHs e
 * áreas) não tem dono, então só aceita o escopo `ALL`, e nenhuma ação está entre as sensíveis da
 * RF-ACS-08 (o OOO, sensível, entra na E03 do M2).
 *
 * Sugestões aos modelos (H5, SPEC §3.1): o Gerente consulta e mantém o cadastro ("toda a operação");
 * a Recepcionista e a Governanta o consultam (reservas, check-in, distribuição e status de UH).
 */
object RoomsPermissions : ModulePermissions {
    override val module = "rooms"

    /** Consultar tipos de UH, características, UHs e áreas comuns. */
    val REGISTRY_READ = PermissionCode("rooms.registry.read")

    /** Cadastrar, editar, desativar e reativar tipos de UH, características, UHs e áreas comuns. */
    val REGISTRY_MANAGE = PermissionCode("rooms.registry.manage")

    private val all = TemplateGrant(ALL)

    override val permissions =
        listOf(
            PermissionDefinition(
                REGISTRY_READ,
                "Consultar tipos de UH, características, UHs e áreas comuns",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.HOUSEKEEPER to all,
                    ),
            ),
            PermissionDefinition(
                REGISTRY_MANAGE,
                "Cadastrar, editar, desativar e reativar tipos de UH, características, UHs e áreas comuns",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
        )
}
