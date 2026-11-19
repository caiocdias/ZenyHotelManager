package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.OfflineSource
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * As fontes offline da inspeção (M6 E05, Task 3, D7): o inspetor recebe pela `housekeeping-inspections` as tarefas
 * `AWAITING_INSPECTION`, com a `version` que é o `ETag` online, e o *tombstone* quando deixam de aguardar; a camareira
 * não recebe a fonte; o item `REJECTED` da fonte `housekeeping-tasks` da camareira traz os itens reprovados da última
 * inspeção e a versão do checklist de inspeção; nada de `notes` nem dado de hóspede (RN-13).
 *
 * O banco é compartilhado: cada teste afirma só sobre as próprias tarefas. Os modelos **gerais** de governança e de
 * inspeção são neutralizados antes de cada teste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingInspectionFeedTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution
    private val housekeeping = support.housekeeping

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `the inspector receives the tasks awaiting inspection with the online ETag and no notes, then a tombstone`() {
        val type = support.cleaningType()
        val attendant = execution.attendant()
        val notes = "Hóspede pediu travesseiro extra ${UUID.randomUUID()}"
        val task = awaitingInspectionWithNotes(type, attendant, notes)
        val inspector = iam.asTestUser(support.inspector())

        val first = download(inspector)

        val item = inspectionsOf(first.changes).getValue(task)
        val row = taskRow(task)
        assertSoftly {
            item["deleted"].asBoolean() shouldBe false
            "\"${item["version"].asLong()}\"" shouldBe housekeeping.etagOf("${HousekeepingTestSupport.TASKS}/$task")
            item["data"].propertyNames().toList().sorted() shouldContainExactly
                listOf("areaId", "businessDate", "checklistExecutionId", "cleaningTypeId", "roomId")
            item["data"]["roomId"].asString() shouldBe row["room_id"].toString()
            item["data"]["cleaningTypeId"].asString() shouldBe type.toString()
            item["data"]["businessDate"].asString() shouldBe row["business_date"].toString()
            item["data"]["checklistExecutionId"].takeUnless { it.isNull }?.asString() shouldBe
                row["checklist_execution_id"]?.toString()
            first.bodies.joinToString("\n") shouldNotContain notes
        }

        val (startEtag, _) = execution.acted(task, "inspection/start", inspector)
        val started = download(inspector, first.cursor)
        withClue("o início sobe a versão: o item vem de novo") {
            val again = inspectionsOf(started.changes).getValue(task)
            again["deleted"].asBoolean() shouldBe false
            "\"${again["version"].asLong()}\"" shouldBe startEtag
        }

        execution.acted(task, "inspection/complete", inspector, support.complete("APPROVED"))

        withClue("a tarefa que deixa de aguardar vira tombstone") {
            inspectionsOf(download(inspector, started.cursor).changes).getValue(task)["deleted"].asBoolean() shouldBe
                true
        }
    }

    @Test
    fun `the attendant does not receive the inspections source`() {
        val attendant = execution.attendant()
        support.awaitingInspection(support.cleaningType(), attendant)

        val feed = download(iam.asTestUser(attendant.user))

        feed.changes.filter { it["source"].asString() == HousekeepingOffline.INSPECTIONS.name }.shouldBeEmpty()
    }

    @Test
    fun `the attendant's rejected task carries the rejected items and the inspection checklist version`() {
        val type = support.cleaningType()
        val template = support.inspectionTemplate(type)
        val attendant = execution.attendant()
        val (task, _) = support.awaitingInspection(type, attendant)
        val inspector = iam.asTestUser(support.inspector())
        execution.acted(task, "inspection/start", inspector)
        execution.acted(
            task,
            "inspection/complete",
            inspector,
            support.complete("REJECTED", listOf("towels", "floor"), listOf(support.check("bed"))),
        )

        val item = tasksOf(download(iam.asTestUser(attendant.user)).changes).getValue(task)

        assertSoftly {
            item["deleted"].asBoolean() shouldBe false
            item["data"]["status"].asString() shouldBe "REJECTED"
            item["data"]["rejectedItems"].values().map { it.asString() } shouldContainExactly listOf("towels", "floor")
            item["data"]["inspectionChecklistVersionId"].asString() shouldBe versionId(template).toString()
        }
    }

    @Test
    fun `a task never rejected carries no rejected items nor inspection checklist version`() {
        val attendant = execution.attendant()
        val task = execution.assignedTask(attendant.employee)

        val item = tasksOf(download(iam.asTestUser(attendant.user)).changes).getValue(task)

        item["data"]["rejectedItems"].values().toList().shouldBeEmpty()
        item["data"]["inspectionChecklistVersionId"].isNull shouldBe true
    }

    // --- Apoio -------------------------------------------------------------------------------------------------------

    private data class Download(
        val changes: List<JsonNode>,
        val cursor: String,
        val bodies: List<String>,
    )

    /** O download desde [from] (sem cursor: o completo), página a página, com os corpos crus. */
    private fun download(
        auth: RequestPostProcessor,
        from: String? = null,
    ): Download =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            val bodies = mutableListOf<String>()
            var cursor = from
            var pages = 0
            do {
                // O download só serve transações terminadas: espere os listeners do inventário (M4 E05).
                InventoryListenersQuiescence.await(jdbc)
                val response =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth)
                val page = response.ok()
                bodies += response.getContentAsString(Charsets.UTF_8)
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            Download(changes, checkNotNull(cursor), bodies)
        }

    private fun inspectionsOf(changes: List<JsonNode>): Map<UUID, JsonNode> =
        of(changes, HousekeepingOffline.INSPECTIONS)

    private fun tasksOf(changes: List<JsonNode>): Map<UUID, JsonNode> = of(changes, HousekeepingOffline.TASKS)

    /** As mudanças da [source], a última de cada id. */
    private fun of(
        changes: List<JsonNode>,
        source: OfflineSource,
    ): Map<UUID, JsonNode> =
        changes
            .filter { it["source"].asString() == source.name }
            .associateBy { UUID.fromString(it["id"].asString()) }

    /** Uma tarefa do [type] com as [notes] numa UH nova, limpa pela [attendant]: `AWAITING_INSPECTION`. */
    private fun awaitingInspectionWithNotes(
        type: UUID,
        attendant: HousekeepingExecutionTestSupport.Attendant,
        notes: String,
    ): UUID {
        val body = housekeeping.taskBody(type, roomId = housekeeping.createRoom(), notes = notes)
        val task = UUID.fromString(housekeeping.requestTask(body)["id"].asString())
        execution.assign(task, attendant.employee)
        val auth = iam.asTestUser(attendant.user)
        execution.acted(task, "start", auth)
        iam.json(execution.acted(task, "complete", auth).second)["status"].asString() shouldBe "AWAITING_INSPECTION"
        return task
    }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun versionId(template: UUID): UUID =
        jdbc
            .sql("SELECT id FROM checklists.checklist_version WHERE template_id = :id AND number = 1")
            .param("id", template)
            .query(UUID::class.java)
            .single()

    private companion object {
        const val MAX_PAGES = 500
    }
}
