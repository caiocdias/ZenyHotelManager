package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistRoutineView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.RoutineDefinition
import com.zeny.hotel.checklists.RoutineFrequency
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DaySchedule
import com.zeny.hotel.staff.ShiftSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A validação de uma [RoutineDefinition]: nome sem espaços nas pontas, dias só na semanal, horário em minutos. */
internal object RoutineRules {
    fun validated(definition: RoutineDefinition): RoutineDefinition {
        val trimmed = definition.name.trim()
        if (trimmed.isEmpty() || trimmed.length > ChecklistRoutine.MAX_NAME_LENGTH) {
            invalid("name", "Informe o nome da rotina com até ${ChecklistRoutine.MAX_NAME_LENGTH} caracteres.")
        }
        when (definition.frequency) {
            RoutineFrequency.DAILY -> {
                if (definition.weekdays.isNotEmpty()) invalid("weekdays", "Uma rotina diária não tem dias da semana.")
            }

            RoutineFrequency.WEEKLY -> {
                if (definition.weekdays.isEmpty()) invalid("weekdays", "Informe os dias da semana da rotina semanal.")
            }
        }
        if (definition.dueAt.second != 0 || definition.dueAt.nano != 0) {
            invalid("dueAt", "Informe o horário em horas e minutos.")
        }
        return definition.copy(name = trimmed)
    }

    private fun invalid(
        field: String,
        message: String,
    ): Nothing {
        val code = ChecklistsErrorCodes.CHECKLIST_ROUTINE_INVALID
        throw InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
    }
}

/**
 * Rotina de checklist (RF-CHK-08, SPEC §8.2.11, H14): um checklist recorrente sem tarefa associada, do modelo
 * [definition], atribuído por setor e escala. A geração (só pelo listener de `BusinessDateAdvanced`) cria, em
 * cada data em que a rotina ocorre, uma execução pendente com os candidatos escalados ([candidates]). Nunca é
 * apagada, só desativada (desativada, não gera).
 */
class ChecklistRoutine private constructor(
    val id: ChecklistRoutineId,
    definition: RoutineDefinition,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) {
    var definition: RoutineDefinition = definition
        private set
    var active: Boolean = active
        private set
    var updatedAt: Instant = updatedAt
        private set
    var version: Long = version
        private set

    /** Se a rotina ocorre na data (diária: todo dia; semanal: nos dias escolhidos). */
    fun occursOn(date: LocalDate): Boolean =
        when (definition.frequency) {
            RoutineFrequency.DAILY -> true
            RoutineFrequency.WEEKLY -> date.dayOfWeek in definition.weekdays
        }

    fun edit(
        definition: RoutineDefinition,
        now: Instant,
    ): Boolean {
        val validated = RoutineRules.validated(definition)
        if (validated == this.definition) return false
        this.definition = validated
        touch(now)
        return true
    }

    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        touch(now)
        return true
    }

    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        touch(now)
        return true
    }

    private fun touch(now: Instant) {
        updatedAt = now
        version += 1
    }

    fun view() =
        ChecklistRoutineView(
            id,
            definition.name,
            definition.templateId,
            definition.departmentId,
            definition.frequency,
            definition.weekdays,
            definition.dueAt,
            definition.location,
            active,
            createdAt,
            updatedAt,
            version,
        )

    companion object {
        const val MAX_NAME_LENGTH = 120

        fun create(
            definition: RoutineDefinition,
            now: Instant,
        ) = ChecklistRoutine(ChecklistRoutineId.new(), RoutineRules.validated(definition), true, now, now, 0)

        @Suppress("LongParameterList") // reconstituída da persistência com todos os campos
        fun restore(
            id: ChecklistRoutineId,
            definition: RoutineDefinition,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ) = ChecklistRoutine(id, definition, active, createdAt, updatedAt, version)

        /**
         * Os candidatos de uma rotina às [dueAt] da data [today] (H14): os colaboradores ativos, com usuário
         * vinculado, escalados no setor num turno que cobre o horário — um turno da própria data (do início até o
         * fim, ou até a meia-noite se atravessa) ou um turno da véspera que atravessa a meia-noite e ainda não
         * terminou. Folgas, faltas e férias não estão na escala do dia ([DaySchedule] só traz quem trabalha).
         */
        fun candidates(
            dueAt: LocalTime,
            today: DaySchedule,
            yesterday: DaySchedule,
        ): Set<UserId> {
            val fromToday = today.assignments.filter { covers(it.shift, dueAt, sameDay = true) }
            val fromYesterday = yesterday.assignments.filter { covers(it.shift, dueAt, sameDay = false) }
            return (fromToday + fromYesterday)
                .filter { it.employee.active }
                .mapNotNull { it.employee.userId }
                .toSet()
        }

        private fun covers(
            shift: ShiftSummary,
            time: LocalTime,
            sameDay: Boolean,
        ): Boolean =
            when {
                sameDay && !shift.crossesMidnight -> time >= shift.start && time < shift.end
                sameDay -> time >= shift.start
                shift.crossesMidnight -> time < shift.end
                else -> false
            }
    }
}
