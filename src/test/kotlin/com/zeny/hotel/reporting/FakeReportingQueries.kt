package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.application.AttendantProductivityRow
import com.zeny.hotel.reporting.application.CleaningTypeRow
import com.zeny.hotel.reporting.application.CompletedWorkOrdersRow
import com.zeny.hotel.reporting.application.DayCapacityRow
import com.zeny.hotel.reporting.application.DayMovementsRow
import com.zeny.hotel.reporting.application.InspectionCountsRow
import com.zeny.hotel.reporting.application.OpenCashierShiftRow
import com.zeny.hotel.reporting.application.OpenFolioBalanceRow
import com.zeny.hotel.reporting.application.OutOfOrderCountsRow
import com.zeny.hotel.reporting.application.PendingFnrhSubmissionRow
import com.zeny.hotel.reporting.application.ReportingQueries
import com.zeny.hotel.reporting.application.RoomCountsRow
import com.zeny.hotel.reporting.application.WorkOrderCountRow
import com.zeny.hotel.reporting.domain.DailyStatisticsInput
import com.zeny.hotel.shared.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A porta falsa dos testes de unidade: tudo vazio ou zero, e cada teste sobrescreve o que quer provar. */
open class FakeReportingQueries : ReportingQueries {
    override fun attendantProductivity(
        from: LocalDate,
        to: LocalDate,
    ): List<AttendantProductivityRow> = emptyList()

    override fun cleaningByType(
        from: LocalDate,
        to: LocalDate,
    ): List<CleaningTypeRow> = emptyList()

    override fun voluntaryInspections(
        from: Instant,
        to: Instant,
    ): InspectionCountsRow = InspectionCountsRow(0, 0)

    override fun dayMovements(
        from: LocalDate,
        to: LocalDate,
    ): List<DayMovementsRow> = emptyList()

    override fun dayCapacity(
        from: LocalDate,
        to: LocalDate,
    ): List<DayCapacityRow> = emptyList()

    override fun roomCounts() = RoomCountsRow(0, 0, 0, 0, 0, 0, 0)

    override fun outOfOrderOn(on: LocalDate) = OutOfOrderCountsRow(0, 0)

    override fun openFolioBalances(): List<OpenFolioBalanceRow> = emptyList()

    override fun openCashierShifts(): List<OpenCashierShiftRow> = emptyList()

    override fun pendingFnrhSubmissions(): List<PendingFnrhSubmissionRow> = emptyList()

    override fun workOrdersByStatus(
        from: Instant,
        to: Instant,
    ): List<WorkOrderCountRow> = emptyList()

    override fun workOrdersByCategory(
        from: Instant,
        to: Instant,
    ): List<WorkOrderCountRow> = emptyList()

    override fun completedWorkOrders(
        from: Instant,
        to: Instant,
    ) = CompletedWorkOrdersRow(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

    override fun dailyStatisticsInput(
        date: LocalDate,
        from: Instant,
        to: Instant,
    ) = DailyStatisticsInput(0, 0, 0, 0, 0, 0, 0, 0, 0, Money.ZERO, Money.ZERO, Money.ZERO)
}
