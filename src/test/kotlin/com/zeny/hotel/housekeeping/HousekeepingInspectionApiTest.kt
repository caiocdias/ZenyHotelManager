package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * O início e a aprovação da inspeção de uma tarefa (M6 E05, Task 1; D2 e D3): o `start` abre a inspeção e, havendo
 * modelo de contexto `INSPECTION` para a tarefa, a execução do checklist da versão vigente; o `complete` com
 * `APPROVED` conclui a inspeção e o checklist, leva a tarefa `AWAITING_INSPECTION` a `DONE` e a UH Limpa com inspeção
 * pendente a Inspecionada. Sem modelo, o `complete` abre e conclui numa chamada; com modelo e sem `start`, `409
 * HOUSEKEEPING_INSPECTION_NOT_STARTED`. A UH que perdeu a pendência não muda e a resposta o diz (Review Focus 1).
 *
 * Os modelos **gerais** de governança e de inspeção são neutralizados antes de cada teste; o modelo de inspeção daqui
 * vale só para o tipo de limpeza do próprio teste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // as APIs conferidas e o canal de tempo real
class HousekeepingInspectionApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val clock: Clock,
    @Autowired private val json: JsonMapper,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)

    /** O `ETag` de cada tarefa ao ficar `AWAITING_INSPECTION`; o usuário de cada inspetor. */
    private val etagsBefore = mutableMapOf<UUID, String>()
    private val inspectors = mutableMapOf<RequestPostProcessor, UUID>()

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `approving with a template inspects the room, finishes the task and the checklist, and publishes`(): Unit =
        with(iam) {
            val type = inspectedType()
            inspectionTemplate(type)
            val (task, room) = awaitingInspection(type)
            val inspector = inspector()
            val frames = Frames()
            val connection = listen(frames)
            try {
                val (startEtag, startResponse) = execution.acted(task, "inspection/start", inspector)
                val started = json(startResponse)
                startEtag shouldNotBe etagsBefore.getValue(task)
                started["inspection"]["status"].asString() shouldBe "IN_PROGRESS"
                started["inspection"]["taskId"].asString() shouldBe task.toString()
                started["inspection"]["result"].isNull shouldBe true
                started["task"]["status"].asString() shouldBe "AWAITING_INSPECTION"
                val checklist =
                    ChecklistExecutionId(UUID.fromString(started["inspection"]["checklistExecutionId"].asString()))
                checklistsApi.execution(checklist).shouldNotBeNull().context shouldBe ChecklistContext.INSPECTION
                awaitTaskMessage(frames, task, startEtag)

                val (etag, response) =
                    execution.acted(task, "inspection/complete", inspector, approve(answers = listOf(check("bed"))))
                val body = json(response)

                body["task"]["status"].asString() shouldBe "DONE"
                body["inspection"]["status"].asString() shouldBe "COMPLETED"
                body["inspection"]["result"].asString() shouldBe "APPROVED"
                body["inspection"]["rejectedItems"].values().toList().shouldBeEmpty()
                body["inspection"]["inspectorId"].asString() shouldBe inspectors.getValue(inspector).toString()
                body["roomChanged"].asBoolean() shouldBe true
                roomsApi.status(room).shouldNotBeNull().let {
                    it.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
                    it.inspectionPending shouldBe false
                }
                checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.COMPLETED
                inspectionRow(task).let {
                    it["status"] shouldBe "COMPLETED"
                    it["result"] shouldBe "APPROVED"
                    it["checklist_execution_id"] shouldBe checklist.value
                    it["completed_at"].shouldNotBeNull()
                }
                housekeeping.auditActions("housekeeping.task", task).last() shouldBe
                    "housekeeping.inspection.complete"
                housekeeping.auditActions(
                    "housekeeping.inspection",
                    UUID.fromString(body["inspection"]["id"].asString()),
                ) shouldContainExactly
                    listOf("housekeeping.inspection.start", "housekeeping.inspection.complete")
                awaitTaskMessage(frames, task, etag)
                etag shouldBe housekeeping.etagOf("${HousekeepingTestSupport.TASKS}/$task")
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `without a template the approval opens and completes the inspection in one call, with no start`(): Unit =
        with(iam) {
            val type = inspectedType()
            val (task, room) = awaitingInspection(type)

            val body = json(execution.acted(task, "inspection/complete", inspector(), approve()).second)

            body["task"]["status"].asString() shouldBe "DONE"
            body["inspection"]["checklistExecutionId"].isNull shouldBe true
            body["inspection"]["status"].asString() shouldBe "COMPLETED"
            roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
            inspectionRow(task)["checklist_execution_id"].shouldBeNull()
        }

    @Test
    fun `a room that lost the pending inspection does not change, and the task is DONE (Review Focus 1)`(): Unit =
        with(iam) {
            val type = inspectedType()
            val (checkedOut, dirtyRoom) = awaitingInspection(type)
            roomsApi.markVacant(dirtyRoom, RoomStatusSource.CHECK_OUT)
            val (approvedByRooms, inspectedRoom) = awaitingInspection(type)
            roomsApi.markInspected(inspectedRoom, RoomStatusSource.MANUAL)

            listOf(checkedOut to dirtyRoom, approvedByRooms to inspectedRoom).forEach { (task, room) ->
                withClue("task $task") {
                    val before = roomsApi.status(room).shouldNotBeNull()

                    val body = json(execution.acted(task, "inspection/complete", inspector(), approve()).second)

                    body["task"]["status"].asString() shouldBe "DONE"
                    body["roomChanged"].asBoolean() shouldBe false
                    roomsApi.status(room) shouldBe before
                }
            }
        }

    @Test
    fun `a second start is 409 HOUSEKEEPING_INSPECTION_ALREADY_OPEN`(): Unit =
        with(iam) {
            val type = inspectedType()
            val (task, _) = awaitingInspection(type)
            execution.acted(task, "inspection/start", inspector())

            execution.act(task, "inspection/start", inspector()).problem(409, "HOUSEKEEPING_INSPECTION_ALREADY_OPEN")
            inspectionCount(task) shouldBe 1
        }

    @Test
    fun `completing without a start when the task has a template is 409 INSPECTION_NOT_STARTED`(): Unit =
        with(iam) {
            val type = inspectedType()
            inspectionTemplate(type)
            val (task, room) = awaitingInspection(type)
            val before = roomsApi.status(room).shouldNotBeNull()

            execution
                .act(task, "inspection/complete", inspector(), body = approve(answers = listOf(check("bed"))))
                .problem(409, "HOUSEKEEPING_INSPECTION_NOT_STARTED")

            inspectionCount(task) shouldBe 0
            execution.plans.task(task)["status"].asString() shouldBe "AWAITING_INSPECTION"
            roomsApi.status(room) shouldBe before
        }

    @Test
    fun `a missing required item is 422 and nothing changes`(): Unit =
        with(iam) {
            val type = inspectedType()
            inspectionTemplate(type)
            val (task, room) = awaitingInspection(type)
            val inspector = inspector()
            val (etag, _) = execution.acted(task, "inspection/start", inspector)
            val roomBefore = roomsApi.status(room).shouldNotBeNull()

            execution
                .act(task, "inspection/complete", inspector, etag, approve(answers = listOf(check("towels"))))
                .problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")

            housekeeping.etagOf("${HousekeepingTestSupport.TASKS}/$task") shouldBe etag
            execution.plans.task(task)["status"].asString() shouldBe "AWAITING_INSPECTION"
            roomsApi.status(room) shouldBe roomBefore
            inspectionRow(task)["status"] shouldBe "IN_PROGRESS"
        }

    @Test
    fun `starting the inspection records the task in the trail, with the open inspection`(): Unit =
        with(iam) {
            val (task, _) = awaitingInspection(inspectedType())

            val (_, response) = execution.acted(task, "inspection/start", inspector())

            val inspection = json(response)["inspection"]["id"].asString()
            housekeeping.auditActions("housekeeping.task", task).last() shouldBe "housekeeping.inspection.start"
            jdbc
                .sql(
                    "SELECT after_state ->> 'openInspectionId' FROM audit.audit_entry WHERE entity_type = " +
                        "'housekeeping.task' AND entity_id = :id AND action = 'housekeeping.inspection.start'",
                ).param("id", task.toString())
                .query(String::class.java)
                .single() shouldBe inspection
        }

    // --- Apoio -------------------------------------------------------------------------------------------

    /** Um tipo de limpeza novo que exige inspeção. */
    private fun inspectedType(): UUID =
        housekeeping.createCleaningType(housekeeping.cleaningTypeBody(requiresInspection = true))

    /** Um modelo de inspeção publicado, com `bed` obrigatório e `towels` opcional, só para o tipo [type]. */
    private fun inspectionTemplate(type: UUID): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    context = ChecklistContext.INSPECTION,
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(type))),
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("bed", extra = mapOf("required" to true)),
                                templates.item("towels"),
                            ),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    /**
     * Uma tarefa do [type] numa UH nova, limpa pela camareira (`start` e `complete`): `AWAITING_INSPECTION`, com a UH
     * Limpa e a inspeção pendente. Guarda o `ETag` dela.
     */
    private fun awaitingInspection(type: UUID): Pair<UUID, RoomId> {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val room = RoomId(housekeeping.createRoom())
        val task =
            UUID.fromString(
                housekeeping.requestTask(housekeeping.taskBody(type, roomId = room.value))["id"].asString(),
            )
        execution.assign(task, attendant.employee)
        execution.acted(task, "start", auth)
        val (etag, response) = execution.acted(task, "complete", auth)
        iam.json(response)["status"].asString() shouldBe "AWAITING_INSPECTION"
        etagsBefore[task] = etag
        return task to room
    }

    /** Uma Governanta nova (o modelo da carga, com `housekeeping.inspections.perform`). */
    private fun inspector(): RequestPostProcessor {
        val user = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        return iam.asTestUser(user).also { inspectors[it] = user.id.value }
    }

    private fun approve(answers: List<Map<String, Any?>>? = null): Map<String, Any?> =
        buildMap {
            put("result", "APPROVED")
            if (answers != null) put("answers", answers)
        }

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun inspectionRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.inspection WHERE task_id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun inspectionCount(task: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.inspection WHERE task_id = :id")
            .param("id", task)
            .query(Int::class.java)
            .single()

    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit
    }

    private fun listen(frames: Frames) =
        hub.connect(
            StreamSubscription(
                Actor(UserId.new(), DeviceSessionId.new()),
                mapOf(HousekeepingRealtime.TOPIC to PermissionScope.ALL),
                clock.instant().plus(1, ChronoUnit.HOURS),
            ),
            null,
            frames,
        )

    /** A mensagem `housekeeping.task` da [task] com a versão do [etag] (o `ETag` da ação). */
    private fun awaitTaskMessage(
        frames: Frames,
        task: UUID,
        etag: String,
    ) {
        val version = etag.trim('"').toLong()
        await().atMost(Duration.ofSeconds(5)).until {
            frames.events.any { event ->
                val message = json.readTree(event.data)
                // O primeiro quadro (`ready`) não é uma mensagem de tópico.
                message["type"]?.asString() == HousekeepingRealtime.TASK &&
                    message["id"]?.asString() == task.toString() &&
                    message["version"]?.asLong() == version
            }
        }
    }
}
