package com.zeny.hotel.reporting.application

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
 * As consultas dos relatórios (M6 E09). A implementação lê **só** as views `report_*` dos módulos (ARCHITECTURE §11),
 * nunca as tabelas internas: o `ReportingViewsOnlyTest` roda cada método com um papel que só lê as views. Cada
 * relatório novo acrescenta métodos aqui. O período em datas é o `business_date`; o em instantes, `from` inclusive e
 * `to` exclusive.
 */
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
}
