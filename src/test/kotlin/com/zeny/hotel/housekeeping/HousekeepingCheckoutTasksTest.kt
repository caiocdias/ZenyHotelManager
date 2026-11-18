package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.HousekeepingTaskGeneration
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * As tarefas de saída geradas pelos eventos (M6 E02, Task 2; RF-GOV-02): o check-out e a troca de UH reais, pela
 * `ReservationsApi`, publicam `GuestCheckedOut` e `RoomMoved` pelo outbox, e o listener da governança gera a `CHECKOUT`
 * na UH que ficou vaga, na data do evento (D4-E02), com a marca de chegada prevista (D8-E02), auditada com o ator
 * sistema e sem dado pessoal (RN-13). A entrega repetida não cria outra tarefa (Review Focus 2). O banco é
 * compartilhado: cada teste afirma só sobre as UHs que criou, sem avançar a data operacional nem mexer nos tipos da
 * carga.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class HousekeepingCheckoutTasksTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val generation: HousekeepingTaskGeneration,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)
    private val today: LocalDate get() = hotel.currentBusinessDate()

    @Test
    fun `a check-out generates the CHECKOUT task on the room and date of the event, audited as the system`() {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(3, today)
        val plan = stays.planFor(type)
        val name = "Hóspede Saída ${UuidV7.generate().toString().takeLast(8)}"
        val (leaving, holder) = stays.inHouse(type, plan, rooms[0], today.plusDays(1), name)
        val (leavingBeforeArrival, _) = stays.inHouse(type, plan, rooms[1], today.plusDays(1))
        val (arriving, _) = stays.reservation(type, plan)

        reservationsApi.checkOut(leaving)
        // A chegada é alocada à UH na transação do check-out: o listener roda depois do commit e a vê.
        transactions.executeWithoutResult {
            reservationsApi.checkOut(leavingBeforeArrival)
            reservationsApi.assignRoom(arriving, RoomId(rooms[1]))
        }
        awaitListener(GuestCheckedOut::class.java, leaving)
        awaitListener(GuestCheckedOut::class.java, leavingBeforeArrival)

        val task = tasksOf(rooms[0]).single()
        task.date shouldBe today
        task.cleaningTypeId shouldBe checkoutTypeId()
        task.source shouldBe "CHECKOUT"
        task.status shouldBe "PENDING"
        task.arrivalExpected shouldBe false
        tasksOf(rooms[1]).single().let {
            it.source shouldBe "CHECKOUT"
            it.arrivalExpected shouldBe true
        }
        tasksOf(rooms[2]).shouldBeEmpty()
        // A trilha: o ator sistema, só ids, datas e a origem; nada do hóspede nem da reserva (RN-13).
        val entry = generateEntry(task.id)
        entry.userId shouldBe null
        listOf(name, holder.toString(), leaving.value.toString()).forEach { entry.after shouldNotContain it }

        // A entrega repetida do mesmo evento não cria outra tarefa nem lança (Review Focus 2).
        generation.generateCheckout(RoomId(rooms[0]), today, TaskSource.CHECKOUT) shouldBe
            false
        tasksOf(rooms[0]).map { it.id } shouldBe listOf(task.id)
    }

    @Test
    fun `a room move generates the CHECKOUT task on the room left, marked when an arrival is assigned to it`() {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(2, today)
        val plan = stays.planFor(type)
        val (moving, _) = stays.inHouse(type, plan, rooms[0], today.plusDays(2))
        val (arriving, _) = stays.reservation(type, plan)

        transactions.executeWithoutResult {
            reservationsApi.moveRoom(moving, RoomMove(RoomId(rooms[1]), RoomAssignmentReason.GUEST_REQUEST))
            reservationsApi.assignRoom(arriving, RoomId(rooms[0]))
        }
        awaitListener(RoomMoved::class.java, moving)

        val task = tasksOf(rooms[0]).single()
        task.date shouldBe today
        task.cleaningTypeId shouldBe checkoutTypeId()
        task.source shouldBe "ROOM_MOVE"
        task.arrivalExpected shouldBe true
        tasksOf(rooms[1]).shouldBeEmpty()
        generateEntry(task.id).userId shouldBe null
    }

    @Test
    fun `generating the CHECKOUT task twice for the same event creates one task and does not throw`() {
        val room = RoomId(housekeeping.createRoom())

        generation.generateCheckout(room, today, TaskSource.CHECKOUT) shouldBe true
        generation.generateCheckout(room, today, TaskSource.CHECKOUT) shouldBe false

        val task = tasksOf(room.value).single()
        task.source shouldBe "CHECKOUT"
        task.arrivalExpected shouldBe false
        housekeeping.auditActions("housekeeping.task", task.id) shouldBe listOf("housekeeping.task.generate")
    }

    @Test
    fun `concurrent deliveries of the same check-out create one task, one trail entry and no exception`() {
        val room = RoomId(housekeeping.createRoom())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES)
        val results =
            try {
                val futures =
                    (1..CONCURRENT_DELIVERIES).map {
                        pool.submit<Boolean> {
                            start.await()
                            generation.generateCheckout(room, today, TaskSource.CHECKOUT)
                        }
                    }
                start.countDown()
                futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        results.count { it } shouldBe 1
        val task = tasksOf(room.value).single()
        housekeeping.auditActions("housekeeping.task", task.id) shouldBe listOf("housekeeping.task.generate")
    }

    @Test
    fun `an ad hoc request on a room with an arrival assigned on the date is marked with the arrival`() {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(2, today)
        val plan = stays.planFor(type)
        val (arriving, _) = stays.reservation(type, plan)
        reservationsApi.assignRoom(arriving, RoomId(rooms[0]))
        val cleaningType = housekeeping.createCleaningType()

        housekeeping
            .requestTask(housekeeping.taskBody(cleaningType, roomId = rooms[0]))["arrivalExpected"]
            .asBoolean() shouldBe true
        housekeeping
            .requestTask(housekeeping.taskBody(cleaningType, roomId = rooms[1]))["arrivalExpected"]
            .asBoolean() shouldBe false
    }

    @Test
    fun `an ad hoc request on a future date is marked only by an arrival on that very date`() {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, today)
        val plan = stays.planFor(type)
        val (arriving, _) = stays.reservation(type, plan, arrival = today.plusDays(1), departure = today.plusDays(2))
        reservationsApi.assignRoom(arriving, RoomId(rooms[0]))
        val cleaningType = housekeeping.createCleaningType()

        // A chegada de D+1 não marca a avulsa de D+5 (já terá saído), só a da própria data.
        housekeeping
            .requestTask(housekeeping.taskBody(cleaningType, roomId = rooms[0], date = "${today.plusDays(5)}"))
            .get("arrivalExpected")
            .asBoolean() shouldBe false
        housekeeping
            .requestTask(housekeeping.taskBody(cleaningType, roomId = rooms[0], date = "${today.plusDays(1)}"))
            .get("arrivalExpected")
            .asBoolean() shouldBe true
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private data class Task(
        val id: UUID,
        val date: LocalDate,
        val cleaningTypeId: UUID,
        val source: String,
        val status: String,
        val arrivalExpected: Boolean,
    )

    private data class AuditRow(
        val userId: UUID?,
        val after: String,
    )

    /** Espera a publicação do evento da reserva para o listener da governança (e só ele) ser concluída. */
    private fun awaitListener(
        type: Class<*>,
        reservation: ReservationId,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            jdbc
                .sql(
                    "SELECT count(*) FROM shared.event_publication WHERE event_type = :type " +
                        "AND serialized_event LIKE :reservation AND listener_id LIKE :listener " +
                        "AND completion_date IS NOT NULL",
                ).param("type", type.name)
                .param("reservation", "%\"reservationId\":\"${reservation.value}\"%")
                .param("listener", "%housekeeping.application.HousekeepingTaskListeners%")
                .query(Int::class.java)
                .single() == 1
        }
    }

    private fun tasksOf(room: UUID): List<Task> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE room_id = :room ORDER BY business_date, id")
            .param("room", room)
            .query { rs, _ ->
                Task(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getObject("cleaning_type_id", UUID::class.java),
                    rs.getString("source"),
                    rs.getString("status"),
                    rs.getBoolean("arrival_expected"),
                )
            }.list()

    private fun checkoutTypeId(): UUID =
        jdbc
            .sql("SELECT id FROM housekeeping.cleaning_type WHERE system_key = 'CHECKOUT'")
            .query(UUID::class.java)
            .single()

    private fun generateEntry(task: UUID): AuditRow =
        jdbc
            .sql(
                "SELECT user_id, after_state::text AS after FROM audit.audit_entry " +
                    "WHERE action = 'housekeeping.task.generate' AND entity_type = 'housekeeping.task' " +
                    "AND entity_id = :id",
            ).param("id", task.toString())
            .query { rs, _ -> AuditRow(rs.getObject("user_id", UUID::class.java), rs.getString("after")) }
            .single()

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val CONCURRENT_DELIVERIES = 4
    }
}
