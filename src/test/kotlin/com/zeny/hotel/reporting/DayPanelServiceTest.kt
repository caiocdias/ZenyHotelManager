package com.zeny.hotel.reporting

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.application.DayCapacityRow
import com.zeny.hotel.reporting.application.DayMovementsRow
import com.zeny.hotel.reporting.application.DayPanelService
import com.zeny.hotel.reporting.application.OutOfOrderCountsRow
import com.zeny.hotel.reporting.application.RoomCountsRow
import com.zeny.hotel.shared.Authorization
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * O painel do dia (M6 E09, D3) sobre uma porta falsa: os zeros e o `null` do dia sem movimento e da data sem inventário
 * (Review Focus 1), que um banco compartilhado não consegue provar.
 */
class DayPanelServiceTest {
    private val today = LocalDate.of(2031, 4, 12)
    private val authorization = mockk<Authorization>(relaxed = true)
    private val hotel = mockk<HotelApi> { every { currentBusinessDate() } returns today }

    private fun service(queries: FakeReportingQueries) = DayPanelService(queries, authorization, hotel)

    @Test
    fun `a hotel with no movement and no inventory gives zeros and a null occupancy, never an error`() {
        val panel = service(FakeReportingQueries()).panel(3)

        panel.businessDate shouldBe today
        panel.days.map { it.date } shouldBe listOf(today, today.plusDays(1), today.plusDays(2))
        panel.days.forEach {
            it.arrivals shouldBe 0L
            it.departures shouldBe 0L
            it.hosted shouldBe 0L
            it.occupancy shouldBe null
        }
        panel.rooms.active shouldBe 0L
        panel.rooms.outOfOrder shouldBe 0L
        panel.rooms.outOfService shouldBe 0L
        verify { authorization.require(ReportingPermissions.DASHBOARD_READ) }
    }

    @Test
    fun `a date with inventory but no sold night has occupancy zero, and one without capacity has null`() {
        val queries =
            object : FakeReportingQueries() {
                override fun dayCapacity(
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(DayCapacityRow(today, 10), DayCapacityRow(today.plusDays(2), 0))
            }

        val days = service(queries).panel(3).days

        days[0].occupancy.toString() shouldBe "0.0000"
        days[1].occupancy shouldBe null
        // Capacidade zero (todas as UHs fora de ordem): sem divisão por zero.
        days[2].occupancy shouldBe null
    }

    @Test
    fun `the occupancy is the occupied nights over the capacity with 2 places, and the movements are carried`() {
        val queries =
            object : FakeReportingQueries() {
                override fun dayMovements(
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    DayMovementsRow(today, arrivals = 3, departures = 2, hosted = 9, occupiedNights = 2),
                    DayMovementsRow(today.plusDays(1), arrivals = 0, departures = 1, hosted = 4, occupiedNights = 1),
                )

                override fun dayCapacity(
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(DayCapacityRow(today, 3), DayCapacityRow(today.plusDays(1), 8))

                override fun roomCounts() = RoomCountsRow(12, 5, 7, 4, 6, 2, 1)

                override fun outOfOrderOn(on: LocalDate) = OutOfOrderCountsRow(2, 1)
            }

        val panel = service(queries).panel(2)

        // 2 de 3 = 66,67%; 1 de 8 = 12,5%.
        panel.days[0].occupancy.toString() shouldBe "66.6700"
        panel.days[1].occupancy.toString() shouldBe "12.5000"
        panel.days[0].arrivals shouldBe 3L
        panel.days[0].departures shouldBe 2L
        panel.days[0].hosted shouldBe 9L
        panel.rooms.let {
            it.active shouldBe 12L
            it.occupied shouldBe 5L
            it.vacant shouldBe 7L
            it.dirty shouldBe 4L
            it.clean shouldBe 6L
            it.inspected shouldBe 2L
            it.inspectionPending shouldBe 1L
            it.outOfOrder shouldBe 2L
            it.outOfService shouldBe 1L
        }
    }

    @Test
    fun `days outside 1 to 30 are refused`() {
        shouldThrow<IllegalArgumentException> { service(FakeReportingQueries()).panel(0) }
        shouldThrow<IllegalArgumentException> { service(FakeReportingQueries()).panel(31) }
        service(FakeReportingQueries()).panel(30).days.size shouldBe 30
    }
}
