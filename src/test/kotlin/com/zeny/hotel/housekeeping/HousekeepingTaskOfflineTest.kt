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
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
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
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A execução da tarefa de governança offline (M6 E19, Task 1, D1 e D2): os seis comandos `housekeeping.<verbo>-task`
 * pela sincronização (`POST /api/v1/sync/commands`), tratados pela mesma execução das ações online da E04, com o
 * `occurredAt` corrigido pela defasagem, idempotentes pelo `commandId`, e o `start-task` com a versão do checklist que
 * o aparelho tem (Review Focus 4). Nesta fatia, a posse é só a atual: com `ALL`, aplica; com `OWN`, só a dona.
 *
 * Os modelos **gerais** de governança são neutralizados antes de cada teste; os modelos usados aqui são vinculados ao
 * tipo de limpeza do próprio teste (ou só citados pelo aparelho).
 */
@IntegrationTest
@RecordApplicationEvents
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
class HousekeepingTaskOfflineTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired files: FilesTestSupport,
    @Autowired staffApi: StaffApi,
) {
    // O registro de eventos do Spring só chega por campo (não pelo construtor).
    @Suppress("VarCouldBeVal") // lateinit, injetado pelo Spring
    @Autowired
    private lateinit var events: ApplicationEvents

    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)
    private val photos = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    @Test
    fun `a batch with start-task and complete-task reaches the state of the online actions`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val template = publishedTemplate()
        val room = housekeeping.createRoom()
        val task = task(attendant, type(checklistTemplateId = template), room = room)
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        // A tarefa foi atribuída há uma hora; o aparelho (com o relógio certo) iniciou há 30 min e concluiu há 5.
        backdate(task, now.minus(HOURS_1))
        val start = command("start-task", mapOf("taskId" to task), now.minus(MINUTES_30), now)
        val complete =
            command(
                "complete-task",
                mapOf(
                    "taskId" to task,
                    "answers" to listOf(check("bed"), check("towels")),
                    "foundOccupancy" to "VACANT",
                ),
                now.minus(MINUTES_5),
                now,
            )

        val results = sync(auth, start, complete)

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        val startedAt = Instant.parse(results[0]["occurredAt"].asString())
        val finishedAt = Instant.parse(results[1]["occurredAt"].asString())
        val row = taskRow(task)
        val checklist = ChecklistExecutionId(row["checklist_execution_id"] as UUID)
        assertSoftly {
            row["status"] shouldBe "DONE"
            row["cleaned_by"] shouldBe attendant.user.id.value
            instant(row["started_at"]) shouldBe startedAt
            instant(row["finished_at"]) shouldBe finishedAt
            row["worked_seconds"] shouldBe Duration.between(startedAt, finishedAt).seconds
            withClue("o início é o do aparelho, uns 30 min antes do recebimento") {
                (Duration.between(startedAt, now).seconds in MINUTES_30.seconds - SLACK..MINUTES_30.seconds) shouldBe
                    true
            }
            checklistsApi.execution(checklist).shouldNotBeNull().let {
                it.status shouldBe ChecklistExecutionStatus.COMPLETED
                it.executorId shouldBe attendant.user.id
                it.startedAt shouldBe startedAt
                it.completedAt shouldBe finishedAt
            }
            roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            completedEvents(task).single().let {
                it.finalStatus shouldBe TaskStatus.DONE
                it.completedBy shouldBe attendant.user.id.value
                it.completedAt shouldBe finishedAt
            }
            housekeeping.auditActions("housekeeping.task", task) shouldContainExactly
                listOf(
                    "housekeeping.task.request",
                    "housekeeping.task.start-offline",
                    "housekeeping.task.complete-offline",
                )
        }
    }

    @Test
    fun `refuse, retry, start, pause, resume and complete are all applied offline`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = task(attendant, type(), room = housekeeping.createRoom())
        val now = Instant.now()
        val commands =
            listOf(
                command("refuse-task", mapOf("taskId" to task, "reason" to "GUEST_REFUSED"), now, now),
                command("retry-task", mapOf("taskId" to task), now, now),
                command("start-task", mapOf("taskId" to task), now, now),
                command("pause-task", mapOf("taskId" to task), now, now),
                command("resume-task", mapOf("taskId" to task), now, now),
                command("complete-task", mapOf("taskId" to task), now, now),
            )

        val results = sync(auth, *commands.toTypedArray())

        withClue(results.toString()) { results.map { it["status"].asString() } shouldBe List(6) { "APPLIED" } }
        taskRow(task)["status"] shouldBe "DONE"
        housekeeping.auditActions("housekeeping.task", task).drop(1) shouldContainExactly
            listOf("refuse", "retry", "start", "pause", "resume", "complete").map { "housekeeping.task.$it-offline" }
    }

    @Test
    fun `the housekeeper with ALL starts and completes another attendant's task offline`() {
        val attendant = execution.attendant()
        val housekeeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val task = task(attendant, type(), room = housekeeping.createRoom())
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(housekeeper),
                command("start-task", mapOf("taskId" to task), now, now),
                command("complete-task", mapOf("taskId" to task), now, now),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        taskRow(task).let {
            it["status"] shouldBe "DONE"
            it["cleaned_by"] shouldBe housekeeper.id.value
        }
    }

    @Test
    fun `the same commandId sent again returns the previous result without applying it again`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = task(attendant, type(), room = housekeeping.createRoom())
        val now = Instant.now()
        val start = command("start-task", mapOf("taskId" to task), now, now)
        val complete = command("complete-task", mapOf("taskId" to task), now, now)
        val first = sync(auth, start, complete)
        val version = taskRow(task)["version"]

        val second = sync(auth, start, complete)

        second.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        second.map { it["status"].asString() } shouldContainExactly first.map { it["status"].asString() }
        second.map { it["occurredAt"].asString() } shouldContainExactly first.map { it["occurredAt"].asString() }
        taskRow(task)["version"] shouldBe version
        completedEvents(task) shouldHaveSize 1
        housekeeping.auditActions("housekeeping.task", task) shouldHaveSize 3
    }

    @Test
    fun `a device clock beyond the tolerance is applied with a warning and never writes before the last transition`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = task(attendant, type(checklistTemplateId = publishedTemplate()), room = housekeeping.createRoom())
        val lastTransition = instant(taskRow(task)["last_transition_at"])
        // Aparelho 2 h atrasado que diz ter iniciado 1 h antes do relógio dele: corrigido, 1 h antes de agora, antes
        // da atribuição da tarefa.
        val deviceClock = Instant.now().minus(HOURS_2)
        val start = command("start-task", mapOf("taskId" to task), deviceClock.minus(HOURS_1), deviceClock)
        val complete =
            command(
                "complete-task",
                mapOf("taskId" to task, "answers" to listOf(check("bed"))),
                deviceClock.minus(MINUTES_30),
                deviceClock,
            )

        val results = sync(auth, start, complete)

        results.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED_WITH_WARNINGS", "APPLIED_WITH_WARNINGS")
        results.flatMap { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
            listOf("CLOCK_SKEW_EXCEEDED", "CLOCK_SKEW_EXCEEDED")
        val row = taskRow(task)
        val startedAt = instant(row["started_at"])
        val finishedAt = instant(row["finished_at"])
        val checklist = checklistsApi.execution(ChecklistExecutionId(row["checklist_execution_id"] as UUID))!!
        assertSoftly {
            startedAt shouldBeGreaterThanOrEqualTo lastTransition
            finishedAt shouldBeGreaterThanOrEqualTo startedAt
            instant(row["last_transition_at"]) shouldBe finishedAt
            checklist.startedAt.shouldNotBeNull() shouldBeGreaterThanOrEqualTo lastTransition
            checklist.completedAt.shouldNotBeNull() shouldBeGreaterThanOrEqualTo startedAt
        }
    }

    @Test
    fun `the answeredAt of each answer is corrected by the device clock skew`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = task(attendant, type(checklistTemplateId = publishedTemplate()), room = housekeeping.createRoom())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        backdate(task, now.minus(HOURS_1))
        // Aparelho 2 min atrasado (dentro da tolerância): tudo o que ele informa é corrigido em +2 min.
        val deviceClock = now.minus(MINUTES_2)
        val deviceEnd = deviceClock.minus(MINUTES_5)
        val deviceAnswer = deviceClock.minus(MINUTES_10)
        val results =
            sync(
                auth,
                command("start-task", mapOf("taskId" to task), deviceClock.minus(MINUTES_30), deviceClock),
                command(
                    "complete-task",
                    mapOf("taskId" to task, "answers" to listOf(check("bed") + ("answeredAt" to deviceAnswer))),
                    deviceEnd,
                    deviceClock,
                ),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        val completedAt = Instant.parse(results[1]["occurredAt"].asString())
        val checklist =
            checklistsApi.execution(ChecklistExecutionId(taskRow(task)["checklist_execution_id"] as UUID))!!
        checklist.answers.single { it.itemKey == "bed" }.answeredAt shouldBe
            deviceAnswer.plus(Duration.between(deviceEnd, completedAt))
    }

    @Test
    fun `a photo not yet uploaded or that does not serve leaves complete-task applied with photo warnings`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val template =
            publishedTemplate(
                extraItems =
                    arrayOf(
                        templates.item("photo", type = ChecklistItemType.PHOTO, extra = mapOf("required" to true)),
                        templates.item("detail", type = ChecklistItemType.PHOTO),
                    ),
            )
        val task = task(attendant, type(checklistTemplateId = template), room = housekeeping.createRoom())
        // A foto de outra pessoa (a Governanta) não serve à execução iniciada pela camareira.
        val foreign = photos.uploadPhoto(iam.asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)))
        val now = Instant.now()
        val answers =
            listOf(
                check("bed"),
                mapOf("itemKey" to "photo", "photoFileId" to UuidV7.generate()),
                mapOf("itemKey" to "detail", "photoFileId" to foreign),
            )

        val results =
            sync(
                auth,
                command("start-task", mapOf("taskId" to task), now, now),
                command("complete-task", mapOf("taskId" to task, "answers" to answers), now, now),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED_WITH_WARNINGS")
        results[1]["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_PHOTO_MISSING", "CHECKLIST_PHOTO_INVALID")
        taskRow(task)["status"] shouldBe "DONE"
        val checklist =
            checklistsApi.execution(ChecklistExecutionId(taskRow(task)["checklist_execution_id"] as UUID))!!
        checklist.status shouldBe ChecklistExecutionStatus.COMPLETED
        checklist.answers.filter { it.photoFileId != null } shouldBe emptyList()
    }

    @Test
    fun `start-task opens the device's older version of the same template with CHECKLIST_VERSION_OUTDATED`() {
        val attendant = execution.attendant()
        val template = publishedTemplate()
        val v1 = versionId(template, 1)
        republish(template)
        val task = task(attendant, type(checklistTemplateId = template), room = housekeeping.createRoom())

        val result =
            sync(iam.asTestUser(attendant.user), startCommand(task, checklistVersionId = v1)).single()

        result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_VERSION_OUTDATED")
        val checklist = checklistsApi.execution(ChecklistExecutionId(taskRow(task)["checklist_execution_id"] as UUID))
        checklist.shouldNotBeNull().versionId?.value shouldBe v1
    }

    @Test
    fun `start-task with the current version of the linked template opens it without warning`() {
        val attendant = execution.attendant()
        val template = publishedTemplate()
        val task = task(attendant, type(checklistTemplateId = template), room = housekeeping.createRoom())

        val result =
            sync(iam.asTestUser(attendant.user), startCommand(task, versionId(template, 1))).single()

        result["status"].asString() shouldBe "APPLIED"
        val checklist = checklistsApi.execution(ChecklistExecutionId(taskRow(task)["checklist_execution_id"] as UUID))
        checklist.shouldNotBeNull().versionId?.value shouldBe versionId(template, 1)
    }

    @Test
    fun `start-task with a version of another housekeeping template opens it, warns and records the swap`() {
        val attendant = execution.attendant()
        val expected = publishedTemplate()
        val received = publishedTemplate()
        val task = task(attendant, type(checklistTemplateId = expected), room = housekeeping.createRoom())

        val result =
            sync(iam.asTestUser(attendant.user), startCommand(task, versionId(received, 1))).single()

        result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH")
        val checklist = checklistsApi.execution(ChecklistExecutionId(taskRow(task)["checklist_execution_id"] as UUID))
        checklist.shouldNotBeNull().versionId?.value shouldBe versionId(received, 1)
        startTrail(task).let {
            it["expected"] shouldBe expected.toString()
            it["received"] shouldBe received.toString()
        }
    }

    @Test
    fun `start-task with a device version on a task without template opens it with the mismatch warning`() {
        val attendant = execution.attendant()
        val received = publishedTemplate()
        val task = task(attendant, type(), room = housekeeping.createRoom())

        val result =
            sync(iam.asTestUser(attendant.user), startCommand(task, versionId(received, 1))).single()

        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH")
        taskRow(task)["checklist_execution_id"].shouldNotBeNull()
        startTrail(task).let {
            it["expected"].shouldBeNull()
            it["received"] shouldBe received.toString()
        }
    }

    @Test
    fun `start-task with an unknown version or one of another context is rejected without opening a checklist`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val maintenance = publishedTemplate(ChecklistContext.MAINTENANCE)
        val task = task(attendant, type(checklistTemplateId = publishedTemplate()), room = housekeeping.createRoom())

        val results =
            sync(
                auth,
                startCommand(task, UuidV7.generate()),
                startCommand(task, versionId(maintenance, 1)),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "REJECTED")
        results.map { it["code"].asString() } shouldContainExactly
            List(2) { "HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID" }
        taskRow(task).let {
            it["status"] shouldBe "ASSIGNED"
            it["checklist_execution_id"].shouldBeNull()
        }
        checklistExecutionsOf(task) shouldBe 0
    }

    @Test
    fun `an invalid transition, another attendant's task and a malformed payload are rejected`() {
        val owner = execution.attendant()
        val other = execution.attendant()
        val task = task(owner, type(), room = housekeeping.createRoom())
        val now = Instant.now()

        val own = sync(iam.asTestUser(owner.user), command("pause-task", mapOf("taskId" to task), now, now)).single()
        val foreign =
            sync(iam.asTestUser(other.user), command("start-task", mapOf("taskId" to task), now, now)).single()
        val unknown =
            sync(
                iam.asTestUser(other.user),
                command("start-task", mapOf("taskId" to UuidV7.generate()), now, now),
            ).single()
        val malformed =
            sync(iam.asTestUser(owner.user), command("start-task", mapOf("taskId" to ""), now, now)).single()

        assertSoftly {
            own["status"].asString() shouldBe "REJECTED"
            own["code"].asString() shouldBe "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED"
            foreign["status"].asString() shouldBe "REJECTED"
            foreign["code"].asString() shouldBe "RESOURCE_OUT_OF_SCOPE"
            unknown["code"].asString() shouldBe "RESOURCE_OUT_OF_SCOPE"
            malformed["status"].asString() shouldBe "REJECTED"
            malformed["code"].asString() shouldBe "COMMAND_PAYLOAD_INVALID"
            taskRow(task)["status"] shouldBe "ASSIGNED"
        }
    }

    // --- apoio -------------------------------------------------------------------------------------------------

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        occurredAt: Instant,
        deviceClock: Instant,
    ) = SyncCommand(UuidV7.generate(), "housekeeping.$verb", payload, occurredAt, deviceClock)

    private fun startCommand(
        task: UUID,
        checklistVersionId: UUID,
    ): SyncCommand {
        val now = Instant.now()
        return command("start-task", mapOf("taskId" to task, "checklistVersionId" to checklistVersionId), now, now)
    }

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

    private fun type(checklistTemplateId: UUID? = null): UUID =
        housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = checklistTemplateId))

    /** Um modelo publicado com `bed` obrigatório e `towels` opcional, que só entra pelo vínculo do tipo. */
    private fun publishedTemplate(
        context: ChecklistContext = ChecklistContext.HOUSEKEEPING,
        extraItems: Array<Map<String, Any?>> = emptyArray(),
    ): UUID {
        val id = templates.create(templateBody(context, *extraItems))
        templates.publish(id)
        return id
    }

    private fun templateBody(
        context: ChecklistContext,
        vararg extraItems: Map<String, Any?>,
    ) = templates.templateBody(
        context = context,
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
    )

    /** Publica a versão 2 do modelo (o rascunho com outro nome). */
    private fun republish(template: UUID) {
        val body = templateBody(ChecklistContext.HOUSEKEEPING) - "context"
        iam.call(PUT, "/api/v1/checklist-templates/$template", iam.asAdmin(), body, templates.etagOf(template)).let {
            withClue(it.getContentAsString(Charsets.UTF_8)) { it.status shouldBe 200 }
        }
        templates.publish(template) shouldBe 2
    }

    private fun versionId(
        template: UUID,
        number: Int,
    ): UUID =
        jdbc
            .sql("SELECT id FROM checklists.checklist_version WHERE template_id = :id AND number = :number")
            .param("id", template)
            .param("number", number)
            .query(UUID::class.java)
            .single()

    private fun task(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        type: UUID,
        room: UUID,
    ): UUID =
        UUID
            .fromString(housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))["id"].asString())
            .also { execution.assign(it, attendant.employee) }

    /** A tarefa criada e atribuída em [at] (escrita direta: as ações só usam o relógio do servidor). */
    private fun backdate(
        task: UUID,
        at: Instant,
    ) {
        jdbc
            .sql(
                "UPDATE housekeeping.housekeeping_task SET created_at = :t, updated_at = :t, last_transition_at = :t " +
                    "WHERE id = :id",
            ).param("t", at.atOffset(ZoneOffset.UTC))
            .param("id", task)
            .update() shouldBe 1
    }

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun instant(value: Any?): Instant = (value as Timestamp).toInstant()

    private fun checklistExecutionsOf(task: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM checklists.checklist_execution WHERE subject_id = :id")
            .param("id", task)
            .query(Int::class.java)
            .single()

    /** O modelo esperado e o recebido no retrato "depois" da trilha do início offline. */
    private fun startTrail(task: UUID): Map<String, Any?> =
        jdbc
            .sql(
                """
                SELECT after_state ->> 'expectedChecklistTemplateId' AS expected,
                       after_state ->> 'receivedChecklistTemplateId' AS received
                FROM audit.audit_entry
                WHERE entity_type = 'housekeeping.task' AND entity_id = :id
                  AND action = 'housekeeping.task.start-offline'
                """.trimIndent(),
            ).param("id", task.toString())
            .query()
            .singleRow()

    private fun completedEvents(task: UUID): List<HousekeepingTaskCompleted> =
        events.stream(HousekeepingTaskCompleted::class.java).filter { it.taskId == task }.toList()

    private companion object {
        const val SLACK = 60L
        val HOURS_1: Duration = Duration.ofHours(1)
        val HOURS_2: Duration = Duration.ofHours(2)
        val MINUTES_2: Duration = Duration.ofMinutes(2)
        val MINUTES_5: Duration = Duration.ofMinutes(5)
        val MINUTES_10: Duration = Duration.ofMinutes(10)
        val MINUTES_30: Duration = Duration.ofMinutes(30)
    }
}
