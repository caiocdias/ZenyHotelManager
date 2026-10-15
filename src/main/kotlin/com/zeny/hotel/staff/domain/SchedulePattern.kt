package com.zeny.hotel.staff.domain

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Id de um padrão de escala. */
@JvmInline
value class SchedulePatternId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): SchedulePatternId = SchedulePatternId(UuidV7.generate())
    }
}

/** A definição de um padrão, como chega para criar ou editar. [endsOn] nulo: sem fim. */
data class PatternDefinition(
    val kind: SchedulePatternKind,
    val shiftId: ShiftId,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    /** Só no [SchedulePatternKind.FIXED], e obrigatório nele. */
    val weekdays: Set<DayOfWeek>,
) {
    init {
        if (endsOn != null && endsOn < startsOn) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_PATTERN_INVALID,
                "endsOn",
                "O fim do padrão não pode ser antes do início.",
            )
        }
        if (kind == SchedulePatternKind.FIXED && weekdays.isEmpty()) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_PATTERN_INVALID,
                "weekdays",
                "Informe os dias da semana do padrão fixo.",
            )
        }
        if (kind != SchedulePatternKind.FIXED && weekdays.isNotEmpty()) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_PATTERN_INVALID,
                "weekdays",
                "Dias da semana só valem no padrão fixo; 12x36 e 6x1 contam a partir do início.",
            )
        }
    }
}

/**
 * Padrão recorrente de escala de um colaborador num turno (RF-PES-03): 12x36 (um dia sim, um não, a
 * partir de [startsOn]), 6x1 (seis dias de trabalho e um de folga, em ciclos de sete dias a partir de
 * [startsOn]) ou fixo (os [weekdays]). Vale de [startsOn] a [endsOn] (inclusive; sem fim, se nulo).
 * Um colaborador não tem dois padrões ativos que se sobreponham, então cada data tem no máximo um.
 * Não é apagado: desativado, deixa de gerar entradas.
 */
@Entity
@Table(name = "schedule_pattern", schema = "staff")
class SchedulePattern private constructor(
    @Id
    val id: SchedulePatternId,
    @Column(name = "employee_id", nullable = false, updatable = false)
    val employeeId: EmployeeId,
    definition: PatternDefinition,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: SchedulePatternKind = definition.kind
        protected set

    @Column(name = "shift_id", nullable = false)
    var shiftId: ShiftId = definition.shiftId
        protected set

    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate = definition.startsOn
        protected set

    @Column(name = "ends_on")
    var endsOn: LocalDate? = definition.endsOn
        protected set

    /** Os dias da semana do padrão fixo, um bit por dia (segunda = 1, domingo = 64). */
    @Column(name = "weekday_mask", nullable = false)
    private var weekdayMask: Int = maskOf(definition.weekdays)

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    val weekdays: Set<DayOfWeek> get() = DayOfWeek.entries.filter { weekdayMask and bit(it) != 0 }.toSet()

    fun definition(): PatternDefinition = PatternDefinition(kind, shiftId, startsOn, endsOn, weekdays)

    /** Se o padrão vale em [date] (ativo e dentro do período), trabalhando ou não. */
    fun covers(date: LocalDate): Boolean = active && date >= startsOn && (endsOn == null || date <= endsOn)

    /** Se o colaborador trabalha em [date] por este padrão. */
    fun worksOn(date: LocalDate): Boolean {
        if (!covers(date)) return false
        val day = ChronoUnit.DAYS.between(startsOn, date)
        return when (kind) {
            SchedulePatternKind.TWELVE_THIRTY_SIX -> day % TWELVE_THIRTY_SIX_CYCLE == 0L
            SchedulePatternKind.SIX_ON_ONE_OFF -> day % SIX_ON_ONE_OFF_CYCLE < SIX_ON_ONE_OFF_WORKING_DAYS
            SchedulePatternKind.FIXED -> date.dayOfWeek in weekdays
        }
    }

    /** As datas de trabalho do padrão em [period], em ordem. */
    fun workDatesIn(period: SchedulePeriod): List<LocalDate> = period.dates().filter(::worksOn).toList()

    /** Se, ativo, o período deste padrão se sobrepõe a [startsOn]–[endsOn] (fim nulo: sem fim). */
    fun overlaps(
        startsOn: LocalDate,
        endsOn: LocalDate?,
    ): Boolean {
        if (!active) return false
        val thisEndsOn = this.endsOn
        val startsBeforeOtherEnds = endsOn == null || this.startsOn <= endsOn
        val otherStartsBeforeThisEnds = thisEndsOn == null || startsOn <= thisEndsOn
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds
    }

    /** Troca a definição; devolve se algo mudou. Entradas já geradas só mudam na próxima geração. */
    fun update(
        definition: PatternDefinition,
        now: Instant,
    ): Boolean {
        if (definition == definition()) return false
        kind = definition.kind
        shiftId = definition.shiftId
        startsOn = definition.startsOn
        endsOn = definition.endsOn
        weekdayMask = maskOf(definition.weekdays)
        updatedAt = now
        return true
    }

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    companion object {
        private const val TWELVE_THIRTY_SIX_CYCLE = 2L
        private const val SIX_ON_ONE_OFF_CYCLE = 7L
        private const val SIX_ON_ONE_OFF_WORKING_DAYS = 6L

        fun create(
            employeeId: EmployeeId,
            definition: PatternDefinition,
            now: Instant,
        ): SchedulePattern = SchedulePattern(SchedulePatternId.new(), employeeId, definition, now)

        private fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

        private fun maskOf(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or bit(day) }
    }
}
