package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O ataque da etapa E05 (M6): as lacunas das revisões de fatia. Execução do checklist aberta depois do 422, inspetor
 * que conclui a inspeção de outro, `answers` sem checklist, defasagem offline com igualdade, controle positivo do
 * feed da camareira, UH depois da recusa da voluntária e a concorrência por `pg_blocking_pids`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class HousekeepingInspectionAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired files: FilesTestSupport,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution
    private val templates = ChecklistsTestSupport(iam)
    private val executions = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `a 422 for a missing required item keeps the checklist execution open and a retry completes it`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            support.inspectionTemplate(type)
            val (task, _) = support.awaitingInspection(type)
            val inspector = asTestUser(support.inspector())
            val (etag, started) = execution.acted(task, "inspection/start", inspector)
            val checklist =
                ChecklistExecutionId(UUID.fromString(json(started)["inspection"]["checklistExecutionId"].asString()))
            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.IN_PROGRESS

            execution
                .act(
                    task,
                    "inspection/complete",
                    inspector,
                    etag,
                    support.complete("APPROVED", answers = listOf(support.check("towels"))),
                ).problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")

            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.IN_PROGRESS
            support.inspectionRow(task)["status"] shouldBe "IN_PROGRESS"
            execution
                .act(
                    task,
                    "inspection/complete",
                    inspector,
                    etag,
                    support.complete("APPROVED", answers = listOf(support.check("bed"))),
                ).ok()["task"]["status"]
                .asString() shouldBe "DONE"
            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.COMPLETED
        }

    @Test
    fun `an inspector completes the inspection opened by another, and inspectorId is who completed`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            support.inspectionTemplate(type)
            val (task, _) = support.awaitingInspection(type)
            val a = support.inspector()
            val b = support.inspector()
            execution.acted(task, "inspection/start", asTestUser(a))

            val body =
                execution
                    .act(
                        task,
                        "inspection/complete",
                        asTestUser(b),
                        body = support.complete("APPROVED", answers = listOf(support.check("bed"))),
                    ).ok()

            body["inspection"]["inspectorId"].asString() shouldBe b.id.toString()
            support.inspectionRow(task)["inspector_id"].toString() shouldBe b.id.toString()
        }

    @Test
    fun `a required photo of an inspection started by A is refused when B completes with a photo of B`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val template =
                templates.create(
                    templates.templateBody(
                        context = ChecklistContext.INSPECTION,
                        applicability = listOf(mapOf("cleaningTypeIds" to listOf(type))),
                        sections =
                            listOf(
                                templates.section(
                                    "Quarto",
                                    templates.item("bed"),
                                    templates.item(
                                        "photo",
                                        type = ChecklistItemType.PHOTO,
                                        extra =
                                            mapOf("required" to true),
                                    ),
                                ),
                            ),
                    ),
                )
            templates.publish(template)
            val (task, _) = support.awaitingInspection(type)
            val a = support.inspector()
            val b = support.inspector()
            val (etag, _) = execution.acted(task, "inspection/start", asTestUser(a))

            val photoOfB = executions.uploadPhoto(asTestUser(b))
            val answers = listOf(support.check("bed"), mapOf("itemKey" to "photo", "photoFileId" to photoOfB))
            execution
                .act(task, "inspection/complete", asTestUser(b), etag, support.complete("APPROVED", answers = answers))
                .problem(422, "CHECKLIST_PHOTO_INVALID")
            support.inspectionRow(task)["status"] shouldBe "IN_PROGRESS"

            // Controle positivo: a foto do inspetor que iniciou serve, ainda que B conclua.
            val photoOfA = executions.uploadPhoto(asTestUser(a))
            val ownAnswers = listOf(support.check("bed"), mapOf("itemKey" to "photo", "photoFileId" to photoOfA))
            execution
                .act(
                    task,
                    "inspection/complete",
                    asTestUser(b),
                    etag,
                    support.complete("APPROVED", answers = ownAnswers),
                ).ok()["task"]["status"]
                .asString() shouldBe "DONE"
        }

    @Test
    fun `answers on an inspection without a checklist are 400 on the field and nothing changes`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val (task, room) = support.awaitingInspection(type)
            val before = roomsApi.status(room).shouldNotBeNull()

            val problem =
                execution
                    .act(
                        task,
                        "inspection/complete",
                        asTestUser(support.inspector()),
                        body = support.complete("APPROVED", answers = listOf(support.check("bed"))),
                    ).problem(400, "VALIDATION_FAILED")

            problem["errors"][0]["field"].asString() shouldBe "answers"
            roomsApi.status(room) shouldBe before
            execution.plans.task(task)["status"].asString() shouldBe "AWAITING_INSPECTION"
            support.inspectionCount(task) shouldBe 0
        }

    @Test
    fun `the attendant feed carries her own tasks and still not the inspections source`() {
        val attendant = execution.attendant()
        val assigned = support.assignedTask(support.cleaningType(), attendant.employee).first
        support.awaitingInspection(support.cleaningType(), attendant)

        val changes = download(iam.asTestUser(attendant.user))

        changes
            .filter { it["source"].asString() == HousekeepingOffline.TASKS.name }
            .map { it["id"].asString() } shouldContain assigned.toString()
        changes.filter { it["source"].asString() == HousekeepingOffline.INSPECTIONS.name }.shouldBeEmpty()
    }

    @Test
    fun `an offline start before the last transition is stored at it, and the completion equals the start`() {
        val type = support.cleaningType()
        support.inspectionTemplate(type)
        val (task, _) = support.awaitingInspection(type)
        val lastTransition = instant(taskRow(task)["last_transition_at"])
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                command("start-inspection", mapOf("taskId" to task), lastTransition.minusSeconds(60), now),
                command(
                    "complete-inspection",
                    mapOf("taskId" to task, "result" to "APPROVED", "answers" to listOf(support.check("bed"))),
                    lastTransition.minusSeconds(30),
                    now,
                ),
            )

        results.map { it["status"].asString() }.forEach { it.startsWith("APPLIED") shouldBe true }
        val row = support.inspectionRow(task)
        instant(row["started_at"]) shouldBe lastTransition
        instant(row["completed_at"]) shouldBe instant(row["started_at"])
    }

    @Test
    fun `a refused voluntary inspection of a dirty room leaves the room dirty and records nothing`(): Unit =
        with(iam) {
            val room = RoomId(support.housekeeping.createRoom())
            val before = roomsApi.status(room).shouldNotBeNull()

            call(POST, "/api/v1/room-inspections", asTestUser(support.inspector()), mapOf("roomId" to room.value))
                .problem(409, "HOUSEKEEPING_ROOM_NOT_CLEAN")

            roomsApi.status(room) shouldBe before
            before.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            jdbc
                .sql("SELECT count(*) FROM housekeeping.inspection WHERE room_id = :r")
                .param("r", room.value)
                .query(Int::class.java)
                .single() shouldBe 0
        }

    @Test
    fun `two simultaneous starts on the same task leave one open inspection and no 500`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            support.inspectionTemplate(type)
            val (task, _) = support.awaitingInspection(type)
            val inspectors = listOf(asTestUser(support.inspector()), asTestUser(support.inspector()))
            val etag = support.housekeeping.etagOf("${HousekeepingTestSupport.TASKS}/$task")
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val statuses =
                    holdingTaskLock(task) { holder ->
                        val calls =
                            inspectors.map { who ->
                                CompletableFuture.supplyAsync(
                                    { execution.act(task, "inspection/start", who, etag).status },
                                    pool,
                                )
                            }
                        await().atMost(Duration.ofSeconds(LOCK_WAIT_SECONDS)).until { blockedBy(holder) >= 1 }
                        calls
                    }.map { it.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS) }

                statuses.count { it == 200 } shouldBe 1
                statuses.none { it >= 500 } shouldBe true
                jdbc
                    .sql("SELECT count(*) FROM housekeeping.inspection WHERE task_id = :t AND status = 'IN_PROGRESS'")
                    .param("t", task)
                    .query(Int::class.java)
                    .single() shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

    @Test
    fun `the database refuses a second open inspection of the same task`() {
        val type = support.cleaningType()
        val (task, room) = support.awaitingInspection(type)
        val insert = {
            jdbc
                .sql(
                    "INSERT INTO housekeeping.inspection (id, task_id, room_id, status, inspector_id, started_at) " +
                        "VALUES (:id, :t, :r, 'IN_PROGRESS', :i, now())",
                ).param("id", UUID.randomUUID())
                .param("t", task)
                .param("r", room.value)
                .param("i", UUID.randomUUID())
                .update()
        }
        insert() shouldBe 1
        val failure = runCatching { insert() }.exceptionOrNull()
        failure?.message.orEmpty().contains("inspection_open_task_uk") shouldBe true
    }

    // --- Apoio -------------------------------------------------------------------------------------------

    /** Roda o [block] com a linha da tarefa travada por outra sessão; solta a trava ao fim do bloco. */
    private fun <T> holdingTaskLock(
        task: UUID,
        block: (holderPid: Int) -> T,
    ): T =
        transactions.execute {
            jdbc
                .sql("SELECT id FROM housekeeping.housekeeping_task WHERE id = :t FOR UPDATE")
                .param("t", task)
                .query(UUID::class.java)
                .single()
            val pid = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()
            block(pid)
        }!!

    private fun blockedBy(holder: Int): Int {
        // O retrato de `pg_stat_activity` fica em cache dentro da transação que segura a trava.
        jdbc.sql("SELECT pg_stat_clear_snapshot()").query().singleRow()
        return jdbc
            .sql("SELECT count(*) FROM pg_stat_activity a WHERE :h = ANY (pg_blocking_pids(a.pid))")
            .param("h", holder)
            .query(Int::class.java)
            .single()
    }

    private fun download(auth: RequestPostProcessor): List<JsonNode> =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            var cursor: String? = null
            do {
                InventoryListenersQuiescence.await(jdbc)
                val page =
                    call(
                        GET,
                        "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(),
                        auth,
                    ).ok()
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
            } while (page["hasMore"].asBoolean())
            changes
        }

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        occurredAt: Instant,
        deviceClock: Instant,
    ) = SyncCommand(UuidV7.generate(), "housekeeping.$verb", payload, occurredAt, deviceClock)

    private fun sync(
        auth: RequestPostProcessor,
        vararg commands: SyncCommand,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
        }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun instant(value: Any?): Instant = (value as Timestamp).toInstant()

    private companion object {
        const val THREADS = 2
        const val LOCK_WAIT_SECONDS = 30L
    }
}
