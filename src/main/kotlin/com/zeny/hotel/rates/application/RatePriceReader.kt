package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.domain.AgeBand
import com.zeny.hotel.rates.domain.ChildBands
import com.zeny.hotel.rates.domain.DerivationRule
import com.zeny.hotel.rates.domain.Derivations
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rooms.RoomTypeId
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Os preços **efetivos** de um plano (RF-TAR-02 e 03, H1): os gravados, num plano com preços próprios; num derivado,
 * os do plano raiz da cadeia (o primeiro, subindo, que não deriva) com as regras aplicadas na leitura, do base mais
 * próximo da raiz até o plano pedido, cada uma pela regra única de `Money`. Nada é gravado no derivado: uma mudança no
 * base aparece no derivado na leitura seguinte. Roda na transação de quem chama (reusado pelo cálculo da E04).
 */
@Component
class RatePriceReader(
    private val store: RatesStore,
    private val prices: RatePriceStore,
    private val hotel: HotelApi,
) {
    /** O plano raiz (com preços próprios) e as regras do caminho até [plan], na ordem de aplicação. */
    fun pricing(plan: RatePlan): Pair<RatePlanId, List<DerivationRule>>? {
        val rules = ArrayDeque<DerivationRule>()
        var current: RatePlan? = plan
        // A árvore nunca tem ciclo (conferido com a trava exclusiva); o teto só protege de um dado corrompido.
        while (current?.derivation != null && rules.size < Derivations.MAX_DEPTH) {
            val rule = checkNotNull(current.derivation)
            rules.addFirst(rule)
            current = store.ratePlan(rule.basePlanId)
        }
        return current?.takeIf { it.derivation == null }?.let { it.id to rules.toList() }
    }

    /** Os preços efetivos de [plan] nos tipos [roomTypeIds], de [from] a [to]; datas sem preço ficam de fora. */
    fun effectivePrices(
        plan: RatePlan,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<PriceKey, PriceSet> {
        val (root, rules) = pricing(plan)?.takeIf { roomTypeIds.isNotEmpty() } ?: return emptyMap()
        return prices.prices(root, roomTypeIds, from, to).mapValues { DerivationRule.applyAll(rules, it.value) }
    }

    /** As faixas de criança atuais do hotel (H2): as da política ou, sem faixas, a faixa única até a idade limite. */
    fun currentChildBands(): ChildBands {
        val policy = hotel.policy()
        return ChildBands.of(policy.childMaxAge, policy.childAgeBands.map { AgeBand(it.minAge, it.maxAge, it.label) })
    }
}
