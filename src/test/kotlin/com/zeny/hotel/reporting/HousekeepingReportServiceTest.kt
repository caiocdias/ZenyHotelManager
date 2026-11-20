package com.zeny.hotel.reporting

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.application.AttendantProductivityRow
import com.zeny.hotel.reporting.application.CleaningTypeRow
import com.zeny.hotel.reporting.application.HousekeepingReportService
import com.zeny.hotel.reporting.application.InspectionCountsRow
import com.zeny.hotel.reporting.application.ReportingQueries
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
import java.util.UUID

/**
 * O relatório de governança (M6 E09, D5), sobre uma porta falsa: os zeros e o `null` dos casos que um banco
 * compartilhado não consegue provar (Review Focus 2) e o período.
 */
class HousekeepingReportServiceTest {
    private val authorization = mockk<Authorization>(relaxed = true)
    private val hotel = mockk<HotelApi> { every { timezone() } returns ZoneId.of("America/Sao_Paulo") }
    private val day = LocalDate.of(2031, 4, 12)

    private class FakeQueries(
        val attendants: List<AttendantProductivityRow> = emptyList(),
        val types: List<CleaningTypeRow> = emptyList(),
        val voluntary: InspectionCountsRow = InspectionCountsRow(0, 0),
    ) : FakeReportingQueries() {
        var voluntaryRange: Pair<Instant, Instant>? = null

        override fun attendantProductivity(
            from: LocalDate,
            to: LocalDate,
        ) = attendants

        override fun cleaningByType(
            from: LocalDate,
            to: LocalDate,
        ) = types

        override fun voluntaryInspections(
            from: Instant,
            to: Instant,
        ): InspectionCountsRow {
            voluntaryRange = from to to
            return voluntary
        }
    }

    private fun service(queries: ReportingQueries) = HousekeepingReportService(queries, authorization, hotel)

    @Test
    fun `an attendant with no finished task has zeros and a type without inspection has a null rate`() {
        val attendant = UUID.randomUUID()
        val type = UUID.randomUUID()
        val roomType = UUID.randomUUID()
        val queries =
            FakeQueries(
                attendants = listOf(AttendantProductivityRow(attendant, rooms = 0, credits = 0)),
                types =
                    listOf(
                        CleaningTypeRow(type, roomType, tasks = 0, workedSeconds = 0, inspections = 0, rejections = 0),
                    ),
            )

        val report = service(queries).report(day, day)

        report.attendants.single().let {
            it.employeeId shouldBe attendant
            it.rooms shouldBe 0L
            it.credits shouldBe 0L
        }
        report.cleaningTypes.single().let {
            it.averageMinutes shouldBe null
            it.inspections shouldBe 0L
            it.rejectionRate shouldBe null
        }
        report.voluntaryInspections.rejectionRate shouldBe null
    }

    @Test
    fun `the average time comes from the worked seconds and the rejection rate from the inspections`() {
        val type = UUID.randomUUID()
        val queries =
            FakeQueries(
                types =
                    listOf(
                        // 3 limpezas, 5400 s no total: 30 min em média; 1 reprovação em 3 inspeções.
                        CleaningTypeRow(type, UUID.randomUUID(), 3, 5400, 3, 1),
                        // 7 limpezas, 633 s: 1,5 min arredondado a 1 casa; sem reprovação: taxa zero (não nula).
                        CleaningTypeRow(type, UUID.randomUUID(), 7, 7 * 90 + 3, 2, 0),
                    ),
                voluntary = InspectionCountsRow(inspections = 4, rejections = 1),
            )

        val report = service(queries).report(day, day)

        report.cleaningTypes[0].averageMinutes shouldBe BigDecimal("30.0")
        report.cleaningTypes[0].rejectionRate.toString() shouldBe "33.3333"
        report.cleaningTypes[1].averageMinutes shouldBe BigDecimal("1.5")
        report.cleaningTypes[1].rejectionRate.toString() shouldBe "0.0000"
        report.voluntaryInspections.inspections shouldBe 4L
        report.voluntaryInspections.rejectionRate.toString() shouldBe "25.0000"
    }

    @Test
    fun `the voluntary inspections are read for the period as instants in the hotel time zone`() {
        val queries = FakeQueries()

        service(queries).report(day, day.plusDays(1))

        // São Paulo é UTC-3 em abril de 2031: do início do dia 12 ao início do dia 14.
        queries.voluntaryRange shouldBe (Instant.parse("2031-04-12T03:00:00Z") to Instant.parse("2031-04-14T03:00:00Z"))
    }

    @Test
    fun `a period that ends before it starts or lasts more than 366 days is refused`() {
        val service = service(FakeQueries())

        shouldThrow<InvalidInputException> { service.report(day, day.minusDays(1)) }
            .code.value shouldBe "REPORT_PERIOD_INVALID"
        shouldThrow<InvalidInputException> { service.report(day, day.plusDays(366)) }
            .code.value shouldBe "REPORT_PERIOD_INVALID"
        // 366 dias, as duas pontas inclusive, é o limite.
        service.report(day, day.plusDays(365)).from shouldBe day
        verify(exactly = 3) { authorization.require(ReportingPermissions.HOUSEKEEPING_READ) }
    }
}
