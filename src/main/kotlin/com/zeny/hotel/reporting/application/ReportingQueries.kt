package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.domain.DailyStatisticsInput
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A produtividade de uma camareira no período: as UHs limpas e os créditos das limpezas concluídas. */
data class AttendantProductivityRow(
    val employeeId: UUID,
    val rooms: Long,
    val credits: Long,
)

/**
 * Um tipo de limpeza num tipo de UH no período (só tarefas de UH): as limpezas concluídas, o tempo trabalhado delas
 * (segundos, sem as pausas) e as inspeções concluídas das tarefas do período, com as reprovadas.
 */
data class CleaningTypeRow(
    val cleaningTypeId: UUID,
    val roomTypeId: UUID,
    val tasks: Long,
    val workedSeconds: Long,
    val inspections: Long,
    val rejections: Long,
)

/** As inspeções concluídas e as reprovadas entre elas. */
data class InspectionCountsRow(
    val inspections: Long,
    val rejections: Long,
)

/**
 * O movimento de uma data no painel do dia: as reservas que chegam e que saem (`PROVISIONAL`, `CONFIRMED`, `IN_HOUSE`
 * e `CHECKED_OUT`; o day use entra nas duas), as noites vendidas que hospedam (sem o day use) e as que contam na
 * ocupação (sem o day use nem o uso da casa; a cortesia conta).
 */
data class DayMovementsRow(
    val date: LocalDate,
    val arrivals: Long,
    val departures: Long,
    val hosted: Long,
    val occupiedNights: Long,
)

/** A capacidade de uma data no inventário: as UHs ativas menos as fora de ordem (OOO) de todos os tipos. */
data class DayCapacityRow(
    val date: LocalDate,
    val rooms: Long,
)

/** As UHs ativas contadas por ocupação, por status de governança e as que esperam inspeção. */
data class RoomCountsRow(
    val active: Long,
    val occupied: Long,
    val vacant: Long,
    val dirty: Long,
    val clean: Long,
    val inspected: Long,
    val inspectionPending: Long,
)

/** As UHs com bloqueio em vigor numa data: fora de ordem (OOO) e fora de serviço (OOS). */
data class OutOfOrderCountsRow(
    val outOfOrder: Long,
    val outOfService: Long,
)

/** Uma conta aberta de reserva encerrada (`CHECKED_OUT`) com o saldo: lançamentos menos recebimentos líquidos. */
data class OpenFolioBalanceRow(
    val folioId: UUID,
    val folioNumber: String,
    val reservationId: UUID,
    val reservationCode: String,
    val departure: LocalDate,
    val balance: BigDecimal,
)

/** Um turno de caixa aberto. */
data class OpenCashierShiftRow(
    val shiftId: UUID,
    val shiftNumber: String,
    val cashPointName: String,
    val operatorId: UUID,
    val openedOn: LocalDate,
    val openedAt: Instant,
)

/** Uma submissão à FNRH pendente (`PENDING`, `FAILED` ou `INVALID`); o código da reserva é nulo se a reserva sumiu. */
data class PendingFnrhSubmissionRow(
    val operation: String,
    val status: String,
    val reservationId: UUID,
    val reservationCode: String?,
    val attempts: Int,
    val lastFailure: String?,
    val createdAt: Instant,
)

/** Quantas ordens de serviço há para um valor de status ou de categoria. */
data class WorkOrderCountRow(
    val key: String,
    val orders: Long,
)

/**
 * As ordens de serviço concluídas (`DONE`) no período: quantas, a soma dos tempos de atendimento (segundos, da abertura
 * à conclusão) e a soma de cada custo.
 */
data class CompletedWorkOrdersRow(
    val orders: Long,
    val totalSeconds: BigDecimal,
    val laborCost: BigDecimal,
    val thirdPartyCost: BigDecimal,
    val partsCost: BigDecimal,
)

/**
 * As consultas dos relatórios (M6 E09). A implementação lê **só** as views `report_*` dos módulos (ARCHITECTURE §11),
 * nunca as tabelas internas: o `ReportingViewsOnlyTest` roda cada método com um papel que só lê as views. Cada
 * relatório novo acrescenta métodos aqui. O período em datas é o `business_date`; o em instantes, `from` inclusive e
 * `to` exclusive.
 */
@Suppress("TooManyFunctions") // uma consulta por assunto dos relatórios; cada fatia acrescenta as suas
interface ReportingQueries {
    /**
     * As camareiras com tarefa atribuída no período, em ordem de créditos (maior primeiro): as UHs e os créditos das
     * tarefas concluídas (`AWAITING_INSPECTION` e `DONE`); quem só tem tarefa não concluída vem com zeros.
     */
    fun attendantProductivity(
        from: LocalDate,
        to: LocalDate,
    ): List<AttendantProductivityRow>

    /** Os tipos de limpeza por tipo de UH com tarefa de UH no período, em ordem de ids. */
    fun cleaningByType(
        from: LocalDate,
        to: LocalDate,
    ): List<CleaningTypeRow>

    /** As inspeções voluntárias (sem tarefa) concluídas no intervalo de instantes. */
    fun voluntaryInspections(
        from: Instant,
        to: Instant,
    ): InspectionCountsRow

    /**
     * O movimento das datas de [from] a [to] (inclusive): só as datas com alguma reserva aparecem; as sem movimento
     * ficam de fora e quem usa as completa com zeros.
     */
    fun dayMovements(
        from: LocalDate,
        to: LocalDate,
    ): List<DayMovementsRow>

    /** A capacidade (UHs ativas menos OOO) de cada data de [from] a [to] com linha no inventário. */
    fun dayCapacity(
        from: LocalDate,
        to: LocalDate,
    ): List<DayCapacityRow>

    /** As UHs ativas contadas por ocupação e status de governança. */
    fun roomCounts(): RoomCountsRow

    /** As UHs com OOO e OOS em vigor em [on] (o período inclusive, até a véspera da liberação antecipada). */
    fun outOfOrderOn(on: LocalDate): OutOfOrderCountsRow

    /** As contas abertas de reservas `CHECKED_OUT` com saldo diferente de zero, pela saída e pelo número. */
    fun openFolioBalances(): List<OpenFolioBalanceRow>

    /** Os turnos de caixa abertos, de qualquer data operacional, do mais antigo ao mais novo. */
    fun openCashierShifts(): List<OpenCashierShiftRow>

    /** As submissões à FNRH `PENDING`, `FAILED` e `INVALID`, da mais antiga à mais nova. */
    fun pendingFnrhSubmissions(): List<PendingFnrhSubmissionRow>

    /** As ordens de serviço abertas no intervalo de instantes, por status, em ordem de status. */
    fun workOrdersByStatus(
        from: Instant,
        to: Instant,
    ): List<WorkOrderCountRow>

    /** As ordens de serviço abertas no intervalo de instantes, por categoria, em ordem de categoria. */
    fun workOrdersByCategory(
        from: Instant,
        to: Instant,
    ): List<WorkOrderCountRow>

    /** As ordens `DONE` abertas no intervalo de instantes, com o tempo de atendimento e os custos somados. */
    fun completedWorkOrders(
        from: Instant,
        to: Instant,
    ): CompletedWorkOrdersRow

    /**
     * Os números da data [date] para o snapshot diário (D1 da E23): as noites vendidas que hospedam por categoria de
     * plano, as UHs disponíveis e fora de ordem do inventário, as chegadas, saídas, no-shows e cancelamentos, e as
     * receitas dos lançamentos da data sem repasse nem impostos. Os cancelamentos são os de `cancelled_at` no
     * intervalo de instantes [from] (inclusive) a [to] (exclusive), o dia no fuso do hotel.
     */
    fun dailyStatisticsInput(
        date: LocalDate,
        from: Instant,
        to: Instant,
    ): DailyStatisticsInput
}
