package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * O conflito offline da RN-22 (M6 E19, Task 3, D4 e D5): o comando da **ex-dona** (camareira com `OWN` que já teve a
 * tarefa, pela posse em `housekeeping.task_assignment`) numa tarefa reatribuída, retirada ou cancelada é aplicado com o
 * alerta `HOUSEKEEPING_TASK_CONFLICT`, registrado em `housekeeping.task_conflict` sem texto livre e sem vincular as
 * fotos, marca `hasConflict` na tarefa e avisa uma vez por par (tarefa, ex-dona) quem distribui as tarefas, sem mudar
 * status, tempos, checklist nem UH (Review Focus 1). A dona de novo (A → B → A) aplica normalmente (Review Focus 2);
 * quem nunca foi dona recebe `RESOURCE_OUT_OF_SCOPE`, sem conflito (Review Focus 3).
 *
 * O banco é compartilhado: cada teste afirma só sobre as próprias tarefas, UHs e camareiras. As tarefas são atribuídas
 * por escrita direta, com a posse gravada como a publicação do plano grava.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class HousekeepingTaskConflictTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired files: FilesTestSupport,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)
    private val photos = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    @Test
    @Suppress("LongMethod") // o resultado, a tarefa intacta, o registro sem texto livre, o aviso e o reenvio
    fun `the former owner's commands on a task completed by the new owner are a recorded, notified conflict`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val housekeeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val room = housekeeping.createRoom()
        val task = task(type(checklistTemplateId = publishedTemplate()), room)
        execution.assign(task, ana.employee)
        execution.assign(task, bia.employee)
        val now = Instant.now()
        // A nova dona concluiu a tarefa (Review Focus 1): DONE, a UH Limpa e o checklist dela concluído.
        sync(
            iam.asTestUser(bia.user),
            command("start-task", mapOf("taskId" to task), now),
            command("complete-task", mapOf("taskId" to task, "answers" to listOf(check("bed"))), now),
        ).map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        val before = taskRow(task)
        val checklistBefore = checklist(before)
        val photo = photos.uploadPhoto(iam.asTestUser(ana.user))
        val frames = Frames()
        val connection = subscribe(frames)
        val start = command("start-task", mapOf("taskId" to task), now)
        val complete =
            command(
                "complete-task",
                mapOf(
                    "taskId" to task,
                    "answers" to
                        listOf(
                            check("bed"),
                            mapOf("itemKey" to "remarks", "text" to FREE_TEXT, "note" to FREE_NOTE),
                            mapOf("itemKey" to "photo", "photoFileId" to photo),
                        ),
                    "foundOccupancy" to "OCCUPIED",
                    "foundGuestCount" to 2,
                ),
                now,
            )

        val results =
            try {
                sync(iam.asTestUser(ana.user), start, complete).also {
                    await().atMost(Duration.ofSeconds(5)).until { taskMessages(frames, task).isNotEmpty() }
                }
            } finally {
                hub.release(connection)
            }

        val after = taskRow(task)
        assertSoftly {
            results.map { it["status"].asString() } shouldContainExactly List(2) { "APPLIED_WITH_WARNINGS" }
            results.map { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
                List(2) { listOf("HOUSEKEEPING_TASK_CONFLICT") }
            withClue("status, tempos, checklist e camareira não mudam (Review Focus 1)") {
                UNCHANGED_COLUMNS.forEach { column -> withClue(column) { after[column] shouldBe before[column] } }
            }
            after["has_conflict"] shouldBe true
            after["version"] shouldBe (before["version"] as Long) + 1
            taskMessages(frames, task) shouldContainExactly listOf(after["version"] as Long)
            roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            checklist(after) shouldBe checklistBefore
            withClue("a foto não é vinculada") { photoOwner(photo).shouldBeNull() }
        }
        val conflicts = conflicts(task)
        assertSoftly {
            conflicts.map { it["command_id"] } shouldContainExactly listOf(start.id, complete.id)
            conflicts.map { it["command_type"] } shouldContainExactly
                listOf("housekeeping.start-task", "housekeeping.complete-task")
            conflicts.forEach {
                it["user_id"] shouldBe ana.user.id.value
                it["employee_id"] shouldBe ana.employee
                it["task_status"] shouldBe "DONE"
            }
            val text = conflictsText(task)
            withClue("sem texto livre nem a foto") {
                text shouldNotContain FREE_TEXT
                text shouldNotContain FREE_NOTE
                text shouldNotContain photo.toString()
            }
            val summary = jsonMapper.readTree(conflicts[1]["summary"].toString())
            summary["foundOccupancy"].asString() shouldBe "OCCUPIED"
            summary["foundGuestCount"].asInt() shouldBe 2
            summary["photoCount"].asInt() shouldBe 1
            val answers = summary["answers"].values().associateBy { it["itemKey"].asString() }
            answers.getValue("bed")["check"].asString() shouldBe "OK"
            answers.getValue("remarks")["hadText"].asBoolean() shouldBe true
            answers.getValue("remarks")["hadNote"].asBoolean() shouldBe true
            answers.getValue("photo")["hasPhoto"].asBoolean() shouldBe true
        }
        val notices = conflictNotices(housekeeper, roomNumber(room))
        withClue("um aviso só pelo par (tarefa, ex-dona), mesmo com dois comandos") { notices shouldHaveSize 1 }
        notices.single().toString() shouldNotContain FREE_TEXT

        val replayed = sync(iam.asTestUser(ana.user), start, complete)

        replayed.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        conflicts(task) shouldHaveSize 2
        taskRow(task)["version"] shouldBe after["version"]
    }

    @Test
    fun `a removed task and a cancelled one that kept the attendant are conflicts for the former owner`() {
        val ana = execution.attendant()
        val housekeeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val removedRoom = housekeeping.createRoom()
        val removed = task(type(), removedRoom)
        execution.assign(removed, ana.employee)
        execution.unassign(removed)
        val cancelledRoom = housekeeping.createRoom()
        val cancelled = task(type(), cancelledRoom)
        execution.assign(cancelled, ana.employee)
        // Ainda não há caminho de cancelamento (fica para outra etapa): o status vai direto ao banco, mantendo a
        // camareira, como o cancelamento vai deixar a tarefa.
        setStatus(cancelled, "CANCELLED")
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(ana.user),
                command("pause-task", mapOf("taskId" to removed), now),
                command("refuse-task", mapOf("taskId" to cancelled, "reason" to "GUEST_REFUSED"), now),
            )

        assertSoftly {
            results.map { it["status"].asString() } shouldContainExactly List(2) { "APPLIED_WITH_WARNINGS" }
            results.map { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
                List(2) { listOf("HOUSEKEEPING_TASK_CONFLICT") }
            taskRow(removed).let {
                it["status"] shouldBe "PENDING"
                it["has_conflict"] shouldBe true
            }
            taskRow(cancelled).let {
                it["status"] shouldBe "CANCELLED"
                it["refusal_reason"].shouldBeNull()
                it["has_conflict"] shouldBe true
            }
            conflicts(removed).single()["task_status"] shouldBe "PENDING"
            conflicts(cancelled).single().let {
                it["task_status"] shouldBe "CANCELLED"
                jsonMapper.readTree(it["summary"].toString())["reason"].asString() shouldBe "GUEST_REFUSED"
            }
            conflictNotices(housekeeper, roomNumber(removedRoom)) shouldHaveSize 1
            conflictNotices(housekeeper, roomNumber(cancelledRoom)) shouldHaveSize 1
        }
    }

    @Test
    fun `the new owner's feed brings the task with hasConflict and the GET shows it`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val task = task(type(), housekeeping.createRoom())
        execution.assign(task, ana.employee)
        execution.assign(task, bia.employee)

        sync(iam.asTestUser(ana.user), command("start-task", mapOf("taskId" to task), Instant.now()))
            .single()["status"]
            .asString() shouldBe "APPLIED_WITH_WARNINGS"

        val item = tasksOf(download(iam.asTestUser(bia.user))).getValue(task)
        assertSoftly {
            item["deleted"].asBoolean() shouldBe false
            item["data"]["status"].asString() shouldBe "ASSIGNED"
            item["data"]["hasConflict"].asBoolean() shouldBe true
            with(iam) { call(GET, "${HousekeepingTestSupport.TASKS}/$task", asAdmin()).ok() }["hasConflict"]
                .asBoolean() shouldBe true
        }
    }

    @Test
    fun `an attendant who got the task back applies normally, without conflict (Review Focus 2)`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val task = task(type(), housekeeping.createRoom())
        execution.assign(task, ana.employee)
        execution.assign(task, bia.employee)
        execution.assign(task, ana.employee)

        val result = sync(iam.asTestUser(ana.user), command("start-task", mapOf("taskId" to task), Instant.now()))

        result.single()["status"].asString() shouldBe "APPLIED"
        taskRow(task).let {
            it["status"] shouldBe "IN_PROGRESS"
            it["has_conflict"] shouldBe false
        }
        conflicts(task) shouldHaveSize 0
    }

    @Test
    fun `whoever never owned the task is rejected out of scope, without conflict (Review Focus 3)`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val carla = execution.attendant()
        val reassigned = task(type(), housekeeping.createRoom())
        execution.assign(reassigned, ana.employee)
        execution.assign(reassigned, bia.employee)
        val cancelled = task(type(), housekeeping.createRoom())
        execution.assign(cancelled, ana.employee)
        setStatus(cancelled, "CANCELLED")
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(carla.user),
                command("start-task", mapOf("taskId" to reassigned), now),
                command("start-task", mapOf("taskId" to cancelled), now),
            )

        assertSoftly {
            results.map { it["status"].asString() } shouldContainExactly List(2) { "REJECTED" }
            results.map { it["code"].asString() } shouldContainExactly List(2) { "RESOURCE_OUT_OF_SCOPE" }
            listOf(reassigned, cancelled).forEach {
                conflicts(it) shouldHaveSize 0
                taskRow(it)["has_conflict"] shouldBe false
            }
        }
    }

    @Test
    fun `each conflict has its own trail entry, and no trail entry carries the former owner's free text`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val task = task(type(checklistTemplateId = publishedTemplate()), housekeeping.createRoom())
        execution.assign(task, ana.employee)
        execution.assign(task, bia.employee)
        val now = Instant.now()
        sync(iam.asTestUser(bia.user), command("start-task", mapOf("taskId" to task), now))
        val secret = "$FREE_TEXT ${UUID.randomUUID()}"
        val pause = command("pause-task", mapOf("taskId" to task), now)
        val complete =
            command(
                "complete-task",
                mapOf("taskId" to task, "answers" to listOf(mapOf("itemKey" to "remarks", "text" to secret))),
                now,
            )

        sync(iam.asTestUser(ana.user), pause, complete).map { it["status"].asString() } shouldContainExactly
            List(2) { "APPLIED_WITH_WARNINGS" }

        val entries = conflictTrail(task)
        assertSoftly {
            withClue("uma entrada por conflito, também depois da primeira marca") { entries shouldHaveSize 2 }
            entries.map { it["entity_id"] } shouldContainExactly conflicts(task).map { it["id"].toString() }
            entries.map { jsonMapper.readTree(it["after"] as String)["commandType"].asString() } shouldContainExactly
                listOf("housekeeping.pause-task", "housekeeping.complete-task")
            entries.forEach {
                val after = jsonMapper.readTree(it["after"] as String)
                after["employeeId"].asString() shouldBe ana.employee.toString()
                after["taskStatus"].asString() shouldBe "IN_PROGRESS"
            }
            withClue("nenhuma entrada da trilha leva o texto da ex-dona") { trailEntriesContaining(secret) shouldBe 0 }
        }
    }

    @Test
    fun `numbers and quantities outside the checklist limits leave the summary, which keeps only that there was one`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val task = task(type(checklistTemplateId = publishedTemplate()), housekeeping.createRoom())
        execution.assign(task, ana.employee)
        execution.assign(task, bia.employee)
        val answers =
            listOf(
                mapOf("itemKey" to "cpf", "number" to BigDecimal("12345678901")),
                mapOf("itemKey" to "exponent", "number" to BigDecimal("1e300")),
                mapOf("itemKey" to "tiny", "number" to BigDecimal("1e-300")),
                mapOf("itemKey" to "fine", "number" to BigDecimal("21.5")),
                mapOf("itemKey" to "fraction", "number" to BigDecimal("1.23456")),
                mapOf("itemKey" to "stock", "quantity" to BigDecimal("1000000")),
                mapOf("itemKey" to "negative", "quantity" to BigDecimal("-1")),
                mapOf("itemKey" to "towels", "quantity" to BigDecimal("3")),
            )
        val huge = listOf(mapOf("itemKey" to "huge", "number" to BigDecimal("1e999999999")))

        val results =
            sync(
                iam.asTestUser(ana.user),
                command("complete-task", mapOf("taskId" to task, "answers" to answers), Instant.now()),
                command("complete-task", mapOf("taskId" to task, "answers" to huge), Instant.now()),
            )

        withClue(results.toString()) {
            results[0]["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
            // O expoente além do double nem passa pela leitura do payload: recusado pelo protocolo, sem falhar.
            results[1]["status"].asString() shouldBe "REJECTED"
            results[1]["code"].asString() shouldBe "COMMAND_PAYLOAD_INVALID"
        }
        val conflict = conflicts(task).single()
        conflict["command_id"] shouldBe results[0]["commandId"].asString().let(UUID::fromString)
        val byKey =
            jsonMapper
                .readTree(conflict["summary"].toString())["answers"]
                .values()
                .associateBy { it["itemKey"].asString() }
        assertSoftly {
            listOf("cpf", "exponent", "tiny", "fraction").forEach {
                withClue(it) {
                    byKey.getValue(it)["number"].isNull shouldBe true
                    byKey.getValue(it)["hadNumber"].asBoolean() shouldBe true
                }
            }
            listOf("stock", "negative").forEach {
                withClue(it) {
                    byKey.getValue(it)["quantity"].isNull shouldBe true
                    byKey.getValue(it)["hadQuantity"].asBoolean() shouldBe true
                }
            }
            byKey.getValue("fine")["number"].decimalValue().compareTo(BigDecimal("21.5")) shouldBe 0
            byKey.getValue("towels")["quantity"].decimalValue().compareTo(BigDecimal("3")) shouldBe 0
            conflictsText(task) shouldNotContain "12345678901"
        }
    }

    @Test
    fun `the conflict notification type is in the catalog loaded at startup`() {
        val types =
            with(iam) { call(GET, "/api/v1/notification-preferences", asAdmin()).ok() }["preferences"]
                .values()
                .map { it["type"].asString() }

        types.filter { it.startsWith("housekeeping.") } shouldContainExactlyInAnyOrder
            listOf("housekeeping.task-conflict")
    }

    // --- apoio -------------------------------------------------------------------------------------------------

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        at: Instant,
    ) = SyncCommand(UuidV7.generate(), "housekeeping.$verb", payload, at, at)

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

    private fun task(
        type: UUID,
        room: UUID,
    ): UUID = UUID.fromString(housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))["id"].asString())

    /** Um modelo publicado com `bed` obrigatório, `remarks` (texto) e `photo`, que só entra pelo vínculo do tipo. */
    private fun publishedTemplate(): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(UuidV7.generate()))),
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("bed", extra = mapOf("required" to true)),
                                templates.item("remarks", type = ChecklistItemType.TEXT),
                                templates.item("photo", type = ChecklistItemType.PHOTO),
                            ),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun setStatus(
        task: UUID,
        status: String,
    ) {
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET status = :status, version = version + 1 WHERE id = :id")
            .param("status", status)
            .param("id", task)
            .update() shouldBe 1
    }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun checklist(row: Map<String, Any?>) =
        checklistsApi.execution(ChecklistExecutionId(row["checklist_execution_id"] as UUID)).shouldNotBeNull()

    private fun photoOwner(photo: UUID): Any? =
        jdbc
            .sql("SELECT owner_entity_id FROM files.stored_file WHERE id = :id")
            .param("id", photo)
            .query()
            .singleRow()["owner_entity_id"]

    private fun conflicts(task: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM housekeeping.task_conflict WHERE task_id = :task ORDER BY recorded_at, id")
            .param("task", task)
            .query()
            .listOfRows()

    /** Todas as colunas dos conflitos da tarefa como texto, para conferir que nenhum texto livre ficou nelas. */
    private fun conflictsText(task: UUID): String =
        jdbc
            .sql("SELECT string_agg(row_to_json(c)::text, ' ') FROM housekeeping.task_conflict c WHERE task_id = :t")
            .param("t", task)
            .query(String::class.java)
            .single()

    /** As entradas da trilha dos conflitos da [task], na ordem: o id do conflito e o retrato "depois". */
    private fun conflictTrail(task: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                """
                SELECT entity_id, after_state::text AS after FROM audit.audit_entry
                WHERE entity_type = 'housekeeping.task-conflict' AND after_state ->> 'taskId' = :task
                ORDER BY id
                """.trimIndent(),
            ).param("task", task.toString())
            .query()
            .listOfRows()

    private fun trailEntriesContaining(text: String): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry " +
                    "WHERE strpos(coalesce(before_state::text, '') || coalesce(after_state::text, ''), :text) > 0",
            ).param("text", text)
            .query(Int::class.java)
            .single()

    private fun roomNumber(room: UUID): String = roomsApi.room(RoomId(room)).shouldNotBeNull().number

    /** Os avisos de conflito no painel de [user] que citam a UH [number]. */
    private fun conflictNotices(
        user: TestUser,
        number: String,
    ): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/notifications?limit=100", asTestUser(user)).ok() }["items"]
            .values()
            .filter { it["type"].asString() == "housekeeping.task-conflict" }
            .filter { "UH $number" in it["title"].asString() + " " + it["body"].asString() }
            .onEach { it["body"].asString() shouldContain "UH $number" }

    private fun subscribe(frames: Frames): UUID =
        hub.connect(
            StreamSubscription(
                Actor(UserId.new(), DeviceSessionId.new()),
                mapOf(HousekeepingRealtime.TOPIC to PermissionScope.ALL),
                Instant.now().plus(1, ChronoUnit.HOURS),
            ),
            null,
            frames,
        )

    /** As versões das mensagens `housekeeping.task` da [task] recebidas no tópico. */
    private fun taskMessages(
        frames: Frames,
        task: UUID,
    ): List<Long> =
        frames.events
            .map { jsonMapper.readTree(it.data) }
            .filter { it["type"]?.asString() == HousekeepingRealtime.TASK && it["id"]?.asString() == "$task" }
            .map { it["version"].asLong() }

    private fun download(auth: RequestPostProcessor): List<JsonNode> =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            var cursor: String? = null
            var pages = 0
            do {
                // O download só serve transações terminadas: espere os listeners do inventário (M4 E05).
                InventoryListenersQuiescence.await(jdbc)
                val page =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            changes
        }

    private fun tasksOf(changes: List<JsonNode>): Map<UUID, JsonNode> =
        changes
            .filter { it["source"].asString() == HousekeepingOffline.TASKS.name }
            .associateBy { UUID.fromString(it["id"].asString()) }

    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit
    }

    private companion object {
        const val MAX_PAGES = 200
        const val FREE_TEXT = "Hospede Joao Silva deixou o RG 12.345.678 no criado-mudo"
        const val FREE_NOTE = "Ligar para (11) 98888-7777"

        /** O que o conflito nunca muda na tarefa (Review Focus 1). */
        val UNCHANGED_COLUMNS =
            listOf(
                "status",
                "assigned_employee_id",
                "assignment_position",
                "started_at",
                "finished_at",
                "worked_seconds",
                "in_progress_since",
                "last_transition_at",
                "updated_at",
                "cleaned_by",
                "refusal_reason",
                "checklist_execution_id",
            )
    }
}
