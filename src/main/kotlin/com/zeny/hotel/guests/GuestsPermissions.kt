package com.zeny.hotel.guests

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `guests` (RF-ACS-02, SPEC §5.5; nomes da H16 do M3). Perfis e empresas não têm dono,
 * então só aceitam o escopo `ALL`. Nenhuma é sensível no sentido da RF-ACS-08 (a lista de restrição não está
 * lá): a exportação de dados pessoais, sensível, chega na E03.
 *
 * - [PROFILES_READ]: consultar e buscar perfis, com o documento **mascarado** (H9) e sem os dados sensíveis
 *   da FNRH (RN-19), que nunca voltam pela API.
 * - [PROFILES_MANAGE]: cadastrar e corrigir perfis, documentos e os dados sensíveis da FNRH (que se gravam,
 *   mas não se leem).
 * - [DOCUMENTS_REVEAL]: ver o número inteiro de um documento, uma ação própria com cada acesso na trilha
 *   (H9, RNF-09).
 * - [RESTRICTIONS_MANAGE]: incluir e retirar da lista de restrição (RF-HOS-04).
 * - [COMPANIES_READ] e [COMPANIES_MANAGE]: o cadastro de empresas (RF-HOS-06).
 *
 * Sugestões aos modelos (SPEC §3.1): a Recepcionista consulta e mantém perfis, revela documentos e consulta
 * empresas; o Gerente tudo; o Financeiro consulta perfis e mantém empresas (pagadoras, contas a receber);
 * o Comercial/Eventos consulta empresas (clientes de eventos).
 */
object GuestsPermissions : ModulePermissions {
    override val module = "guests"

    /** Consultar e buscar perfis de hóspedes (documento mascarado, sem os dados sensíveis da FNRH). */
    val PROFILES_READ = PermissionCode("guests.profiles.read")

    /** Cadastrar e corrigir perfis, documentos e os dados sensíveis da FNRH. */
    val PROFILES_MANAGE = PermissionCode("guests.profiles.manage")

    /** Ver o número inteiro de um documento de hóspede (cada acesso fica na trilha). */
    val DOCUMENTS_REVEAL = PermissionCode("guests.documents.reveal")

    /** Incluir e retirar hóspedes da lista de restrição. */
    val RESTRICTIONS_MANAGE = PermissionCode("guests.restrictions.manage")

    /** Consultar e buscar empresas. */
    val COMPANIES_READ = PermissionCode("guests.companies.read")

    /** Cadastrar, editar, desativar e reativar empresas. */
    val COMPANIES_MANAGE = PermissionCode("guests.companies.manage")

    private val all = TemplateGrant(ALL)

    override val permissions =
        listOf(
            PermissionDefinition(
                PROFILES_READ,
                "Consultar e buscar perfis de hóspedes (documento mascarado, sem os dados sensíveis da FNRH)",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.FINANCE to all,
                    ),
            ),
            PermissionDefinition(
                PROFILES_MANAGE,
                "Cadastrar e corrigir perfis de hóspedes, documentos e os dados sensíveis da FNRH",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                DOCUMENTS_REVEAL,
                "Ver o número inteiro de um documento de hóspede (cada acesso fica registrado)",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                RESTRICTIONS_MANAGE,
                "Incluir e retirar hóspedes da lista de restrição",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                COMPANIES_READ,
                "Consultar e buscar empresas",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.FINANCE to all,
                        RoleTemplate.SALES_EVENTS to all,
                    ),
            ),
            PermissionDefinition(
                COMPANIES_MANAGE,
                "Cadastrar, editar, desativar e reativar empresas",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.FINANCE to all),
            ),
        )
}
