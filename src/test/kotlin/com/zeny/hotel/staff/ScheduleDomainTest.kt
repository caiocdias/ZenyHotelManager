package com.zeny.hotel.staff

import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.EmployeeDetails
import com.zeny.hotel.staff.domain.ManualEntry
import com.zeny.hotel.staff.domain.PatternDefinition
import com.zeny.hotel.staff.domain.PlannedEntry
import com.zeny.hotel.staff.domain.ScheduleEntry
import com.zeny.hotel.staff.domain.SchedulePattern
import com.zeny.hotel.staff.domain.SchedulePeriod
import com.zeny.hotel.staff.domain.SchedulePlan
import com.zeny.hotel.staff.domain.SchedulePlanner
import com.zeny.hotel.staff.domain.Shift
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Regras da escala (M1 E07, RF-PES-02 e 03): os padrões 12x36, 6x1 e fixo geram exatamente as datas
 * esperadas, as exceções e atribuições lançadas à mão prevalecem sobre a geração (inclusive ao gerar
 * de novo), turnos que atravessam a meia-noite e as validações de turno, padrão, período e entrada.
 */
class ScheduleDomainTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val later = Instant.parse("2026-10-15T12:00:00Z")
    private val department = DepartmentId.new()
    private val otherDepartment = DepartmentId.new()
    private val day = Shift.create(department, "Diurno", LocalTime.of(7, 0), LocalTime.of(19, 0), now)
    private val night = Shift.create(department, "Noturno", LocalTime.of(19, 0), LocalTime.of(7, 0), now)
    private val employee = employee("Ana")

    /** Novembro de 2026: 30 dias, de domingo (1º) a segunda (30). */
    private val november = SchedulePeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))

    private fun nov(vararg days: Int) = days.map { LocalDate.of(2026, 11, it) }

    private fun employee(name: String) =
        Employee.create(EmployeeDetails(name, "Camareira", null, null, department), null, now)

    @Suppress("LongParameterList") // padrões com os valores padrão do cenário
    private fun pattern(
        kind: SchedulePatternKind,
        startsOn: LocalDate,
        endsOn: LocalDate? = null,
        weekdays: Set<DayOfWeek> = emptySet(),
        shift: Shift = day,
        employeeId: EmployeeId = employee.id,
    ) = SchedulePattern.create(employeeId, PatternDefinition(kind, shift.id, startsOn, endsOn, weekdays), now)

    private fun plan(
        patterns: List<SchedulePattern>,
        existing: List<ScheduleEntry>,
        active: Set<EmployeeId> = setOf(employee.id),
        period: SchedulePeriod = november,
    ): SchedulePlan =
        SchedulePlanner.plan(period, active, patterns, {
            if (it ==
                night.id
            ) {
                otherDepartment
            } else {
                department
            }
        }, existing)

    /** Aplica o plano como a aplicação faz: cria, refaz e retira. */
    private fun apply(
        plan: SchedulePlan,
        existing: List<ScheduleEntry>,
    ): List<ScheduleEntry> {
        plan.toUpdate.forEach { (entry, planned) -> entry.replan(planned, later) }
        return existing - plan.toRemove.toSet() + plan.toCreate.map { ScheduleEntry.planned(it, later) }
    }

    private inline fun <reified T : DomainException> rejected(
        code: String,
        block: () -> Unit,
    ): T {
        val error = shouldThrow<T>(block)
        error.code shouldBe ErrorCode(code)
        return error
    }

    // --- Datas geradas pelos padrões, num período de 30 dias -----------------------------------------

    @Test
    fun `12x36 works every other day from the start`() {
        val pattern = pattern(SchedulePatternKind.TWELVE_THIRTY_SIX, LocalDate.of(2026, 11, 2))

        pattern.workDatesIn(november) shouldContainExactly nov(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30)
        // O ciclo conta a partir do início do padrão, mesmo que ele tenha começado antes do período.
        pattern(
            SchedulePatternKind.TWELVE_THIRTY_SIX,
            LocalDate.of(2026, 10, 1),
        ).workDatesIn(november) shouldContainExactly
            nov(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30)
    }

    @Test
    fun `6x1 works six days and rests on the seventh`() {
        val pattern = pattern(SchedulePatternKind.SIX_ON_ONE_OFF, LocalDate.of(2026, 11, 1))

        val dates = pattern.workDatesIn(november)

        dates shouldHaveSize 26
        dates shouldContainExactly (1..30).filterNot { it in setOf(7, 14, 21, 28) }.map { LocalDate.of(2026, 11, it) }
    }

    @Test
    fun `fixed works on the chosen weekdays only`() {
        val pattern =
            pattern(SchedulePatternKind.FIXED, LocalDate.of(2026, 10, 1), weekdays = setOf(MONDAY, WEDNESDAY, FRIDAY))

        pattern.workDatesIn(november) shouldContainExactly nov(2, 4, 6, 9, 11, 13, 16, 18, 20, 23, 25, 27, 30)
        pattern.weekdays shouldBe setOf(MONDAY, WEDNESDAY, FRIDAY)
    }

    @Test
    fun `a pattern only works between its start and end`() {
        val pattern =
            pattern(SchedulePatternKind.SIX_ON_ONE_OFF, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20))

        pattern.workDatesIn(november) shouldContainExactly nov(10, 11, 12, 13, 14, 15, 17, 18, 19, 20)
        pattern.deactivate(later) shouldBe true
        pattern.workDatesIn(november).shouldBeEmpty()
    }

    @Test
    fun `patterns of the same employee overlap when their periods meet`() {
        val pattern =
            pattern(SchedulePatternKind.FIXED, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20), setOf(MONDAY))

        pattern.overlaps(LocalDate.of(2026, 11, 20), null) shouldBe true
        pattern.overlaps(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)) shouldBe true
        pattern.overlaps(LocalDate.of(2026, 11, 21), null) shouldBe false
        pattern.overlaps(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9)) shouldBe false
        pattern(SchedulePatternKind.FIXED, LocalDate.of(2026, 11, 10), weekdays = setOf(MONDAY))
            .overlaps(LocalDate.of(2027, 1, 1), null) shouldBe true
    }

    // --- Geração: exceções prevalecem --------------------------------------------------------------

    @Test
    fun `generation creates the pattern entries and an exception is never overwritten, even when generating again`() {
        val pattern = pattern(SchedulePatternKind.TWELVE_THIRTY_SIX, LocalDate.of(2026, 11, 2))
        val first = plan(listOf(pattern), emptyList())
        first.toCreate.map { it.date } shouldContainExactly pattern.workDatesIn(november)
        first.toCreate.all {
            it.shiftId == day.id && it.departmentId == department && it.patternId == pattern.id
        } shouldBe
            true
        var entries = apply(first, emptyList())

        // Folga lançada sobre um dia de trabalho, férias sobre outro e uma troca num dia de descanso.
        val dayOff = entries.single { it.date == LocalDate.of(2026, 11, 10) }
        dayOff.overrideWith(ManualEntry(ScheduleEntryKind.DAY_OFF, null, department, "Folga pedida"), later) shouldBe
            true
        val vacation = entries.single { it.date == LocalDate.of(2026, 11, 12) }
        vacation.overrideWith(ManualEntry(ScheduleEntryKind.VACATION, null, department, null), later) shouldBe true
        val swap =
            ScheduleEntry.manual(
                employee.id,
                LocalDate.of(2026, 11, 11),
                ManualEntry(ScheduleEntryKind.SWAP, night.id, otherDepartment, "Troca com a Bia"),
                later,
            )
        entries = entries + swap

        // Gerar de novo: nada a criar, refazer ou retirar; as três lançadas ficam como estão.
        val again = plan(listOf(pattern), entries)
        again.toCreate.shouldBeEmpty()
        again.toUpdate.shouldBeEmpty()
        again.toRemove.shouldBeEmpty()
        again.keptManual.map { it.date }.sorted() shouldContainExactly nov(10, 11, 12)
        dayOff.kind shouldBe ScheduleEntryKind.DAY_OFF
        dayOff.source shouldBe ScheduleEntrySource.MANUAL
        dayOff.shiftId shouldBe null
        vacation.kind shouldBe ScheduleEntryKind.VACATION
        swap.shiftId shouldBe night.id

        // Mesmo com o padrão trocado para 6x1 noturno (que pede os dias 10, 11 e 12), as lançadas ficam.
        pattern.update(
            PatternDefinition(
                SchedulePatternKind.SIX_ON_ONE_OFF,
                night.id,
                LocalDate.of(2026, 11, 1),
                null,
                emptySet(),
            ),
            later,
        )
        val changed = plan(listOf(pattern), entries)
        changed.keptManual.map { it.date }.sorted() shouldContainExactly nov(10, 11, 12)
        (changed.toCreate.map { it.date } + changed.toUpdate.map { it.first.date } + changed.toRemove.map { it.date })
            .intersect(nov(10, 11, 12).toSet())
            .shouldBeEmpty()
        entries = apply(changed, entries)
        entries.filter { !it.manual }.map { it.date }.sorted() shouldContainExactly
            (1..30).filterNot { it in setOf(7, 14, 21, 28, 10, 11, 12) }.map { LocalDate.of(2026, 11, it) }
        entries.filter { !it.manual }.all { it.shiftId == night.id && it.departmentId == otherDepartment } shouldBe true
        entries.single { it.date == LocalDate.of(2026, 11, 10) }.kind shouldBe ScheduleEntryKind.DAY_OFF
    }

    @Test
    fun `generation removes generated entries no pattern asks for, but keeps the manual ones`() {
        val pattern = pattern(SchedulePatternKind.FIXED, LocalDate.of(2026, 11, 1), weekdays = setOf(MONDAY))
        var entries = apply(plan(listOf(pattern), emptyList()), emptyList())
        entries.map { it.date } shouldContainExactly nov(2, 9, 16, 23, 30)
        val absence = entries.single { it.date == LocalDate.of(2026, 11, 9) }
        absence.overrideWith(ManualEntry(ScheduleEntryKind.ABSENCE, null, department, null), later)

        // O padrão termina no dia 15: as entradas geradas depois saem.
        pattern.update(
            PatternDefinition(
                SchedulePatternKind.FIXED,
                day.id,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 15),
                setOf(MONDAY),
            ),
            later,
        )
        val ended = plan(listOf(pattern), entries)
        ended.toRemove.map { it.date } shouldContainExactly nov(16, 23, 30)
        entries = apply(ended, entries)
        entries.map { it.date } shouldContainExactly nov(2, 9)

        // Colaborador inativo: as geradas saem, a falta lançada fica.
        val inactive = plan(listOf(pattern), entries, active = emptySet())
        inactive.toRemove.map { it.date } shouldContainExactly nov(2)
        inactive.keptManual shouldContainExactly listOf(absence)
        inactive.toCreate.shouldBeEmpty()
    }

    @Test
    fun `generation only touches the requested period`() {
        val pattern = pattern(SchedulePatternKind.SIX_ON_ONE_OFF, LocalDate.of(2026, 11, 1))
        val outside =
            ScheduleEntry.planned(
                PlannedEntry(employee.id, LocalDate.of(2026, 12, 1), day.id, department, pattern.id),
                now,
            )
        val week = SchedulePeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))

        val plan = plan(listOf(pattern), listOf(outside), period = week)

        plan.toCreate.map { it.date } shouldContainExactly nov(1, 2, 3, 4, 5, 6)
        plan.toRemove.shouldBeEmpty()
    }

    // --- Turnos e validações -----------------------------------------------------------------------

    @Test
    fun `a night shift crosses midnight and belongs to the day it starts`() {
        night.crossesMidnight shouldBe true
        day.crossesMidnight shouldBe false
        val entry =
            ScheduleEntry.manual(
                employee.id,
                LocalDate.of(2026, 11, 5),
                ManualEntry(ScheduleEntryKind.SHIFT, night.id, department, null),
                now,
            )
        entry.date shouldBe LocalDate.of(2026, 11, 5)
    }

    @Test
    fun `shift times must differ and be whole minutes`() {
        rejected<InvalidInputException>("SHIFT_TIMES_INVALID") {
            Shift.create(department, "Zero", LocalTime.of(7, 0), LocalTime.of(7, 0), now)
        }.violations.map { it.field } shouldContainExactly listOf("end")
        rejected<InvalidInputException>("SHIFT_TIMES_INVALID") {
            Shift.create(department, "Segundos", LocalTime.of(7, 0, 30), LocalTime.of(15, 0), now)
        }.violations.map { it.field } shouldContainExactly listOf("start")
        rejected<InvalidInputException>("STAFF_DATA_INVALID") {
            Shift.create(department, "  ", LocalTime.of(7, 0), LocalTime.of(15, 0), now)
        }
        rejected<InvalidInputException>("SHIFT_TIMES_INVALID") {
            day.update("Diurno", LocalTime.of(8, 0), LocalTime.of(8, 0), later)
        }
        day.start shouldBe LocalTime.of(7, 0)
    }

    @Test
    fun `patterns must be coherent`() {
        rejected<InvalidInputException>("SCHEDULE_PATTERN_INVALID") {
            PatternDefinition(SchedulePatternKind.FIXED, day.id, LocalDate.of(2026, 11, 1), null, emptySet())
        }.violations.map { it.field } shouldContainExactly listOf("weekdays")
        rejected<InvalidInputException>("SCHEDULE_PATTERN_INVALID") {
            PatternDefinition(
                SchedulePatternKind.TWELVE_THIRTY_SIX,
                day.id,
                LocalDate.of(2026, 11, 1),
                null,
                setOf(MONDAY),
            )
        }
        rejected<InvalidInputException>("SCHEDULE_PATTERN_INVALID") {
            PatternDefinition(
                SchedulePatternKind.SIX_ON_ONE_OFF,
                day.id,
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 1),
                emptySet(),
            )
        }.violations.map { it.field } shouldContainExactly listOf("endsOn")
    }

    @Test
    fun `periods have an end after the start and at most 92 days`() {
        rejected<InvalidInputException>("SCHEDULE_PERIOD_INVALID") {
            SchedulePeriod(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1))
        }
        rejected<InvalidInputException>("SCHEDULE_PERIOD_INVALID") {
            SchedulePeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 2, 1))
        }
        SchedulePeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31)).dates().count() shouldBe 92
    }

    @Test
    fun `only working entries have a shift`() {
        rejected<InvalidInputException>("SCHEDULE_ENTRY_INVALID") {
            ManualEntry(ScheduleEntryKind.SHIFT, null, department, null)
        }
        rejected<InvalidInputException>("SCHEDULE_ENTRY_INVALID") {
            ManualEntry(ScheduleEntryKind.SWAP, null, department, null)
        }
        rejected<InvalidInputException>("SCHEDULE_ENTRY_INVALID") {
            ManualEntry(ScheduleEntryKind.DAY_OFF, day.id, department, null)
        }
        rejected<InvalidInputException>("SCHEDULE_ENTRY_INVALID") {
            ManualEntry(ScheduleEntryKind.VACATION, day.id, department, null)
        }
        ManualEntry(ScheduleEntryKind.ABSENCE, null, department, "Atestado").note shouldBe "Atestado"
    }

    @Test
    fun `an employee is deactivated and reactivated, and links at most one user`() {
        val ana = employee("Ana Souza")
        ana.userId shouldBe null
        val user =
            com.zeny.hotel.shared.UserId
                .new()
        ana.linkUser(user, later) shouldBe true
        ana.userId shouldBe user
        ana.linkUser(user, later) shouldBe false
        ana.deactivate(later) shouldBe true
        ana.active shouldBe false
        ana.deactivate(later) shouldBe false
        ana.reactivate(later) shouldBe true
        rejected<InvalidInputException>("STAFF_DATA_INVALID") {
            Employee.create(EmployeeDetails("", "Camareira", null, null, department), null, now)
        }
    }
}
