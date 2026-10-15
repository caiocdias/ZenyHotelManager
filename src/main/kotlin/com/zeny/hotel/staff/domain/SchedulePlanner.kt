package com.zeny.hotel.staff.domain

import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Um período de datas da escala, de [from] a [to] inclusive, com no máximo [MAX_DAYS] dias. */
data class SchedulePeriod(
    val from: LocalDate,
    val to: LocalDate,
) {
    init {
        if (to < from) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_PERIOD_INVALID,
                "to",
                "O fim do período é antes do início.",
            )
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_PERIOD_INVALID,
                "to",
                "O período tem no máximo $MAX_DAYS dias.",
            )
        }
    }

    fun dates(): Sequence<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }

    operator fun contains(date: LocalDate): Boolean = date in from..to

    companion object {
        const val MAX_DAYS = 92
    }
}

/**
 * O que a geração da escala faz num período: criar as entradas que os padrões pedem e ainda não
 * existem, refazer as geradas que mudaram e retirar as geradas que nenhum padrão pede mais. As
 * lançadas à mão ([keptManual]) ficam como estão.
 */
data class SchedulePlan(
    val toCreate: List<PlannedEntry>,
    val toUpdate: List<Pair<ScheduleEntry, PlannedEntry>>,
    val toRemove: List<ScheduleEntry>,
    val keptManual: List<ScheduleEntry>,
)

/**
 * Geração da escala a partir dos padrões (RF-PES-03): as exceções e atribuições lançadas à mão
 * prevalecem sempre; a geração é idempotente (gerar de novo sem mudança não faz nada).
 */
object SchedulePlanner {
    /**
     * Planeja a geração em [period] para os colaboradores [activeEmployees], com os [patterns] deles e
     * as entradas [existing] do período dos colaboradores considerados (inclusive inativos, cujas
     * entradas geradas são retiradas). [departmentOfShift] dá o setor de cada turno dos padrões.
     */
    fun plan(
        period: SchedulePeriod,
        activeEmployees: Set<EmployeeId>,
        patterns: List<SchedulePattern>,
        departmentOfShift: (ShiftId) -> DepartmentId,
        existing: List<ScheduleEntry>,
    ): SchedulePlan {
        val wanted = LinkedHashMap<Pair<EmployeeId, LocalDate>, PlannedEntry>()
        patterns
            .filter { it.active && it.employeeId in activeEmployees }
            .forEach { pattern ->
                val department = departmentOfShift(pattern.shiftId)
                pattern.workDatesIn(period).forEach { date ->
                    val previous =
                        wanted.put(
                            pattern.employeeId to date,
                            PlannedEntry(pattern.employeeId, date, pattern.shiftId, department, pattern.id),
                        )
                    check(previous == null) { "Dois padrões ativos do mesmo colaborador na mesma data" }
                }
            }

        val toUpdate = mutableListOf<Pair<ScheduleEntry, PlannedEntry>>()
        val toRemove = mutableListOf<ScheduleEntry>()
        val keptManual = mutableListOf<ScheduleEntry>()
        existing
            .filter { it.date in period }
            .forEach { entry ->
                val planned = wanted.remove(entry.employeeId to entry.date)
                when {
                    entry.manual -> keptManual += entry
                    planned == null -> toRemove += entry
                    !entry.matches(planned) -> toUpdate += entry to planned
                }
            }
        return SchedulePlan(wanted.values.toList(), toUpdate, toRemove, keptManual)
    }
}
