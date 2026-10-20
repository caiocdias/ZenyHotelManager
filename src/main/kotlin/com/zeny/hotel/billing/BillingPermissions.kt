package com.zeny.hotel.billing

import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `billing` (RF-ACS-02, SPEC §5.10; nomes da H16 do M3). Códigos, contas e lançamentos não têm
 * dono, então só aceitam o escopo `ALL`. São **sensíveis** (RF-ACS-08: sem a permissão, ou acima do limite, pedem a
 * aprovação de um superior) o estorno ([POSTINGS_REVERSE]) e o ajuste ([POSTINGS_ADJUST]), ambos com o limite
 * [MAX_AMOUNT] (`MONEY`) sobre o total cumulativo das correções do lançamento original, em valor absoluto: as já
 * feitas, por qualquer usuário, mais a nova (decisão do usuário em 2026-10-17; cumulativo desde a revisão humana).
 *
 * Sugestões aos modelos (SPEC §3.1, decisão do usuário em 2026-10-17): o Gerente e o Financeiro tudo, com as
 * correções **sem teto**; a Recepcionista consulta códigos, abre e consulta contas e lança (sem estornar nem ajustar:
 * pede a aprovação de um superior); o Caixa A&B consulta códigos e contas (o PDV lança pela `BillingApi` no M8) e
 * também pede a aprovação para corrigir. O roteamento ([ROUTING_MANAGE]) e a transferência e divisão
 * ([POSTINGS_TRANSFER]) não são sensíveis e vão ao Gerente, ao Financeiro e à Recepcionista (decisão do usuário em
 * 2026-10-19), como a remoção da taxa de serviço a pedido do cliente ([SERVICE_CHARGE_REMOVE], M3 E06: RN-10 a diz
 * opcional para o cliente, e não é ação sensível de RF-ACS-08).
 *
 * Caixa (RF-CTA-09 e 10, M3 E07; nenhuma sensível): o cadastro dos pontos de caixa ([CASH_POINTS_MANAGE], Gerente e
 * Financeiro); operar o próprio turno ([CASHIER_OPERATE]: abrir, sangria, suprimento e fechar; Gerente, Financeiro,
 * Recepcionista e Caixa A&B); consultar turnos ([SHIFTS_READ], a única do módulo com `OWN`: o dono é o operador;
 * Recepcionista e Caixa A&B só os próprios, Gerente e Financeiro todos); e a conferência ([SHIFTS_REVIEW], só `ALL`,
 * Gerente e Financeiro): vê o valor esperado de qualquer turno, mesmo cego e aberto, e recebe a notificação de
 * diferença no fechamento (H14).
 */
object BillingPermissions : ModulePermissions {
    override val module = "billing"

    /** Chave do limite das correções: o total das correções de um lançamento (estornos e ajustes), em reais. */
    const val MAX_AMOUNT = "maxAmount"

    /** Consultar os códigos de lançamento. */
    val CODES_READ = PermissionCode("billing.codes.read")

    /** Cadastrar, editar, desativar e reativar códigos de lançamento. */
    val CODES_MANAGE = PermissionCode("billing.codes.manage")

    /** Consultar contas, saldos e lançamentos. */
    val FOLIOS_READ = PermissionCode("billing.folios.read")

    /** Abrir contas e trocar o pagador e o limite de crédito. */
    val FOLIOS_MANAGE = PermissionCode("billing.folios.manage")

    /** Lançar numa conta (lançamento manual). */
    val POSTINGS_CREATE = PermissionCode("billing.postings.create")

    /** Estornar um lançamento (sensível, com limite [MAX_AMOUNT]). */
    val POSTINGS_REVERSE = PermissionCode("billing.postings.reverse")

    /** Ajustar um lançamento (sensível, com limite [MAX_AMOUNT]). */
    val POSTINGS_ADJUST = PermissionCode("billing.postings.adjust")

    /** Cadastrar, editar, desativar e reativar regras de roteamento (M3 E05; não sensível). */
    val ROUTING_MANAGE = PermissionCode("billing.routing.manage")

    /** Transferir e dividir lançamentos entre contas (M3 E05; não sensível). */
    val POSTINGS_TRANSFER = PermissionCode("billing.postings.transfer")

    /** Remover a taxa de serviço a pedido do cliente (RN-10, M3 E06; não sensível). */
    val SERVICE_CHARGE_REMOVE = PermissionCode("billing.servicecharge.remove")

    /** Cadastrar, editar, desativar e reativar pontos de caixa (M3 E07). */
    val CASH_POINTS_MANAGE = PermissionCode("billing.cashpoints.manage")

    /** Operar o próprio turno de caixa: abrir, sangria, suprimento e fechar (M3 E07). */
    val CASHIER_OPERATE = PermissionCode("billing.cashier.operate")

    /** Consultar turnos de caixa (`OWN`: só os próprios; `ALL`: todos) e os pontos de caixa (M3 E07). */
    val SHIFTS_READ = PermissionCode("billing.shifts.read")

    /** Conferir turnos de caixa: o esperado sempre visível e a notificação de diferença (M3 E07, H14; só `ALL`). */
    val SHIFTS_REVIEW = PermissionCode("billing.shifts.review")

    private val all = TemplateGrant(ALL)
    private val own = TemplateGrant(OWN)
    private val supervisors = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.FINANCE to all)
    private val operators =
        mapOf(RoleTemplate.MANAGER to all, RoleTemplate.FINANCE to all, RoleTemplate.RECEPTIONIST to all)
    private val unlimited = TemplateGrant(ALL, mapOf(MAX_AMOUNT to LimitValue.Unlimited))
    private val maxAmount =
        LimitDefinition(
            MAX_AMOUNT,
            LimitUnit.MONEY,
            "Valor máximo do total das correções de um lançamento (estornos e ajustes), em reais",
        )

    override val permissions =
        listOf(
            PermissionDefinition(
                CODES_READ,
                "Consultar os códigos de lançamento",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.FINANCE to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.FNB_CASHIER to all,
                    ),
            ),
            PermissionDefinition(
                CODES_MANAGE,
                "Cadastrar, editar, desativar e reativar códigos de lançamento",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to all, RoleTemplate.FINANCE to all),
            ),
            PermissionDefinition(
                FOLIOS_READ,
                "Consultar contas, saldos e lançamentos",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.FINANCE to all,
                        RoleTemplate.RECEPTIONIST to all,
                        RoleTemplate.FNB_CASHIER to all,
                    ),
            ),
            PermissionDefinition(
                FOLIOS_MANAGE,
                "Abrir contas e trocar o pagador e o limite de crédito",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.FINANCE to all,
                        RoleTemplate.RECEPTIONIST to all,
                    ),
            ),
            PermissionDefinition(
                POSTINGS_CREATE,
                "Lançar numa conta",
                setOf(ALL),
                templates =
                    mapOf(
                        RoleTemplate.MANAGER to all,
                        RoleTemplate.FINANCE to all,
                        RoleTemplate.RECEPTIONIST to all,
                    ),
            ),
            PermissionDefinition(
                POSTINGS_REVERSE,
                "Estornar um lançamento (ação sensível, com valor máximo)",
                setOf(ALL),
                sensitive = true,
                limits = listOf(maxAmount),
                templates = mapOf(RoleTemplate.MANAGER to unlimited, RoleTemplate.FINANCE to unlimited),
            ),
            PermissionDefinition(
                POSTINGS_ADJUST,
                "Ajustar um lançamento (ação sensível, com valor máximo)",
                setOf(ALL),
                sensitive = true,
                limits = listOf(maxAmount),
                templates = mapOf(RoleTemplate.MANAGER to unlimited, RoleTemplate.FINANCE to unlimited),
            ),
            PermissionDefinition(
                ROUTING_MANAGE,
                "Cadastrar, editar, desativar e reativar regras de roteamento entre contas",
                setOf(ALL),
                templates = operators,
            ),
            PermissionDefinition(
                POSTINGS_TRANSFER,
                "Transferir e dividir lançamentos entre contas",
                setOf(ALL),
                templates = operators,
            ),
            PermissionDefinition(
                SERVICE_CHARGE_REMOVE,
                "Remover a taxa de serviço de um lançamento a pedido do cliente",
                setOf(ALL),
                templates = operators,
            ),
            PermissionDefinition(
                CASH_POINTS_MANAGE,
                "Cadastrar, editar, desativar e reativar pontos de caixa",
                setOf(ALL),
                templates = supervisors,
            ),
            PermissionDefinition(
                CASHIER_OPERATE,
                "Operar o próprio turno de caixa (abrir, sangria, suprimento e fechar)",
                setOf(ALL),
                templates = operators + (RoleTemplate.FNB_CASHIER to all),
            ),
            PermissionDefinition(
                SHIFTS_READ,
                "Consultar turnos de caixa (só os próprios ou todos) e os pontos de caixa",
                setOf(OWN, ALL),
                templates = supervisors + mapOf(RoleTemplate.RECEPTIONIST to own, RoleTemplate.FNB_CASHIER to own),
            ),
            PermissionDefinition(
                SHIFTS_REVIEW,
                "Conferir turnos de caixa: ver o valor esperado e receber a notificação de diferença",
                setOf(ALL),
                templates = supervisors,
            ),
        )
}
