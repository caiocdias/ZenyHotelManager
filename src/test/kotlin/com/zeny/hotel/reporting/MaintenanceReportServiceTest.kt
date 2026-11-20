package com.zeny.hotel.reporting

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.application.CompletedWorkOrdersRow
import com.zeny.hotel.reporting.application.MaintenanceCategory
import com.zeny.hotel.reporting.application.MaintenanceReportService
import com.zeny.hotel.reporting.application.MaintenanceStatus
import com.zeny.hotel.reporting.application.WorkOrderCountRow
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * O relatório de manutenção (M6 E09, D5) sobre uma porta falsa: o período vazio (zeros e tempo médio `null`), o
 * arredondamento do tempo médio e o período em instantes no fuso do hotel.
 */
class MaintenanceReportServiceTest {
    private val authorization = mockk<Authorization>(relaxed = true)
    private val hotel = mockk<HotelApi> { every { timezone() } returns ZoneId.of("America/Sao_Paulo") }
    private val day = LocalDate.of(2031, 4, 12)

    private class Queries(
        val statuses: List<WorkOrderCountRow> = emptyList(),
        val categories: List<WorkOrderCountRow> = emptyList(),
        val completed: CompletedWorkOrdersRow =
            CompletedWorkOrdersRow(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
    ) : FakeReportingQueries() {
        var range: Pair<Instant, Instant>? = null

        override fun workOrdersByStatus(
            from: Instant,
            to: Instant,
        ): List<WorkOrderCountRow> {
            range = from to to
            return statuses
        }

        override fun workOrdersByCategory(
            from: Instant,
            to: Instant,
        ) = categories

        override fun completedWorkOrders(
            from: Instant,
            to: Instant,
        ) = completed
    }

    private fun service(queries: Queries) = MaintenanceReportService(queries, authorization, hotel)

    @Test
    fun `a period with no work order gives empty lists, zero costs and a null average time`() {
        val report = service(Queries()).report(day, day)

        report.byStatus shouldBe emptyList()
        report.byCategory shouldBe emptyList()
        report.completed.orders shouldBe 0L
        report.completed.averageHours shouldBe null
        report.completed.laborCost.toString() shouldBe "0.00"
        report.completed.totalCost.toString() shouldBe "0.00"
        verify { authorization.require(ReportingPermissions.MAINTENANCE_READ) }
    }

    @Test
    fun `the average time is the total seconds over the orders in hours with 1 place, and the cost is the sum`() {
        val queries =
            Queries(
                statuses = listOf(WorkOrderCountRow("OPEN", 2), WorkOrderCountRow("DONE", 3)),
                categories = listOf(WorkOrderCountRow("PLUMBING", 5)),
                // 10000 s / 3 ordens / 3600 = 0,9259 h → 0,9.
                completed =
                    CompletedWorkOrdersRow(
                        3,
                        BigDecimal(10_000),
                        BigDecimal("10.50"),
                        BigDecimal("20.00"),
                        BigDecimal("5.25"),
                    ),
            )

        val report = service(queries).report(day, day)

        report.byStatus.map { it.status to it.orders } shouldBe
            listOf(MaintenanceStatus.OPEN to 2L, MaintenanceStatus.DONE to 3L)
        report.byCategory.map { it.category to it.orders } shouldBe listOf(MaintenanceCategory.PLUMBING to 5L)
        report.completed.averageHours shouldBe BigDecimal("0.9")
        report.completed.laborCost.toString() shouldBe "10.50"
        report.completed.thirdPartyCost.toString() shouldBe "20.00"
        report.completed.partsCost.toString() shouldBe "5.25"
        report.completed.totalCost.toString() shouldBe "35.75"
    }

    @Test
    fun `the period is read as instants in the hotel time zone, to exclusive`() {
        val queries = Queries()

        service(queries).report(day, day.plusDays(1))

        // São Paulo é UTC-3 em 2031.
        queries.range shouldBe (Instant.parse("2031-04-12T03:00:00Z") to Instant.parse("2031-04-14T03:00:00Z"))
    }

    @Test
    fun `a period that ends before it starts or lasts more than 366 days is refused`() {
        val service = service(Queries())

        shouldThrow<InvalidInputException> { service.report(day, day.minusDays(1)) }
            .code.value shouldBe "REPORT_PERIOD_INVALID"
        shouldThrow<InvalidInputException> { service.report(day, day.plusDays(366)) }
            .code.value shouldBe "REPORT_PERIOD_INVALID"
        service.report(day, day.plusDays(365))
    }
}
