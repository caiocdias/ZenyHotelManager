package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.ChildAgeBandSummary
import com.zeny.hotel.rates.DailyChildRate
import com.zeny.hotel.rates.DailyRates
import com.zeny.hotel.rates.DailyRestriction
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackageSummary
import com.zeny.hotel.rates.RateCalendar
import com.zeny.hotel.rates.RateCalendarDay
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.Sellability
import com.zeny.hotel.rates.StayOption
import com.zeny.hotel.rates.StayOptionsQuery
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.rates.domain.ChildBands
import com.zeny.hotel.rates.domain.EffectiveRestriction
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSelection
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatesRules
import com.zeny.hotel.rates.domain.SaleConditions
import com.zeny.hotel.rates.domain.SaleRules
import com.zeny.hotel.rates.domain.StayPricing
import com.zeny.hotel.rates.domain.StayRules
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomTypeSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * O preço e a vendabilidade de uma estadia (M4 E04; `RatesApi.priceFor` e `isSellable`), a cotação em vários planos e
 * tipos (E06, `stayOptions`) e o calendário de preços e restrições (E06, `rateCalendar`), na transação de quem chama
 * (só leitura). Lê o plano, os preços **efetivos** do tipo de UH nas noites ([RatePriceReader], que aplica a derivação,
 * H1), as faixas de criança atuais do hotel (H2), os pacotes do plano, as restrições efetivas da chegada à saída
 * ([RestrictionReader]), o tipo de UH pela `RoomsApi` e a data operacional pela `HotelApi` (RN-01, H15); as regras são
 * do domínio ([StayPricing], [SaleRules]).
 *
 * As quatro leituras passam pelo mesmo [Prepared], que lê os preços e as restrições de cada plano **uma vez** para o
 * período inteiro (nunca por noite nem por tipo de UH) e os pacotes de todos os planos numa consulta: a cotação de
 * vários planos e tipos dá exatamente o resultado de `isSellable` e `priceFor` de cada combinação.
 */
@Component
@Suppress("LongParameterList") // as portas de que a avaliação precisa
class StayEvaluator(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val prices: RatePriceReader,
    private val restrictions: RestrictionReader,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
) {
    fun priceFor(query: StayQuery): StayPriceResult {
        val bands = prices.currentChildBands()
        StayRules.requireValid(query, bands)
        val plan = store.ratePlan(query.ratePlanId)
        return when {
            plan == null -> {
                StayPriceResult.NotPriced(listOf(UNKNOWN_PLAN))
            }

            query.roomTypeId !in plan.roomTypeIds -> {
                StayPriceResult.NotPriced(listOf(NOT_IN_PLAN))
            }

            else -> {
                Prepared(
                    listOf(plan),
                    setOf(query.roomTypeId),
                    query.arrival,
                    query.departure,
                    bands,
                ).price(plan, query)
            }
        }
    }

    fun isSellable(
        query: StayQuery,
        channel: SalesChannel,
    ): Sellability {
        val bands = prices.currentChildBands()
        StayRules.requireValid(query, bands)
        val plan = store.ratePlan(query.ratePlanId)
        val roomType = rooms.roomType(query.roomTypeId)
        return when {
            plan == null -> {
                Sellability(listOf(UNKNOWN_PLAN))
            }

            roomType == null -> {
                Sellability(listOf(UNKNOWN_ROOM_TYPE))
            }

            else -> {
                Prepared(listOf(plan), setOf(query.roomTypeId), query.arrival, query.departure, bands)
                    .evaluate(plan, roomType, query, channel)
                    .sellability
            }
        }
    }

    fun stayOptions(query: StayOptionsQuery): List<StayOption> {
        val bands = prices.currentChildBands()
        StayRules.requireValid(query.stay(PLACEHOLDER_PLAN, PLACEHOLDER_ROOM_TYPE), bands)
        val plans = candidatePlans(query)
        if (plans.isEmpty()) return emptyList()
        val roomTypes = query.roomTypeIds.associateWith { rooms.roomType(it) }
        val prepared = Prepared(plans, query.roomTypeIds, query.arrival, query.departure, bands)
        return plans.flatMap { plan ->
            val summary = plan.summary()
            query.roomTypeIds
                .filter { it in plan.roomTypeIds }
                .sortedBy { it.value.toString() }
                .map { type ->
                    val stay = query.stay(plan.id, type)
                    val roomType = roomTypes[type]
                    if (roomType == null) {
                        val price = prepared.price(plan, stay)
                        StayOption(summary, type, Sellability(listOf(UNKNOWN_ROOM_TYPE)), price.priced())
                    } else {
                        val evaluation = prepared.evaluate(plan, roomType, stay, query.channel)
                        StayOption(summary, type, evaluation.sellability, evaluation.price.priced())
                    }
                }
        }
    }

    fun rateCalendar(
        ratePlanId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): RateCalendar? {
        require(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) < PriceSelection.MAX_DAYS) {
            "Período do calendário inválido: $from a $to"
        }
        val plan = store.ratePlan(ratePlanId) ?: return null
        val bands = prices.currentChildBands()
        val types = roomTypeIds.distinct().filter { it in plan.roomTypeIds }
        val nightly = if (types.isEmpty()) emptyMap() else prices.effectivePrices(plan, types, from, to)
        val effective = if (types.isEmpty()) emptyMap() else restrictions.effective(plan.id, types, from, to)
        val dates = from.datesUntil(to.plusDays(1)).toList()
        val days =
            types.flatMap { type ->
                dates.map { date ->
                    RateCalendarDay(
                        type,
                        date,
                        nightly[PriceKey(type, date)]?.let { daily(it, bands) },
                        (effective[type to date] ?: EffectiveRestriction.NONE).public(),
                    )
                }
            }
        return RateCalendar(
            plan.summary(),
            bands.bands.map { ChildAgeBandSummary(it.minAge, it.maxAge, it.label) },
            days,
        )
    }

    /** Os planos da cotação: os pedidos (todos precisam existir) ou os ativos com algum dos tipos, por código. */
    private fun candidatePlans(query: StayOptionsQuery): List<RatePlan> {
        val requested = query.ratePlanIds
        val plans =
            if (requested == null) {
                store.ratePlans(directory.activeRatePlansWith(query.roomTypeIds))
            } else {
                val loaded = store.ratePlans(requested.map { it.value })
                if (loaded.size != requested.size) {
                    throw RatesRules.invalid(
                        RatesErrorCodes.UNKNOWN_RATE_PLAN,
                        "ratePlanIds",
                        "Algum dos planos tarifários informados não existe.",
                    )
                }
                loaded
            }
        return plans.sortedWith(compareBy({ it.code }, { it.id.value.toString() }))
    }

    private fun daily(
        prices: PriceSet,
        bands: ChildBands,
    ): DailyRates {
        val (current, _) = bands.classify(prices.children)
        return DailyRates(
            prices.oneAdult,
            prices.twoAdults,
            prices.extraAdult,
            bands.bands.map { band ->
                DailyChildRate(band.minAge, band.maxAge, band.label, current.firstOrNull { it.first == band }?.second)
            },
        )
    }

    private fun EffectiveRestriction.public() =
        DailyRestriction(closed, closedToArrival, closedToDeparture, minStay, maxStay, minAdvanceDays, maxAdvanceDays)

    private fun StayPriceResult.priced() = (this as? StayPriceResult.Priced)?.price

    /** A vendabilidade e o resultado do preço (nulo se o tipo não está no plano) de uma combinação. */
    private data class Evaluation(
        val sellability: Sellability,
        val price: StayPriceResult,
    )

    /**
     * O que a avaliação lê, para os [plans] e os tipos [roomTypeIds] da chegada [arrival] à saída [departure]: os
     * pacotes de todos os planos numa consulta e, por plano (na primeira vez que é pedido), os preços efetivos das
     * noites e as restrições efetivas da chegada à saída de todos os tipos dele, numa leitura cada.
     */
    private inner class Prepared(
        plans: List<RatePlan>,
        private val roomTypeIds: Set<RoomTypeId>,
        private val arrival: LocalDate,
        private val departure: LocalDate,
        private val bands: ChildBands,
    ) {
        private val packages: Map<PackageId, PackageSummary> =
            plans
                .flatMap { it.packageIds }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { ids -> store.ratePackages(ids.map { it.value }).associate { it.id to it.summary() } }
                .orEmpty()
        private val nightly = HashMap<RatePlanId, Map<PriceKey, PriceSet>>()
        private val effective = HashMap<RatePlanId, Map<Pair<RoomTypeId, LocalDate>, EffectiveRestriction>>()
        private val businessDate by lazy { hotel.currentBusinessDate() }

        private fun typesOf(plan: RatePlan) = roomTypeIds.filter { it in plan.roomTypeIds }

        fun price(
            plan: RatePlan,
            query: StayQuery,
        ): StayPriceResult {
            val planPrices =
                nightly.getOrPut(
                    plan.id,
                ) { prices.effectivePrices(plan, typesOf(plan), arrival, departure.minusDays(1)) }
            val stayPrices =
                planPrices.filterKeys { it.roomTypeId == query.roomTypeId }.mapKeys { it.key.date }
            val planPackages = plan.packageIds.mapNotNull { packages[it] }.sortedBy { it.code }
            return StayPricing.price(query, stayPrices, bands, planPackages)
        }

        fun evaluate(
            plan: RatePlan,
            roomType: RoomTypeSummary,
            query: StayQuery,
            channel: SalesChannel,
        ): Evaluation {
            val inPlan = query.roomTypeId in plan.roomTypeIds
            val restrictionsByDate =
                if (inPlan) {
                    effective
                        .getOrPut(plan.id) { restrictions.effective(plan.id, typesOf(plan), arrival, departure) }
                        .filterKeys { it.first == query.roomTypeId }
                        .mapKeys { it.key.second }
                } else {
                    emptyMap()
                }
            val conditions =
                SaleConditions(
                    planActive = plan.active,
                    channels = plan.channels,
                    planRoomTypeIds = plan.roomTypeIds,
                    roomTypeActive = roomType.active,
                    maxAdults = roomType.occupancy.maxAdults,
                    maxChildren = roomType.occupancy.maxChildren,
                    businessDate = businessDate,
                    restrictions = restrictionsByDate,
                )
            val rules = SaleRules.evaluate(query, channel, conditions)
            val price = if (inPlan) price(plan, query) else StayPriceResult.NotPriced(listOf(NOT_IN_PLAN))
            val pricing = if (inPlan) (price as? StayPriceResult.NotPriced)?.refusals.orEmpty() else emptyList()
            return Evaluation(Sellability(rules + pricing), price)
        }
    }

    private companion object {
        val UNKNOWN_PLAN = SaleRefusal(RatesErrorCodes.UNKNOWN_RATE_PLAN, "O plano tarifário não existe.")
        val UNKNOWN_ROOM_TYPE = SaleRefusal(RatesErrorCodes.UNKNOWN_ROOM_TYPE, "O tipo de UH não existe.")
        val NOT_IN_PLAN =
            SaleRefusal(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN, "O tipo de UH não está no plano tarifário.")

        /** Ids que nunca existem, só para conferir o formato da estadia da cotação antes de escolher os planos. */
        val PLACEHOLDER_PLAN = RatePlanId(UuidV7.generate())
        val PLACEHOLDER_ROOM_TYPE = RoomTypeId(UuidV7.generate())
    }
}
