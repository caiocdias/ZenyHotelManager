package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesChangeKind
import com.zeny.hotel.rates.RatesChanged
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.domain.DerivationRule
import com.zeny.hotel.rates.domain.Derivations
import com.zeny.hotel.rates.domain.PriceAdjustment
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatesRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Planos derivados (RF-TAR-03, H1 e H9; M4 E02): um plano passa a derivar de outro (percentual ou valor, por UH ou por
 * pessoa) e os preços dele são os do base ajustados, calculados na leitura. Exige `rates.prices.manage` e o
 * `If-Match` do plano.
 *
 * - **Ciclo e profundidade** ([Derivations]): o base não pode ser o próprio plano nem derivar dele (`422
 *   RATE_PLAN_DERIVATION_CYCLE`), e a árvore não passa de 5 níveis (`422 RATE_PLAN_DERIVATION_TOO_DEEP`). Toda escrita
 *   de derivação toma antes a trava consultiva exclusiva da árvore: duas derivações opostas simultâneas nunca formam
 *   ciclo (uma passa, a outra recebe o `422`).
 * - **Plano com preços próprios**: tornar derivado um plano que tem preços a partir da data operacional é `422
 *   RATE_PLAN_HAS_PRICES` (retire-os antes; os do passado ficam e são ignorados).
 * - **Tarifa publicada** (H9): trocar ou retirar a derivação de um plano publicado (ativo, ou base de um ativo) muda
 *   as tarifas dele e pede `rates.prices.override` ou a aprovação de um superior; definir a derivação de um plano que
 *   não derivava (sem preços) não pede.
 *
 * A mudança vai à trilha (o plano, com a regra) e publica [RatesChanged] (`DERIVATION_CHANGED`, da data operacional
 * em diante, com os planos que derivam deste).
 */
@Service
@Suppress("LongParameterList") // as portas de que a derivação precisa
class RatePlanDerivationService(
    private val store: RatesStore,
    private val prices: RatePriceStore,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Audited(RatesAudit.SET_DERIVATION)
    @Transactional
    fun derive(
        id: RatePlanId,
        expectedVersion: Long,
        command: DerivationCommand,
        approvalToken: String?,
    ): RatePlanView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val adjustment = PriceAdjustment.of(command.type, command.percent, command.amount, command.perPerson)
        val rule = DerivationRule(command.basePlanId, adjustment)
        prices.lockDerivations()
        val plan = forUpdate(id, expectedVersion)
        if (store.ratePlan(rule.basePlanId) == null) {
            throw RatesRules.invalid(RatesErrorCodes.UNKNOWN_RATE_PLAN, "basePlanId", "O plano base não existe.")
        }
        val descendants = prices.descendants(plan.id)
        Derivations.requireValid(
            plan.id,
            rule.basePlanId,
            baseChain(plan.id, rule.basePlanId),
            descendants.maxOfOrNull { it.depth } ?: 0,
        )
        val current = plan.derivation
        if (current == rule) return plan.view().also { audit.noChanges() }
        if (current == null) {
            if (prices.hasPricesFrom(plan.id, hotel.currentBusinessDate())) {
                throw BusinessRuleException(
                    RatesErrorCodes.RATE_PLAN_HAS_PRICES,
                    "O plano tem preços próprios a partir da data operacional; retire-os antes de torná-lo derivado.",
                )
            }
        } else {
            authorizeOverride(plan, descendants, RatesAudit.SET_DERIVATION, approvalToken)
        }
        return change(plan, rule, descendants)
    }

    @Audited(RatesAudit.REMOVE_DERIVATION)
    @Transactional
    fun removeDerivation(
        id: RatePlanId,
        expectedVersion: Long,
        approvalToken: String?,
    ): RatePlanView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        prices.lockDerivations()
        val plan = forUpdate(id, expectedVersion)
        if (plan.derivation == null) return plan.view().also { audit.noChanges() }
        val descendants = prices.descendants(plan.id)
        authorizeOverride(plan, descendants, RatesAudit.REMOVE_DERIVATION, approvalToken)
        return change(plan, null, descendants)
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun forUpdate(
        id: RatePlanId,
        expectedVersion: Long,
    ): RatePlan {
        val plan =
            store.ratePlanForUpdate(id)
                ?: throw NotFoundException(RatesErrorCodes.RATE_PLAN_NOT_FOUND, "Plano não encontrado.")
        requireCurrentVersion(expectedVersion, checkNotNull(plan.version))
        audit.track(RatesAudit.RATE_PLAN, id.value) { plan.auditState() }
        return plan
    }

    /**
     * A cadeia a partir de [base], subindo pelos bases (lidos com a trava exclusiva da árvore, então sem ciclo), até
     * um plano com preços próprios ou até chegar a [planId] (ciclo); nunca mais que um nível além do teto.
     */
    private fun baseChain(
        planId: RatePlanId,
        base: RatePlanId,
    ): List<RatePlanId> {
        val chain = mutableListOf<RatePlanId>()
        var current: RatePlanId? = base
        while (current != null && chain.size <= Derivations.MAX_DEPTH) {
            chain += current
            if (current == planId) break
            current = store.ratePlan(current)?.derivation?.basePlanId
        }
        return chain
    }

    private fun authorizeOverride(
        plan: RatePlan,
        descendants: List<DerivedPlan>,
        action: String,
        approvalToken: String?,
    ) {
        if (!plan.active && descendants.none { it.active }) return
        authorization.requireOrApproval(
            SensitiveAction(action, RatesPermissions.PRICES_OVERRIDE, "rates.rate-plan:${plan.id}"),
            approvalToken,
        )
    }

    private fun change(
        plan: RatePlan,
        rule: DerivationRule?,
        descendants: List<DerivedPlan>,
    ): RatePlanView {
        val now = clock.instant()
        plan.derive(rule, now)
        store.flush()
        events.publishEvent(
            RatesChanged(
                plan.id.value,
                RatesChangeKind.DERIVATION_CHANGED,
                plan.roomTypeIds.map { it.value }.sorted(),
                hotel.currentBusinessDate(),
                null,
                descendants.map { it.id.value }.sorted(),
                now,
            ),
        )
        return plan.view()
    }
}
