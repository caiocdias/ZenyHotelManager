package com.zeny.hotel.guests

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `guests` (RF-ACS-02, SPEC §5.5; nomes da H16 do M3). Perfis e empresas não têm dono,
 * então só aceitam o escopo `ALL`. São **sensíveis** (RF-ACS-08: sem a permissão, pedem a aprovação de um
 * superior) a exportação dos dados do titular ([DATA_EXPORT]) e a anonimização ([DATA_ANONYMIZE], decisão do usuário
 * em 2026-10-17); a lista de restrição não é.
 *
 * - [PROFILES_READ]: consultar e buscar perfis, com o documento **mascarado** (H9) e sem os dados sensíveis
 *   da FNRH (RN-19), que nunca voltam pela API.
 * - [PROFILES_MANAGE]: cadastrar e corrigir perfis, documentos, consentimentos e os dados sensíveis da FNRH (que
 *   se gravam, mas não se leem).
 * - [DOCUMENTS_REVEAL]: ver o número inteiro de um documento, uma ação própria com cada acesso na trilha
 *   (H9, RNF-09).
 * - [RESTRICTIONS_MANAGE]: incluir e retirar da lista de restrição (RF-HOS-04).
 * - [PROFILES_MERGE]: mesclar perfis duplicados (RF-HOS-05); listar os candidatos exige só [PROFILES_READ].
 * - [COMPANIES_READ] e [COMPANIES_MANAGE]: o cadastro de empresas (RF-HOS-06).
 * - [ATTACHMENTS_READ] e [ATTACHMENTS_MANAGE]: listar e baixar, e enviar, vincular e retirar os anexos do hóspede
 *   (RF-HOS-08; finalidades sensíveis em [GuestsFilePurposes], com cada acesso na trilha).
 * - [DATA_EXPORT]: exportar os dados pessoais do titular (RF-HOS-07), **sensível**.
 * - [DATA_ANONYMIZE]: anonimizar o perfil a pedido do titular (RF-HOS-07), **sensível**.
 *
 * Sugestões aos modelos (SPEC §3.1): a Recepcionista consulta e mantém perfis, revela documentos, consulta
 * empresas e cuida dos anexos (sem exportar nem anonimizar: com a aprovação de um superior); o Gerente tudo (só ele
 * mescla perfis); o Financeiro consulta perfis e mantém empresas (pagadoras, contas a receber); o Comercial/Eventos
 * consulta empresas (clientes de eventos).
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

    /** Mesclar dois perfis de hóspede duplicados num sobrevivente (o absorvido fica como mesclado). */
    val PROFILES_MERGE = PermissionCode("guests.profiles.merge")

    /** Consultar e buscar empresas. */
    val COMPANIES_READ = PermissionCode("guests.companies.read")

    /** Cadastrar, editar, desativar e reativar empresas. */
    val COMPANIES_MANAGE = PermissionCode("guests.companies.manage")

    /** Listar e baixar os anexos do hóspede (documento, autorização de menor); cada download fica na trilha. */
    val ATTACHMENTS_READ = PermissionCode("guests.attachments.read")

    /** Enviar, vincular e retirar anexos do hóspede. */
    val ATTACHMENTS_MANAGE = PermissionCode("guests.attachments.manage")

    /** Exportar os dados pessoais do titular (sensível: sem ela, aprovação de superior). */
    val DATA_EXPORT = PermissionCode("guests.data.export")

    /** Anonimizar um perfil a pedido do titular (sensível: sem ela, aprovação de superior). */
    val DATA_ANONYMIZE = PermissionCode("guests.data.anonymize")

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
                PROFILES_MERGE,
                "Mesclar perfis de hóspedes duplicados (o absorvido fica como mesclado, nunca é apagado)",
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
            PermissionDefinition(
                ATTACHMENTS_READ,
                "Listar e baixar anexos de hóspedes (documento, autorização de menor; cada download é registrado)",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                ATTACHMENTS_MANAGE,
                "Enviar, vincular e retirar anexos de hóspedes",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.RECEPTIONIST to all),
            ),
            PermissionDefinition(
                DATA_EXPORT,
                "Exportar os dados pessoais de um hóspede a pedido do titular (LGPD)",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                DATA_ANONYMIZE,
                "Anonimizar o perfil de um hóspede a pedido do titular (LGPD; irreversível)",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
        )
}
