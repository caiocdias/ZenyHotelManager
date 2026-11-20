package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.domain.DailyStatisticsCalculator
import com.zeny.hotel.reporting.domain.DailyStatisticsInput
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** As derivadas do snapshot diário (M6 E23, D1), sem Spring nem banco. */
class DailyStatisticsCalculatorTest {
    private val day = LocalDate.of(2052, 3, 4)

    private fun input(
        sold: Long = 0,
        complimentary: Long = 0,
        houseUse: Long = 0,
        available: Long = 0,
        roomRevenue: String = "0.00",
    ) = DailyStatisticsInput(
        roomsSold = sold,
        roomsComplimentary = complimentary,
        roomsHouseUse = houseUse,
        roomsAvailable = available,
        roomsOutOfOrder = 0,
        arrivals = 0,
        departures = 0,
        noShows = 0,
        cancellations = 0,
        roomRevenue = Money.of(roomRevenue),
        foodBeverageRevenue = Money.ZERO,
        otherRevenue = Money.ZERO,
    )

    @Test
    fun `a date without any movement has zeros and null derived figures`() {
        val snapshot = DailyStatisticsCalculator.calculate(day, input())

        snapshot.businessDate shouldBe day
        snapshot.roomsSold shouldBe 0
        snapshot.roomRevenue shouldBe Money.ZERO
        snapshot.eventsRevenue shouldBe Money.ZERO
        snapshot.occupancy shouldBe null
        snapshot.adr shouldBe null
        snapshot.revpar shouldBe null
    }

    @Test
    fun `occupancy counts sold plus complimentary over available, never house use`() {
        val snapshot =
            DailyStatisticsCalculator.calculate(
                day,
                input(sold = 3, complimentary = 1, houseUse = 5, available = 10),
            )

        snapshot.occupancy shouldBe Percent.of("40")
    }

    @Test
    fun `adr is the room revenue over the sold rooms and revpar over the available, half even`() {
        // 100,01 / 2 = 50,005 -> 50,00 (HALF_EVEN, o vizinho par); 100,03 / 2 = 50,015 -> 50,02.
        DailyStatisticsCalculator.calculate(day, input(sold = 2, available = 4, roomRevenue = "100.01")).also {
            it.adr shouldBe Money.of("50.00")
            it.revpar shouldBe Money.of("25.00")
        }
        DailyStatisticsCalculator.calculate(day, input(sold = 2, available = 8, roomRevenue = "100.03")).also {
            it.adr shouldBe Money.of("50.02")
            it.revpar shouldBe Money.of("12.50")
        }
    }

    @Test
    fun `adr is null without sold rooms even with revenue, and revpar is null without available rooms`() {
        val onlyRevenue = DailyStatisticsCalculator.calculate(day, input(available = 5, roomRevenue = "80.00"))
        onlyRevenue.adr shouldBe null
        onlyRevenue.revpar shouldBe Money.of("16.00")

        val noAvailable = DailyStatisticsCalculator.calculate(day, input(sold = 1, roomRevenue = "80.00"))
        noAvailable.adr shouldBe Money.of("80.00")
        noAvailable.revpar shouldBe null
        noAvailable.occupancy shouldBe null
    }

    @Test
    fun `occupancy above the ceiling is null`() {
        // 1.000 vendidas sobre 1 disponível = 100.000%: acima de 999,9999%.
        DailyStatisticsCalculator.calculate(day, input(sold = 1_000, available = 1)).occupancy shouldBe null
        // 9 sobre 1 = 900%: cabe.
        DailyStatisticsCalculator.calculate(day, input(sold = 9, available = 1)).occupancy shouldBe Percent.of("900")
    }

    @Test
    fun `occupancy rounds to 2 places`() {
        DailyStatisticsCalculator.calculate(day, input(sold = 1, available = 3)).occupancy shouldBe Percent.of("33.33")
    }
}
