package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.function.Supplier

/**
 * O ciclo de vida da reserva (M4 E08; RF-RES-05 e 06, RF-TAR-07, RN-14, RN-15, SPEC §8.4, H6, H15): a alteração
 * recalcula só as noites afetadas e ajusta o inventário na mesma transação, com histórico e `ReservationChanged`; o
 * cancelamento lança a multa da política copiada uma vez, na conta da reserva, ou a isenta com aprovação, devolve o
 * inventário e publica `ReservationCancelled`; a reativação é sensível e toma o inventário de novo; as transições fora
 * da SPEC §8.4 são `409`. Cada teste cria os próprios tipos de UH (banco compartilhado). O mesmo contexto dos outros
 * testes das reservas.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // as portas e os cenários do ciclo de vida
class ReservationLifecycleApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Alteração ------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a extensão inteira: preço congelado, inventário, histórico, evento e versões
    fun `extending a stay by one night keeps the frozen nights, prices only the new one and takes its unit`(
        scenario: Scenario,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        // A tarifa muda depois da criação: só a noite nova a segue.
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10), twoAdults = "550.00")

        var changed: JsonNode? = null
        scenario
            .stimulate(
                Supplier {
                    with(support.billing) {
                        support
                            .change(id, support.changeBody(reservation, departure = arrival.plusDays(3)))
                            .expect(200)
                            .also { changed = it }
                    }
                },
            ).andWaitForEventOfType(ReservationChanged::class.java)
            .matching { it.reservationId == id }
            .toArriveAndVerify { event ->
                event.previousDeparture shouldBe arrival.plusDays(2)
                event.departure shouldBe arrival.plusDays(3)
                event.changedFields shouldContainExactly listOf("departure", "nightlyRates")
                event.version shouldBe 1
            }

        val after = changed!!["reservation"]
        after["nightlyRates"].values().map { it["date"].asString() to it["roomAmount"].asString() } shouldContainExactly
            listOf(
                arrival.toString() to "400.00",
                arrival.plusDays(1).toString() to "400.00",
                arrival.plusDays(2).toString() to "550.00",
            )
        after["nightlyRates"][0]["calculatedRoomAmount"].asString() shouldBe "400.00"
        after["total"].asString() shouldBe "1350.00"
        after["version"].asLong() shouldBe 1
        support.etag(id) shouldBe "\"1\""
        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(2))!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(3))!!.sold shouldBe 0

        // O histórico: a alteração com os campos e as noites antes e depois.
        val history = with(iam) { call(GET, "/api/v1/reservations/$id/history", support.admin).ok() }
        val entry = history["items"].values().single()
        entry["kind"].asString() shouldBe "CHANGE"
        entry["changedFields"].values().map { it.asString() } shouldContainExactly listOf("departure", "nightlyRates")
        entry["before"]["nights"].size() shouldBe 2
        entry["after"]["nights"].size() shouldBe 3
        entry["after"]["total"].asString() shouldBe "1350.00"
        entry["businessDate"].asString() shouldBe today.toString()
        support.lifecyclePublications(ReservationChanged::class.java, id) shouldHaveSize 1
        support.auditEntries(id).map { it["action"] } shouldContainExactly
            listOf("reservations.booking.create", "reservations.reservation.change")
    }

    @Test
    fun `the change asks for If-Match and refuses a stale version, and changing nothing changes nothing`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        val id = reservation.id()
        val body = support.changeBody(reservation, departure = today.plusDays(4))

        with(support.billing) {
            support.change(id, body, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
            support.change(id, body, ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")
            // Sem diferença: a mesma versão, sem histórico nem evento.
            support.change(id, support.changeBody(reservation)).expect(200)["reservation"]["version"].asLong() shouldBe
                0
        }
        support.historyKinds(id).shouldBeEmpty()
        support.lifecyclePublications(ReservationChanged::class.java, id).shouldBeEmpty()
    }

    @Test
    @Suppress("LongMethod") // o tipo sem unidade, nada muda; o tipo com unidade, troca na mesma transação
    fun `changing the room type releases the old units and takes the new ones together, and nothing changes without`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val (full, _) = inventory.roomTypeWithRooms(1, today)
        val (other, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type, full, other))
        support.setPrices(plan.id, listOf(type, full), today, today.plusDays(10))
        support.setPrices(plan.id, listOf(other), today, today.plusDays(10), twoAdults = "480.00")
        val arrival = today.plusDays(3)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        // O tipo cheio na segunda noite.
        created(full, plan.id, arrival.plusDays(1), arrival.plusDays(2))

        val refused =
            with(support.billing) {
                support.change(id, support.changeBody(reservation, type = full)).problem(409, "ROOM_TYPE_UNAVAILABLE")
            }
        refused["dates"].values().map { it.asString() } shouldContainExactly listOf(arrival.plusDays(1).toString())
        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(full, arrival)!!.sold shouldBe 0
        inventory.row(full, arrival.plusDays(1))!!.sold shouldBe 1
        support.reservation(id)["reservation"]["version"].asLong() shouldBe 0
        support.historyKinds(id).shouldBeEmpty()

        val changed =
            with(support.billing) {
                support.change(id, support.changeBody(reservation, type = other)).expect(200)["reservation"]
            }
        changed["roomTypeId"].asString() shouldBe other.toString()
        // O tipo novo reprecifica todas as noites.
        changed["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("480.00", "480.00")
        listOf(arrival, arrival.plusDays(1)).forEach {
            inventory.row(type, it)!!.sold shouldBe 0
            inventory.row(other, it)!!.sold shouldBe 1
        }
    }

    @Test
    fun `a sale rule that did not change does not block the change, but a new plan must be sellable`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val inactive = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        support.setPrices(inactive.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today.plusDays(2), today.plusDays(4))
        val id = reservation.id()
        deactivate(plan.id)
        deactivate(inactive.id)

        with(support.billing) {
            // O plano desativado depois da venda não impede mudar a ocupação (todas as noites são recalculadas).
            val changed = support.change(id, support.changeBody(reservation, adults = 1)).expect(200)["reservation"]
            changed["adults"].asInt() shouldBe 1
            changed["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
                listOf("300.00", "300.00")
            // Trocar para outro plano desativado é uma venda nova dele.
            val problem =
                support
                    .change(id, support.changeBody(changed, plan = inactive.id))
                    .problem(422, "RATE_PLAN_INACTIVE")
            problem.has("reservationIndex") shouldBe false
            problem["refusals"].values().map { it["code"].asString() } shouldContainExactly listOf("RATE_PLAN_INACTIVE")
        }
    }

    @Test
    fun `a discount on a repriced night asks for approval, and a frozen night cannot be edited`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(manage()))

        with(support.billing) {
            support
                .change(
                    id,
                    support.changeBody(
                        reservation,
                        departure = arrival.plusDays(3),
                        nightlyRates = listOf(arrival to "300.00"),
                    ),
                    auth = receptionist.auth,
                ).problem(400, "NIGHTLY_RATE_INVALID")["errors"][0]["field"]
                .asString() shouldBe "nightlyRates[0].date"
            val required =
                support
                    .change(
                        id,
                        support.changeBody(
                            reservation,
                            departure = arrival.plusDays(3),
                            nightlyRates = listOf(arrival.plusDays(2) to "300.00"),
                        ),
                        auth = receptionist.auth,
                    ).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "reservations.reservation.change"
            required["permission"].asString() shouldBe "rates.discounts.apply"
            required["resourceRef"].asString() shouldBe "reservations.reservation:$id"
            BigDecimal(required["limit"]["amount"].asString()).compareTo(BigDecimal("25")) shouldBe 0
        }
        inventory.row(type, arrival.plusDays(2))!!.sold shouldBe 0
        support.reservation(id)["reservation"]["version"].asLong() shouldBe 0
    }

    @Test
    fun `moving a provisional reservation to a plan without a guarantee confirms it and copies the new policies`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val deposit = support.plan(listOf(type), guarantee = "DEPOSIT")
        val plain = support.plan(listOf(type))
        support.setPrices(deposit.id, listOf(type), today, today.plusDays(10))
        support.setPrices(plain.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, deposit.id, today.plusDays(3), today.plusDays(4))
        reservation["status"].asString() shouldBe "PROVISIONAL"

        val changed =
            with(support.billing) {
                support.change(reservation.id(), support.changeBody(reservation, plan = plain.id)).expect(200)
            }["reservation"]

        changed["status"].asString() shouldBe "CONFIRMED"
        changed["provisionalUntil"].isNull shouldBe true
        changed["cancellationTerms"]["policyId"].asString() shouldBe plain.cancellationPolicy.toString()
        changed["guaranteeTerms"]["type"].asString() shouldBe "NONE"
        support.reservation(reservation.id())["reservation"]["status"].asString() shouldBe "CONFIRMED"
    }

    // --- Cancelamento ---------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a multa pela política copiada, uma vez, na conta; inventário, histórico, evento, trilha
    fun `cancelling past the deadline posts the copied penalty once, to the reservation folio, and frees the units`(
        scenario: Scenario,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = penaltyAlways(nights = 1))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        // A política muda depois: vale a copiada (uma noite de 400,00), não a atual.
        editPolicy(
            plan.cancellationPolicy,
            support.rates.cancellationBody(
                penaltyType = "FIXED_AMOUNT",
                penaltyNights = null,
                penaltyAmount = "999.00",
                deadlineHours = 8760,
            ),
        )
        val key = UuidV7.generate().toString()

        var cancelled: JsonNode? = null
        scenario
            .stimulate(
                Supplier {
                    with(support.billing) {
                        support
                            .cancel(id, notes = "Hóspede Conceição desistiu", idempotencyKey = key)
                            .expect(200)
                            .also { cancelled = it }
                    }
                },
            ).andWaitForEventOfType(ReservationCancelled::class.java)
            .matching { it.reservationId == id }
            .toArriveAndVerify { event ->
                event.previousStatus shouldBe ReservationStatus.CONFIRMED
                event.reason shouldBe CancellationReason.GUEST_REQUEST
                event.penaltyCharged shouldBe true
                event.penaltyWaived shouldBe false
            }

        val after = cancelled!!["reservation"]
        after["status"].asString() shouldBe "CANCELLED"
        after["cancellation"]["penalty"]["amount"].asString() shouldBe "400.00"
        after["cancellation"]["penalty"]["waived"].asBoolean() shouldBe false
        after["cancellation"]["notes"].asString() shouldBe "Hóspede Conceição desistiu"
        after["penaltiesCharged"].asString() shouldBe "400.00"
        val postings = support.postings(folio)
        postings shouldHaveSize 1
        postings.single().let {
            it["key"] shouldBe "CANCELLATION_FEE"
            it["amount"] shouldBe "400.00"
            it["type"] shouldBe "REGULAR"
            it["source"] shouldBe "MANUAL"
            it["businessDate"] shouldBe today
        }
        after["cancellation"]["penalty"]["postingId"].isNull shouldBe false
        billingApi.folio(FolioId(folio))!!.balance.toString() shouldBe "400.00"
        inventory.row(type, arrival)!!.sold shouldBe 0
        inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 0

        // A repetição com a mesma chave devolve a resposta guardada; com outra, a reserva já está cancelada.
        with(support.billing) {
            support.cancel(id, notes = "Hóspede Conceição desistiu", idempotencyKey = key).let {
                it.status shouldBe 200
                it.getHeader("Idempotent-Replayed") shouldBe "true"
            }
            val again = support.cancel(id).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            again["reservationStatus"].asString() shouldBe "CANCELLED"
            again["action"].asString() shouldBe "CANCEL"
        }
        support.postings(folio) shouldHaveSize 1
        support.historyKinds(id) shouldContainExactly listOf("CANCEL")
        support.lifecyclePublications(ReservationCancelled::class.java, id) shouldHaveSize 1
        // A observação nunca vai à trilha, ao histórico nem ao evento.
        support.auditEntries(id).joinToString { "${it["before"]} ${it["after"]}" } shouldNotContain "Conceição"
        historyJson(id) shouldNotContain "Conceição"
        support.lifecyclePublications(ReservationCancelled::class.java, id).single() shouldNotContain "Conceição"
    }

    @Test
    fun `cancelling before the deadline has no penalty, and the percent of the stay is over the frozen room rates`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val free = support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(deadlineHours = 0))
        val percent =
            support.plan(
                listOf(type),
                cancellationBody =
                    support.rates.cancellationBody(
                        deadlineHours = 8760,
                        penaltyType = "PERCENT_OF_STAY",
                        penaltyNights = null,
                        penaltyPercent = "15.0000",
                    ),
            )
        support.setPrices(free.id, listOf(type), today, today.plusDays(10))
        support.setPrices(percent.id, listOf(type), today, today.plusDays(10), twoAdults = "333.33")
        val early = created(type, free.id, today.plusDays(5), today.plusDays(6))
        val late = created(type, percent.id, today.plusDays(5), today.plusDays(8))

        val freeCancelled = with(support.billing) { support.cancel(early.id()).expect(200)["reservation"] }
        freeCancelled["cancellation"]["penalty"]["amount"].asString() shouldBe "0.00"
        freeCancelled["cancellation"]["penalty"]["postingId"].isNull shouldBe true
        support.postings(UUID.fromString(early["folioId"].asString())).shouldBeEmpty()

        // 15% de 999,99 = 149,9985: 150,00 (HALF_EVEN).
        val charged = with(support.billing) { support.cancel(late.id()).expect(200)["reservation"] }
        charged["cancellation"]["penalty"]["amount"].asString() shouldBe "150.00"
        support.postings(UUID.fromString(late["folioId"].asString())).single()["amount"] shouldBe "150.00"
    }

    @Test
    fun `a failure after the penalty is posted undoes everything, the penalty, the cancellation and the units`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = penaltyAlways(nights = 1))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(1))
        val id = ReservationId(reservation.id())

        shouldThrow<SimulatedFailure> {
            transactions.executeWithoutResult {
                val cancelled =
                    reservationsApi.cancelReservation(id, ReservationCancellationRequest(CancellationReason.OTHER))
                cancelled.cancellation!!.penalty.postingId shouldNotBe null
                throw SimulatedFailure()
            }
        }

        support.status(reservation.id()) shouldBe "CONFIRMED"
        support.postings(UUID.fromString(reservation["folioId"].asString())).shouldBeEmpty()
        inventory.row(type, arrival)!!.sold shouldBe 1
        support.historyKinds(reservation.id()).shouldBeEmpty()
        // Pela API pública, de fato.
        reservationsApi
            .cancelReservation(id, ReservationCancellationRequest(CancellationReason.OTHER))
            .penaltiesCharged
            .toString() shouldBe "400.00"
    }

    @Test
    @Suppress("LongMethod") // a isenção sem permissão, a aprovação e a trilha com solicitante e aprovador
    fun `waiving the penalty without the permission asks for approval, and the approved waiver is audited`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = penaltyAlways(nights = 1))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(manage()))
        val approver = approver()

        val required =
            with(support.billing) {
                support.cancel(id, waivePenalty = true, auth = receptionist.auth).problem(403, "APPROVAL_REQUIRED")
            }
        required["action"].asString() shouldBe "reservations.reservation.cancel"
        required["permission"].asString() shouldBe "reservations.penalties.waive"
        required["resourceRef"].asString() shouldBe "reservations.reservation:$id"
        support.status(id) shouldBe "CONFIRMED"
        inventory.row(type, arrival)!!.sold shouldBe 1

        val approval = approve(required, receptionist, approver)
        val waived =
            with(support.billing) {
                support
                    .cancel(
                        id,
                        waivePenalty = true,
                        auth = receptionist.auth,
                        approvalToken = approval["approvalToken"].asString(),
                    ).expect(200)["reservation"]
            }
        waived["cancellation"]["penalty"]["waived"].asBoolean() shouldBe true
        waived["cancellation"]["penalty"]["amount"].asString() shouldBe "400.00"
        waived["cancellation"]["penalty"]["postingId"].isNull shouldBe true
        waived["cancellation"]["penalty"]["waiverApprovalId"].asString() shouldBe approval["approvalId"].asString()
        waived["cancellation"]["penalty"]["waiverApprovedBy"].asString() shouldBe approver.id.toString()
        waived["penaltiesCharged"].asString() shouldBe "0.00"
        support.postings(folio).shouldBeEmpty()
        inventory.row(type, arrival)!!.sold shouldBe 0
        val trail = support.auditEntries(id).filter { it["action"] == "reservations.reservation.cancel" }
        trail.single()["userId"] shouldBe receptionist.user.id.value
        trail.single()["approvalId"].toString() shouldBe approval["approvalId"].asString()
        historyApproval(id) shouldBe approval["approvalId"].asString()

        // Quem tem a permissão isenta direto; sem multa devida, ninguém precisa dela.
        val other = created(type, plan.id, arrival, arrival.plusDays(2))
        with(support.billing) {
            support
                .cancel(
                    other.id(),
                    waivePenalty = true,
                ).expect(200)["reservation"]["cancellation"]["penalty"]["waived"]
                .asBoolean() shouldBe true
        }
        val free = support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(deadlineHours = 0))
        support.setPrices(free.id, listOf(type), today, today.plusDays(10))
        val early = created(type, free.id, today.plusDays(6), today.plusDays(7))
        with(support.billing) {
            support
                .cancel(
                    early.id(),
                    waivePenalty = true,
                    auth = receptionist.auth,
                ).expect(200)["reservation"]["cancellation"]["penalty"]["waived"]
                .asBoolean() shouldBe false
        }
    }

    // --- Reativação -----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a reativação sem aprovação, sem unidade, com as duas, e o cancelamento de novo
    fun `reactivating needs approval and a unit, and a second cancellation never charges the penalty twice`(
        scenario: Scenario,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), cancellationBody = penaltyAlways(nights = 1))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = reservation.id()
        val folio = UUID.fromString(reservation["folioId"].asString())
        with(support.billing) { support.cancel(id).expect(200) }
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(manage()))
        val approver = approver()

        val required =
            with(support.billing) { support.reactivate(id, auth = receptionist.auth).problem(403, "APPROVAL_REQUIRED") }
        required["action"].asString() shouldBe "reservations.reservation.reactivate"
        required["permission"].asString() shouldBe "reservations.reservations.reactivate"
        // Outra reserva tomou a unidade: com a aprovação, ainda 409, e nada muda.
        val blocker = created(type, plan.id, arrival.plusDays(1), arrival.plusDays(2))
        val approval = approve(required, receptionist, approver)
        with(support.billing) {
            support
                .reactivate(id, auth = receptionist.auth, approvalToken = approval["approvalToken"].asString())
                .problem(409, "ROOM_TYPE_UNAVAILABLE")
        }
        support.status(id) shouldBe "CANCELLED"
        inventory.row(type, arrival)!!.sold shouldBe 0

        with(support.billing) { support.cancel(blocker.id()).expect(200) }
        var reactivated: JsonNode? = null
        scenario
            .stimulate(
                Supplier {
                    with(support.billing) {
                        // O uso do token desfeito pelo 409 volta a valer (outra Idempotency-Key).
                        support
                            .reactivate(
                                id,
                                auth = receptionist.auth,
                                approvalToken = approval["approvalToken"].asString(),
                            ).expect(200)
                            .also { reactivated = it }
                    }
                },
            ).andWaitForEventOfType(ReservationReactivated::class.java)
            .matching { it.reservationId == id }
            .toArriveAndVerify { event -> event.previousStatus shouldBe ReservationStatus.CANCELLED }

        val after = reactivated!!["reservation"]
        after["status"].asString() shouldBe "CONFIRMED"
        after["cancellation"].isNull shouldBe true
        after["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("400.00", "400.00")
        after["penaltiesCharged"].asString() shouldBe "400.00"
        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 1
        val trail = support.auditEntries(id).filter { it["action"] == "reservations.reservation.reactivate" }
        trail.single()["userId"] shouldBe receptionist.user.id.value
        trail.single()["approvalId"].toString() shouldBe approval["approvalId"].asString()

        // Cancelada de novo: a multa já foi lançada, nada mais é devido.
        val again = with(support.billing) { support.cancel(id).expect(200)["reservation"] }
        again["cancellation"]["penalty"]["amount"].asString() shouldBe "0.00"
        support.postings(folio) shouldHaveSize 1
        support.historyKinds(id) shouldContainExactly listOf("CANCEL", "REACTIVATE", "CANCEL")
    }

    // --- Transições -----------------------------------------------------------------------------------------------

    @Test
    fun `transitions outside the state machine are 409 with a stable code`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val confirmed = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        val checkedOut = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        val inHouse = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        support.forceCheckedOut(checkedOut.id())
        support.forceInHouse(inHouse.id(), rooms.first())

        with(support.billing) {
            support
                .reactivate(confirmed.id())
                .problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")["reservationStatus"]
                .asString() shouldBe "CONFIRMED"
            support
                .change(checkedOut.id(), support.changeBody(checkedOut, departure = today.plusDays(4)))
                .problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")["action"]
                .asString() shouldBe "CHANGE"
            support.cancel(checkedOut.id()).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            support.cancel(inHouse.id()).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            support.reactivate(inHouse.id()).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            support.cancel(confirmed.id()).expect(200)
            support
                .change(confirmed.id(), support.changeBody(confirmed, departure = today.plusDays(4)))
                .problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            support.cancel(UuidV7.generate()).problem(404, "RESERVATION_NOT_FOUND")
        }
    }

    // --- Histórico, permissões e catálogo ------------------------------------------------------------------------

    @Test
    fun `the history is append-only for the application role and for the owner`(
        @Autowired flyway: Flyway,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        with(support.billing) { support.cancel(reservation.id()).expect(200) }
        val owner = JdbcClient.create(flyway.configuration.dataSource)

        listOf(
            "UPDATE reservations.reservation_history SET version = 9 WHERE reservation_id = :id",
            "DELETE FROM reservations.reservation_history WHERE reservation_id = :id",
        ).forEach { sql ->
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult { jdbc.sql(sql).param("id", reservation.id()).update() }
            }.mostSpecificCause.message shouldContain "permission denied"
            shouldThrow<DataAccessException> { owner.sql(sql).param("id", reservation.id()).update() }
                .mostSpecificCause.message shouldContain "imutável"
        }
        support.historyKinds(reservation.id()) shouldContainExactly listOf("CANCEL")
    }

    @Test
    fun `changing and cancelling need reservations manage, the history needs reservations read`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today.plusDays(2), today.plusDays(3))
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.RESERVATIONS_READ), ALL))
        val nobody = iam.createUser()

        with(support.billing) {
            support
                .change(reservation.id(), support.changeBody(reservation, adults = 1), auth = iam.asTestUser(reader))
                .problem(403, "PERMISSION_DENIED")
            support.cancel(reservation.id(), auth = iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            support.reactivate(reservation.id(), auth = iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
        }
        with(iam) {
            call(GET, "/api/v1/reservations/${reservation.id()}/history", asTestUser(reader)).ok()
            call(GET, "/api/v1/reservations/${reservation.id()}/history", asTestUser(nobody))
                .problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/reservations/${UuidV7.generate()}/history", asAdmin())
                .problem(404, "RESERVATION_NOT_FOUND")
        }
        listOf(ReservationsPermissions.PENALTIES_WAIVE, ReservationsPermissions.RESERVATIONS_REACTIVATE).forEach {
            val definition = definition(it)
            definition.sensitive shouldBe true
            definition.templates.keys shouldContainExactly setOf(RoleTemplate.MANAGER)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private class SimulatedFailure : RuntimeException("falha simulada depois do lançamento")

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, departure)),
        )["reservations"][0]

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    /** Multa sempre devida (prazo de um ano antes do check-in), de [nights] diárias. */
    private fun penaltyAlways(nights: Int) =
        support.rates.cancellationBody(deadlineHours = 8760, penaltyNights = nights)

    private fun editPolicy(
        id: UUID,
        body: Map<String, Any?>,
    ) = with(iam) {
        val path = "/api/v1/cancellation-policies/$id"
        call(PUT, path, support.admin, body, ifMatch = support.rates.etagOf(path)).ok()
    }

    private fun deactivate(plan: UUID) =
        with(iam) { call(POST, "/api/v1/rate-plans/$plan/deactivate", support.admin).ok() }

    private fun manage() = TestGrant(definition(ReservationsPermissions.RESERVATIONS_MANAGE), ALL)

    private fun approver(): TestUser =
        iam
            .createUserWith(
                TestGrant(definition(ReservationsPermissions.PENALTIES_WAIVE), ALL),
                TestGrant(definition(ReservationsPermissions.RESERVATIONS_REACTIVATE), ALL),
                TestGrant(
                    RatesTestSupport.definition(RatesPermissions.DISCOUNTS_APPLY),
                    ALL,
                    mapOf(RatesPermissions.MAX_PERCENT to LimitValue.Unlimited),
                ),
            ).also { iam.setPin(it, APPROVER_PIN) }

    private fun approve(
        problem: JsonNode,
        requester: ReservationsTestSupport.Operator,
        approver: TestUser,
    ): JsonNode =
        with(iam) {
            val body =
                buildMap {
                    put("action", problem["action"].asString())
                    put("permission", problem["permission"].asString())
                    put("resourceRef", problem["resourceRef"].asString())
                    problem["limit"]?.takeUnless { it.isNull }?.let {
                        put("limit", mapOf("key" to it["key"].asString(), "amount" to it["amount"].asString()))
                    }
                    put("approverLogin", approver.login)
                    put("approverPin", APPROVER_PIN)
                    put("reason", "Cliente frequente")
                }
            call(POST, "/api/v1/auth/approvals", requester.auth, body).expectStatus(201)!!
        }

    private fun historyJson(id: UUID): String =
        jdbc
            .sql(
                "SELECT string_agg(before_state::text || after_state::text, ' ') " +
                    "FROM reservations.reservation_history " +
                    "WHERE reservation_id = :id",
            ).param("id", id)
            .query(String::class.java)
            .single()

    private fun historyApproval(id: UUID): String =
        jdbc
            .sql("SELECT approval_id::text FROM reservations.reservation_history WHERE reservation_id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private companion object {
        const val APPROVER_PIN = "5174"

        fun definition(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }
    }
}
