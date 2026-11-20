package com.zeny.hotel.reporting.web

import com.zeny.hotel.reporting.application.AttendantReportRow
import com.zeny.hotel.reporting.application.CleaningTypeReportRow
import com.zeny.hotel.reporting.application.DEFAULT_PANEL_DAYS
import com.zeny.hotel.reporting.application.DayPanel
import com.zeny.hotel.reporting.application.DayPanelDay
import com.zeny.hotel.reporting.application.DayPanelRooms
import com.zeny.hotel.reporting.application.DayPanelService
import com.zeny.hotel.reporting.application.EndOfDayPending
import com.zeny.hotel.reporting.application.EndOfDayPendingService
import com.zeny.hotel.reporting.application.HousekeepingReport
import com.zeny.hotel.reporting.application.HousekeepingReportService
import com.zeny.hotel.reporting.application.MAX_PANEL_DAYS
import com.zeny.hotel.reporting.application.MaintenanceCategory
import com.zeny.hotel.reporting.application.MaintenanceReport
import com.zeny.hotel.reporting.application.MaintenanceReportService
import com.zeny.hotel.reporting.application.MaintenanceStatus
import com.zeny.hotel.reporting.application.PendingCashierShift
import com.zeny.hotel.reporting.application.PendingFnrhSubmission
import com.zeny.hotel.reporting.application.PendingFolio
import com.zeny.hotel.reporting.application.VoluntaryInspectionsReport
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.Instant
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
    private val maintenance: MaintenanceReportService,
    private val dayPanel: DayPanelService,
    private val pending: EndOfDayPendingService,
) {
    @GetMapping("/day-panel")
    @Operation(
        operationId = "getDayPanel",
        summary = "Painel do dia",
        description =
            "Para a data operacional e as `days` − 1 seguintes: as chegadas e as saídas (reservas `PROVISIONAL`, " +
                "`CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`; o day use entra nas duas; `CANCELLED` e `NO_SHOW` não " +
                "contam), os hospedados (noites vendidas da data, sem o day use) e a ocupação (noites vendidas de " +
                "plano normal ou cortesia, sem o uso da casa nem o day use, sobre as UHs ativas menos as fora de " +
                "ordem, em percentual; nula sem inventário na data e acima de 999,9999%). Para hoje, as UHs ativas " +
                "por ocupação e status de governança e as UHs em OOO e OOS em vigor. Só contagens, sem dado de " +
                "hóspede. `days` de 1 a $MAX_PANEL_DAYS (padrão $DEFAULT_PANEL_DAYS); fora disso, 400. Exige " +
                "`reporting.dashboard.read`.",
    )
    fun dayPanel(
        @Parameter(description = "Quantos dias, a partir da data operacional (de 1 a $MAX_PANEL_DAYS).")
        @RequestParam(required = false, defaultValue = "$DEFAULT_PANEL_DAYS")
        @Min(1)
        @Max(MAX_PANEL_DAYS.toLong()) days: Int,
    ): DayPanelResponse = DayPanelResponse.of(dayPanel.panel(days))

    @GetMapping("/end-of-day-pending")
    @Operation(
        operationId = "getEndOfDayPending",
        summary = "Pendências do fechamento do dia",
        description =
            "O estado corrente, sem parâmetro de data: as contas abertas com saldo diferente de zero de reservas " +
                "`CHECKED_OUT` (o saldo é o total lançado menos o recebido líquido, com transferências, reembolsos e " +
                "depósitos; positivo é o que o hóspede deve), os turnos de caixa abertos de qualquer data e as " +
                "submissões à FNRH `PENDING`, `FAILED` e `INVALID`. Só números, códigos e ids, sem nome de hóspede. " +
                "Exige `reporting.pending.read`.",
    )
    fun endOfDayPending(): EndOfDayPendingResponse = EndOfDayPendingResponse.of(pending.pending())

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

    @GetMapping("/maintenance")
    @Operation(
        operationId = "getMaintenanceReport",
        summary = "Relatório de manutenção",
        description =
            "Pela abertura das ordens de serviço (data local do hotel): as ordens por status e por categoria; " +
                "das concluídas (`DONE`), o tempo médio de atendimento (da abertura à conclusão) em horas com 1 " +
                "casa, nulo sem ordem concluída, e a soma dos custos (mão de obra, terceiros e peças). As " +
                "canceladas contam por status e categoria, não nos custos. Sem texto livre. $PERIOD_RULE Exige " +
                "`reporting.maintenance.read`.",
    )
    fun maintenance(
        @Parameter(description = "Primeira data (YYYY-MM-DD).")
        @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.")
        @RequestParam to: LocalDate,
    ): MaintenanceReportResponse = MaintenanceReportResponse.of(maintenance.report(from, to))
}

@Schema(description = "O relatório de manutenção do período.")
data class MaintenanceReportResponse(
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(description = "As ordens abertas no período por status, só os status com ordem.")
    val byStatus: List<MaintenanceStatusCountResponse>,
    @field:Schema(description = "As ordens abertas no período por categoria, só as categorias com ordem.")
    val byCategory: List<MaintenanceCategoryCountResponse>,
    val completed: CompletedWorkOrdersResponse,
) {
    companion object {
        fun of(report: MaintenanceReport) =
            MaintenanceReportResponse(
                report.from,
                report.to,
                report.byStatus.map { MaintenanceStatusCountResponse(it.status, it.orders) },
                report.byCategory.map { MaintenanceCategoryCountResponse(it.category, it.orders) },
                CompletedWorkOrdersResponse(
                    report.completed.orders,
                    report.completed.averageHours,
                    report.completed.laborCost,
                    report.completed.thirdPartyCost,
                    report.completed.partsCost,
                    report.completed.totalCost,
                ),
            )
    }
}

@Schema(description = "As ordens de serviço de um status.")
data class MaintenanceStatusCountResponse(
    @field:ExtensibleEnum
    val status: MaintenanceStatus,
    val orders: Long,
)

@Schema(description = "As ordens de serviço de uma categoria.")
data class MaintenanceCategoryCountResponse(
    @field:ExtensibleEnum
    val category: MaintenanceCategory,
    val orders: Long,
)

@Schema(description = "As ordens concluídas (`DONE`) abertas no período.")
data class CompletedWorkOrdersResponse(
    val orders: Long,
    @field:Schema(
        description = "O tempo médio da abertura à conclusão em horas, com 1 casa; nulo sem ordem concluída.",
        nullable = true,
    )
    val averageHours: BigDecimal?,
    @field:Schema(description = "A soma da mão de obra.")
    val laborCost: Money,
    @field:Schema(description = "A soma dos custos de terceiros.")
    val thirdPartyCost: Money,
    @field:Schema(description = "A soma das peças.")
    val partsCost: Money,
    @field:Schema(description = "A soma dos três custos.")
    val totalCost: Money,
)

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

@Schema(description = "O painel do dia: uma linha por data e a situação das UHs de hoje.")
data class DayPanelResponse(
    @field:Schema(description = "A data operacional do hotel (a primeira data do painel).")
    val businessDate: LocalDate,
    @field:Schema(description = "Uma linha por data, da data operacional em diante, em ordem.")
    val days: List<DayPanelDayResponse>,
    val rooms: DayPanelRoomsResponse,
) {
    companion object {
        fun of(panel: DayPanel) =
            DayPanelResponse(
                panel.businessDate,
                panel.days.map(DayPanelDayResponse::of),
                DayPanelRoomsResponse.of(panel.rooms),
            )
    }
}

@Schema(description = "O movimento e a ocupação de uma data.")
data class DayPanelDayResponse(
    val date: LocalDate,
    @field:Schema(description = "As reservas que chegam na data, o day use incluído.")
    val arrivals: Long,
    @field:Schema(description = "As reservas que saem na data, o day use incluído.")
    val departures: Long,
    @field:Schema(description = "Os hospedados: noites vendidas da data, sem o day use.")
    val hosted: Long,
    @field:Schema(
        description =
            "Noites vendidas que contam na ocupação sobre as UHs ativas menos as fora de ordem, em pontos " +
                "percentuais; nula se a data não tem inventário ou se passa de 999,9999%.",
        nullable = true,
    )
    val occupancy: Percent?,
) {
    companion object {
        fun of(day: DayPanelDay) =
            DayPanelDayResponse(day.date, day.arrivals, day.departures, day.hosted, day.occupancy)
    }
}

@Schema(description = "As UHs de hoje.")
data class DayPanelRoomsResponse(
    @field:Schema(description = "As UHs ativas.")
    val active: Long,
    val occupied: Long,
    val vacant: Long,
    @field:Schema(description = "Status de governança sujo (`DIRTY`).")
    val dirty: Long,
    @field:Schema(description = "Status de governança limpo (`CLEAN`).")
    val clean: Long,
    @field:Schema(description = "Status de governança inspecionado (`INSPECTED`).")
    val inspected: Long,
    @field:Schema(description = "As limpas à espera de inspeção.")
    val inspectionPending: Long,
    @field:Schema(description = "As UHs com fora de ordem (OOO) em vigor hoje.")
    val outOfOrder: Long,
    @field:Schema(description = "As UHs com fora de serviço (OOS) em vigor hoje.")
    val outOfService: Long,
) {
    companion object {
        fun of(rooms: DayPanelRooms) =
            DayPanelRoomsResponse(
                rooms.active,
                rooms.occupied,
                rooms.vacant,
                rooms.dirty,
                rooms.clean,
                rooms.inspected,
                rooms.inspectionPending,
                rooms.outOfOrder,
                rooms.outOfService,
            )
    }
}

@Schema(description = "As pendências do fechamento do dia, no estado corrente.")
data class EndOfDayPendingResponse(
    @field:Schema(description = "As contas abertas de reservas encerradas com saldo diferente de zero.")
    val folios: List<PendingFolioResponse>,
    @field:Schema(description = "Os turnos de caixa abertos, de qualquer data operacional.")
    val cashierShifts: List<PendingCashierShiftResponse>,
    @field:Schema(description = "As submissões à FNRH ainda não enviadas com sucesso.")
    val fnrhSubmissions: List<PendingFnrhSubmissionResponse>,
) {
    companion object {
        fun of(pending: EndOfDayPending) =
            EndOfDayPendingResponse(
                pending.folios.map(PendingFolioResponse::of),
                pending.cashierShifts.map(PendingCashierShiftResponse::of),
                pending.fnrhSubmissions.map(PendingFnrhSubmissionResponse::of),
            )
    }
}

@Schema(description = "Uma conta aberta de reserva encerrada com saldo.")
data class PendingFolioResponse(
    val folioId: UUID,
    val folioNumber: String,
    val reservationId: UUID,
    val reservationCode: String,
    @field:Schema(description = "A data de saída da reserva.")
    val departure: LocalDate,
    @field:Schema(description = "Lançamentos menos recebimentos líquidos; positivo é o que o hóspede deve.")
    val balance: Money,
) {
    companion object {
        fun of(folio: PendingFolio) =
            PendingFolioResponse(
                folio.folioId,
                folio.folioNumber,
                folio.reservationId,
                folio.reservationCode,
                folio.departure,
                folio.balance,
            )
    }
}

@Schema(description = "Um turno de caixa aberto.")
data class PendingCashierShiftResponse(
    val shiftId: UUID,
    val shiftNumber: String,
    @field:Schema(description = "O nome do ponto de caixa (configuração).")
    val cashPointName: String,
    @field:Schema(description = "O usuário que opera o turno.")
    val operatorId: UUID,
    @field:Schema(description = "A data operacional da abertura.")
    val openedOn: LocalDate,
    val openedAt: Instant,
) {
    companion object {
        fun of(shift: PendingCashierShift) =
            PendingCashierShiftResponse(
                shift.shiftId,
                shift.shiftNumber,
                shift.cashPointName,
                shift.operatorId,
                shift.openedOn,
                shift.openedAt,
            )
    }
}

@Schema(description = "Uma submissão à FNRH pendente.")
data class PendingFnrhSubmissionResponse(
    @field:Schema(
        description =
            "A operação: `REGISTER_RESERVATION`, `UPDATE_RESERVATION`, `CANCEL_RESERVATION`, `CHECK_IN`, " +
                "`UNDO_CHECK_IN`, `CHECK_OUT` ou `NO_SHOW`; a lista pode crescer, trate o valor desconhecido.",
    )
    val operation: String,
    @field:Schema(description = "`PENDING`, `FAILED` ou `INVALID`; a lista pode crescer.")
    val status: String,
    val reservationId: UUID,
    @field:Schema(description = "O código da reserva; nulo se a reserva não existe mais.", nullable = true)
    val reservationCode: String?,
    val attempts: Int,
    @field:Schema(
        description =
            "A causa da última falha, nula sem falha: `VALIDATION`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, " +
                "`SERVER_ERROR`, `UNAVAILABLE`, `UNKNOWN_OUTCOME`, `INTERNAL_ERROR`, `REMOTE_CANCELLED`, " +
                "`NO_REMOTE_OPERATION` ou `REMOTE_NO_SHOW`; a lista pode crescer, trate o valor desconhecido.",
        nullable = true,
    )
    val lastFailure: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(submission: PendingFnrhSubmission) =
            PendingFnrhSubmissionResponse(
                submission.operation,
                submission.status,
                submission.reservationId,
                submission.reservationCode,
                submission.attempts,
                submission.lastFailure,
                submission.createdAt,
            )
    }
}
