package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Os `ReservationMarkedNoShow` entregues pelo outbox ao listener de teste (só depois do commit). */
object NoShowEvents {
    val received: MutableList<ReservationMarkedNoShow> = CopyOnWriteArrayList()

    fun of(reservation: UUID): List<ReservationMarkedNoShow> = received.filter { it.reservationId == reservation }
}

open class NoShowProbeListener {
    @ApplicationModuleListener
    open fun on(event: ReservationMarkedNoShow) {
        NoShowEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class NoShowAttackConfiguration {
    @Bean
    fun noShowProbeListener() = NoShowProbeListener()
}

/**
 * Ataque ao no-show do fechamento (M6 E11, RF-RES-07, RN-15): multa zero ou já cobrada, `NIGHTS` maior que as noites,
 * day use, pick-up de bloqueio, chegada passada, reserva sem UH, roteamento, conta encerrada (desfaz tudo), trilha do
 * sistema, chave de origem e publicação só no commit. Banco compartilhado: sem avançar a data operacional.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, NoShowAttackConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // as portas e os cenários do ataque
class ReservationNoShowAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a policy without penalty posts nothing and the event says no penalty was charged`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val zeroNights =
            support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(penaltyNights = 0))
        val zeroAmount = support.plan(listOf(type), cancellationBody = fixed("0.00"))
        val zeroPercent =
            support.plan(
                listOf(type),
                cancellationBody =
                    support.rates.cancellationBody(
                        penaltyType = "PERCENT_OF_STAY",
                        penaltyNights = null,
                        penaltyPercent = "0.0000",
                    ),
            )
        listOf(zeroNights, zeroAmount, zeroPercent).forEach {
            support.setPrices(it.id, listOf(type), today, today.plusDays(10))
        }
        val a = created(type, zeroNights.id, today, today.plusDays(2))
        val b = created(type, zeroAmount.id, today, today.plusDays(2))
        val c = created(type, zeroPercent.id, today, today.plusDays(2))
        val ids = listOf(a, b, c).map { it.id() }

        ids.forEach { markNoShow(it) shouldNotBe null }

        listOf(a, b, c).forEach {
            support.postings(it.folio()).shouldBeEmpty()
            support.status(it.id()) shouldBe "NO_SHOW"
            support.reservation(it.id())["reservation"]["penaltiesCharged"].asString() shouldBe "0.00"
            await().atMost(Duration.ofSeconds(WAIT)).until { NoShowEvents.of(it.id()).isNotEmpty() }
            NoShowEvents.of(it.id()).single().penaltyCharged shouldBe false
            sourceReferences(it.folio()).shouldBeEmpty()
        }
    }

    @Test
    fun `only what is still due is charged when part or all of the penalty was already charged`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val partial = created(type, plan.id, today, today.plusDays(1))
        val whole = created(type, plan.id, today, today.plusDays(1))
        setCharged(partial.id(), "100.00")
        setCharged(whole.id(), "200.00")

        markNoShow(partial.id()) shouldNotBe null
        markNoShow(whole.id()) shouldNotBe null

        support.postings(partial.folio()).single()["amount"] shouldBe "50.00"
        support.reservation(partial.id())["reservation"]["penaltiesCharged"].asString() shouldBe "150.00"
        support.postings(whole.folio()).shouldBeEmpty()
        // Nada mais cobrado: o total fica como estava, sem encolher para a multa da política.
        support.reservation(whole.id())["reservation"]["penaltiesCharged"].asString() shouldBe "200.00"
        await().atMost(Duration.ofSeconds(WAIT)).until {
            NoShowEvents.of(partial.id()).isNotEmpty() && NoShowEvents.of(whole.id()).isNotEmpty()
        }
        NoShowEvents.of(partial.id()).single().penaltyCharged shouldBe true
        NoShowEvents.of(whole.id()).single().penaltyCharged shouldBe false
    }

    @Test
    fun `a nights penalty larger than the stay charges every night and no more`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan =
            support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(penaltyNights = 5))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(2))

        markNoShow(reservation.id()) shouldNotBe null

        // Duas noites de 400,00 congeladas: as 5 diárias da política viram as 2 que existem.
        support.postings(reservation.folio()).single()["amount"] shouldBe "800.00"
    }

    @Test
    fun `a day use is charged by its policy and takes nothing from the inventory`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val fixedPlan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        val nightsPlan = support.plan(listOf(type))
        support.setPrices(fixedPlan.id, listOf(type), today, today.plusDays(10))
        support.setPrices(nightsPlan.id, listOf(type), today, today.plusDays(10))
        val byAmount = created(type, fixedPlan.id, today, today)
        val byNights = created(type, nightsPlan.id, today, today)
        val before = inventory.row(type, today)!!

        markNoShow(byAmount.id()) shouldNotBe null
        markNoShow(byNights.id()) shouldNotBe null

        support.status(byAmount.id()) shouldBe "NO_SHOW"
        support.status(byNights.id()) shouldBe "NO_SHOW"
        support.postings(byAmount.folio()).single()["amount"] shouldBe "150.00"
        // NIGHTS 1 sobre a diária congelada do day use.
        support.postings(byNights.folio()).single()["amount"] shouldBe "400.00"
        // O day use nunca tomou inventário (H5): devolvê-lo não pode mexer nos contadores.
        inventory.settledRow(type, today) shouldBe before
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 0
    }

    @Test
    fun `a block pick-up returns its units to the active block, not to the free inventory`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        val blockId =
            UUID.fromString(
                support
                    .createdBlock(
                        support.blockBody(
                            plan.id,
                            today,
                            support.allocation(type, today, today.plusDays(2), 2),
                            holderGuest = support.guest(),
                        ),
                    )["id"]
                    .asString(),
            )
        val booking =
            with(support.billing) {
                support.pickUp(blockId, support.blockReservation(type, today, today.plusDays(2))).expect(201)
            }
        val picked = booking["reservations"][0]
        val id = picked.id()
        val atPickUp = listOf(inventory.row(type, today)!!, inventory.row(type, today.plusDays(1))!!)
        support.allocationRow(blockId, type, today) shouldBe Triple(2, 1, 0)

        markNoShow(id) shouldNotBe null

        listOf(today, today.plusDays(1)).forEachIndexed { index, date ->
            inventory.row(type, date)!!.let {
                it.sold shouldBe atPickUp[index].sold - 1
                it.blocked shouldBe atPickUp[index].blocked + 1
                it.available shouldBe atPickUp[index].available
            }
            support.allocationRow(blockId, type, date) shouldBe Triple(2, 0, 0)
        }
        support.postings(picked.folio()).single()["amount"] shouldBe "150.00"
    }

    @Test
    fun `an arrival that is already in the past is still marked no-show and only the future nights are returned`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(3))
        val id = reservation.id()
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :d WHERE id = :id")
            .param("d", today.minusDays(1))
            .param("id", id)
            .update()
        val soldBefore = (0L..2L).map { inventory.row(type, today.plusDays(it))!!.sold }
        soldBefore shouldContainExactly listOf(1, 1, 1)

        markNoShow(id) shouldNotBe null

        support.status(id) shouldBe "NO_SHOW"
        support.postings(reservation.folio()).single()["amount"] shouldBe "150.00"
        sourceReferences(reservation.folio()) shouldContainExactly listOf("reservations.reservation:$id:$today:no-show")
        (0L..2L).map { inventory.row(type, today.plusDays(it))!!.sold } shouldContainExactly listOf(0, 0, 0)
        // O passado não é mexido: nenhuma linha de inventário nasce nem fica negativa para a véspera.
        inventory.row(type, today.minusDays(1))?.sold?.let { it shouldBe 0 }
    }

    @Test
    fun `a reservation without an assigned room becomes no-show and the event carries no room`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()

        markNoShow(id) shouldNotBe null

        support.status(id) shouldBe "NO_SHOW"
        jdbc
            .sql("SELECT count(*) FROM reservations.room_assignment WHERE reservation_id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 0
        await().atMost(Duration.ofSeconds(WAIT)).until { NoShowEvents.of(id).isNotEmpty() }
        NoShowEvents.of(id).single().let {
            it.roomId shouldBe null
            it.penaltyCharged shouldBe true
        }
    }

    @Test
    fun `a routing rule by code takes the no-show fee to the other folio`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()
        val target: UUID
        with(support.billing) {
            target = openFolio("GROUP")
            createRule(reservation.folio(), target, today, null, codes = listOf(codeId("NO_SHOW_FEE"))).expect(201)
        }

        markNoShow(id) shouldNotBe null

        support.postings(reservation.folio()).shouldBeEmpty()
        support.postings(target).single().let {
            it["key"] shouldBe "NO_SHOW_FEE"
            it["amount"] shouldBe "150.00"
            it["source"] shouldBe "END_OF_DAY"
        }
        // A reserva guarda o que foi cobrado, mesmo que a conta final seja outra.
        support.reservation(id)["reservation"]["penaltiesCharged"].asString() shouldBe "150.00"
        sourceReferences(target) shouldContainExactly listOf("reservations.reservation:$id:$today:no-show")
    }

    @Test
    fun `a closed folio refuses the no-show with 409 and everything is rolled back`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(2))
        val id = reservation.id()
        with(iam) {
            call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to rooms.single())).ok()
        }
        with(support.billing) { call(POST, "/api/v1/folios/${reservation.folio()}/close", support.admin).expect(200) }
        val before = listOf(inventory.row(type, today)!!, inventory.row(type, today.plusDays(1))!!)
        val version = support.reservation(id)["reservation"]["version"].asLong()

        val failure = shouldThrow<ConflictException> { markNoShow(id) }

        failure.code.value shouldBe "FOLIO_CLOSED"
        support.status(id) shouldBe "CONFIRMED"
        support.reservation(id)["reservation"]["version"].asLong() shouldBe version
        support.reservation(id)["reservation"]["roomAssignment"].isNull shouldBe false
        jdbc
            .sql("SELECT count(*) FROM reservations.room_assignment WHERE reservation_id = :id AND released_at IS NULL")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 1
        listOf(today, today.plusDays(1)).forEachIndexed { index, date ->
            inventory.row(type, date) shouldBe
                before[index]
        }
        support.historyKinds(id).shouldBeEmpty()
        support.postings(reservation.folio()).shouldBeEmpty()
        Thread.sleep(SETTLE_MILLIS)
        NoShowEvents.of(id).shouldBeEmpty()
    }

    @Test
    fun `without a user the audit trail records the no-show in the name of the system`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val id = created(type, plan.id, today, today.plusDays(1)).id()

        markNoShow(id) shouldNotBe null

        val entries = support.auditEntries(id).filter { it["action"] == "reservations.reservation.no-show" }
        entries shouldHaveSize 1
        entries.single()["userId"] shouldBe null
        entries.single()["approvalId"] shouldBe null
        entries.single()["before"].toString().contains("CONFIRMED") shouldBe true
        entries.single()["after"].toString().contains("NO_SHOW") shouldBe true
    }

    @Test
    fun `the posting keeps the exact source reference and the end-of-day origin`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today, today.plusDays(1))
        val id = reservation.id()

        markNoShow(id) shouldNotBe null

        jdbc
            .sql("SELECT source, source_reference, description FROM billing.posting WHERE folio_id = :folio")
            .param("folio", reservation.folio())
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
            .single() shouldBe
            Triple(
                "END_OF_DAY",
                "reservations.reservation:$id:$today:no-show",
                "Multa de no-show da reserva ${reservation["code"].asString()}",
            )
    }

    @Test
    fun `the event is published only after the commit`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val rolledBack = created(type, plan.id, today, today.plusDays(1)).id()
        val committed = created(type, plan.id, today, today.plusDays(1)).id()

        transactions.execute { status ->
            reservationsApi.markNoShow(ReservationId(rolledBack)) shouldNotBe null
            status.setRollbackOnly()
        }
        markNoShow(committed) shouldNotBe null

        await().atMost(Duration.ofSeconds(WAIT)).until { NoShowEvents.of(committed).isNotEmpty() }
        Thread.sleep(SETTLE_MILLIS)
        NoShowEvents.of(rolledBack).shouldBeEmpty()
        support.status(rolledBack) shouldBe "CONFIRMED"
        support.historyKinds(rolledBack).shouldBeEmpty()
        support.postings(folioOf(rolledBack)).shouldBeEmpty()
        NoShowEvents.of(committed) shouldHaveSize 1
    }

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

    private fun setCharged(
        id: UUID,
        amount: String,
    ) = jdbc
        .sql("UPDATE reservations.reservation SET penalties_charged = CAST(:amount AS numeric) WHERE id = :id")
        .param("amount", amount)
        .param("id", id)
        .update()

    private fun folioOf(id: UUID): UUID = UUID.fromString(support.reservation(id)["reservation"]["folioId"].asString())

    private fun sourceReferences(folio: UUID): List<String> =
        jdbc
            .sql(
                "SELECT source_reference FROM billing.posting WHERE folio_id = :folio AND source_reference IS NOT NULL",
            ).param("folio", folio)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private companion object {
        const val WAIT = 20L
        const val SETTLE_MILLIS = 1500L
    }
}
