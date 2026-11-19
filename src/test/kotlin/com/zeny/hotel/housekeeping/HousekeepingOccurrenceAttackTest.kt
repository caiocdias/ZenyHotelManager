package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.DamageReportDraft
import com.zeny.hotel.housekeeping.application.HousekeepingDamages
import com.zeny.hotel.housekeeping.application.HousekeepingLostAndFound
import com.zeny.hotel.housekeeping.application.LostAndFoundDraft
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Ataque às ocorrências do checklist (M6 E06, lacunas das revisões de fatia): o vínculo do dano de checklist só
 * acontece numa tarefa que não seja de saída (`CHECKOUT` não vincula, `STAYOVER` vincula, mesmo com a reserva
 * hospedada na UH) e dois registros simultâneos do mesmo item de execução deixam uma linha só.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, banco, checklist, eventos, reservas e transação
class HousekeepingOccurrenceAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val hotel: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired private val damages: HousekeepingDamages,
    @Autowired private val lostAndFound: HousekeepingLostAndFound,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val templates = ChecklistsTestSupport(iam)

    @Test
    fun `a CHECKOUT task checklist damage does not link the in-house reservation, a STAYOVER one does`() {
        val (templateId, versionId) = template()
        val (checkoutRoom, checkoutReservation) = occupied()
        val (stayoverRoom, stayoverReservation) = occupied()
        val checkoutTask = taskIn(checkoutRoom, "CHECKOUT")
        val stayoverTask = taskIn(stayoverRoom, "STAYOVER")
        val actions = listOf(damage("lamp"))
        val onCheckout = event(versionId, templateId, checkoutTask, actions)
        val onStayover = event(versionId, templateId, stayoverTask, actions)

        publish(onCheckout)
        publish(onStayover)
        await().atMost(TIMEOUT).until {
            damagesOf(onCheckout.executionId).isNotEmpty() && damagesOf(onStayover.executionId).isNotEmpty()
        }

        damagesOf(onCheckout.executionId).single()["reservation_id"] shouldBe null
        damagesOf(onStayover.executionId).single()["reservation_id"] shouldBe stayoverReservation
        checkoutReservation.shouldNotBeNull()
    }

    @Test
    fun `two simultaneous damage records of one execution item leave one row, a redelivery returns it`() {
        val execution = ChecklistExecutionId(UuidV7.generate())
        val room = RoomId(housekeeping.createRoom())
        val draft = {
            DamageReportDraft(
                id = DamageReportId.new(),
                roomId = room,
                areaId = null,
                description = "Dano concorrente",
                estimatedCost = null,
                occurredAt = Instant.now(),
                reportedBy = UserId(iam.adminId()),
                source = OccurrenceSource.CHECKLIST,
                checklistExecutionId = execution,
                checklistItemKey = "lamp",
            )
        }

        val results = race { damages.record(draft()) }

        // O perdedor pode falhar na chave única (o outbox reentrega) ou ver a primeira linha.
        (results.count { it.isSuccess } >= 1) shouldBe true
        damagesOf(execution.value).size shouldBe 1
        damages.record(draft())
        damagesOf(execution.value).size shouldBe 1
    }

    @Test
    fun `two simultaneous lost item records of the same execution item leave one row`() {
        val execution = ChecklistExecutionId(UuidV7.generate())
        val room = RoomId(housekeeping.createRoom())
        val draft = {
            LostAndFoundDraft(
                LostAndFoundItemId.new(),
                room,
                null,
                "Achado concorrente",
                storagePlace = null,
                foundAt = Instant.now(),
                reportedBy = UserId(iam.adminId()),
                source = OccurrenceSource.CHECKLIST,
                checklistExecutionId = execution,
                checklistItemKey = "wallet",
            )
        }

        val results = race { lostAndFound.record(draft()) }

        (results.count { it.isSuccess } >= 1) shouldBe true
        lostOf(execution.value) shouldBe 1
        lostAndFound.record(draft())
        lostOf(execution.value) shouldBe 1
    }

    /** Duas chamadas de [action] soltas juntas por uma barreira. */
    private fun race(action: () -> Unit): List<Result<Unit>> {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            return (1..2)
                .map {
                    pool.submit<Result<Unit>> {
                        barrier.await()
                        runCatching { action() }
                    }
                }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    /** Uma UH nova ocupada por um check-in real: a UH e a reserva. */
    private fun occupied(): Pair<UUID, UUID> {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
        val (reservation, _) =
            stays.inHouse(type, stays.planFor(type), rooms[0], hotel.currentBusinessDate().plusDays(2), "Hóspede Chk")
        return rooms[0] to reservation.value
    }

    /** Uma tarefa avulsa na UH [room], com a origem trocada para [source] direto no banco. */
    private fun taskIn(
        room: UUID,
        source: String,
    ): UUID {
        val body = housekeeping.taskBody(housekeeping.createCleaningType(), roomId = room)
        val id = UUID.fromString(housekeeping.requestTask(body)["id"].asString())
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET source = :source WHERE id = :id")
            .param("source", source)
            .param("id", id)
            .update() shouldBe 1
        return id
    }

    private fun template(): Pair<UUID, UUID> {
        val id =
            templates.create(
                templates.templateBody(
                    sections = listOf(templates.section("Quarto", templates.item("lamp", "Abajur"))),
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
        versionId: UUID,
        templateId: UUID,
        task: UUID,
        actions: List<ChecklistTriggeredAction>,
    ): ChecklistCompleted {
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return ChecklistCompleted(
            executionId = UuidV7.generate(),
            templateId = templateId,
            versionId = versionId,
            versionNumber = 1,
            context = ChecklistContext.HOUSEKEEPING,
            subjectType = "housekeeping.task",
            subjectId = task,
            routineId = null,
            executorId = UUID.randomUUID(),
            startedAt = completedAt.minusSeconds(600),
            completedAt = completedAt,
            actions = actions,
        )
    }

    private fun damage(key: String) =
        ChecklistTriggeredAction(
            key,
            ItemActionType.OPEN_OCCURRENCE,
            occurrenceKind = OccurrenceKind.DAMAGE,
            category = null,
            note = null,
        )

    private fun publish(event: ChecklistCompleted) {
        transactions.executeWithoutResult { events.publishEvent(event) }
    }

    private fun damagesOf(execution: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM housekeeping.damage_report WHERE checklist_execution_id = :execution")
            .param("execution", execution)
            .query()
            .listOfRows()

    private fun lostOf(execution: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.lost_and_found_item WHERE checklist_execution_id = :execution")
            .param("execution", execution)
            .query(Int::class.java)
            .single()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
