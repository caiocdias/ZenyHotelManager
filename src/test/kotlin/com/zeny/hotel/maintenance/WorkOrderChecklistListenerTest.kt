package com.zeny.hotel.maintenance

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistLocation
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
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
 * A ordem de serviço do checklist (M6 E08, Task 2, D2): o ouvinte de `ChecklistCompleted` abre uma ordem
 * `origin = CHECKLIST` por ação `OPEN_OCCURRENCE` de tipo `MAINTENANCE`, no local do evento, com o vínculo à execução
 * e ao item, o executor como quem abriu e a categoria mapeada. A reentrega não duplica; a execução registrada à parte
 * (`duplicateOf`) não abre outra e a ordem guarda o `executionId` real (Review Focus 1); sem local (avulsa) e com o
 * local inexistente ou desativado nada abre e o ouvinte não falha (Review Focus 2); achado e dano não abrem ordem.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, banco, checklist, eventos e transação
class WorkOrderChecklistListenerTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)
    private val templates = ChecklistsTestSupport(iam)
    private val published: Pair<UUID, UUID> by lazy { template() }

    @Test
    fun `a maintenance item not ok opens exactly one order with the place, the link, the executor and the category`() {
        val room = support.room()
        val executor = UUID.randomUUID()
        val event =
            event(ChecklistLocation.room(room), listOf(maintenance("tap", "PLUMBING", "Pinga")), executor)

        publish(event)
        await().atMost(TIMEOUT).until { orders(event.executionId).isNotEmpty() }

        val order = orders(event.executionId).single()
        assertSoftly {
            order["origin"] shouldBe "CHECKLIST"
            order["room_id"] shouldBe room
            order["area_id"] shouldBe null
            order["category"] shouldBe "PLUMBING"
            order["priority"] shouldBe "NORMAL"
            order["status"] shouldBe "OPEN"
            order["description"] shouldBe "Torneira - PLUMBING - Pinga"
            order["opened_by"] shouldBe executor
            (order["opened_at"] as Timestamp).toInstant() shouldBe event.completedAt
            order["checklist_execution_id"] shouldBe event.executionId
            order["checklist_item_key"] shouldBe "tap"
            order["checklist_reference_execution_id"] shouldBe event.executionId
        }

        // O outbox reentrega: a mesma ordem, sem duplicar.
        publish(event)
        await().atMost(TIMEOUT).until { completedDeliveries(event.executionId) == 2 }
        orders(event.executionId) shouldHaveSize 1
    }

    @Test
    fun `an area location and an unknown category open an OTHER order in the area`() {
        val area = support.area()
        val event =
            event(ChecklistLocation.area(area), listOf(maintenance("tap", "Inexistente", null)))

        publish(event)
        await().atMost(TIMEOUT).until { orders(event.executionId).isNotEmpty() }

        val order = orders(event.executionId).single()
        order["area_id"] shouldBe area
        order["room_id"] shouldBe null
        order["category"] shouldBe "OTHER"
    }

    @Test
    fun `a copy registered apart from a routine opens no second order and keeps the real executionId`() {
        val room = support.room()
        val first = event(ChecklistLocation.room(room))
        val copy = event(ChecklistLocation.room(room)).copy(duplicateOf = first.executionId)

        publish(first)
        await().atMost(TIMEOUT).until { orders(first.executionId).isNotEmpty() }
        publish(copy)
        await().atMost(TIMEOUT).until { completedDeliveries(copy.executionId) == 1 }

        orders(copy.executionId).shouldBeEmpty()
        orders(first.executionId).single()["checklist_execution_id"] shouldBe first.executionId
    }

    @Test
    fun `a copy that arrives first keeps its own executionId and the original opens nothing more`() {
        val room = support.room()
        val original = UuidV7.generate()
        val copy = event(ChecklistLocation.room(room)).copy(duplicateOf = original)
        val late = event(ChecklistLocation.room(room)).copy(executionId = original)

        publish(copy)
        await().atMost(TIMEOUT).until { orders(copy.executionId).isNotEmpty() }
        publish(late)
        await().atMost(TIMEOUT).until { completedDeliveries(late.executionId) == 1 }

        orders(late.executionId).shouldBeEmpty()
        val order = orders(copy.executionId).single()
        order["checklist_execution_id"] shouldBe copy.executionId
        order["checklist_reference_execution_id"] shouldBe original
    }

    @Test
    fun `a standalone execution, a missing room and a deactivated room open nothing and do not fail the listener`() {
        val deactivated = support.room().also { support.deactivate("/api/v1/rooms/$it/deactivate") }
        val deactivatedArea = support.area().also { support.deactivate("/api/v1/areas/$it/deactivate") }
        val ignored =
            listOf(
                event(null),
                event(ChecklistLocation.room(UUID.randomUUID())),
                event(ChecklistLocation.area(UUID.randomUUID())),
                event(ChecklistLocation.room(deactivated)),
                event(ChecklistLocation.area(deactivatedArea)),
            )

        ignored.forEach(::publish)
        await().atMost(TIMEOUT).until { ignored.all { completedDeliveries(it.executionId) == 1 } }

        ignored.forEach { orders(it.executionId).shouldBeEmpty() }
    }

    @Test
    fun `found and damage items open no order, and a maintenance item beside them opens its own`() {
        val room = support.room()
        val event =
            event(
                ChecklistLocation.room(room),
                listOf(
                    occurrence("wallet", OccurrenceKind.LOST_AND_FOUND),
                    occurrence("lamp", OccurrenceKind.DAMAGE),
                    maintenance("tap"),
                ),
            )

        publish(event)
        await().atMost(TIMEOUT).until { orders(event.executionId).isNotEmpty() }

        orders(event.executionId).map { it["checklist_item_key"] } shouldBe listOf("tap")
    }

    private fun template(): Pair<UUID, UUID> {
        val id =
            templates.create(
                templates.templateBody(
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("tap", "Torneira"),
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

    private fun event(
        location: ChecklistLocation?,
        actions: List<ChecklistTriggeredAction> = listOf(maintenance("tap")),
        executor: UUID = UUID.randomUUID(),
    ): ChecklistCompleted {
        val (templateId, versionId) = published
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return ChecklistCompleted(
            executionId = UuidV7.generate(),
            templateId = templateId,
            versionId = versionId,
            versionNumber = 1,
            context = ChecklistContext.HOUSEKEEPING,
            subjectType = location?.let { "rooms.room" },
            subjectId = location?.roomId ?: location?.areaId,
            routineId = null,
            executorId = executor,
            startedAt = completedAt.minusSeconds(600),
            completedAt = completedAt,
            actions = actions,
            location = location,
        )
    }

    private fun maintenance(
        key: String,
        category: String? = null,
        note: String? = null,
    ) = ChecklistTriggeredAction(
        key,
        ItemActionType.OPEN_OCCURRENCE,
        occurrenceKind = OccurrenceKind.MAINTENANCE,
        category = category,
        note = note,
    )

    private fun occurrence(
        key: String,
        kind: OccurrenceKind,
    ) = ChecklistTriggeredAction(key, ItemActionType.OPEN_OCCURRENCE, occurrenceKind = kind)

    private fun publish(event: ChecklistCompleted) {
        transactions.executeWithoutResult { events.publishEvent(event) }
    }

    /** As ordens abertas pela execução real [execution]. */
    private fun orders(execution: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM maintenance.work_order WHERE checklist_execution_id = :execution ORDER BY number")
            .param("execution", execution)
            .query()
            .listOfRows()

    /** As entregas concluídas do evento da execução [execution] ao ouvinte da manutenção. */
    private fun completedDeliveries(execution: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS NOT NULL " +
                    "AND serialized_event LIKE :execution AND listener_id LIKE '%MaintenanceChecklistListener%'",
            ).param("execution", "%\"executionId\":\"$execution\"%")
            .query(Int::class.java)
            .single()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
