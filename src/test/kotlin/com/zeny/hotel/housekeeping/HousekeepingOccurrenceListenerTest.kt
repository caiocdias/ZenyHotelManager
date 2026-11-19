package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * As ocorrências do checklist (M6 E06, Task 3, D5): o ouvinte de `ChecklistCompleted` cria o achado e o dano das ações
 * `OPEN_OCCURRENCE` (`LOST_AND_FOUND` e `DAMAGE`) de uma execução cujo sujeito é uma tarefa ou inspeção da governança,
 * ou uma UH ou área de rotina, com origem `CHECKLIST`, o instante do fato `completedAt`, o executor como quem
 * registrou e a descrição do rótulo do item, da categoria e da observação (até 500). A reentrega não duplica; a
 * execução à parte de uma rotina (`duplicateOf`) cria o próprio registro (Review Focus 3); sujeitos de outro módulo, UH
 * inexistente e rotina sem local são ignorados. O evento é publicado pelo teste e o ouvinte, assíncrono, é esperado por
 * Awaitility.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, banco, checklist, eventos e transação
class HousekeepingOccurrenceListenerTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired staffApi: StaffApi,
) {
    private val inspection = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = inspection.execution
    private val housekeeping = inspection.housekeeping
    private val rooms = RoomsTestSupport(iam)
    private val templates = ChecklistsTestSupport(iam)

    @Test
    fun `a task checklist with a found item and a damage creates both, with the label, category and note`() {
        val (templateId, versionId) = template()
        val task = execution.pendingTask()
        val room = taskRoom(task)
        val executor = UUID.randomUUID()
        val event =
            event(
                versionId,
                templateId,
                "housekeeping.task",
                task,
                executor,
                actions = listOf(found("wallet", "Objeto", "Na gaveta"), damage("lamp", "Elétrico", "Cúpula rasgada")),
            )

        publish(event)
        await().atMost(TIMEOUT).until {
            lostFound(event.executionId).isNotEmpty() && damages(event.executionId).isNotEmpty()
        }

        val item = lostFound(event.executionId).single()
        val damage = damages(event.executionId).single()
        assertSoftly {
            item["room_id"] shouldBe room
            item["area_id"] shouldBe null
            item["description"] shouldBe "Carteira esquecida - Objeto - Na gaveta"
            item["source"] shouldBe "CHECKLIST"
            item["status"] shouldBe "STORED"
            item["checklist_item_key"] shouldBe "wallet"
            item["reported_by"] shouldBe executor
            instant(item["found_at"]) shouldBe event.completedAt
            damage["room_id"] shouldBe room
            damage["description"] shouldBe "Abajur - Elétrico - Cúpula rasgada"
            damage["source"] shouldBe "CHECKLIST"
            damage["status"] shouldBe "REPORTED"
            damage["checklist_item_key"] shouldBe "lamp"
            damage["reported_by"] shouldBe executor
            damage["estimated_cost"] shouldBe null
            instant(damage["occurred_at"]) shouldBe event.completedAt
        }

        // O outbox reentrega: nada é duplicado.
        publish(event)
        await().atMost(TIMEOUT).until { completedDeliveries(event.executionId) == 2 }
        lostFound(event.executionId) shouldHaveSize 1
        damages(event.executionId) shouldHaveSize 1
    }

    @Test
    fun `an inspection checklist records the damage in the room of the inspected task`() {
        val (templateId, versionId) = template()
        val type = inspection.cleaningType()
        inspection.inspectionTemplate(type)
        val (task, room) = inspection.awaitingInspection(type)
        execution.acted(task, "inspection/start", iam.asTestUser(inspection.inspector()))
        val inspectionId = inspection.inspectionRow(task)["id"] as UUID
        val event =
            event(
                versionId,
                templateId,
                "housekeeping.inspection",
                inspectionId,
                UUID.randomUUID(),
                actions = listOf(damage("lamp", "Elétrico", null)),
            )

        publish(event)
        await().atMost(TIMEOUT).until { damages(event.executionId).isNotEmpty() }

        val damage = damages(event.executionId).single()
        damage["room_id"] shouldBe room.value
        damage["description"] shouldBe "Abajur - Elétrico"
    }

    @Test
    fun `an area routine and its separate duplicate execution each create their own records`() {
        val (templateId, versionId) = template()
        val area = rooms.createArea()
        val routine = UUID.randomUUID()
        val first =
            event(
                versionId,
                templateId,
                "rooms.area",
                area,
                UUID.randomUUID(),
                routineId = routine,
                actions = listOf(found("wallet", "Objeto", "Perto da piscina"), damage("lamp", "Elétrico", null)),
            )
        val duplicate =
            first.copy(executionId = UuidV7.generate(), executorId = UUID.randomUUID(), duplicateOf = first.executionId)

        publish(first)
        publish(duplicate)
        await().atMost(TIMEOUT).until {
            listOf(first, duplicate).all {
                lostFound(it.executionId).isNotEmpty() && damages(it.executionId).isNotEmpty()
            }
        }

        listOf(first, duplicate).forEach {
            val item = lostFound(it.executionId).single()
            item["area_id"] shouldBe area
            item["room_id"] shouldBe null
            item["reported_by"] shouldBe it.executorId
            damages(it.executionId).single()["area_id"] shouldBe area
        }
    }

    @Test
    fun `a room routine records in the room, and the note is cut to 500 characters`() {
        val (templateId, versionId) = template()
        val room = housekeeping.createRoom()
        val event =
            event(
                versionId,
                templateId,
                "rooms.room",
                room,
                UUID.randomUUID(),
                routineId = UUID.randomUUID(),
                actions = listOf(found("wallet", "Objeto", "x".repeat(600))),
            )

        publish(event)
        await().atMost(TIMEOUT).until { lostFound(event.executionId).isNotEmpty() }

        val item = lostFound(event.executionId).single()
        item["room_id"] shouldBe room
        (item["description"] as String).length shouldBe 500
        (item["description"] as String).startsWith("Carteira esquecida - Objeto - xxx") shouldBe true
    }

    @Test
    fun `a subject of another module, a missing room and a routine without place are ignored`() {
        val (templateId, versionId) = template()
        val actions = listOf(found("wallet", "Objeto", null), damage("lamp", "Elétrico", null))
        val ignored =
            listOf(
                event(versionId, templateId, "maintenance.asset", UUID.randomUUID(), UUID.randomUUID(), null, actions),
                event(versionId, templateId, "rooms.room", UUID.randomUUID(), UUID.randomUUID(), null, actions),
                event(versionId, templateId, null, null, UUID.randomUUID(), UUID.randomUUID(), actions),
            )

        ignored.forEach(::publish)
        await().atMost(TIMEOUT).until { ignored.all { completedDeliveries(it.executionId) == 1 } }

        ignored.forEach {
            lostFound(it.executionId).shouldBeEmpty()
            damages(it.executionId).shouldBeEmpty()
        }
    }

    /** Um modelo publicado com `wallet` ("Carteira esquecida") e `lamp` ("Abajur"): o id dele e o da versão 1. */
    private fun template(): Pair<UUID, UUID> {
        val id =
            templates.create(
                templates.templateBody(
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("wallet", "Carteira esquecida"),
                                templates.item("lamp", "Abajur"),
                            ),
                        ),
                ),
            )
        val number = templates.publish(id)
        return id to
            checklistsApi
                .version(ChecklistTemplateId(id), number)
                .shouldNotBeNull()
                .id.value
    }

    @Suppress("LongParameterList") // os campos do evento que variam entre os casos
    private fun event(
        versionId: UUID,
        templateId: UUID,
        subjectType: String?,
        subjectId: UUID?,
        executor: UUID,
        routineId: UUID? = null,
        actions: List<ChecklistTriggeredAction>,
    ): ChecklistCompleted {
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return ChecklistCompleted(
            executionId = UuidV7.generate(),
            templateId = templateId,
            versionId = versionId,
            versionNumber = 1,
            context = ChecklistContext.HOUSEKEEPING,
            subjectType = subjectType,
            subjectId = subjectId,
            routineId = routineId,
            executorId = executor,
            startedAt = completedAt.minusSeconds(600),
            completedAt = completedAt,
            actions = actions,
        )
    }

    private fun found(
        key: String,
        category: String?,
        note: String?,
    ) = ChecklistTriggeredAction(
        key,
        ItemActionType.OPEN_OCCURRENCE,
        occurrenceKind = OccurrenceKind.LOST_AND_FOUND,
        category = category,
        note = note,
    )

    private fun damage(
        key: String,
        category: String?,
        note: String?,
    ) = ChecklistTriggeredAction(
        key,
        ItemActionType.OPEN_OCCURRENCE,
        occurrenceKind = OccurrenceKind.DAMAGE,
        category = category,
        note = note,
    )

    private fun publish(event: ChecklistCompleted) {
        transactions.executeWithoutResult { events.publishEvent(event) }
    }

    private fun taskRoom(task: UUID): UUID =
        jdbc
            .sql("SELECT room_id FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query(UUID::class.java)
            .single()

    private fun lostFound(execution: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM housekeeping.lost_and_found_item WHERE checklist_execution_id = :execution")
            .param("execution", execution)
            .query()
            .listOfRows()

    private fun damages(execution: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM housekeeping.damage_report WHERE checklist_execution_id = :execution")
            .param("execution", execution)
            .query()
            .listOfRows()

    /** As entregas concluídas do evento da execução [execution] ao ouvinte das ocorrências. */
    private fun completedDeliveries(execution: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS NOT NULL " +
                    "AND serialized_event LIKE :execution AND listener_id LIKE '%HousekeepingOccurrenceListener%'",
            ).param("execution", "%\"executionId\":\"$execution\"%")
            .query(Int::class.java)
            .single()

    private fun instant(value: Any?): Instant = (value as Timestamp).toInstant()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
