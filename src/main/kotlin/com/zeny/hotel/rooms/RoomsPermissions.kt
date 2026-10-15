package com.zeny.hotel.rooms

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `rooms` (RF-ACS-02, SPEC §5.4). O cadastro (tipos de UH, características, UHs e
 * áreas), o status das UHs e os períodos OOO/OOS não têm dono, então só aceitam o escopo `ALL`. Das
 * ações sensíveis da RF-ACS-08, só "colocar UH em OOO" é deste módulo: [OUT_OF_ORDER_MANAGE] é sensível
 * (sem ela, abrir, alterar e liberar OOO pedem a aprovação de um superior), e o OOS tem permissão própria,
 * não sensível ([OUT_OF_SERVICE_MANAGE]).
 *
 * Sugestões aos modelos (H5, SPEC §3.1): o Gerente consulta e mantém o cadastro ("toda a operação");
 * a Recepcionista e a Governanta o consultam (reservas, check-in, distribuição e status de UH). O
 * status: o Gerente tudo; a Governanta consulta, muda o status de governança, registra a sinalização e
 * o que encontrou na UH; a Recepcionista consulta e registra a sinalização do hóspede. OOO/OOS: o
 * Gerente tudo; a Manutenção consulta, abre OOS e só pede OOO (SPEC §3.1: "solicitar OOO/OOS", pela
 * aprovação de superior); a Recepcionista e a Governanta consultam.
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

    /** Consultar os períodos OOO e OOS (RF-UH-05). */
    val OUT_OF_ORDER_READ = PermissionCode("rooms.outoforder.read")

    /** Abrir, alterar e liberar períodos OOO (RN-07). Sensível (RF-ACS-08): sem ela, pede aprovação. */
    val OUT_OF_ORDER_MANAGE = PermissionCode("rooms.outoforder.manage")

    /** Abrir, alterar e liberar períodos OOS (a UH continua vendável). */
    val OUT_OF_SERVICE_MANAGE = PermissionCode("rooms.outofservice.manage")

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
            PermissionDefinition(
                OUT_OF_ORDER_READ,
                "Consultar os períodos fora de ordem (OOO) e fora de serviço (OOS) das UHs",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.HOUSEKEEPER to all,
                        RoleTemplate.MAINTENANCE to all,
                    ),
            ),
            PermissionDefinition(
                OUT_OF_ORDER_MANAGE,
                "Colocar UH fora de ordem (OOO), alterar e liberar o período (ação sensível)",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                OUT_OF_SERVICE_MANAGE,
                "Colocar UH fora de serviço (OOS), alterar e liberar o período",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.MAINTENANCE to all),
            ),
        )
}
