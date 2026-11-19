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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * O ataque ao ouvinte da OS do checklist (M6 E08, Review Focus 2 e a corrida da reentrega): evento de sujeito
 * `rooms.room` de UH ativa mas sem `location` não abre ordem; entregas simultâneas do mesmo evento abrem uma ordem só e
 * deixam todas as publicações concluídas (nada de evento venenoso); a descrição respeita o limite em code points.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, banco, checklist, eventos e transação
class WorkOrderChecklistListenerAttackTest(
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
    fun `an event with a room subject of an active room but no location opens no order`() {
        val room = support.room()
        val event = event(null, subjectType = "rooms.room", subjectId = room)

        publish(event)
        await().atMost(TIMEOUT).until { deliveries(event.executionId, done = true) == 1 }

        orders(event.executionId).shouldBeEmpty()
    }

    @Test
    fun `simultaneous deliveries of the same event open one order and leave no delivery incomplete`() {
        val room = support.room()
        val batch = List(ROUNDS) { event(ChecklistLocation.room(room)) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val futures =
                batch.flatMap { e ->
                    List(COPIES) {
                        pool.submit {
                            start.await()
                            publish(e)
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        batch.forEach { e ->
            await()
                .atMost(TIMEOUT)
                .untilAsserted { deliveries(e.executionId, done = false) shouldBe 0 }
        }
        assertSoftly {
            batch.forEach { orders(it.executionId).size shouldBe 1 }
            batch.forEach { deliveries(it.executionId, done = true) shouldBe COPIES }
        }
    }

    @Test
    fun `a long note is cut by code points in the order description without splitting a pair`() {
        val room = support.room()
        val emoji = "😀"
        val event =
            event(
                ChecklistLocation.room(room),
                listOf(
                    ChecklistTriggeredAction(
                        "tap",
                        ItemActionType.OPEN_OCCURRENCE,
                        occurrenceKind = OccurrenceKind.MAINTENANCE,
                        category = "PLUMBING",
                        note = emoji.repeat(WorkOrderLimits.TEXT * 2),
                    ),
                ),
            )

        publish(event)
        await().atMost(TIMEOUT).until { orders(event.executionId).isNotEmpty() }

        val description = orders(event.executionId).single()["description"] as String
        assertSoftly {
            description.codePointCount(0, description.length) shouldBe WorkOrderLimits.TEXT
            description.startsWith("Torneira - PLUMBING - ") shouldBe true
            description.endsWith(emoji) shouldBe true
        }
    }

    private fun template(): Pair<UUID, UUID> {
        val id =
            templates.create(
                templates.templateBody(
                    sections = listOf(templates.section("Quarto", templates.item("tap", "Torneira"))),
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
        actions: List<ChecklistTriggeredAction> =
            listOf(
                ChecklistTriggeredAction(
                    "tap",
                    ItemActionType.OPEN_OCCURRENCE,
                    occurrenceKind = OccurrenceKind.MAINTENANCE,
                ),
            ),
        subjectType: String? = location?.let { "rooms.room" },
        subjectId: UUID? = location?.roomId ?: location?.areaId,
    ): ChecklistCompleted {
        val (templateId, versionId) = published
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return ChecklistCompleted(
            executionId = UuidV7.generate(),
            templateId = templateId,
            versionId = versionId,
            versionNumber = 1,
            context = ChecklistContext.HOUSEKEEPING,
            subjectType = subjectType,
            subjectId = subjectId,
            routineId = null,
            executorId = UUID.randomUUID(),
            startedAt = completedAt.minusSeconds(600),
            completedAt = completedAt,
            actions = actions,
            location = location,
        )
    }

    private fun publish(event: ChecklistCompleted) {
        transactions.executeWithoutResult { events.publishEvent(event) }
    }

    private fun orders(execution: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM maintenance.work_order WHERE checklist_execution_id = :execution ORDER BY number")
            .param("execution", execution)
            .query()
            .listOfRows()

    /** As publicações do evento para o ouvinte da manutenção, concluídas ([done]) ou pendentes. */
    private fun deliveries(
        execution: UUID,
        done: Boolean,
    ): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS " +
                    (if (done) "NOT NULL" else "NULL") +
                    " AND serialized_event LIKE :execution AND listener_id LIKE '%MaintenanceChecklistListener%'",
            ).param("execution", "%\"executionId\":\"$execution\"%")
            .query(Int::class.java)
            .single()

    private object WorkOrderLimits {
        const val TEXT = 2000
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(40)
        const val ROUNDS = 6
        const val COPIES = 2
        const val THREADS = 12
    }
}
