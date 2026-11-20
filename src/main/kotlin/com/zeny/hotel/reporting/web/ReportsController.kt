package com.zeny.hotel.reporting.web

import com.zeny.hotel.reporting.application.AttendantReportRow
import com.zeny.hotel.reporting.application.CleaningTypeReportRow
import com.zeny.hotel.reporting.application.HousekeepingReport
import com.zeny.hotel.reporting.application.HousekeepingReportService
import com.zeny.hotel.reporting.application.VoluntaryInspectionsReport
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private const val PERIOD_RULE =
    "Período em datas (`from` e `to`, obrigatórias, inclusive nas duas pontas), de no máximo 366 dias; fim antes do " +
        "início ou período maior: 400 `REPORT_PERIOD_INVALID`."

/**
 * Relatórios operacionais (M6 E09, RF-REL): lêem só as views `report_*` dos módulos (ARCHITECTURE §11) e não trazem
 * dado de hóspede nem texto livre (RN-13). Cada assunto tem a sua permissão.
 */
@ApiV1Controller
@RequestMapping("/reports")
@Tag(name = "reporting", description = "Relatórios operacionais, painel do dia e pendências do fechamento.")
class ReportsController(
    private val housekeeping: HousekeepingReportService,
) {
    @GetMapping("/housekeeping")
    @Operation(
        operationId = "getHousekeepingReport",
        summary = "Relatório de governança",
        description =
            "Pelo `businessDate` das tarefas: por camareira, as UHs limpas e os créditos das limpezas concluídas " +
                "(`AWAITING_INSPECTION` e `DONE`); por tipo de limpeza e tipo de UH (só tarefas de UH), o tempo " +
                "médio em minutos (do tempo trabalhado, sem as pausas) e a taxa de reprovação das inspeções " +
                "concluídas das tarefas do período, nula sem inspeção; as inspeções voluntárias à parte. " +
                "$PERIOD_RULE Exige `reporting.housekeeping.read`.",
    )
    fun housekeeping(
        @Parameter(description = "Primeira data (YYYY-MM-DD).")
        @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.")
        @RequestParam to: LocalDate,
    ): HousekeepingReportResponse = HousekeepingReportResponse.of(housekeeping.report(from, to))
}

@Schema(description = "O relatório de governança do período.")
data class HousekeepingReportResponse(
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(description = "As camareiras com tarefa atribuída no período, das que mais fizeram às que menos.")
    val attendants: List<AttendantReportResponse>,
    @field:Schema(description = "Os tipos de limpeza por tipo de UH com tarefa de UH no período.")
    val cleaningTypes: List<CleaningTypeReportResponse>,
    val voluntaryInspections: VoluntaryInspectionsResponse,
) {
    companion object {
        fun of(report: HousekeepingReport) =
            HousekeepingReportResponse(
                report.from,
                report.to,
                report.attendants.map(AttendantReportResponse::of),
                report.cleaningTypes.map(CleaningTypeReportResponse::of),
                VoluntaryInspectionsResponse.of(report.voluntaryInspections),
            )
    }
}

@Schema(description = "A produtividade de uma camareira no período.")
data class AttendantReportResponse(
    @field:Schema(description = "O colaborador (staff) da camareira.")
    val employeeId: UUID,
    @field:Schema(description = "As UHs limpas: tarefas de UH concluídas (`AWAITING_INSPECTION` e `DONE`).")
    val rooms: Long,
    @field:Schema(description = "A soma dos créditos de todas as tarefas concluídas, de UH ou de área.")
    val credits: Long,
) {
    companion object {
        fun of(row: AttendantReportRow) = AttendantReportResponse(row.employeeId, row.rooms, row.credits)
    }
}

@Schema(description = "Um tipo de limpeza num tipo de UH.")
data class CleaningTypeReportResponse(
    val cleaningTypeId: UUID,
    val roomTypeId: UUID,
    @field:Schema(description = "As limpezas concluídas.")
    val tasks: Long,
    @field:Schema(
        description = "O tempo médio das limpezas concluídas em minutos, com 1 casa; nulo sem limpeza concluída.",
        nullable = true,
    )
    val averageMinutes: BigDecimal?,
    @field:Schema(description = "As inspeções concluídas das tarefas do período.")
    val inspections: Long,
    @field:Schema(description = "As inspeções reprovadas.")
    val rejections: Long,
    @field:Schema(
        description = "Reprovadas sobre concluídas, em pontos percentuais com 4 casas; nula sem inspeção.",
        nullable = true,
    )
    val rejectionRate: Percent?,
) {
    companion object {
        fun of(row: CleaningTypeReportRow) =
            CleaningTypeReportResponse(
                row.cleaningTypeId,
                row.roomTypeId,
                row.tasks,
                row.averageMinutes,
                row.inspections,
                row.rejections,
                row.rejectionRate,
            )
    }
}

@Schema(description = "As inspeções voluntárias (sem tarefa) concluídas no período, à parte dos tipos de limpeza.")
data class VoluntaryInspectionsResponse(
    val inspections: Long,
    val rejections: Long,
    @field:Schema(
        description = "Reprovadas sobre concluídas, em pontos percentuais com 4 casas; nula sem inspeção.",
        nullable = true,
    )
    val rejectionRate: Percent?,
) {
    companion object {
        fun of(row: VoluntaryInspectionsReport) =
            VoluntaryInspectionsResponse(row.inspections, row.rejections, row.rejectionRate)
    }
}
