package com.zeny.hotel.reporting.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.StorableRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

private const val SECONDS_PER_MINUTE = 60L
private const val PERCENT = 100L

/** A camareira no relatório de governança. */
data class AttendantReportRow(
    val employeeId: UUID,
    val rooms: Long,
    val credits: Long,
)

/** O tipo de limpeza num tipo de UH: o tempo médio (minutos, 1 casa) e a taxa de reprovação, `null` sem o que medir. */
data class CleaningTypeReportRow(
    val cleaningTypeId: UUID,
    val roomTypeId: UUID,
    val tasks: Long,
    val averageMinutes: BigDecimal?,
    val inspections: Long,
    val rejections: Long,
    val rejectionRate: Percent?,
)

/** As inspeções voluntárias (sem tarefa, sem tipo) à parte. */
data class VoluntaryInspectionsReport(
    val inspections: Long,
    val rejections: Long,
    val rejectionRate: Percent?,
)

/** O relatório de governança do período. */
data class HousekeepingReport(
    val from: LocalDate,
    val to: LocalDate,
    val attendants: List<AttendantReportRow>,
    val cleaningTypes: List<CleaningTypeReportRow>,
    val voluntaryInspections: VoluntaryInspectionsReport,
)

/**
 * O relatório de governança (RF-REL, M6 E09, D5), pelo `business_date` das tarefas: UHs e créditos por camareira,
 * tempo médio de limpeza (de `worked_seconds`, sem as pausas) e taxa de reprovação por tipo de limpeza e tipo de UH.
 * Exige `reporting.housekeeping.read`. Só leitura: nada é gravado nem auditado. A taxa de reprovação é das inspeções
 * concluídas das tarefas do período; sem inspeção é `null`, nunca zero. As inspeções voluntárias entram à parte, pelo
 * instante da conclusão no fuso do hotel.
 */
@Service
class HousekeepingReportService(
    private val queries: ReportingQueries,
    private val authorization: Authorization,
    private val hotel: HotelApi,
) {
    @Transactional(readOnly = true)
    fun report(
        from: LocalDate,
        to: LocalDate,
    ): HousekeepingReport {
        authorization.require(ReportingPermissions.HOUSEKEEPING_READ)
        requireValidReportPeriod(from, to)
        val zone = hotel.timezone()
        val voluntary =
            queries.voluntaryInspections(
                StorableRange.startOfDay(from, zone),
                StorableRange.startOfDay(to.plusDays(1), zone),
            )
        return HousekeepingReport(
            from = from,
            to = to,
            attendants =
                queries
                    .attendantProductivity(
                        from,
                        to,
                    ).map { AttendantReportRow(it.employeeId, it.rooms, it.credits) },
            cleaningTypes = queries.cleaningByType(from, to).map(::cleaningTypeRow),
            voluntaryInspections =
                VoluntaryInspectionsReport(
                    voluntary.inspections,
                    voluntary.rejections,
                    rate(voluntary.rejections, voluntary.inspections),
                ),
        )
    }

    private fun cleaningTypeRow(row: CleaningTypeRow) =
        CleaningTypeReportRow(
            cleaningTypeId = row.cleaningTypeId,
            roomTypeId = row.roomTypeId,
            tasks = row.tasks,
            averageMinutes =
                row.tasks.takeIf { it > 0 }?.let {
                    BigDecimal(row.workedSeconds).divide(BigDecimal(it * SECONDS_PER_MINUTE), 1, RoundingMode.HALF_UP)
                },
            inspections = row.inspections,
            rejections = row.rejections,
            rejectionRate = rate(row.rejections, row.inspections),
        )

    private fun rate(
        part: Long,
        total: Long,
    ): Percent? =
        total.takeIf { it > 0 }?.let {
            Percent.of(BigDecimal(part * PERCENT).divide(BigDecimal(it), Percent.SCALE, RoundingMode.HALF_UP))
        }
}
