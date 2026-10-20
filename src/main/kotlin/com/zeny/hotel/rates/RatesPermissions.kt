package com.zeny.hotel.rates

import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import java.math.BigDecimal

/**
 * Permissões do módulo `rates` (RF-ACS-02, SPEC §5.6, H7, H9 e H16 do roadmap do M4). Planos, políticas, pacotes,
 * preços, temporadas e restrições não têm dono, então só aceitam o escopo `ALL`.
 *
 * Preços (E02, H9, decisão do usuário em 2026-10-20): [PRICES_MANAGE] grava preços onde não havia, ou num plano
 * inativo, mantém as temporadas e a derivação de um plano que ainda não deriva; [PRICES_OVERRIDE] é **sensível**:
 * alterar ou retirar uma **tarifa publicada** (um preço já gravado, a partir da data operacional, de um plano ativo ou
 * que é base de um plano ativo) e trocar ou retirar a derivação de um plano publicado pedem a permissão ou a aprovação
 * de um superior. Quem altera precisa também de [PRICES_MANAGE] (sem ela, `403 PERMISSION_DENIED`). Só o Gerente as
 * recebe no modelo.
 *
 * [DISCOUNTS_APPLY] é a permissão do desconto (ARCHITECTURE §8.2, H7, decisão do usuário em 2026-10-20): **sensível**,
 * com o limite [MAX_PERCENT] (`PERCENT`, o desconto sobre o preço calculado da noite); o `reservations` a confere ao
 * gravar uma noite abaixo do preço calculado (M4 E07): acima do teto, pede a aprovação de um superior. Modelos: o
 * Gerente sem teto e a Recepcionista com teto de 10%.
 *
 * Restrições de venda (E03): [RESTRICTIONS_MANAGE] grava e retira as restrições por data, plano e tipo de UH (não
 * sensível: a H9 vale só para a tarifa); só o Gerente a recebe no modelo. Consultar exige [PLANS_READ].
 *
 * Sugestões aos modelos (SPEC §3.1, H16): o Gerente consulta e mantém planos, políticas e pacotes; a Recepcionista, o
 * Comercial/Eventos e o Financeiro os consultam.
 */
object RatesPermissions : ModulePermissions {
    override val module = "rates"

    /** Chave do limite do desconto: o percentual máximo abaixo do preço calculado da noite. */
    const val MAX_PERCENT = "maxPercent"

    /** Teto do desconto da Recepcionista no modelo (H7), em pontos percentuais. */
    val RECEPTIONIST_DISCOUNT_CEILING: BigDecimal = BigDecimal("10")

    /** Consultar planos tarifários, políticas de cancelamento e de garantia e pacotes. */
    val PLANS_READ = PermissionCode("rates.plans.read")

    /** Cadastrar, editar, desativar e reativar planos tarifários, políticas e pacotes. */
    val PLANS_MANAGE = PermissionCode("rates.plans.manage")

    /** Dar desconto sobre o preço calculado de uma noite (sensível, com limite [MAX_PERCENT]; aplicado na E07). */
    val DISCOUNTS_APPLY = PermissionCode("rates.discounts.apply")

    /** Gravar preços onde não havia (ou num plano inativo), manter temporadas e derivar um plano (E02). */
    val PRICES_MANAGE = PermissionCode("rates.prices.manage")

    /** Alterar ou retirar uma tarifa publicada e trocar a derivação de um plano publicado (sensível, H9; E02). */
    val PRICES_OVERRIDE = PermissionCode("rates.prices.override")

    /** Gravar e retirar as restrições de venda por data, plano e tipo de UH (E03). */
    val RESTRICTIONS_MANAGE = PermissionCode("rates.restrictions.manage")

    private val all = TemplateGrant(ALL)

    override val permissions =
        listOf(
            PermissionDefinition(
                PLANS_READ,
                "Consultar planos tarifários, políticas de cancelamento e de garantia e pacotes",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.SALES_EVENTS to all,
                        RoleTemplate.FINANCE to all,
                    ),
            ),
            PermissionDefinition(
                PLANS_MANAGE,
                "Cadastrar, editar, desativar e reativar planos tarifários, políticas e pacotes",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                DISCOUNTS_APPLY,
                "Dar desconto sobre o preço calculado da diária (ação sensível, com percentual máximo)",
                setOf(ALL),
                sensitive = true,
                limits =
                    listOf(
                        LimitDefinition(
                            MAX_PERCENT,
                            LimitUnit.PERCENT,
                            "Percentual máximo de desconto sobre o preço calculado da noite",
                        ),
                    ),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to TemplateGrant(ALL, mapOf(MAX_PERCENT to LimitValue.Unlimited)),
                        RoleTemplate.RECEPTIONIST to
                            TemplateGrant(ALL, mapOf(MAX_PERCENT to LimitValue.Max(RECEPTIONIST_DISCOUNT_CEILING))),
                    ),
            ),
            PermissionDefinition(
                PRICES_MANAGE,
                "Gravar preços por data e ocupação onde não havia, manter temporadas e derivar planos",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                PRICES_OVERRIDE,
                "Alterar ou retirar tarifa publicada e trocar a derivação de plano publicado (ação sensível)",
                setOf(ALL),
                sensitive = true,
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
            PermissionDefinition(
                RESTRICTIONS_MANAGE,
                "Gravar e retirar restrições de venda (fechado, CTA, CTD, estadia e antecedência) por data e tipo",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all),
            ),
        )
}
