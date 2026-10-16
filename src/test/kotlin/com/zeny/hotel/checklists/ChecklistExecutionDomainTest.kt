package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.domain.ChecklistAnswer
import com.zeny.hotel.checklists.domain.ChecklistAnswerRules
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.checklists.domain.ChecklistRoutine
import com.zeny.hotel.checklists.domain.RequiredItemsMissingException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.DaySchedule
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.EmployeeSummary
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ShiftAssignment
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.ShiftSummary
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Regras da execução (M2 E07, RF-CHK-02, 03 e 05, H14): visibilidade dos condicionais (inclusive em cadeia),
 * obrigatórios só quando visíveis, ações disparadas, formato das respostas pelo tipo, o ciclo da execução (só o
 * executor responde e conclui, uma vez) e os candidatos de uma rotina pela escala.
 */
class ChecklistExecutionDomainTest {
    private val t0 = Instant.parse("2026-10-16T12:00:00Z")
    private val stock = UUID.randomUUID()
    private val content =
        ChecklistContent(
            emptyList(),
            listOf(
                ChecklistSection(
                    "Quarto",
                    listOf(
                        ChecklistItem("bed", "Cama", ChecklistItemType.CHECK, required = true),
                        ChecklistItem(
                            "shower",
                            "Chuveiro",
                            ChecklistItemType.CHECK,
                            required = true,
                            actions = listOf(OpenOccurrenceAction(OccurrenceKind.MAINTENANCE, "PLUMBING")),
                        ),
                        ChecklistItem(
                            "leak",
                            "Vazamento?",
                            ChecklistItemType.SINGLE_CHOICE,
                            required = true,
                            visibleWhen = ItemCondition("shower", "NOT_OK"),
                            options = listOf(ChoiceOption("yes", "Sim"), ChoiceOption("no", "Não")),
                        ),
                        ChecklistItem(
                            "leak-photo",
                            "Foto do vazamento",
                            ChecklistItemType.PHOTO,
                            required = true,
                            visibleWhen = ItemCondition("leak", "yes"),
                        ),
                        ChecklistItem(
                            "towels",
                            "Toalhas",
                            ChecklistItemType.QUANTITY,
                            actions = listOf(ConsumeStockAction(stock)),
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `a conditional item is visible only when the earlier item has the answer, chained`() {
        fun visible(vararg answers: ChecklistAnswer) =
            ChecklistAnswerRules.visibleKeys(content, answers.associateBy { it.itemKey })

        visible() shouldBe setOf("bed", "shower", "towels")
        visible(check("shower", CheckAnswer.OK)) shouldBe setOf("bed", "shower", "towels")
        visible(check("shower", CheckAnswer.NOT_OK)) shouldBe setOf("bed", "shower", "leak", "towels")
        visible(check("shower", CheckAnswer.NOT_OK), choice("leak", "yes")) shouldBe
            setOf("bed", "shower", "leak", "leak-photo", "towels")
        // A condição sobre um item oculto não é atendida, mesmo com a resposta dele gravada.
        visible(check("shower", CheckAnswer.OK), choice("leak", "yes")) shouldBe setOf("bed", "shower", "towels")
    }

    @Test
    fun `only visible required items without an answer are missing`() {
        fun missing(vararg answers: ChecklistAnswer) =
            ChecklistAnswerRules.missingRequired(content, answers.associateBy { it.itemKey })

        missing() shouldContainExactly listOf("bed", "shower")
        missing(check("bed", CheckAnswer.OK), check("shower", CheckAnswer.OK)) shouldBe emptyList()
        missing(check("bed", CheckAnswer.OK), check("shower", CheckAnswer.NOT_OK)) shouldContainExactly
            listOf("leak")
        missing(
            check("bed", CheckAnswer.OK),
            check("shower", CheckAnswer.NOT_OK),
            choice("leak", "yes"),
        ) shouldContainExactly
            listOf("leak-photo")
        // A foto que não chegou (offline, H10) conta como respondida.
        val photo = ChecklistAnswer("leak-photo", ChecklistItemType.PHOTO, photoMissing = true, answeredAt = t0)
        missing(
            check("bed", CheckAnswer.OK),
            check("shower", CheckAnswer.NOT_OK),
            choice("leak", "yes"),
            photo,
        ) shouldBe
            emptyList()
    }

    @Test
    fun `actions are triggered by NOT_OK and a positive quantity, only on visible items`() {
        val answers =
            listOf(
                check("shower", CheckAnswer.NOT_OK).copy(note = "Sem água"),
                ChecklistAnswer("towels", ChecklistItemType.QUANTITY, quantity = BigDecimal("2"), answeredAt = t0),
            ).associateBy { it.itemKey }
        ChecklistAnswerRules.triggeredActions(content, answers) shouldContainExactly
            listOf(
                ChecklistTriggeredAction(
                    "shower",
                    ItemActionType.OPEN_OCCURRENCE,
                    occurrenceKind = OccurrenceKind.MAINTENANCE,
                    category = "PLUMBING",
                    note = "Sem água",
                ),
                ChecklistTriggeredAction("towels", ItemActionType.CONSUME_STOCK, BigDecimal("2"), stockItemId = stock),
            )
        val none =
            listOf(
                check("shower", CheckAnswer.OK),
                ChecklistAnswer("towels", ChecklistItemType.QUANTITY, quantity = BigDecimal.ZERO, answeredAt = t0),
            ).associateBy { it.itemKey }
        ChecklistAnswerRules.triggeredActions(content, none) shouldBe emptyList()
    }

    @Test
    fun `answers must fit the item type`() {
        fun refused(input: ChecklistAnswerInput): String =
            shouldThrow<InvalidInputException> { ChecklistAnswerRules.normalize(content, listOf(input), t0) }
                .violations
                .single()
                .field

        refused(ChecklistAnswerInput("nope", check = CheckAnswer.OK)) shouldBe "answers[0].itemKey"
        refused(ChecklistAnswerInput("bed")) shouldBe "answers[0].check"
        refused(ChecklistAnswerInput("bed", check = CheckAnswer.OK, quantity = BigDecimal.ONE)) shouldBe
            "answers[0].quantity"
        refused(ChecklistAnswerInput("towels", quantity = BigDecimal("1000000"))) shouldBe "answers[0].quantity"
        refused(ChecklistAnswerInput("leak", choice = "maybe")) shouldBe "answers[0].choice"
        val normalized =
            ChecklistAnswerRules.normalize(
                content,
                listOf(ChecklistAnswerInput("towels", quantity = BigDecimal("2.500"))),
                t0,
            )
        normalized.single().quantity shouldBe BigDecimal("2.5")
    }

    @Test
    fun `only the executor answers and completes, once, and hidden answers are dropped`() {
        val executor = UserId(UuidV7.generate())
        val execution =
            ChecklistExecution.start(ChecklistExecutionId.new(), version(), null, executor, t0, null, null, t0)
        val answers =
            listOf(check("bed", CheckAnswer.OK), check("shower", CheckAnswer.OK), choice("leak", "yes"))
        shouldThrow<PermissionDeniedException> {
            execution.replaceAnswers(UserId(UuidV7.generate()), answers, t0)
        }.code.value shouldBe "CHECKLIST_EXECUTOR_REQUIRED"
        execution.replaceAnswers(executor, answers, t0) shouldBe true
        execution.replaceAnswers(executor, answers.map { it.copy(answeredAt = t0.plusSeconds(60)) }, t0) shouldBe false

        val end = t0.plus(Duration.ofMinutes(10))
        execution.complete(executor, content, end, null, null, end) shouldBe emptyList()
        execution.status shouldBe ChecklistExecutionStatus.COMPLETED
        execution.completedAt shouldBe end
        execution.answers.map { it.itemKey } shouldContainExactly listOf("bed", "shower")
        shouldThrow<ConflictException> {
            execution.complete(executor, content, end, null, null, end)
        }.code.value shouldBe "CHECKLIST_EXECUTION_ALREADY_COMPLETED"
    }

    @Test
    fun `completing without a visible required answer is refused and changes nothing`() {
        val executor = UserId(UuidV7.generate())
        val execution =
            ChecklistExecution.start(ChecklistExecutionId.new(), version(), null, executor, t0, null, null, t0)
        execution.replaceAnswers(
            executor,
            listOf(check("bed", CheckAnswer.OK), check("shower", CheckAnswer.NOT_OK)),
            t0,
        )
        val error =
            shouldThrow<RequiredItemsMissingException> { execution.complete(executor, content, t0, null, null, t0) }
        error.missingItems shouldContainExactly listOf("leak")
        error.problemProperties shouldBe mapOf("missingItems" to listOf("leak"))
        execution.status shouldBe ChecklistExecutionStatus.IN_PROGRESS
    }

    @Test
    fun `a routine pending execution is assumed once, by the first candidate`() {
        val candidate = UserId(UuidV7.generate())
        val pending =
            ChecklistExecution.scheduled(
                ChecklistRoutineId.new(),
                version().templateId,
                ChecklistContext.HOUSEKEEPING,
                null,
                LocalDate.of(2026, 10, 17),
                t0,
                setOf(candidate),
                t0,
            )
        pending.ownerIds shouldBe setOf(candidate)
        pending.assume(version(), candidate, t0, null, null, t0)
        pending.ownerIds shouldBe setOf(candidate)
        shouldThrow<ConflictException> { pending.assume(version(), candidate, t0, null, null, t0) }
            .code.value shouldBe "CHECKLIST_EXECUTION_ALREADY_STARTED"
    }

    @Test
    fun `candidates are the linked staff whose shift covers the routine time, including the night before`() {
        val department = DepartmentId(UuidV7.generate())
        val morning = shift(department, 6, 14)
        val night = shift(department, 22, 6)
        val users = List(4) { UserId(UuidV7.generate()) }
        val (a, b, c) = users
        val d = users[3]
        val today =
            DaySchedule(
                LocalDate.of(2026, 10, 17),
                department,
                listOf(
                    ShiftAssignment(employee(department, a), morning, ScheduleEntryKind.SHIFT),
                    ShiftAssignment(employee(department, b), night, ScheduleEntryKind.SHIFT),
                    ShiftAssignment(employee(department, null), morning, ScheduleEntryKind.SHIFT),
                    ShiftAssignment(employee(department, d, active = false), morning, ScheduleEntryKind.SWAP),
                ),
            )
        val yesterday =
            DaySchedule(
                LocalDate.of(2026, 10, 16),
                department,
                listOf(
                    ShiftAssignment(employee(department, c), night, ScheduleEntryKind.SHIFT),
                    ShiftAssignment(employee(department, a), morning, ScheduleEntryKind.SHIFT),
                ),
            )

        ChecklistRoutine.candidates(LocalTime.of(8, 0), today, yesterday) shouldContainExactlyInAnyOrder listOf(a)
        ChecklistRoutine.candidates(LocalTime.of(3, 0), today, yesterday) shouldContainExactlyInAnyOrder listOf(c)
        ChecklistRoutine.candidates(LocalTime.of(23, 0), today, yesterday) shouldContainExactlyInAnyOrder listOf(b)
        ChecklistRoutine.candidates(LocalTime.of(14, 0), today, yesterday) shouldBe emptySet()
    }

    @Test
    fun `a weekly routine occurs only on its weekdays`() {
        val routine =
            ChecklistRoutine.create(
                RoutineDefinition(
                    "Piscina",
                    ChecklistTemplateId.new(),
                    DepartmentId(UuidV7.generate()),
                    RoutineFrequency.WEEKLY,
                    setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                    LocalTime.of(7, 0),
                    null,
                ),
                t0,
            )
        (12..18).map { LocalDate.of(2026, 10, it) }.filter(routine::occursOn).map { it.dayOfWeek } shouldContainExactly
            listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        shouldThrow<InvalidInputException> {
            ChecklistRoutine.create(routine.definition.copy(frequency = RoutineFrequency.DAILY), t0)
        }.code.value shouldBe "CHECKLIST_ROUTINE_INVALID"
    }

    private val versionView by lazy {
        ChecklistVersionView(
            ChecklistVersionId.new(),
            ChecklistTemplateId.new(),
            1,
            "Modelo",
            ChecklistContext.HOUSEKEEPING,
            content,
            t0,
            null,
        )
    }

    private fun version() = versionView

    private fun check(
        key: String,
        value: CheckAnswer,
    ) = ChecklistAnswer(key, ChecklistItemType.CHECK, check = value, answeredAt = t0)

    private fun choice(
        key: String,
        value: String,
    ) = ChecklistAnswer(key, ChecklistItemType.SINGLE_CHOICE, choice = value, answeredAt = t0)

    private fun shift(
        department: DepartmentId,
        start: Int,
        end: Int,
    ) = ShiftSummary(ShiftId(UuidV7.generate()), department, "T$start", LocalTime.of(start, 0), LocalTime.of(end, 0))

    private fun employee(
        department: DepartmentId,
        user: UserId?,
        active: Boolean = true,
    ) = EmployeeSummary(EmployeeId(UuidV7.generate()), "Colaboradora", "Camareira", department, user, active)
}
