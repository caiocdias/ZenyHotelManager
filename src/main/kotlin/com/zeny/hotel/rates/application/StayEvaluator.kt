package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.Sellability
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.rates.domain.ChildBands
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.SaleConditions
import com.zeny.hotel.rates.domain.SaleRules
import com.zeny.hotel.rates.domain.StayPricing
import com.zeny.hotel.rates.domain.StayRules
import com.zeny.hotel.rooms.RoomTypeSummary
import com.zeny.hotel.rooms.RoomsApi
import org.springframework.stereotype.Component

/**
 * O preço e a vendabilidade de uma estadia (M4 E04; `RatesApi.priceFor` e `isSellable`), na transação de quem chama
 * (só leitura). Lê o plano, os preços **efetivos** do tipo de UH nas noites ([RatePriceReader], que aplica a derivação,
 * H1), as faixas de criança atuais do hotel (H2), os pacotes do plano, as restrições efetivas da chegada à saída
 * ([RestrictionReader]), o tipo de UH pela `RoomsApi` e a data operacional pela `HotelApi` (RN-01, H15); as regras são
 * do domínio ([StayPricing], [SaleRules]).
 */
@Component
class StayEvaluator(
    private val store: RatesStore,
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
            plan == null -> StayPriceResult.NotPriced(listOf(UNKNOWN_PLAN))
            query.roomTypeId !in plan.roomTypeIds -> StayPriceResult.NotPriced(listOf(NOT_IN_PLAN))
            else -> price(plan, query, bands)
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
            plan == null -> Sellability(listOf(UNKNOWN_PLAN))
            roomType == null -> Sellability(listOf(UNKNOWN_ROOM_TYPE))
            else -> evaluate(plan, roomType, query, channel, bands)
        }
    }

    private fun evaluate(
        plan: RatePlan,
        roomType: RoomTypeSummary,
        query: StayQuery,
        channel: SalesChannel,
        bands: ChildBands,
    ): Sellability {
        val inPlan = query.roomTypeId in plan.roomTypeIds
        val effective =
            if (inPlan) {
                restrictions
                    .effective(plan.id, listOf(query.roomTypeId), query.arrival, query.departure)
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
                businessDate = hotel.currentBusinessDate(),
                restrictions = effective,
            )
        val rules = SaleRules.evaluate(query, channel, conditions)
        val pricing =
            if (inPlan) {
                (price(plan, query, bands) as? StayPriceResult.NotPriced)?.refusals.orEmpty()
            } else {
                emptyList()
            }
        return Sellability(rules + pricing)
    }

    private fun price(
        plan: RatePlan,
        query: StayQuery,
        bands: ChildBands,
    ): StayPriceResult {
        val lastNight = query.departure.minusDays(1)
        val nightly =
            prices
                .effectivePrices(plan, listOf(query.roomTypeId), query.arrival, lastNight)
                .filterKeys { it.roomTypeId == query.roomTypeId }
                .mapKeys { it.key.date }
        val packages =
            store
                .ratePackages(plan.packageIds.map { it.value })
                .map { it.summary() }
                .sortedBy { it.code }
        return StayPricing.price(query, nightly, bands, packages)
    }

    private companion object {
        val UNKNOWN_PLAN = SaleRefusal(RatesErrorCodes.UNKNOWN_RATE_PLAN, "O plano tarifário não existe.")
        val UNKNOWN_ROOM_TYPE = SaleRefusal(RatesErrorCodes.UNKNOWN_ROOM_TYPE, "O tipo de UH não existe.")
        val NOT_IN_PLAN =
            SaleRefusal(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN, "O tipo de UH não está no plano tarifário.")
    }
}
