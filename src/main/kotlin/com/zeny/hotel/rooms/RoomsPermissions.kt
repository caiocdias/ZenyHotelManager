package com.zeny.hotel.rooms

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `rooms` (RF-ACS-02, SPEC §5.4). O cadastro (tipos de UH, características, UHs e
 * áreas) e o status das UHs não têm dono, então só aceitam o escopo `ALL`, e nenhuma ação está entre as
 * sensíveis da RF-ACS-08 (o OOO, sensível, entra na E03 do M2).
 *
 * Sugestões aos modelos (H5, SPEC §3.1): o Gerente consulta e mantém o cadastro ("toda a operação");
 * a Recepcionista e a Governanta o consultam (reservas, check-in, distribuição e status de UH). O
 * status: o Gerente tudo; a Governanta consulta, muda o status de governança, registra a sinalização e
 * o que encontrou na UH; a Recepcionista consulta e registra a sinalização do hóspede.
 */
object RoomsPermissions : ModulePermissions {
    override val module = "rooms"

    /** Consultar tipos de UH, características, UHs e áreas comuns. */
    val REGISTRY_READ = PermissionCode("rooms.registry.read")

    /** Cadastrar, editar, desativar e reativar tipos de UH, características, UHs e áreas comuns. */
    val REGISTRY_MANAGE = PermissionCode("rooms.registry.manage")

    /** Consultar o painel de status das UHs, o histórico de status e as discrepâncias (RF-UH-06 a 08). */
    val STATUS_READ = PermissionCode("rooms.status.read")

    /** Mudar à mão o status de governança de uma UH (RN-08). */
    val STATUS_MANAGE = PermissionCode("rooms.status.manage")

    /** Registrar a sinalização do hóspede (não perturbe, arrumar agora). */
    val STATUS_FLAG = PermissionCode("rooms.status.flag")

    /** Registrar o que a governança encontrou na UH (ocupação e número de pessoas; RF-UH-08). */
    val STATUS_OBSERVE = PermissionCode("rooms.status.observe")

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
            PermissionDefinition(
                STATUS_READ,
                "Consultar o painel de status das UHs, o histórico de status e as discrepâncias",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.HOUSEKEEPER to all,
                    ),
            ),
            PermissionDefinition(
                STATUS_MANAGE,
                "Mudar à mão o status de governança de uma UH (sujo, limpo, inspecionado)",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.HOUSEKEEPER to all),
            ),
            PermissionDefinition(
                STATUS_FLAG,
                "Registrar a sinalização do hóspede numa UH (não perturbe, arrumar agora)",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.HOUSEKEEPER to all,
                    ),
            ),
            PermissionDefinition(
                STATUS_OBSERVE,
                "Registrar a ocupação e o número de pessoas encontrados pela governança numa UH",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.HOUSEKEEPER to all),
            ),
        )
}
