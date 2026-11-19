package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A conclusão da tarefa de governança (M6 E04, Task 2): o `start` abre o checklist da versão publicada vigente (D5);
 * o `complete` fecha o checklist (ou aceita o já concluído por fora), leva a UH Suja a Limpa pela `RoomsApi` (D3),
 * grava a observação de ocupação, atualiza a faxina programada (D10), quem limpou e os tempos (D6) e publica
 * `HousekeepingTaskCompleted` uma vez (D7); com item obrigatório faltando, `422` e nada muda (Review Focus 4). A recusa
 * por não perturbe liga o sinal só numa UH ocupada (D4).
 *
 * Os modelos **gerais** de governança são neutralizados antes de cada teste
 * (`HousekeepingExecutionTestSupport.neutralizeGeneralHousekeepingTemplates`); os modelos usados aqui são vinculados ao
 * tipo de limpeza do próprio teste.
 */
@IntegrationTest
@RecordApplicationEvents
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // os cenários da conclusão e da recusa por não perturbe
class HousekeepingTaskCompletionApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired staffApi: StaffApi,
) {
    // O registro de eventos do Spring só chega por campo (não pelo construtor).
    @Suppress("VarCouldBeVal") // lateinit, injetado pelo Spring
    @Autowired
    private lateinit var events: ApplicationEvents

    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    // --- UH, inspeção, quem limpou e os tempos ---------------------------------------------------------

    @Test
    fun `a dirty room is cleaned - DONE without inspection, AWAITING_INSPECTION with it`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)

            val plainRoom = RoomId(housekeeping.createRoom())
            val plain = task(attendant, type(requiresInspection = false), room = plainRoom.value)
            execution.acted(plain, "start", auth)
            val done = complete(plain, auth).ok()
            done["status"].asString() shouldBe "DONE"
            done["cleanedBy"].asString() shouldBe attendant.user.id.toString()
            roomsApi.status(plainRoom).shouldNotBeNull().let {
                it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
                it.inspectionPending shouldBe false
            }

            val inspectedRoom = RoomId(housekeeping.createRoom())
            val inspected = task(attendant, type(requiresInspection = true), room = inspectedRoom.value)
            execution.acted(inspected, "start", auth)
            complete(inspected, auth).ok()["status"].asString() shouldBe "AWAITING_INSPECTION"
            roomsApi.status(inspectedRoom).shouldNotBeNull().let {
                it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
                it.inspectionPending shouldBe true
            }
            housekeeping.auditActions("housekeeping.task", inspected).last() shouldBe "housekeeping.task.complete"
        }

    @Test
    fun `who cleaned, the first start, the finish and the worked time without the pauses are recorded`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = task(attendant, type(requiresInspection = false), room = housekeeping.createRoom())
            val now = Instant.now()

            // Trabalhou de T-5h a T-2h, ficou pausada de T-2h a T-1h e retomou em T-1h (ajustes diretos no banco,
            // porque o instante das ações é o do relógio do servidor).
            execution.acted(task, "start", auth)
            shift(task, "started_at = :t, in_progress_since = :t, last_transition_at = :t", now.minus(HOURS_5))
            execution.acted(task, "pause", auth)
            jdbc
                .sql(
                    "UPDATE housekeeping.housekeeping_task SET worked_seconds = :worked, last_transition_at = :t, " +
                        "updated_at = :t WHERE id = :id",
                ).param("worked", HOURS_3.seconds)
                .param("t", now.minus(HOURS_2).atOffset(ZoneOffset.UTC))
                .param("id", task)
                .update()
            execution.acted(task, "resume", auth)
            shift(task, "in_progress_since = :t, last_transition_at = :t", now.minus(HOURS_1))

            val body = complete(task, auth).ok()
            body["status"].asString() shouldBe "DONE"
            body["cleanedBy"].asString() shouldBe attendant.user.id.toString()
            val startedAt = Instant.parse(body["startedAt"].asString())
            val finishedAt = Instant.parse(body["finishedAt"].asString())
            // O banco guarda microssegundos (arredondados).
            Duration.between(now.minus(HOURS_5), startedAt).abs().toMillis() shouldBe 0
            Duration.between(startedAt, finishedAt).seconds shouldBeGreaterThanOrEqual HOURS_5.seconds
            // 3 h no primeiro trecho e 1 h no segundo, sem a pausa de 1 h.
            body["workedSeconds"].asLong().shouldBeBetween(HOURS_4.seconds, HOURS_4.seconds + SLACK_SECONDS)

            val row = taskRow(task)
            row["cleaned_by"] shouldBe attendant.user.id.value
            row["worked_seconds"] shouldBe body["workedSeconds"].asLong()
            row["finished_at"].shouldNotBeNull()
            row["in_progress_since"].shouldBeNull()
        }

    // --- D3: UH já limpa ou inspecionada, e área (Review Focus 3) --------------------------------------

    @Test
    fun `a clean or inspected room, or an area, is not changed and the task is DONE (Review Focus 3)`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val type = type(requiresInspection = true, checklistTemplateId = publishedTemplate())

            val clean = RoomId(housekeeping.createRoom())
            roomsApi.markClean(clean, requiresInspection = false, source = RoomStatusSource.MANUAL)
            val inspected = RoomId(housekeeping.createRoom())
            roomsApi.markClean(inspected, requiresInspection = false, source = RoomStatusSource.MANUAL)
            roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)
            val area = housekeeping.rooms.createArea()

            listOf(
                task(attendant, type, room = clean.value) to clean,
                task(attendant, type, room = inspected.value) to inspected,
                task(attendant, type, area = area) to null,
            ).forEach { (task, room) ->
                withClue("task $task, room $room") {
                    val before = room?.let { roomsApi.status(it).shouldNotBeNull() }
                    execution.acted(task, "start", auth)
                    val checklist = checklistOf(task).shouldNotBeNull()

                    complete(task, auth, answers = listOf(check("bed"))).ok()["status"].asString() shouldBe "DONE"

                    room?.let { roomsApi.status(it) shouldBe before }
                    checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe
                        ChecklistExecutionStatus.COMPLETED
                    completedEvents(task).single().finalStatus shouldBe TaskStatus.DONE
                }
            }
        }

    // --- D5: o checklist no início e as respostas sem checklist -------------------------------------------

    @Test
    fun `start opens the execution of the current published version and keeps its id`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val template = publishedTemplate()
            // Uma versão 2 publicada: é ela que vale no início.
            val body =
                templates.templateBody(sections = listOf(templates.section("Novo", templates.item("bed", "Cama"))))
            call(PUT, "/api/v1/checklist-templates/$template", asAdmin(), body, templates.etagOf(template)).ok()
            templates.publish(template) shouldBe 2
            val task = task(attendant, type(checklistTemplateId = template), room = housekeeping.createRoom())

            val started = json(execution.acted(task, "start", auth).second)

            // O id da execução vai na resposta (M1 da revisão): o cliente sabe que há checklist e manda `answers`.
            started["checklistExecutionId"].asString() shouldBe checklistOf(task).shouldNotBeNull().value.toString()
            val checklist = checklistsApi.execution(checklistOf(task).shouldNotBeNull()).shouldNotBeNull()
            checklist.status shouldBe ChecklistExecutionStatus.IN_PROGRESS
            checklist.templateId.value shouldBe template
            checklist.versionNumber shouldBe 2
            checklist.subject.shouldNotBeNull().let {
                it.type shouldBe "housekeeping.task"
                it.id shouldBe task
            }
            checklist.executorId shouldBe attendant.user.id
        }

    @Test
    fun `without a template the task has no checklist, and answers on the completion are a 400`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            // O modelo vinculado e depois desativado não vale, e nenhum outro se aplica.
            val template = publishedTemplate()
            val type = type(checklistTemplateId = template)
            call(POST, "/api/v1/checklist-templates/$template/deactivate", asAdmin()).ok()
            val task = task(attendant, type, room = housekeeping.createRoom())

            json(execution.acted(task, "start", auth).second)["checklistExecutionId"].isNull shouldBe true
            checklistOf(task).shouldBeNull()

            val problem = complete(task, auth, answers = listOf(check("bed"))).problem(400, "VALIDATION_FAILED")
            problem["errors"].values().map { it["field"].asString() } shouldBe listOf("answers")
            taskRow(task)["status"] shouldBe "IN_PROGRESS"
            // O número de hóspedes sem a ocupação: a ocupação é obrigatória (REQUIRED, padrão da E20).
            complete(task, auth, foundGuestCount = 2).problem(400, "VALIDATION_FAILED")["errors"].values().map {
                it["field"].asString() to it["code"].asString()
            } shouldBe listOf("foundOccupancy" to "REQUIRED")

            complete(task, auth).ok()["status"].asString() shouldBe "DONE"
        }

    // --- Review Focus 4: item obrigatório faltando ------------------------------------------------------

    @Test
    fun `a missing required item is a 422 and nothing changes, then the completion does everything once`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val room = RoomId(housekeeping.createRoom())
            val type = type(requiresInspection = true, checklistTemplateId = publishedTemplate())
            val today = hotel.currentBusinessDate()
            val schedule = schedule(type, room.value, lastDoneOn = today.minusDays(1))
            val task = scheduledTask(attendant, schedule, type, room.value, today)
            execution.acted(task, "start", auth)
            val checklist = checklistOf(task).shouldNotBeNull()
            val taskBefore = taskRow(task)
            val roomBefore = roomsApi.status(room).shouldNotBeNull()
            val scheduleBefore = scheduleRow(schedule)

            complete(task, auth, answers = listOf(check("towels")), foundOccupancy = "VACANT")
                .problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")

            taskRow(task) shouldBe taskBefore
            checklistsApi.execution(checklist).shouldNotBeNull().let {
                it.status shouldBe ChecklistExecutionStatus.IN_PROGRESS
                it.answers shouldBe emptyList()
            }
            roomsApi.status(room) shouldBe roomBefore
            scheduleRow(schedule) shouldBe scheduleBefore
            observations(room) shouldBe 0
            completedEvents(task) shouldBe emptyList()
            checklistCompletions(checklist) shouldBe 0

            val body = complete(task, auth, answers = listOf(check("bed")), foundOccupancy = "VACANT").ok()

            body["status"].asString() shouldBe "AWAITING_INSPECTION"
            roomsApi.status(room).shouldNotBeNull().inspectionPending shouldBe true
            checklistCompletions(checklist) shouldBe 1
            observations(room) shouldBe 1
            scheduleRow(schedule).let {
                (it["last_done_on"] as java.sql.Date).toLocalDate() shouldBe today
                (it["next_due_on"] as java.sql.Date).toLocalDate() shouldBe today.plusDays(FREQUENCY_DAYS)
                (it["updated_at"] == scheduleBefore["updated_at"]) shouldBe false
            }
            completedEvents(task).single().let {
                it.taskId shouldBe task
                it.roomId shouldBe room.value
                it.areaId shouldBe null
                it.cleaningTypeId shouldBe type
                it.businessDate shouldBe today
                it.finalStatus shouldBe TaskStatus.AWAITING_INSPECTION
                it.completedBy shouldBe attendant.user.id.value
                it.completedAt shouldBe Instant.parse(body["finishedAt"].asString())
            }
        }

    @Test
    fun `the completed event carries no guest data (RN-13)`() {
        HousekeepingTaskCompleted::class.java.declaredFields
            .filterNot {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }.map { it.name } shouldContainExactlyInAnyOrder
            listOf(
                "taskId",
                "roomId",
                "areaId",
                "cleaningTypeId",
                "businessDate",
                "finalStatus",
                "completedBy",
                "completedAt",
            )
    }

    // --- Foto do checklist na conclusão online (I1 da revisão) ---------------------------------------------

    @Test
    fun `an unknown photo on a required photo item is refused online, also when the housekeeper completes`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val photo = templates.item("photo", type = ChecklistItemType.PHOTO, extra = mapOf("required" to true))
            val room = RoomId(housekeeping.createRoom())
            val task = task(attendant, type(checklistTemplateId = publishedTemplate(photo)), room = room.value)
            execution.acted(task, "start", asTestUser(attendant.user))
            val checklist = checklistOf(task).shouldNotBeNull()
            val taskBefore = taskRow(task)
            val roomBefore = roomsApi.status(room).shouldNotBeNull()
            val answers = listOf(check("bed"), mapOf("itemKey" to "photo", "photoFileId" to UuidV7.generate()))

            listOf(
                asTestUser(attendant.user),
                asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)),
            ).forEach { auth ->
                complete(task, auth, answers = answers).problem(422, "CHECKLIST_PHOTO_NOT_FOUND")
            }

            taskRow(task) shouldBe taskBefore
            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.IN_PROGRESS
            roomsApi.status(room) shouldBe roomBefore
            completedEvents(task) shouldBe emptyList()
            checklistCompletions(checklist) shouldBe 0
        }

    // --- Checklist concluído por fora --------------------------------------------------------------------

    @Test
    fun `an execution completed elsewhere - the task completes without 500 and without a second ChecklistCompleted`():
        Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task =
                task(attendant, type(checklistTemplateId = publishedTemplate()), room = housekeeping.createRoom())
            execution.acted(task, "start", auth)
            val checklist = checklistOf(task).shouldNotBeNull()
            checklistsApi.completeExecution(
                checklist,
                listOf(ChecklistAnswerInput("bed", check = CheckAnswer.OK)),
                Instant.now(),
            )
            checklistCompletions(checklist) shouldBe 1

            // As respostas do corpo são ignoradas (até as que faltariam).
            complete(task, auth, answers = listOf(check("towels"))).ok()["status"].asString() shouldBe "DONE"

            checklistCompletions(checklist) shouldBe 1
            completedEvents(task).size shouldBe 1
        }

    // --- D10: faxina desativada depois da geração -------------------------------------------------------

    @Test
    fun `a schedule deactivated after the generation does not change, and the task completes`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val room = housekeeping.createRoom()
            val type = type()
            val today = hotel.currentBusinessDate()
            val schedule = schedule(type, room, lastDoneOn = today.minusDays(1))
            val task = scheduledTask(attendant, schedule, type, room, today)
            call(POST, "${HousekeepingTestSupport.SCHEDULED_CLEANINGS}/$schedule/deactivate", asAdmin()).ok()
            val before = scheduleRow(schedule)
            execution.acted(task, "start", auth)

            complete(task, auth).ok()["status"].asString() shouldBe "DONE"

            scheduleRow(schedule) shouldBe before
            jdbc
                .sql(
                    "SELECT after_state ->> 'scheduledCleaningInactive' FROM audit.audit_entry " +
                        "WHERE entity_type = 'housekeeping.task' AND entity_id = :id " +
                        "AND action = 'housekeeping.task.complete'",
                ).param("id", task.toString())
                .query(String::class.java)
                .single() shouldBe "true"
        }

    // --- D4: a recusa por não perturbe ------------------------------------------------------------------

    @Test
    fun `refusing for do not disturb sets the flag on an occupied room only, and always answers 200 REFUSED`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val dnd = mapOf("reason" to "DO_NOT_DISTURB")

            val occupied = RoomId(housekeeping.createRoom())
            roomsApi.markOccupied(occupied, 2, RoomStatusSource.CHECK_IN)
            val onOccupied = task(attendant, type(), room = occupied.value)
            json(execution.acted(onOccupied, "refuse", auth, dnd).second)["status"].asString() shouldBe "REFUSED"
            roomsApi.status(occupied).shouldNotBeNull().serviceFlag shouldBe GuestServiceFlag.DO_NOT_DISTURB

            val vacant = RoomId(housekeeping.createRoom())
            val vacantBefore = roomsApi.status(vacant).shouldNotBeNull()
            val onVacant = task(attendant, type(), room = vacant.value)
            json(execution.acted(onVacant, "refuse", auth, dnd).second)["status"].asString() shouldBe "REFUSED"
            roomsApi.status(vacant) shouldBe vacantBefore

            val onArea = task(attendant, type(), area = housekeeping.rooms.createArea())
            json(execution.acted(onArea, "refuse", auth, dnd).second)["status"].asString() shouldBe "REFUSED"
        }

    // --- Apoio -------------------------------------------------------------------------------------------

    private fun type(
        requiresInspection: Boolean = false,
        checklistTemplateId: UUID? = null,
    ): UUID =
        housekeeping.createCleaningType(
            housekeeping.cleaningTypeBody(
                requiresInspection = requiresInspection,
                checklistTemplateId = checklistTemplateId,
            ),
        )

    /** Um modelo de governança publicado com `bed` obrigatório e `towels` opcional, só para o tipo que o vincula. */
    private fun publishedTemplate(vararg extraItems: Map<String, Any?>): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    // Uma regra que não vale para nenhuma tarefa: o modelo só entra pelo vínculo do tipo.
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(UuidV7.generate()))),
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("bed", extra = mapOf("required" to true)),
                                templates.item("towels"),
                                *extraItems,
                            ),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    /** Uma tarefa avulsa da data operacional no local dado, atribuída à [attendant]. */
    private fun task(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        type: UUID,
        room: UUID? = null,
        area: UUID? = null,
    ): UUID {
        val body = housekeeping.taskBody(type, roomId = room, areaId = area)
        return UUID.fromString(housekeeping.requestTask(body)["id"].asString()).also {
            execution.assign(it, attendant.employee)
        }
    }

    private fun schedule(
        type: UUID,
        room: UUID,
        lastDoneOn: LocalDate,
    ): UUID =
        housekeeping.createSchedule(
            housekeeping.scheduleBody(
                type,
                roomId = room,
                frequencyDays = FREQUENCY_DAYS.toInt(),
                lastDoneOn = lastDoneOn.toString(),
            ),
        )

    /**
     * Uma tarefa `SCHEDULED` da faxina [schedule] na [date], já atribuída: escrita direta (o que a geração grava),
     * porque a geração roda para o hotel inteiro no banco compartilhado. A faxina tem a próxima data depois de [date],
     * então a geração não cria outra.
     */
    private fun scheduledTask(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        schedule: UUID,
        type: UUID,
        room: UUID,
        date: LocalDate,
    ): UUID {
        val id = UuidV7.generate()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, business_date, cleaning_type_id, source,
                    status, priority, credits, arrival_expected, scheduled_cleaning_id, assigned_employee_id,
                    assignment_position, created_at, updated_at, last_transition_at, version)
                VALUES (:id, :room, :date, :type, 'SCHEDULED', 'ASSIGNED', 3, 0, false, :schedule, :employee, 1,
                    :now, :now, :now, 0)
                """.trimIndent(),
            ).param("id", id)
            .param("room", room)
            .param("date", date)
            .param("type", type)
            .param("schedule", schedule)
            .param("employee", attendant.employee)
            .param("now", now)
            .update() shouldBe 1
        return id
    }

    private fun complete(
        task: UUID,
        auth: RequestPostProcessor,
        answers: List<Map<String, Any?>>? = null,
        foundOccupancy: String? = null,
        foundGuestCount: Int? = null,
    ) = execution.act(
        task,
        "complete",
        auth,
        body =
            buildMap {
                if (answers != null) put("answers", answers)
                if (foundOccupancy != null) put("foundOccupancy", foundOccupancy)
                if (foundGuestCount != null) put("foundGuestCount", foundGuestCount)
            },
    )

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun shift(
        task: UUID,
        assignments: String,
        at: Instant,
    ) {
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET $assignments, updated_at = :t WHERE id = :id")
            .param("t", at.atOffset(ZoneOffset.UTC))
            .param("id", task)
            .update() shouldBe 1
    }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun checklistOf(task: UUID): ChecklistExecutionId? =
        (taskRow(task)["checklist_execution_id"] as UUID?)?.let(::ChecklistExecutionId)

    private fun scheduleRow(schedule: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.scheduled_cleaning WHERE id = :id")
            .param("id", schedule)
            .query()
            .singleRow()

    private fun observations(room: RoomId): Int =
        jdbc
            .sql("SELECT count(*) FROM rooms.housekeeping_observation WHERE room_id = :id")
            .param("id", room.value)
            .query(Int::class.java)
            .single()

    private fun completedEvents(task: UUID): List<HousekeepingTaskCompleted> =
        events.stream(HousekeepingTaskCompleted::class.java).filter { it.taskId == task }.toList()

    private fun checklistCompletions(checklist: ChecklistExecutionId): Int =
        events
            .stream(ChecklistCompleted::class.java)
            .filter { it.executionId == checklist.value }
            .count()
            .toInt()

    private companion object {
        const val FREQUENCY_DAYS = 7L
        const val SLACK_SECONDS = 60L
        val HOURS_1: Duration = Duration.ofHours(1)
        val HOURS_2: Duration = Duration.ofHours(2)
        val HOURS_3: Duration = Duration.ofHours(3)
        val HOURS_4: Duration = Duration.ofHours(4)
        val HOURS_5: Duration = Duration.ofHours(5)
    }
}
