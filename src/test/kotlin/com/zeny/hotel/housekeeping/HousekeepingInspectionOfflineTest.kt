package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A inspeção offline (M6 E05, Task 3, D7): os comandos `housekeeping.start-inspection`,
 * `housekeeping.complete-inspection` e `housekeeping.inspect-room` pela sincronização, tratados pelo mesmo caso de uso
 * das ações online, com o padrão da E19: o `commandId` do início é o id da inspeção, `occurredAt` corrigido, a versão
 * do checklist do aparelho com os alertas da E19, foto tolerada com alerta, uma entrada de trilha por comando e
 * `REJECTED` pelo código da recusa (inclusive a tarefa já inspecionada online, Review Focus 5).
 *
 * Os modelos **gerais** de governança e de inspeção são neutralizados antes de cada teste; os modelos de inspeção
 * daqui valem só para o tipo de limpeza do próprio teste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingInspectionOfflineTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution
    private val housekeeping = support.housekeeping
    private val templates = ChecklistsTestSupport(iam)

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `a batch with start-inspection and complete-inspection approves like the online actions`() {
        val type = support.cleaningType()
        support.inspectionTemplate(type)
        val (task, room) = support.awaitingInspection(type)
        val inspector = support.inspector()
        val now = Instant.now()
        val start = command("start-inspection", mapOf("taskId" to task), now, now)
        val complete =
            command(
                "complete-inspection",
                mapOf("taskId" to task, "result" to "APPROVED", "answers" to listOf(support.check("bed"))),
                now,
                now,
            )

        val results = sync(iam.asTestUser(inspector), start, complete)

        withClue(results.toString()) {
            results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        }
        val row = support.inspectionRow(task)
        val checklist = ChecklistExecutionId(row["checklist_execution_id"] as UUID)
        assertSoftly {
            row["id"] shouldBe start.id
            row["status"] shouldBe "COMPLETED"
            row["result"] shouldBe "APPROVED"
            row["inspector_id"] shouldBe inspector.id.value
            instant(row["started_at"]) shouldBe Instant.parse(results[0]["occurredAt"].asString())
            instant(row["completed_at"]) shouldBe Instant.parse(results[1]["occurredAt"].asString())
            taskRow(task)["status"] shouldBe "DONE"
            roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.COMPLETED
            housekeeping.auditActions("housekeeping.inspection", start.id) shouldContainExactly
                listOf("housekeeping.inspection.start-offline", "housekeeping.inspection.complete-offline")
            housekeeping.auditActions("housekeeping.task", task).last() shouldBe
                "housekeeping.inspection.complete-offline"
        }
    }

    @Test
    fun `start-inspection records the task in the trail once, with the offline action`() {
        val (task, _) = support.awaitingInspection(support.cleaningType())
        val now = Instant.now()

        val result =
            sync(
                iam.asTestUser(support.inspector()),
                command("start-inspection", mapOf("taskId" to task), now, now),
            ).single()

        result["status"].asString() shouldBe "APPLIED"
        housekeeping.auditActions("housekeeping.task", task).takeLast(2) shouldContainExactly
            listOf("housekeeping.task.complete", "housekeeping.inspection.start-offline")
    }

    @Test
    fun `a batch with start-inspection and complete-inspection rejects like the online actions`() {
        val type = support.cleaningType()
        support.inspectionTemplate(type)
        val attendant = execution.attendant()
        val (task, room) = support.awaitingInspection(type, attendant)
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                command("start-inspection", mapOf("taskId" to task), now, now),
                command(
                    "complete-inspection",
                    mapOf(
                        "taskId" to task,
                        "result" to "REJECTED",
                        "answers" to listOf(support.check("bed")),
                        "rejectedItems" to listOf("towels", "floor"),
                    ),
                    now,
                    now,
                ),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        assertSoftly {
            taskRow(task)["status"] shouldBe "REJECTED"
            support.rejectedItemsOf(task) shouldContainExactly listOf("towels", "floor")
            roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            support.notices(attendant.user, "notifications.inspection-rejected").size shouldBe 1
        }
    }

    @Test
    fun `complete-inspection without start on a task without template uses its commandId as the inspection id`() {
        val (task, room) = support.awaitingInspection(support.cleaningType())
        val now = Instant.now()
        val complete = command("complete-inspection", mapOf("taskId" to task, "result" to "APPROVED"), now, now)

        val result = sync(iam.asTestUser(support.inspector()), complete).single()

        result["status"].asString() shouldBe "APPLIED"
        support.inspectionRow(task)["id"] shouldBe complete.id
        roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
    }

    @Test
    fun `the same commandIds sent again return the previous results without a second inspection`() {
        val (task, _) = support.awaitingInspection(support.cleaningType())
        val auth = iam.asTestUser(support.inspector())
        val now = Instant.now()
        val start = command("start-inspection", mapOf("taskId" to task), now, now)
        val complete = command("complete-inspection", mapOf("taskId" to task, "result" to "APPROVED"), now, now)
        val first = sync(auth, start, complete)
        val version = taskRow(task)["version"]

        val second = sync(auth, start, complete)

        second.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        second.map { it["status"].asString() } shouldContainExactly first.map { it["status"].asString() }
        taskRow(task)["version"] shouldBe version
        support.inspectionCount(task) shouldBe 1
        housekeeping.auditActions("housekeeping.inspection", start.id) shouldContainExactly
            listOf("housekeeping.inspection.start-offline", "housekeeping.inspection.complete-offline")
    }

    @Test
    fun `a device clock beyond the tolerance is applied with a warning and never writes before the last transition`() {
        val type = support.cleaningType()
        support.inspectionTemplate(type)
        val (task, _) = support.awaitingInspection(type)
        val lastTransition = instant(taskRow(task)["last_transition_at"])
        // Aparelho 2 h atrasado que diz ter iniciado 1 h antes do relógio dele: corrigido, antes da limpeza acabar.
        val deviceClock = Instant.now().minus(HOURS_2)

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                command("start-inspection", mapOf("taskId" to task), deviceClock.minus(HOURS_1), deviceClock),
                command(
                    "complete-inspection",
                    mapOf("taskId" to task, "result" to "APPROVED", "answers" to listOf(support.check("bed"))),
                    deviceClock.minus(MINUTES_30),
                    deviceClock,
                ),
            )

        results.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED_WITH_WARNINGS", "APPLIED_WITH_WARNINGS")
        results.flatMap { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
            listOf("CLOCK_SKEW_EXCEEDED", "CLOCK_SKEW_EXCEEDED")
        val row = support.inspectionRow(task)
        val startedAt = instant(row["started_at"])
        assertSoftly {
            startedAt shouldBeGreaterThanOrEqualTo lastTransition
            instant(row["completed_at"]) shouldBeGreaterThanOrEqualTo startedAt
            instant(taskRow(task)["finished_at"]) shouldBeGreaterThanOrEqualTo lastTransition
        }
    }

    @Test
    fun `start-inspection opens the device's older version of the same template with CHECKLIST_VERSION_OUTDATED`() {
        val type = support.cleaningType()
        val template = support.inspectionTemplate(type)
        val v1 = versionId(template, 1)
        republish(template, type)
        val (task, _) = support.awaitingInspection(type)

        val result = sync(iam.asTestUser(support.inspector()), startCommand(task, v1)).single()

        result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_VERSION_OUTDATED")
        checklistOf(task).versionId?.value shouldBe v1
    }

    @Test
    fun `start-inspection with the current version opens it without warning`() {
        val type = support.cleaningType()
        val template = support.inspectionTemplate(type)
        val (task, _) = support.awaitingInspection(type)

        val result = sync(iam.asTestUser(support.inspector()), startCommand(task, versionId(template, 1))).single()

        result["status"].asString() shouldBe "APPLIED"
        checklistOf(task).versionId?.value shouldBe versionId(template, 1)
    }

    @Test
    fun `start-inspection with another inspection template opens it, warns and records the swap`() {
        val type = support.cleaningType()
        val expected = support.inspectionTemplate(type)
        val received = support.inspectionTemplate(support.cleaningType())
        val (task, _) = support.awaitingInspection(type)
        val start = startCommand(task, versionId(received, 1))

        val result = sync(iam.asTestUser(support.inspector()), start).single()

        result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH")
        checklistOf(task).versionId?.value shouldBe versionId(received, 1)
        startTrail(start.id).let {
            it["expected"] shouldBe expected.toString()
            it["received"] shouldBe received.toString()
        }
    }

    @Test
    fun `start-inspection with a device version on a task without template opens it with the mismatch warning`() {
        val received = support.inspectionTemplate(support.cleaningType())
        val (task, _) = support.awaitingInspection(support.cleaningType())
        val start = startCommand(task, versionId(received, 1))

        val result = sync(iam.asTestUser(support.inspector()), start).single()

        result["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH")
        checklistOf(task).versionId?.value shouldBe versionId(received, 1)
        startTrail(start.id).let {
            it["expected"].shouldBeNull()
            it["received"] shouldBe received.toString()
        }
    }

    @Test
    fun `start-inspection with an unknown version or one of another context is rejected without an inspection`() {
        val type = support.cleaningType()
        support.inspectionTemplate(type)
        val housekeepingTemplate = templates.create(templates.templateBody())
        templates.publish(housekeepingTemplate)
        val (task, _) = support.awaitingInspection(type)

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                startCommand(task, UuidV7.generate()),
                startCommand(task, versionId(housekeepingTemplate, 1)),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "REJECTED")
        results.map { it["code"].asString() } shouldContainExactly
            List(2) { "HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID" }
        support.inspectionCount(task) shouldBe 0
        taskRow(task)["status"] shouldBe "AWAITING_INSPECTION"
    }

    @Test
    fun `a photo not yet uploaded leaves complete-inspection applied with the photo warning`() {
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
                                    extra = mapOf("required" to true),
                                ),
                            ),
                        ),
                ),
            )
        templates.publish(template)
        val (task, _) = support.awaitingInspection(type)
        val now = Instant.now()
        val answers = listOf(support.check("bed"), mapOf("itemKey" to "photo", "photoFileId" to UuidV7.generate()))

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                command("start-inspection", mapOf("taskId" to task), now, now),
                command(
                    "complete-inspection",
                    mapOf("taskId" to task, "result" to "APPROVED", "answers" to answers),
                    now,
                    now,
                ),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED_WITH_WARNINGS")
        results[1]["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_PHOTO_MISSING")
        taskRow(task)["status"] shouldBe "DONE"
        checklistOf(task).status shouldBe ChecklistExecutionStatus.COMPLETED
    }

    @Test
    fun `without the inspection permission the commands are rejected and nothing changes`() {
        val (task, room) = support.awaitingInspection(support.cleaningType())
        val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(attendant),
                command("start-inspection", mapOf("taskId" to task), now, now),
                command("complete-inspection", mapOf("taskId" to task, "result" to "APPROVED"), now, now),
                command("inspect-room", mapOf("roomId" to room.value), now, now),
            )

        results.map { it["status"].asString() } shouldContainExactly List(3) { "REJECTED" }
        results.map { it["code"].asString() } shouldContainExactly List(3) { "PERMISSION_DENIED" }
        support.inspectionCount(task) shouldBe 0
        taskRow(task)["status"] shouldBe "AWAITING_INSPECTION"
    }

    @Test
    fun `an offline inspection of a task already inspected online is rejected by the transition (Review Focus 5)`() {
        val (task, _) = support.awaitingInspection(support.cleaningType())
        val inspector = iam.asTestUser(support.inspector())
        execution.acted(task, "inspection/complete", inspector, support.complete("APPROVED"))
        val now = Instant.now()

        val results =
            sync(
                inspector,
                command("start-inspection", mapOf("taskId" to task), now, now),
                command("complete-inspection", mapOf("taskId" to task, "result" to "REJECTED"), now, now),
            )

        results.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "REJECTED")
        results.map { it["code"].asString() } shouldContainExactly
            List(2) { "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED" }
        support.inspectionCount(task) shouldBe 1
        taskRow(task)["status"] shouldBe "DONE"
    }

    @Test
    fun `inspect-room applies the voluntary inspection with the commandId as the inspection id`() {
        val room = RoomId(housekeeping.createRoom())
        roomsApi.markClean(room, false, RoomStatusSource.MANUAL)
        val now = Instant.now()
        val inspect = command("inspect-room", mapOf("roomId" to room.value), now, now)

        val result = sync(iam.asTestUser(support.inspector()), inspect).single()

        result["status"].asString() shouldBe "APPLIED"
        roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
        voluntaryInspection(inspect.id).let {
            it["room_id"] shouldBe room.value
            it["task_id"].shouldBeNull()
            it["result"] shouldBe "APPROVED"
        }
        housekeeping.auditActions("housekeeping.inspection", inspect.id) shouldContainExactly
            listOf("housekeeping.inspection.inspect-room-offline")
    }

    @Test
    fun `inspect-room of an unknown room or a dirty one is rejected with the online codes`() {
        val dirty = RoomId(housekeeping.createRoom())
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(support.inspector()),
                command("inspect-room", mapOf("roomId" to UUID.randomUUID()), now, now),
                command("inspect-room", mapOf("roomId" to dirty.value), now, now),
            )

        results.map { it["code"].asString() } shouldContainExactly
            listOf("HOUSEKEEPING_INSPECTION_UNKNOWN_ROOM", "HOUSEKEEPING_ROOM_NOT_CLEAN")
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
        return command(
            "start-inspection",
            mapOf("taskId" to task, "checklistVersionId" to checklistVersionId),
            now,
            now,
        )
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

    /** Publica a versão 2 do modelo de inspeção (o rascunho com outro nome), ainda só para o [type]. */
    private fun republish(
        template: UUID,
        type: UUID,
    ) {
        val body =
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
            ) - "context"
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

    private fun checklistOf(task: UUID) =
        checklistsApi
            .execution(ChecklistExecutionId(support.inspectionRow(task)["checklist_execution_id"] as UUID))
            .shouldNotBeNull()

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun voluntaryInspection(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.inspection WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    private fun instant(value: Any?): Instant = (value as Timestamp).toInstant()

    /** O modelo esperado e o recebido no retrato "depois" da trilha do início offline da inspeção. */
    private fun startTrail(inspection: UUID): Map<String, Any?> =
        jdbc
            .sql(
                """
                SELECT after_state ->> 'expectedChecklistTemplateId' AS expected,
                       after_state ->> 'receivedChecklistTemplateId' AS received
                FROM audit.audit_entry
                WHERE entity_type = 'housekeeping.inspection' AND entity_id = :id
                  AND action = 'housekeeping.inspection.start-offline'
                """.trimIndent(),
            ).param("id", inspection.toString())
            .query()
            .singleRow()

    private companion object {
        val HOURS_1: Duration = Duration.ofHours(1)
        val HOURS_2: Duration = Duration.ofHours(2)
        val MINUTES_30: Duration = Duration.ofMinutes(30)
    }
}
