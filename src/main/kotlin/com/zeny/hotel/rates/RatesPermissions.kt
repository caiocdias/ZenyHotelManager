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
 * Permissões do módulo `rates` (RF-ACS-02, SPEC §5.6, H7 e H16 do roadmap do M4). Planos, políticas e pacotes não têm
 * dono, então só aceitam o escopo `ALL`. Os preços (E02) e as restrições (E03) acrescentam as suas.
 *
 * [DISCOUNTS_APPLY] é a permissão do desconto (ARCHITECTURE §8.2, H7, decisão do usuário em 2026-10-20): **sensível**,
 * com o limite [MAX_PERCENT] (`PERCENT`, o desconto sobre o preço calculado da noite); o `reservations` a confere ao
 * gravar uma noite abaixo do preço calculado (M4 E07): acima do teto, pede a aprovação de um superior. Modelos: o
 * Gerente sem teto e a Recepcionista com teto de 10%.
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
        )
}
