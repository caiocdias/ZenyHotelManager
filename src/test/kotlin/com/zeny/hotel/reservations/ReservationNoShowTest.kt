package com.zeny.hotel.reservations

import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * O no-show do fechamento (M6 E11, RF-RES-07, RN-15): `ReservationsApi.markNoShow` lança a multa da política copiada
 * na reserva (sem prazo, descontado o já cobrado), solta a UH no mesmo commit, grava o histórico e publica
 * `ReservationMarkedNoShow`; repetido ou numa reserva que já saiu de `CONFIRMED`, não muda nada; e o check-in que
 * corre com ele termina em exatamente um dos dois. Banco compartilhado: não avança a data operacional.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas e os cenários do no-show
class ReservationNoShowTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val context: ConfigurableApplicationContext,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    @Suppress("LongMethod") // a multa copiada, a UH solta, o inventário, o histórico e o evento
    fun `a confirmed reservation arriving today becomes no-show with the copied penalty and frees the room`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(2))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        assign(id, rooms.single())
        // A política muda depois: vale a copiada (150,00).
        editPolicy(plan.cancellationPolicy, fixed("300.00"))
        // Conta toda publicação do evento desta reserva (síncrono, no publicador): o cenário só espera a primeira.
        val published = CopyOnWriteArrayList<ReservationMarkedNoShow>()
        val counter =
            ApplicationListener.forPayload(
                Consumer<ReservationMarkedNoShow> { if (it.reservationId == id) published += it },
            )
        context.addApplicationListener(counter)

        try {
            scenario
                .stimulate(noShowStimulus(id))
                .andWaitForEventOfType(ReservationMarkedNoShow::class.java)
                .matching { it.reservationId == id }
                .toArriveAndVerify { event ->
                    event.roomId shouldBe rooms.single()
                    event.penaltyCharged shouldBe true
                    event.businessDate shouldBe today
                    event.arrival shouldBe today
                }
        } finally {
            context.removeApplicationListener(counter)
        }

        published shouldHaveSize 1

        support.status(id) shouldBe "NO_SHOW"
        val postings = support.postings(folio)
        postings shouldHaveSize 1
        postings.single().let {
            it["key"] shouldBe "NO_SHOW_FEE"
            it["amount"] shouldBe "150.00"
            it["source"] shouldBe "END_OF_DAY"
            it["businessDate"] shouldBe today
        }
        sourceReferences(folio) shouldContainExactly listOf("reservations.reservation:$id:$today:no-show")
        jdbc
            .sql(
                "SELECT release_reason FROM reservations.room_assignment " +
                    "WHERE reservation_id = :id AND released_at IS NOT NULL",
            ).param("id", id)
            .query(String::class.java)
            .list() shouldContainExactly listOf("RESERVATION_NO_SHOW")
        jdbc
            .sql("SELECT count(*) FROM reservations.room_assignment WHERE reservation_id = :id AND released_at IS NULL")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 0
        inventory.row(type, today)!!.sold shouldBe 0
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 0
        support.historyKinds(id) shouldContainExactly listOf("NO_SHOW")
        support.reservation(id)["reservation"]["penaltiesCharged"].asString() shouldBe "150.00"
        support.reservation(id)["reservation"]["roomAssignment"].isNull shouldBe true
        support.auditEntries(id).count { it["action"] == "reservations.reservation.no-show" } shouldBe 1
    }

    @Test
    fun `percent and nights penalties use the frozen nightly rates`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val percentPlan =
            support.plan(
                listOf(type),
                cancellationBody =
                    support.rates.cancellationBody(
                        penaltyType = "PERCENT_OF_STAY",
                        penaltyNights = null,
                        penaltyPercent = "50.0000",
                    ),
            )
        val nightsPlan =
            support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(penaltyNights = 1))
        support.setPrices(percentPlan.id, listOf(type), today, today.plusDays(10))
        support.setPrices(nightsPlan.id, listOf(type), today, today.plusDays(10))
        val percent = created(type, percentPlan.id, today, today.plusDays(2))
        val nights = created(type, nightsPlan.id, today, today.plusDays(2))
        rooms.size shouldBe 2

        markNoShow(percent.id()) shouldNotBe null
        markNoShow(nights.id()) shouldNotBe null

        // 2 noites de 400,00 congeladas: 50% do total e uma noite.
        support.postings(UUID.fromString(percent["folioId"].asString())).single()["amount"] shouldBe "400.00"
        support.postings(UUID.fromString(nights["folioId"].asString())).single()["amount"] shouldBe "400.00"
    }

    @Test
    fun `calling twice changes nothing the second time`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())

        val first = markNoShow(id)
        val second = markNoShow(id)

        first shouldNotBe null
        second shouldBe null
        support.postings(folio) shouldHaveSize 1
        support.reservation(id)["reservation"]["version"].asLong() shouldBe first!!.version
        support.historyKinds(id) shouldContainExactly listOf("NO_SHOW")
    }

    @Test
    fun `a reservation that is no longer confirmed is left alone`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val inHouse = created(type, plan.id, today, today.plusDays(1))
        support.forceInHouse(inHouse.id(), rooms.first())
        val provisional = provisional(type)
        val future = created(type, plan.id, today.plusDays(1), today.plusDays(2))

        markNoShow(inHouse.id()) shouldBe null
        markNoShow(provisional.id()) shouldBe null
        markNoShow(future.id()) shouldBe null

        support.status(inHouse.id()) shouldBe "IN_HOUSE"
        support.status(provisional.id()) shouldBe "PROVISIONAL"
        support.status(future.id()) shouldBe "CONFIRMED"
        listOf(inHouse, provisional, future).forEach {
            support.postings(UUID.fromString(it["folioId"].asString())).shouldBeEmpty()
            support.historyKinds(it.id()).shouldBeEmpty()
        }
    }

    @Test
    fun `a reactivated no-show marked again is not charged twice`(scenario: Scenario) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        markNoShow(id) shouldNotBe null

        with(support.billing) {
            support.reactivate(id).expect(200)["reservation"]["status"].asString() shouldBe
                "CONFIRMED"
        }
        scenario
            .stimulate(noShowStimulus(id))
            .andWaitForEventOfType(ReservationMarkedNoShow::class.java)
            .matching { it.reservationId == id && !it.penaltyCharged }
            .toArriveAndVerify { event -> event.penaltyCharged shouldBe false }

        support.status(id) shouldBe "NO_SHOW"
        support.postings(folio) shouldHaveSize 1
        support.reservation(id)["reservation"]["penaltiesCharged"].asString() shouldBe "150.00"
        support.historyKinds(id) shouldContainExactly listOf("NO_SHOW", "REACTIVATE", "NO_SHOW")
    }

    @Test
    fun `marking again on the same date replays the posting key and adds nothing to the charged total`(
        scenario: Scenario,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        var version = markNoShow(id)!!.version

        // O total cobrado ficou menor que o razão (100,00 com devido 50,00; depois 0,00 com devido 150,00): a chave
        // da data já foi lançada, então a repetição não grava nada e não soma nada.
        listOf("100.00" to "100.00", "0.00" to "0.00").forEach { (reset, expected) ->
            with(support.billing) { support.reactivate(id).expect(200) }
            version = support.reservation(id)["reservation"]["version"].asLong()
            jdbc
                .sql("UPDATE reservations.reservation SET penalties_charged = CAST(:v AS numeric) WHERE id = :id")
                .param("v", reset)
                .param("id", id)
                .update()

            scenario
                .stimulate(noShowStimulus(id))
                .andWaitForEventOfType(ReservationMarkedNoShow::class.java)
                .matching { it.reservationId == id && it.version > version }
                .toArriveAndVerify { event -> event.penaltyCharged shouldBe false }

            support.postings(folio) shouldHaveSize 1
            support.reservation(id)["reservation"]["penaltiesCharged"].asString() shouldBe expected
            version = support.reservation(id)["reservation"]["version"].asLong()
        }
    }

    @Test
    @Timeout(30)
    fun `check-in and no-show racing on the same reservation end in exactly one of them`() {
        val stay = frontdesk.stay()
        val holder = frontdesk.guest()
        val booked = stay.book(holder, today.plusDays(1))
        stay.ready()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val checkIn =
                pool.submit<Int> {
                    start.await()
                    frontdesk.checkIn(booked.id, holder).raw.status
                }
            val noShow =
                pool.submit<ReservationSummary?> {
                    start.await()
                    markNoShow(booked.id)
                }
            start.countDown()
            val checkInStatus = checkIn.get(20, TimeUnit.SECONDS)
            val marked = noShow.get(20, TimeUnit.SECONDS)

            val status = support.status(booked.id)
            if (status == "IN_HOUSE") {
                checkInStatus shouldBe 200
                marked shouldBe null
                support.postings(booked.folio).filter { it["key"] == "NO_SHOW_FEE" }.shouldBeEmpty()
            } else {
                status shouldBe "NO_SHOW"
                marked shouldNotBe null
                (checkInStatus in 400..499) shouldBe true
                support.historyKinds(booked.id).filter { it == "CHECK_IN" }.shouldBeEmpty()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun noShowStimulus(id: UUID) =
        BiConsumer<TransactionOperations, ApplicationEventPublisher> { _, _ -> markNoShow(id) }

    private fun markNoShow(id: UUID): ReservationSummary? =
        transactions.execute { reservationsApi.markNoShow(ReservationId(id)) }

    private fun fixed(amount: String) =
        support.rates.cancellationBody(
            deadlineHours = 8760,
            penaltyType = "FIXED_AMOUNT",
            penaltyNights = null,
            penaltyAmount = amount,
        )

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, departure)),
        )["reservations"][0]

    /** Uma pré-reserva de hoje: o plano com garantia por depósito a deixa `PROVISIONAL`. */
    private fun provisional(type: UUID): JsonNode {
        val plan = support.plan(listOf(type), guarantee = "DEPOSIT")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        return created(type, plan.id, today, today.plusDays(1))
    }

    private fun assign(
        id: UUID,
        room: UUID,
    ) = with(iam) { call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to room)).ok() }

    private fun editPolicy(
        id: UUID,
        body: Map<String, Any?>,
    ) = with(iam) {
        val path = "/api/v1/cancellation-policies/$id"
        call(PUT, path, support.admin, body, ifMatch = support.rates.etagOf(path)).ok()
    }

    private fun sourceReferences(folio: UUID): List<String> =
        jdbc
            .sql(
                "SELECT source_reference FROM billing.posting WHERE folio_id = :folio AND source_reference IS NOT NULL",
            ).param("folio", folio)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())
}
