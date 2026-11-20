package com.zeny.hotel.reporting.domain

import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Os números de uma data como as views `report_*` os dão, antes das derivadas: contagens disjuntas das noites
 * vendidas que hospedam e receitas dos lançamentos sem repasse nem impostos (`other` sem `TAXES`).
 */
data class DailyStatisticsInput(
    val roomsSold: Long,
    val roomsComplimentary: Long,
    val roomsHouseUse: Long,
    val roomsAvailable: Long,
    val roomsOutOfOrder: Long,
    val arrivals: Long,
    val departures: Long,
    val noShows: Long,
    val cancellations: Long,
    val roomRevenue: Money,
    val foodBeverageRevenue: Money,
    val otherRevenue: Money,
)

/** Calcula as derivadas do snapshot (D1): ocupação, ADR e RevPAR. */
object DailyStatisticsCalculator {
    private const val PERCENT = 100L
    private const val OCCUPANCY_SCALE = 2

    /** O maior percentual que o `Percent` guarda (`numeric(7,4)`). */
    private val MAX_OCCUPANCY = BigDecimal("999.9999")

    fun calculate(
        date: LocalDate,
        input: DailyStatisticsInput,
    ): DailyStatisticsSnapshot =
        DailyStatisticsSnapshot(
            businessDate = date,
            roomsSold = input.roomsSold,
            roomsComplimentary = input.roomsComplimentary,
            roomsHouseUse = input.roomsHouseUse,
            roomsAvailable = input.roomsAvailable,
            roomsOutOfOrder = input.roomsOutOfOrder,
            arrivals = input.arrivals,
            departures = input.departures,
            noShows = input.noShows,
            cancellations = input.cancellations,
            roomRevenue = input.roomRevenue,
            foodBeverageRevenue = input.foodBeverageRevenue,
            eventsRevenue = Money.ZERO,
            otherRevenue = input.otherRevenue,
            occupancy = occupancy(input.roomsSold + input.roomsComplimentary, input.roomsAvailable),
            adr = average(input.roomRevenue, input.roomsSold),
            revpar = average(input.roomRevenue, input.roomsAvailable),
        )

    /** As noites que contam sobre as disponíveis, em percentual de 2 casas; `null` sem disponíveis ou acima do teto. */
    private fun occupancy(
        occupied: Long,
        available: Long,
    ): Percent? =
        available
            .takeIf { it > 0 }
            ?.let { BigDecimal(occupied * PERCENT).divide(BigDecimal(it), OCCUPANCY_SCALE, RoundingMode.HALF_UP) }
            ?.takeIf { it <= MAX_OCCUPANCY }
            ?.let(Percent::of)

    /** O [revenue] por [divisor], pela regra única de arredondamento do dinheiro; `null` com divisor zero. */
    private fun average(
        revenue: Money,
        divisor: Long,
    ): Money? =
        divisor
            .takeIf { it > 0 }
            ?.let { Money.round(revenue.amount.divide(BigDecimal(it), Money.SCALE, Money.ROUNDING_MODE)) }
}
