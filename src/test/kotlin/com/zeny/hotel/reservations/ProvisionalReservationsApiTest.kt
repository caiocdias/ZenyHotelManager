package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.application.ProvisionalExpiryService
import com.zeny.hotel.reservations.infrastructure.ProvisionalExpiryJob
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.PROVISIONAL_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.asUser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pré-reserva (M4 E09; RF-RES-08 no recorte manual, RN-23, RF-TAR-07, H13, H15): a confirmação com a garantia da
 * política copiada, conferindo o depósito líquido da conta da reserva pela `BillingApi` (o reembolsado não conta); o
 * job de expiração (adiado no `@IntegrationTest` e chamado direto, com o `MutableClock` para vencer a validade) que
 * cancela só as vencidas sem multa, devolve o inventário e publica `ReservationCancelled` com `EXPIRED`, uma vez; a
 * confirmação e a expiração simultâneas que terminam num único estado; a lista das pré-reservas que vencem num
 * intervalo. Cada teste cria os próprios tipos de UH e devolve o relógio no fim (o banco e o contexto são
 * compartilhados); o job varre todas as pré-reservas do banco, então as asserções olham só as do próprio teste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // as portas e os cenários da pré-reserva
class ProvisionalReservationsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val expiryJob: ProvisionalExpiryJob,
    @Autowired private val expiry: ProvisionalExpiryService,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val validity: Duration get() = hotelApi.operationalSettings().preReservationExpiry

    // --- Confirmação ----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o depósito recebido, o insuficiente, o reembolsado e a confirmação com a trilha
    fun `a deposit pre-reservation is confirmed only with the net deposit of its folio covering the policy`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "DEPOSIT")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(10)
        val created = created(type, plan.id, arrival, arrival.plusDays(2))
        val id = created.id()
        val folio = UUID.fromString(created["folioId"].asString())
        created["status"].asString() shouldBe "PROVISIONAL"
        // 2 noites de 400 = 800; a política exige 30%.
        created["requiredDeposit"].asString() shouldBe "240.00"
        created["depositDueOn"].asString() shouldBe arrival.minusDays(7).toString()
        created["guarantee"].isNull shouldBe true

        with(support.billing) {
            // A política pede depósito: o cartão informado não serve.
            support.confirm(id, "CARD").problem(422, "GUARANTEE_METHOD_NOT_ACCEPTED")

            val cashier = cashier()
            openShift(cashier)
            val first = pay(folio, cashier, mapOf("method" to "PIX", "amount" to "200.00", "deposit" to true))
            first.expect(201)
            // Um pagamento comum não é depósito.
            pay(folio, cashier, mapOf("method" to "CASH", "amount" to "100.00")).expect(201)
            val short = support.confirm(id, "DEPOSIT").problem(422, "DEPOSIT_INSUFFICIENT")
            short["requiredDeposit"].asString() shouldBe "240.00"
            short["depositReceived"].asString() shouldBe "200.00"

            // Mais 100 de depósito e um reembolso de 61 dele: o líquido (239,00) ainda não cobre.
            val second =
                pay(folio, cashier, mapOf("method" to "PIX", "amount" to "100.00", "deposit" to true)).expect(201)
            refund(folio, second.uuid("id"), "61.00", cashier).expect(201)
            support.confirm(id, "DEPOSIT").problem(422, "DEPOSIT_INSUFFICIENT")["depositReceived"].asString() shouldBe
                "239.00"
            support.status(id) shouldBe "PROVISIONAL"

            pay(folio, cashier, mapOf("method" to "PIX", "amount" to "1.00", "deposit" to true)).expect(201)
            val confirmedResponse = support.confirm(id, "DEPOSIT")
            confirmedResponse.getHeader("ETag") shouldBe "\"1\""
            val confirmed = confirmedResponse.expect(200)["reservation"]
            confirmed["status"].asString() shouldBe "CONFIRMED"
            confirmed["provisionalUntil"].isNull shouldBe true
            confirmed["guarantee"]["method"].asString() shouldBe "DEPOSIT"
            confirmed["guarantee"]["requiredDeposit"].asString() shouldBe "240.00"
            confirmed["guarantee"]["depositReceived"].asString() shouldBe "240.00"
            confirmed["guarantee"]["confirmedBy"].isNull shouldBe false
            confirmed["version"].asLong() shouldBe 1

            // Confirmar de novo: a reserva já não é pré-reserva.
            support.confirm(id, "DEPOSIT").problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
        }
        support.historyKinds(id) shouldContainExactly listOf("CONFIRM")
        val entry = with(iam) { call(GET, "/api/v1/reservations/$id/history", support.admin).ok() }["items"][0]
        entry["changedFields"].values().map { it.asString() } shouldContainExactly listOf("status", "guarantee")
        entry["before"]["status"].asString() shouldBe "PROVISIONAL"
        entry["after"]["guaranteeMethod"].asString() shouldBe "DEPOSIT"
        support.lifecyclePublications(ReservationConfirmed::class.java, id) shouldHaveSize 1
        support.auditEntries(id).map { it["action"] } shouldContainExactly
            listOf("reservations.booking.create", "reservations.reservation.confirm")
        // A confirmação não mexe no inventário.
        inventory.row(type, arrival)!!.sold shouldBe 1
    }

    @Test
    fun `a card pre-reservation is confirmed with the card informed, with the manage permission`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val card = support.plan(listOf(type), guarantee = "CARD")
        val none = support.plan(listOf(type))
        support.setPrices(card.id, listOf(type), today, today.plusDays(20))
        support.setPrices(none.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(5)
        val reservation = created(type, card.id, arrival, arrival.plusDays(1))
        val id = reservation.id()
        reservation["requiredDeposit"].isNull shouldBe true

        val reader =
            iam.createUserWith(TestGrant(permission(ReservationsPermissions.RESERVATIONS_READ), ALL))
        with(support.billing) {
            support.confirm(id, "CARD", auth = iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            support.confirm(id, "DEPOSIT").problem(422, "GUARANTEE_METHOD_NOT_ACCEPTED")
            val confirmed = support.confirm(id, "CARD").expect(200)["reservation"]
            confirmed["status"].asString() shouldBe "CONFIRMED"
            confirmed["guarantee"]["method"].asString() shouldBe "CARD"
            confirmed["guarantee"]["requiredDeposit"].isNull shouldBe true

            // A reserva num plano sem garantia nasce confirmada: não há o que confirmar.
            val direct = created(type, none.id, arrival.plusDays(2), arrival.plusDays(3))
            direct["status"].asString() shouldBe "CONFIRMED"
            support.confirm(direct.id(), "CARD").problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            support.confirm(UuidV7.generate(), "CARD").problem(404, "RESERVATION_NOT_FOUND")
            // O motivo "expirada" é só do sistema.
            support.cancel(id, reason = "EXPIRED").problem(400, "RESERVATION_CANCELLATION_INVALID")
        }
    }

    // --- Expiração ------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a validade, a recusa da confirmação, o job, o inventário, o evento e a repetição
    fun `the job cancels only the due pre-reservations, without penalty, gives the units back and runs once`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        // Multa sempre devida pela política: a expiração não a cobra.
        val plan =
            support.plan(
                listOf(type),
                guarantee = "DEPOSIT",
                cancellationBody = support.rates.cancellationBody(deadlineHours = 8760, penaltyNights = 1),
            )
        val none = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        support.setPrices(none.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(3)
        val start = clock.instant()
        try {
            val due = created(type, plan.id, arrival, arrival.plusDays(2))
            clock.advance(Duration.ofHours(1))
            val later = created(type, plan.id, arrival, arrival.plusDays(2))
            val confirmed = created(type, none.id, arrival, arrival.plusDays(2))
            val dueId = due.id()
            Instant.parse(due["provisionalUntil"].asString()) shouldBe
                start.truncatedTo(ChronoUnit.MICROS).plus(validity)
            inventory.row(type, arrival)!!.sold shouldBe 3

            // Um microssegundo antes da validade, nada vence.
            clock.advance(validity.minusHours(1).minusNanos(1_000))
            expiryJob.expireProvisionalReservations()
            support.status(dueId) shouldBe "PROVISIONAL"

            // Na validade: a confirmação é recusada e o job a cancela.
            clock.advance(Duration.ofNanos(1_000))
            with(support.billing) {
                support.confirm(dueId, "DEPOSIT").problem(422, "PROVISIONAL_RESERVATION_EXPIRED")
            }
            expiryJob.expireProvisionalReservations()

            val expired = support.reservation(dueId)["reservation"]
            expired["status"].asString() shouldBe "CANCELLED"
            expired["provisionalUntil"].isNull shouldBe true
            expired["cancellation"]["reason"].asString() shouldBe "EXPIRED"
            expired["cancellation"]["previousStatus"].asString() shouldBe "PROVISIONAL"
            expired["cancellation"]["cancelledBy"].isNull shouldBe true
            expired["cancellation"]["penalty"]["amount"].asString() shouldBe "0.00"
            expired["cancellation"]["penalty"]["waived"].asBoolean() shouldBe false
            support.postings(UUID.fromString(expired["folioId"].asString())).shouldBeEmpty()
            support.status(later.id()) shouldBe "PROVISIONAL"
            support.status(confirmed.id()) shouldBe "CONFIRMED"
            inventory.row(type, arrival)!!.sold shouldBe 2
            inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 2

            support.historyKinds(dueId) shouldContainExactly listOf("CANCEL")
            val publications = support.lifecyclePublications(ReservationCancelled::class.java, dueId)
            publications shouldHaveSize 1
            publications.single() shouldContain "\"reason\":\"EXPIRED\""
            publications.single() shouldContain "\"penaltyCharged\":false"
            val audit = support.auditEntries(dueId).last()
            audit["action"] shouldBe "reservations.reservation.expire"
            audit["userId"].shouldBeNull()

            // A segunda execução não muda nada.
            expiryJob.expireProvisionalReservations()
            support.reservation(dueId)["reservation"]["version"].asLong() shouldBe 1
            support.historyKinds(dueId) shouldContainExactly listOf("CANCEL")
            support.lifecyclePublications(ReservationCancelled::class.java, dueId) shouldHaveSize 1
            support.auditEntries(dueId).map { it["action"] } shouldContainExactly
                listOf("reservations.booking.create", "reservations.reservation.expire")
            inventory.row(type, arrival)!!.sold shouldBe 2

            // Uma hora depois vence a outra.
            clock.advance(Duration.ofHours(1))
            expiryJob.expireProvisionalReservations()
            support.status(later.id()) shouldBe "CANCELLED"
            support.status(confirmed.id()) shouldBe "CONFIRMED"
            inventory.row(type, arrival)!!.sold shouldBe 1
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `the job runs under ShedLock and does nothing while another instance holds the lock`() {
        val method = ProvisionalExpiryJob::class.java.getMethod("expireProvisionalReservations")
        method.getAnnotation(SchedulerLock::class.java).name shouldBe "reservations-provisional-expiry"
        method.getAnnotation(Scheduled::class.java).fixedDelayString shouldContain "provisional-expiry.interval"
        method.getAnnotation(Scheduled::class.java).initialDelayString shouldContain "provisional-expiry.initial-delay"
        PROVISIONAL_EXPIRY_DEFERRED shouldContain "PT24H"

        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val start = clock.instant()
        try {
            val id = created(type, plan.id, today.plusDays(2), today.plusDays(3)).id()
            clock.advance(validity)
            // Outra instância segura a trava: esta execução não faz nada.
            holdShedLock(Duration.ofMinutes(10))
            expiryJob.expireProvisionalReservations()
            support.status(id) shouldBe "PROVISIONAL"

            holdShedLock(Duration.ZERO)
            expiryJob.expireProvisionalReservations()
            support.status(id) shouldBe "CANCELLED"
        } finally {
            holdShedLock(Duration.ZERO)
            rewind(start)
        }
    }

    // --- Corrida entre a confirmação e a expiração ------------------------------------------------------------------

    @Test
    fun `a confirmation holding the reservation makes the expiry wait, and the expiry then leaves it confirmed`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(4)
        val start = clock.instant()
        try {
            val id = created(type, plan.id, arrival, arrival.plusDays(1)).id()
            // A confirmação passa (ainda na validade) e segura a reserva até o teste a soltar.
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val confirmation =
                CompletableFuture.supplyAsync {
                    transactions.execute {
                        reservationsApi.confirmReservation(ReservationId(id), GuaranteeMethod.CARD).also {
                            locked.countDown()
                            release.await(TIMEOUT, TimeUnit.SECONDS)
                        }
                    }
                }
            locked.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
            // A validade vence enquanto a confirmação não terminou: o job vê a pré-reserva vencida e espera a trava.
            clock.advance(validity)
            val expiring = CompletableFuture.supplyAsync { expiry.expire(ReservationId(id)) }
            await().atMost(Duration.ofSeconds(TIMEOUT)).until { reservationLockWaits() > 0 }

            release.countDown()
            confirmation.get(TIMEOUT, TimeUnit.SECONDS)!!.status shouldBe ReservationStatus.CONFIRMED
            expiring.get(TIMEOUT, TimeUnit.SECONDS) shouldBe false

            support.status(id) shouldBe "CONFIRMED"
            inventory.row(type, arrival)!!.sold shouldBe 1
            support.historyKinds(id) shouldContainExactly listOf("CONFIRM")
            support.lifecyclePublications(ReservationCancelled::class.java, id).shouldBeEmpty()
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `an expiry holding the reservation makes the confirmation wait, and the confirmation is then refused`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(4)
        val start = clock.instant()
        try {
            val id = created(type, plan.id, arrival, arrival.plusDays(1)).id()
            clock.advance(validity)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val expiring =
                CompletableFuture.supplyAsync {
                    transactions.execute {
                        expiry.expire(ReservationId(id)).also {
                            locked.countDown()
                            release.await(TIMEOUT, TimeUnit.SECONDS)
                        }
                    }
                }
            locked.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
            // A confirmação começa antes de a expiração confirmar (como se ainda estivesse na validade) e espera.
            clock.advance(validity.negated())
            val confirmation =
                CompletableFuture.supplyAsync {
                    runCatching {
                        transactions.execute {
                            reservationsApi.confirmReservation(ReservationId(id), GuaranteeMethod.CARD)
                        }
                    }
                }
            await().atMost(Duration.ofSeconds(TIMEOUT)).until { reservationLockWaits() > 0 }

            release.countDown()
            expiring.get(TIMEOUT, TimeUnit.SECONDS) shouldBe true
            val refused = confirmation.get(TIMEOUT, TimeUnit.SECONDS).exceptionOrNull() as ConflictException
            refused.code shouldBe ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED

            support.status(id) shouldBe "CANCELLED"
            inventory.row(type, arrival)!!.sold shouldBe 0
            support.historyKinds(id) shouldContainExactly listOf("CANCEL")
            support.lifecyclePublications(ReservationConfirmed::class.java, id).shouldBeEmpty()
        } finally {
            rewind(start)
        }
    }

    // --- Vencimentos ----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o intervalo, o padrão do alerta, os excluídos e o depósito de cada uma
    fun `the pending guarantees bring the pre-reservations whose validity falls in the interval, with the deposit`() {
        val (type, _) = inventory.roomTypeWithRooms(5, today)
        val deposit = support.plan(listOf(type), guarantee = "DEPOSIT")
        val card = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(deposit.id, listOf(type), today, today.plusDays(20))
        support.setPrices(card.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(9)
        val start = clock.instant().truncatedTo(ChronoUnit.MICROS)
        try {
            val first = created(type, deposit.id, arrival, arrival.plusDays(1))
            clock.advance(Duration.ofHours(2))
            val second = created(type, card.id, arrival, arrival.plusDays(1))
            clock.advance(Duration.ofHours(8))
            val third = created(type, card.id, arrival, arrival.plusDays(1))
            val confirmedLater = created(type, card.id, arrival, arrival.plusDays(1))
            with(support.billing) { support.confirm(confirmedLater.id(), "CARD").expect(200) }
            val cancelled = created(type, card.id, arrival, arrival.plusDays(1))
            with(support.billing) { support.cancel(cancelled.id()).expect(200) }
            val folio = UUID.fromString(first["folioId"].asString())
            with(support.billing) {
                val cashier = cashier()
                openShift(cashier)
                pay(folio, cashier, mapOf("method" to "PIX", "amount" to "50.00", "deposit" to true)).expect(201)
            }
            val own = setOf(first.id(), second.id(), third.id(), confirmedLater.id(), cancelled.id())

            // Da validade da primeira (inclusive) a uma hora depois da validade da segunda (exclusive).
            val to = start.plusSeconds(THREE_HOURS).plus(validity)
            val listed = pending("from=${start.plus(validity)}&to=$to").filter { it.id() in own }
            listed.map { it.id() } shouldContainExactly listOf(first.id(), second.id())
            val item = listed.first()
            item["code"].asString() shouldBe first["code"].asString()
            Instant.parse(item["provisionalUntil"].asString()) shouldBe start.plus(validity)
            item["overdue"].asBoolean() shouldBe false
            item["guaranteeType"].asString() shouldBe "DEPOSIT"
            item["requiredDeposit"].asString() shouldBe "120.00"
            item["depositReceived"].asString() shouldBe "50.00"
            item["depositDueOn"].asString() shouldBe arrival.minusDays(7).toString()
            item["arrival"].asString() shouldBe arrival.toString()
            item["holderGuestId"].isNull shouldBe false
            listed[1]["requiredDeposit"].isNull shouldBe true
            listed[1]["depositReceived"].isNull shouldBe true

            // Sem o início, todas as que vencem antes do fim; sem o fim, agora mais a antecedência do alerta.
            pending(
                "to=${start.plus(validity).plusSeconds(1)}",
            ).filter { it.id() in own }.map { it.id() } shouldContainExactly
                listOf(first.id())
            val lead = hotelApi.operationalSettings().preReservationAlertLead
            clock.advance(validity.minus(lead).minusHours(TEN_HOURS).plusSeconds(1))
            // Agora = início + validade − antecedência + 1 s: o alerta alcança só a primeira.
            pending("").filter { it.id() in own }.map { it.id() } shouldContainExactly listOf(first.id())
            // Passada a validade da primeira (o job ainda não rodou), ela aparece como vencida.
            clock.advance(lead)
            pending("").first { it.id() in own }["overdue"].asBoolean() shouldBe true

            with(iam) {
                call(GET, "/api/v1/reservations/pending-guarantees?from=$to&to=$start", support.admin)
                    .problem(400, "RESERVATION_PERIOD_INVALID")
                val nobody = iam.createUser()
                call(GET, "/api/v1/reservations/pending-guarantees", asTestUser(nobody))
                    .problem(403, "PERMISSION_DENIED")
            }
            // A API pública faz o mesmo.
            reservationsApi
                .pendingGuarantees(start.plus(validity), to)
                .filter { it.reservationId.value in own }
                .map { it.reservationId.value } shouldContainExactly listOf(first.id(), second.id())
        } finally {
            rewind(start)
        }
    }

    @Test
    @Suppress("LongMethod") // a troca de plano, a reativação, a lista, o registro da garantia e a trilha
    fun `a confirmed reservation lacking the guarantee its policy asks for is listed and gets it registered`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val none = support.plan(listOf(type))
        val card = support.plan(listOf(type), guarantee = "CARD")
        val deposit = support.plan(listOf(type), guarantee = "DEPOSIT")
        listOf(none, card, deposit).forEach { support.setPrices(it.id, listOf(type), today, today.plusDays(30)) }
        val arrival = today.plusDays(12)
        val checkIn = hotelApi.policy().checkInTime
        val zone = hotelApi.timezone()
        val start = clock.instant()
        try {
            // Confirmadas num plano sem garantia e trocadas para um com garantia (E08): ficam confirmadas, sem ela.
            val toCard = created(type, none.id, arrival, arrival.plusDays(1))
            val toDeposit = created(type, none.id, arrival, arrival.plusDays(1))
            with(support.billing) {
                support.change(toCard.id(), support.changeBody(toCard, plan = card.id)).expect(200)
                support.change(toDeposit.id(), support.changeBody(toDeposit, plan = deposit.id)).expect(200)
            }
            // Uma pré-reserva expirada e reativada também fica confirmada sem garantia.
            val reactivated = created(type, deposit.id, arrival, arrival.plusDays(1)).id()
            clock.advance(validity)
            expiryJob.expireProvisionalReservations()
            support.status(reactivated) shouldBe "CANCELLED"
            with(support.billing) { support.reactivate(reactivated).expect(200) }
            support.status(reactivated) shouldBe "CONFIRMED"

            val own = setOf(toCard.id(), toDeposit.id(), reactivated)
            val horizon = arrival.plusDays(1).atStartOfDay(zone).toInstant()
            val listed = pending("to=$horizon").filter { it.id() in own }
            val depositDue =
                arrival
                    .minusDays(7)
                    .atTime(checkIn)
                    .atZone(zone)
                    .toInstant()
            val cardDue = arrival.atTime(checkIn).atZone(zone).toInstant()
            // Os dois de depósito vencem no prazo do depósito; o de cartão, no check-in da chegada.
            listed.map { it.id() } shouldContainExactly
                listOf(toDeposit.id(), reactivated).sortedBy { it.toString() } + toCard.id()
            listed.forEach {
                it["kind"].asString() shouldBe "UNGUARANTEED"
                it["status"].asString() shouldBe "CONFIRMED"
                it["provisionalUntil"].isNull shouldBe true
                it["overdue"].asBoolean() shouldBe false
            }
            Instant.parse(listed.first { it.id() == toCard.id() }["dueAt"].asString()) shouldBe cardDue
            Instant.parse(listed.first { it.id() == reactivated }["dueAt"].asString()) shouldBe depositDue
            listed.first { it.id() == toDeposit.id() }["requiredDeposit"].asString() shouldBe "120.00"
            listed.first { it.id() == toDeposit.id() }["depositReceived"].asString() shouldBe "0.00"
            // Antes do prazo do depósito, só a de cartão fica de fora.
            pending("to=$depositDue").filter { it.id() in own }.shouldBeEmpty()
            val byApi = reservationsApi.pendingGuarantees(null, horizon).filter { it.reservationId.value in own }
            byApi.map { it.reservationId.value } shouldContainExactly listed.map { it.id() }
            byApi.map { it.kind }.toSet() shouldBe setOf(PendingGuaranteeKind.UNGUARANTEED)

            with(support.billing) {
                // O mesmo POST …/confirm registra a garantia, com as mesmas conferências; a situação não muda.
                support.confirm(toCard.id(), "DEPOSIT").problem(422, "GUARANTEE_METHOD_NOT_ACCEPTED")
                val registered = support.confirm(toCard.id(), "CARD").expect(200)["reservation"]
                registered["status"].asString() shouldBe "CONFIRMED"
                registered["guarantee"]["method"].asString() shouldBe "CARD"
                support.confirm(toCard.id(), "CARD").problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")

                support.confirm(toDeposit.id(), "DEPOSIT").problem(422, "DEPOSIT_INSUFFICIENT")
                val cashier = cashier()
                openShift(cashier)
                val folio = UUID.fromString(toDeposit["folioId"].asString())
                pay(folio, cashier, mapOf("method" to "PIX", "amount" to "120.00", "deposit" to true)).expect(201)
                support
                    .confirm(toDeposit.id(), "DEPOSIT")
                    .expect(200)["reservation"]["guarantee"]["depositReceived"]
                    .asString() shouldBe "120.00"
                support.confirm(reactivated, "DEPOSIT").problem(422, "DEPOSIT_INSUFFICIENT")
            }
            pending("to=$horizon").filter { it.id() in own }.map { it.id() } shouldContainExactly listOf(reactivated)
            support.historyKinds(toCard.id()) shouldContainExactly listOf("CHANGE", "GUARANTEE")
            val entry = with(iam) { call(GET, "/api/v1/reservations/${toCard.id()}/history", support.admin).ok() }
            entry["items"][0]["changedFields"].values().map { it.asString() } shouldContainExactly listOf("guarantee")
            entry["items"][0]["after"]["guaranteeMethod"].asString() shouldBe "CARD"
            support.auditEntries(toCard.id()).last()["action"] shouldBe "reservations.reservation.confirm"
            // Sem mudança de situação, nenhum ReservationConfirmed.
            support.lifecyclePublications(ReservationConfirmed::class.java, toCard.id()).shouldBeEmpty()
            support.lifecyclePublications(ReservationConfirmed::class.java, toDeposit.id()).shouldBeEmpty()
        } finally {
            rewind(start)
        }
    }

    @Test
    @Suppress("LongMethod") // a garantia de cartão, a troca para o plano de depósito, a lista e a garantia nova
    fun `a card guarantee no longer counts after a change to a deposit plan, and the deposit is registered over it`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val card = support.plan(listOf(type), guarantee = "CARD")
        val deposit = support.plan(listOf(type), guarantee = "DEPOSIT")
        listOf(card, deposit).forEach { support.setPrices(it.id, listOf(type), today, today.plusDays(30)) }
        val arrival = today.plusDays(14)
        val zone = hotelApi.timezone()
        val reservation = created(type, card.id, arrival, arrival.plusDays(1))
        val id = reservation.id()
        with(support.billing) {
            support.confirm(id, "CARD").expect(200)
            val changed =
                support.change(id, support.changeBody(support.reservation(id)["reservation"], plan = deposit.id))
            changed.expect(200)["reservation"].let {
                it["status"].asString() shouldBe "CONFIRMED"
                // A garantia antiga fica gravada, mas não vale para a política nova.
                it["guarantee"]["method"].asString() shouldBe "CARD"
                it["guaranteeTerms"]["type"].asString() shouldBe "DEPOSIT"
            }
        }
        val horizon = arrival.plusDays(1).atStartOfDay(zone).toInstant()
        val item = pending("to=$horizon").single { it.id() == id }
        item["kind"].asString() shouldBe "UNGUARANTEED"
        Instant.parse(item["dueAt"].asString()) shouldBe
            arrival
                .minusDays(7)
                .atTime(hotelApi.policy().checkInTime)
                .atZone(zone)
                .toInstant()
        item["requiredDeposit"].asString() shouldBe "120.00"

        with(support.billing) {
            support.confirm(id, "CARD").problem(422, "GUARANTEE_METHOD_NOT_ACCEPTED")
            support.confirm(id, "DEPOSIT").problem(422, "DEPOSIT_INSUFFICIENT")
            val cashier = cashier()
            openShift(cashier)
            val folio = UUID.fromString(reservation["folioId"].asString())
            pay(folio, cashier, mapOf("method" to "PIX", "amount" to "120.00", "deposit" to true)).expect(201)
            val registered = support.confirm(id, "DEPOSIT").expect(200)["reservation"]
            registered["status"].asString() shouldBe "CONFIRMED"
            registered["guarantee"]["method"].asString() shouldBe "DEPOSIT"
            registered["guarantee"]["depositReceived"].asString() shouldBe "120.00"
            support.confirm(id, "DEPOSIT").problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
        }
        pending("to=$horizon").filter { it.id() == id }.shouldBeEmpty()
        support.historyKinds(id) shouldContainExactly listOf("CONFIRM", "CHANGE", "GUARANTEE")
        val entry = with(iam) { call(GET, "/api/v1/reservations/$id/history", support.admin).ok() }["items"][0]
        entry["before"]["guaranteeMethod"].asString() shouldBe "CARD"
        entry["after"]["guaranteeMethod"].asString() shouldBe "DEPOSIT"
        support.lifecyclePublications(ReservationConfirmed::class.java, id) shouldHaveSize 1
    }

    @Test
    fun `the pending guarantees page by due instant and id without losing or repeating anything`() {
        val (type, _) = inventory.roomTypeWithRooms(4, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(7)
        val start = clock.instant().truncatedTo(ChronoUnit.MICROS)
        try {
            // Três com a mesma validade (o desempate é o id) e uma uma hora depois.
            val tied = (1..3).map { created(type, plan.id, arrival, arrival.plusDays(1)).id() }
            clock.advance(Duration.ofHours(1))
            val later = created(type, plan.id, arrival, arrival.plusDays(1)).id()
            val from = start.plus(validity)
            val to = from.plusSeconds(THREE_HOURS)
            val items = mutableListOf<JsonNode>()
            var pages = 0
            var cursor: String? = null
            do {
                val page =
                    with(support.billing) {
                        call(
                            GET,
                            "/api/v1/reservations/pending-guarantees?from=$from&to=$to&limit=2" +
                                cursor?.let { "&cursor=$it" }.orEmpty(),
                            support.admin,
                        ).expect(200)
                    }
                page["items"].size() shouldBe (if (page["nextCursor"].isNull) page["items"].size() else 2)
                items += page["items"].values()
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
                pages++
            } while (cursor != null)

            (pages >= 2) shouldBe true
            items.map { it.id() }.distinct() shouldHaveSize items.size
            val keys = items.map { Instant.parse(it["dueAt"].asString()) to it.id().toString() }
            keys shouldBe keys.sortedWith(compareBy({ it.first }, { it.second }))
            val own = (tied + later).toSet()
            items.map { it.id() }.filter { it in own } shouldContainExactly
                tied.sortedBy { it.toString() } + later
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `the due instant of a reservation without guarantee is the check-in in the hotel zone, across midnight`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val none = support.plan(listOf(type))
        val card = support.plan(listOf(type), guarantee = "CARD")
        listOf(none, card).forEach { support.setPrices(it.id, listOf(type), today, today.plusDays(20)) }
        val arrival = today.plusDays(8)
        val reservation = created(type, none.id, arrival, arrival.plusDays(1))
        val id = reservation.id()
        with(support.billing) { support.change(id, support.changeBody(reservation, plan = card.id)).expect(200) }
        val zone = hotelApi.timezone()
        // Check-in às 23h30 locais: no fuso do hotel (a oeste de UTC, como o padrão), já é o dia seguinte em UTC.
        withCheckIn(LocalTime.of(23, 30), LocalTime.of(21, 0)) {
            val due = arrival.atTime(LocalTime.of(23, 30)).atZone(zone).toInstant()
            val listed = pending("to=${due.plusSeconds(1)}").single { it.id() == id }
            Instant.parse(listed["dueAt"].asString()) shouldBe due
            // Exclusive no fim: até o próprio vencimento, ainda não entra.
            pending("to=$due").filter { it.id() == id }.shouldBeEmpty()
            reservationsApi
                .pendingGuarantees(
                    null,
                    due.plusSeconds(1),
                ).single { it.reservationId.value == id }
                .dueAt shouldBe
                due
        }
    }

    @Test
    fun `a pre-reservation past its validity is no longer changed, even before the job runs`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val start = clock.instant()
        try {
            val reservation = created(type, plan.id, today.plusDays(6), today.plusDays(7))
            val id = reservation.id()
            clock.advance(validity.minusNanos(1_000))
            with(support.billing) {
                support.change(id, support.changeBody(reservation, segment = "BUSINESS")).expect(200)
            }
            clock.advance(Duration.ofNanos(1_000))
            with(support.billing) {
                support
                    .change(id, support.changeBody(support.reservation(id)["reservation"], segment = "EVENT"))
                    .problem(422, "PROVISIONAL_RESERVATION_EXPIRED")
            }
            support.status(id) shouldBe "PROVISIONAL"
            support.historyKinds(id) shouldContainExactly listOf("CHANGE")
        } finally {
            rewind(start)
        }
    }

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

    /** Todas as páginas da lista das garantias pendentes com a [query]. */
    private fun pending(query: String): List<JsonNode> =
        support.billing.listAll("/api/v1/reservations/pending-guarantees" + if (query.isEmpty()) "" else "?$query")

    /** Troca os horários de check-in e check-out do hotel durante [block] e devolve os anteriores no fim. */
    private fun withCheckIn(
        checkIn: LocalTime,
        checkOut: LocalTime,
        block: () -> Unit,
    ) = with(iam) {
        val path = "/api/v1/hotel/policies"

        fun put(body: Map<*, *>) {
            call(PUT, path, support.admin, body, ifMatch = call(GET, path, support.admin).getHeader("ETag")).ok()
        }
        val original = jsonMapper.convertValue(call(GET, path, support.admin).ok(), Map::class.java)
        put(
            original +
                mapOf(
                    "checkInTime" to checkIn.toString(),
                    "checkOutTime" to checkOut.toString(),
                    "cleaningWindowMinutes" to CLEANING_MINUTES,
                    "earlyCheckIn" to null,
                    "lateCheckOut" to null,
                ),
        )
        try {
            block()
        } finally {
            put(original)
        }
    }

    /** Um operador de caixa (recebe, reembolsa sem teto e lê as contas) com sessão fixa. */
    private fun cashier(): RequestPostProcessor {
        val user =
            iam.createUserWith(
                TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                TestGrant(
                    definition(BillingPermissions.PAYMENTS_REFUND),
                    ALL,
                    mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
                ),
            )
        return asUser(user.id.value, UuidV7.generate())
    }

    private fun refund(
        folio: UUID,
        payment: UUID,
        amount: String,
        auth: RequestPostProcessor,
    ) = support.billing.call(
        POST,
        "/api/v1/folios/$folio/payments/$payment/refund",
        auth,
        mapOf("amount" to amount, "reason" to "Devolução parcial"),
    )

    private fun permission(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }

    /** Quantas sessões esperam a trava de uma reserva (a confirmação ou a expiração paradas no `FOR UPDATE`). */
    private fun reservationLockWaits(): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query LIKE '%FROM reservations.reservation WHERE id%FOR UPDATE%'
                """.trimIndent(),
            ).query(Int::class.java)
            .single()

    /** Segura (ou solta, com zero) a trava do ShedLock do job, como outra instância faria. */
    private fun holdShedLock(duration: Duration) {
        jdbc
            .sql(
                """
                INSERT INTO shared.shedlock (name, lock_until, locked_at, locked_by)
                VALUES (:name, now() + CAST(:seconds || ' seconds' AS interval), now(), 'other-instance')
                ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by
                """.trimIndent(),
            ).param("name", ProvisionalExpiryJob.LOCK_NAME)
            .param("seconds", duration.seconds.toString())
            .update()
    }

    private fun rewind(start: Instant) {
        clock.advance(Duration.between(clock.instant(), start))
    }

    private companion object {
        const val TIMEOUT = 30L
        const val THREE_HOURS = 10800L
        const val CLEANING_MINUTES = 60
        const val TEN_HOURS = 10L
    }
}
