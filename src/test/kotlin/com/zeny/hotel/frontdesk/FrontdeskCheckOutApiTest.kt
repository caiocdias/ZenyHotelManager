package com.zeny.hotel.frontdesk

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.RoomStatusFailureProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.test.EnableScenarios
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O check-out orquestrado da recepção (M5 E04; ARCHITECTURE §6.1, RF-REC-06, RN-06, RN-09, RN-11, H12 a H14): a
 * transação única (as cauções liberadas, as contas encerradas, a reserva `CHECKED_OUT`, a alocação encerrada, a UH Vaga
 * e Suja e `GuestCheckedOut`; a falha forçada no último passo, que não deixa nada), o saldo não zero com as cobranças
 * pendentes já somadas (Review Focus 1), as cobranças da noite da chegada uma vez só (Review Focus 2), todas as cauções
 * em aberto liberadas (Review Focus 3), a segunda conta da reserva (Review Focus 4), o late só pelo valor cotado
 * (Review Focus 5), a idempotência e a permissão. O contexto dos testes das reservas com o relógio mutável (o mesmo do
 * `ReservationSearchApiTest`).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários do check-out de ponta a ponta e os beans que eles usam
class FrontdeskCheckOutApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Sucesso --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o check-out inteiro: resposta, reserva, alocação, UH, caução, conta, trilha e evento
    fun `an in-house stay with a zero balance checks out, releasing holds, closing folios, leaving the room dirty`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(preAuthorization = frontdesk.preAuthorization())
        val hold = frontdesk.preAuthorizations(booked.folio).single()["id"].asString()
        // Um consumo lançado na estadia e a noite da chegada que o check-out lança (sai no dia em que chegou), pagos.
        billing.post(booked.folio, billing.codeId("LAUNDRY"), "30.00")
        frontdesk.pay(booked.folio, frontdesk.cashier(), "430.00")

        val raw = checkOutRaw(booked.id)
        val response = with(billing) { raw.expect(200) }

        response["reservation"]["status"].asString() shouldBe "CHECKED_OUT"
        response["reservation"]["dayUse"].asBoolean() shouldBe true
        raw.getHeader("ETag") shouldBe support.etag(booked.id)
        response["room"]["roomId"].asString() shouldBe stay.room.toString()
        response["room"]["occupancy"].asString() shouldBe "VACANT"
        response["room"]["housekeepingStatus"].asString() shouldBe "DIRTY"
        response["stayCharges"].values().map { it["amount"].asString() } shouldContainExactly listOf("400.00")
        response["lateCheckOut"].isNull shouldBe true
        response["releasedPreAuthorizations"].values().map { it["id"].asString() to it["status"].asString() } shouldBe
            listOf(hold to "RELEASED")
        response["closedFolios"].values().map { it["folioId"].asString() to it["status"].asString() } shouldBe
            listOf(booked.folio.toString() to "CLOSED")

        support.status(booked.id) shouldBe "CHECKED_OUT"
        assignment(booked.id) shouldBe ("CHECKED_OUT" to true)
        roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "VACANT"
        frontdesk.preAuthorizations(booked.folio).single()["status"].asString() shouldBe "RELEASED"
        billing.folio(booked.folio)["status"].asString() shouldBe "CLOSED"
        billing.balance(booked.folio) shouldBe "0.00"
        support.lifecyclePublications(GuestCheckedOut::class.java, booked.id, LISTENER) shouldHaveSize 1
        val trail = support.auditEntries(booked.id)
        trail.map { it["action"] }.filter { it in CHECK_OUT_ACTIONS } shouldContainExactly CHECK_OUT_ACTIONS
        trail.single { it["action"] == "frontdesk.stay.check-out" }["after"].toString().let { after ->
            withClue(after) { after.contains("\"closedFolioIds\": [\"${booked.folio}\"]") shouldBe true }
        }

        // A conta encerrada não recebe mais lançamento.
        with(billing) {
            call(
                POST,
                "/api/v1/folios/${booked.folio}/postings",
                admin,
                mapOf("transactionCodeId" to codeId("LAUNDRY"), "quantity" to 1, "unitPrice" to "10.00"),
            ).problem(409, "FOLIO_CLOSED")
        }
    }

    // --- Saldo ----------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // devedor, credor e o day use, cada um com o que não pode mudar
    fun `a non zero balance refuses the check-out with each folio balance, pending stay charges included`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(preAuthorization = frontdesk.preAuthorization())
        val cashier = frontdesk.cashier()

        // (a) Devedor: pagou 300 de uma noite de 400 que o check-out lança.
        frontdesk.pay(booked.folio, cashier, "300.00")
        checkOut(booked.id).problem(409, "FOLIO_BALANCE_NOT_ZERO").let {
            it["folios"].values().map { folio -> folio["folioId"].asString() to folio["balance"].asString() } shouldBe
                listOf(booked.folio.toString() to "100.00")
            it["folios"][0]["status"].asString() shouldBe "OPEN"
            it["total"].asString() shouldBe "100.00"
        }
        unchanged(stay, booked)

        // (b) Credor: pagou 50 a mais.
        frontdesk.pay(booked.folio, cashier, "150.00")
        checkOut(booked.id).problem(409, "FOLIO_BALANCE_NOT_ZERO").let {
            it["folios"][0]["balance"].asString() shouldBe "-50.00"
            it["total"].asString() shouldBe "-50.00"
        }
        unchanged(stay, booked)

        // (c) Day use sem pagamento: o saldo do problema já tem a diária pendente; pagando-o, o check-out passa.
        val dayUseStay = frontdesk.stay()
        val dayUse = dayUseStay.checkedIn(departure = today)
        val due = checkOut(dayUse.id).problem(409, "FOLIO_BALANCE_NOT_ZERO")["total"].asString()
        due shouldBe "400.00"
        support.postings(dayUse.folio).shouldBeEmpty()
        frontdesk.pay(dayUse.folio, cashier, due)
        checkOut(dayUse.id).expect(200)["reservation"]["status"].asString() shouldBe "CHECKED_OUT"
    }

    @Test
    fun `a routed second folio of the reservation with a balance refuses the check-out`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn()
        val second = billing.openFolio("RESERVATION", booked.id)
        billing.post(second, billing.codeId("LAUNDRY"), "60.00")
        val cashier = frontdesk.cashier()
        frontdesk.pay(booked.folio, cashier, "400.00")

        checkOut(booked.id).problem(409, "FOLIO_BALANCE_NOT_ZERO").let {
            it["folios"].values().map { folio -> folio["folioId"].asString() to folio["balance"].asString() } shouldBe
                listOf(booked.folio.toString() to "0.00", second.toString() to "60.00")
            it["total"].asString() shouldBe "60.00"
        }
        support.status(booked.id) shouldBe "IN_HOUSE"

        frontdesk.pay(second, cashier, "60.00")
        checkOut(booked.id).expect(200)["closedFolios"].values().map { it["folioId"].asString() } shouldBe
            listOf(booked.folio.toString(), second.toString())
        billing.folio(second)["status"].asString() shouldBe "CLOSED"
    }

    // --- Situação -------------------------------------------------------------------------------------------------

    @Test
    fun `a reservation that is not in house is refused`() {
        val stay = frontdesk.stay()
        val confirmed = stay.book(frontdesk.guest())
        checkOut(confirmed.id).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED").let {
            it["reservationStatus"].asString() shouldBe "CONFIRMED"
            it["action"].asString() shouldBe "CHECK_OUT"
        }
        val checkedOut = frontdesk.stay().book(frontdesk.guest())
        unassign(checkedOut.id)
        support.forceCheckedOut(checkedOut.id)
        checkOut(checkedOut.id)
            .problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")["reservationStatus"]
            .asString() shouldBe "CHECKED_OUT"
        checkOut(UuidV7.generate()).problem(404, "RESERVATION_NOT_FOUND")
        support.status(confirmed.id) shouldBe "CONFIRMED"
    }

    // --- Cobranças da noite da chegada (H14) ----------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o day use e o pernoite que sai no dia, e a repetição pela chave
    fun `a day use or a same day check-out charges the arrival night and its packages once`() {
        val breakfast = createPackage("DAILY", "PER_PERSON_PER_NIGHT", "35.00", included = true)
        val welcome = createPackage("ON_ARRIVAL", "PER_STAY", "50.00", included = false)
        val stay = frontdesk.stay(packages = listOf(breakfast.id, welcome.id))
        val cashier = frontdesk.cashier()

        for (departure in listOf(today, today.plusDays(2))) {
            withClue("saída $departure") {
                val booked = stay.checkedIn(departure = departure)
                frontdesk.pay(booked.folio, cashier, "450.00")

                val response = checkOut(booked.id).expect(200)

                response["reservation"]["dayUse"].asBoolean() shouldBe true
                // A hospedagem sem o café incluso, o café (2 × 35,00) e o pacote adicional, cada um no seu código.
                response["stayCharges"].values().map {
                    it["transactionCodeId"].asString() to it["amount"].asString()
                } shouldContainExactlyInAnyOrder
                    listOf(
                        billing.codeId("ROOM_CHARGE").toString() to "330.00",
                        breakfast.code.toString() to "70.00",
                        welcome.code.toString() to "50.00",
                    )
                val base = "reservations.reservation:${booked.id}:$today"
                stored(booked.folio) shouldContainExactlyInAnyOrder
                    listOf(
                        Triple("END_OF_DAY", "$base:room", "330.00"),
                        Triple("END_OF_DAY", "$base:package:${breakfast.id}", "70.00"),
                        Triple("END_OF_DAY", "$base:package:${welcome.id}", "50.00"),
                    )

                // O fechamento do M6 com a mesma chave recebe o lançamento existente, sem linha nova.
                val existing = response["stayCharges"][0]["postingId"].asString()
                val repeated =
                    asAdmin {
                        billingApi.post(
                            FolioId(booked.folio),
                            NewPosting(
                                billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
                                Money.of("330.00"),
                                source = PostingSource.END_OF_DAY,
                                sourceReference = "$base:room",
                            ),
                        )
                    }
                repeated.id.toString() shouldBe existing
                stored(booked.folio) shouldHaveSize 3
            }
        }
    }

    @Test
    fun `a check-out after the arrival date charges nothing for the stay`() {
        val stay = frontdesk.stay()
        val booked = stay.book(frontdesk.guest(), departure = today)
        // Um day use de ontem que ainda não saiu (a API não cria um hospedado no passado).
        unassign(booked.id)
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :date, departure = :date WHERE id = :id AND day_use")
            .param("date", today.minusDays(1))
            .param("id", booked.id)
            .update() shouldBe 1
        support.forceInHouse(booked.id, stay.room)
        jdbc
            .sql("UPDATE reservations.reservation_guest SET checked_in_at = now() WHERE reservation_id = :id")
            .param("id", booked.id)
            .update()
        roomsApi.markOccupied(RoomId(stay.room), 1, RoomStatusSource.MANUAL)

        val response = checkOut(booked.id).expect(200)

        response["stayCharges"].values().toList().shouldBeEmpty()
        response["room"]["housekeepingStatus"].asString() shouldBe "DIRTY"
        support.postings(booked.folio).shouldBeEmpty()
    }

    @Test
    fun `a repeated check-out after a refused one does not charge the arrival night twice`() {
        val booked = frontdesk.stay().checkedIn()
        checkOut(booked.id).problem(409, "FOLIO_BALANCE_NOT_ZERO")
        support.postings(booked.folio).shouldBeEmpty()
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")

        checkOut(booked.id).expect(200)

        stored(booked.folio) shouldBe
            listOf(Triple("END_OF_DAY", "reservations.reservation:${booked.id}:$today:room", "400.00"))
    }

    // --- Late check-out (RN-11, D4) -------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cotado, sem o campo, valor mudado, não oferecido e o formato do valor
    fun `the late check-out is charged only as quoted`() {
        val original = frontdesk.policy()
        val cashier = frontdesk.cashier()
        try {
            frontdesk.putPolicy(original + mapOf("lateCheckOut" to mapOf("limitTime" to "18:00", "fee" to "80.00")))
            val stay = frontdesk.stay()

            // Cotado e pago com a diária: lançado como cotado, no código da diária, origem MANUAL.
            val quoted = stay.checkedIn()
            val fee = quote(quoted.id)["lateCheckOut"]["fee"].asString()
            fee shouldBe "80.00"
            frontdesk.pay(quoted.folio, cashier, "480.00")
            val response = checkOut(quoted.id, mapOf("lateCheckOutFee" to fee)).expect(200)
            response["lateCheckOut"]["amount"].asString() shouldBe "80.00"
            response["lateCheckOut"]["transactionCodeId"].asString() shouldBe billing.codeId("ROOM_CHARGE").toString()
            support.postings(quoted.folio).single { it["description"] == "Late check-out" }.let {
                it["source"] shouldBe "MANUAL"
                it["amount"] shouldBe "80.00"
            }

            // Sem o campo, nada de late.
            val plain = frontdesk.stay().checkedIn()
            frontdesk.pay(plain.folio, cashier, "400.00")
            checkOut(plain.id).expect(200)["lateCheckOut"].isNull shouldBe true
            support.postings(plain.folio).map { it["source"] } shouldBe listOf("END_OF_DAY")

            // A política mudou entre a cotação e o check-out: 409 com o valor vigente e nada lançado.
            val changed = frontdesk.stay().checkedIn()
            frontdesk.pay(changed.folio, cashier, "480.00")
            frontdesk.putPolicy(original + mapOf("lateCheckOut" to mapOf("limitTime" to "18:00", "fee" to "90.00")))
            checkOut(changed.id, mapOf("lateCheckOutFee" to "80.00"))
                .problem(409, "EARLY_LATE_FEE_CHANGED")["currentFee"]
                .asString() shouldBe "90.00"
            support.status(changed.id) shouldBe "IN_HOUSE"
            support.postings(changed.folio).shouldBeEmpty()

            // Sem late na política e o valor no pedido: 422.
            frontdesk.putPolicy(original + mapOf("lateCheckOut" to null))
            checkOut(changed.id, mapOf("lateCheckOutFee" to "80.00")).problem(422, "EARLY_LATE_NOT_OFFERED")
            support.postings(changed.folio).shouldBeEmpty()

            // O valor: positivo e com até duas casas.
            for (invalid in listOf("0.00", "-80.00", "80.001")) {
                withClue(invalid) {
                    checkOut(changed.id, mapOf("lateCheckOutFee" to invalid))
                        .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                        .asString() shouldBe "lateCheckOutFee"
                }
            }
            support.status(changed.id) shouldBe "IN_HOUSE"
        } finally {
            frontdesk.putPolicy(original)
        }
    }

    // --- Cauções (H12, B4) ----------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as quatro cauções: a do check-in desfeito, a do refeito, a vencida e a capturada em parte
    fun `every open hold of the reservation is released, expired and from an undone check-in included (B4)`() {
        val stay = frontdesk.stay()
        val holder = frontdesk.guest()
        val booked = stay.book(holder)
        stay.ready()
        frontdesk.checkIn(booked.id, holder, frontdesk.preAuthorization()).expect(200)
        with(billing) {
            call(POST, "/api/v1/reservations/${booked.id}/undo-check-in", admin, idempotencyKey = null).expect(200)
        }
        frontdesk.checkIn(booked.id, holder, frontdesk.preAuthorization()).expect(200)
        val cashier = frontdesk.cashier()
        val expiring = registerHold(booked.folio, cashier.auth, clock.instant().plus(Duration.ofHours(1)))
        val partial = registerHold(booked.folio, cashier.auth, clock.instant().plus(Duration.ofDays(5)))
        with(billing) {
            call(
                POST,
                "/api/v1/folios/${booked.folio}/card-pre-authorizations/$partial/capture",
                cashier.auth,
                mapOf("amount" to "100.00"),
            ).expect(201)
        }
        frontdesk.pay(booked.folio, cashier, "300.00")
        val holds = frontdesk.preAuthorizations(booked.folio).map { it["id"].asString() }
        holds shouldHaveSize 4

        clock.advance(Duration.ofHours(2))
        val response =
            try {
                frontdesk
                    .preAuthorizations(booked.folio)
                    .single { it["id"].asString() == expiring }["status"]
                    .asString() shouldBe "EXPIRED"
                checkOut(booked.id).expect(200)
            } finally {
                clock.advance(Duration.ofHours(-2))
            }

        response["releasedPreAuthorizations"].values().map { it["id"].asString() } shouldContainExactlyInAnyOrder holds
        response["releasedPreAuthorizations"].values().map { it["status"].asString() }.toSet() shouldBe
            setOf("RELEASED")
        frontdesk.preAuthorizations(booked.folio).map { it["status"].asString() }.toSet() shouldBe setOf("RELEASED")
        billing.folio(booked.folio)["status"].asString() shouldBe "CLOSED"
    }

    // --- Idempotência e permissão ---------------------------------------------------------------------------------

    @Test
    fun `the check-out is idempotent and needs its permission`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn()
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")

        checkOut(booked.id, key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        val receptionist = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN))
        checkOut(booked.id, auth = iam.asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")
        support.status(booked.id) shouldBe "IN_HOUSE"

        val operator = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_OUT))
        val key = UuidV7.generate().toString()
        val first = checkOut(booked.id, auth = iam.asTestUser(operator), key = key).expect(200)
        val replayed = checkOutRaw(booked.id, auth = iam.asTestUser(operator), key = key)
        replayed.getHeader("Idempotent-Replayed") shouldBe "true"
        with(billing) { replayed.expect(200) }["stayCharges"] shouldBe first["stayCharges"]
        support.postings(booked.folio) shouldHaveSize 1
        support.auditEntries(booked.id).single { it["action"] == "frontdesk.stay.check-out" }["userId"] shouldBe
            operator.id.value
    }

    // --- Tudo ou nada ---------------------------------------------------------------------------------------------

    @Test
    fun `a forced failure at the end leaves nothing`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(preAuthorization = frontdesk.preAuthorization())
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")
        RoomStatusFailureProbe.armed += stay.room
        try {
            checkOut(booked.id).problem(409, "TEST_FORCED_FAILURE")
        } finally {
            RoomStatusFailureProbe.armed -= stay.room
        }

        unchanged(stay, booked)
        support.historyKinds(booked.id) shouldBe listOf("CHECK_IN")
        support
            .auditEntries(booked.id)
            .map { it["action"] }
            .filter { it in CHECK_OUT_ACTIONS }
            .shouldBeEmpty()
        support.lifecyclePublications(GuestCheckedOut::class.java, booked.id, LISTENER).shouldBeEmpty()

        // Sem a falha, o mesmo check-out passa.
        checkOut(booked.id).expect(200)
        support.lifecyclePublications(GuestCheckedOut::class.java, booked.id, LISTENER) shouldHaveSize 1
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun checkOutRaw(
        id: UUID,
        body: Map<String, Any?>? = null,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse = billing.call(POST, "/api/v1/reservations/$id/check-out", auth, body, key)

    private fun checkOut(
        id: UUID,
        body: Map<String, Any?>? = null,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String? = UuidV7.generate().toString(),
    ) = frontdesk.Response(checkOutRaw(id, body, auth, key))

    private fun quote(id: UUID): JsonNode =
        with(iam) { call(GET, "/api/v1/reservations/$id/early-late-quote", asAdmin()).ok() }

    /** Nada do check-out recusado ficou: hospedada, alocação ativa, UH ocupada, caução ativa, conta aberta. */
    private fun unchanged(
        stay: FrontdeskTestSupport.Stay,
        booked: FrontdeskTestSupport.Booked,
    ) {
        support.status(booked.id) shouldBe "IN_HOUSE"
        assignment(booked.id) shouldBe (null to false)
        roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "OCCUPIED"
        frontdesk.preAuthorizations(booked.folio).forEach { it["status"].asString() shouldBe "ACTIVE" }
        billing.folio(booked.folio)["status"].asString() shouldBe "OPEN"
        support.postings(booked.folio).shouldBeEmpty()
    }

    /** A alocação mais recente da reserva: o motivo da soltura e se foi solta. */
    private fun assignment(reservation: UUID): Pair<String?, Boolean> =
        jdbc
            .sql(
                "SELECT release_reason, released_at IS NOT NULL AS released FROM reservations.room_assignment " +
                    "WHERE reservation_id = :id ORDER BY assigned_at DESC, id DESC LIMIT 1",
            ).param("id", reservation)
            .query { rs, _ -> rs.getString("release_reason") to rs.getBoolean("released") }
            .single()

    /** Solta a alocação da reserva (a encerrada à força não pode ficar com uma ativa). */
    private fun unassign(id: UUID) {
        with(iam) { call(POST, "/api/v1/reservations/$id/unassign-room", asAdmin()).ok() }
    }

    /** Os lançamentos da conta gravados: a origem, a chave de origem e o valor. */
    private fun stored(folio: UUID): List<Triple<String, String?, String>> =
        jdbc
            .sql("SELECT source, source_reference, amount FROM billing.posting WHERE folio_id = :folio ORDER BY id")
            .param("folio", folio)
            .query { rs, _ ->
                Triple(
                    rs.getString("source"),
                    rs.getString("source_reference"),
                    rs.getBigDecimal("amount").toPlainString(),
                )
            }.list()

    private data class Package(
        val id: UUID,
        val code: UUID,
    )

    private fun createPackage(
        rhythm: String,
        pricing: String,
        price: String,
        included: Boolean,
    ): Package {
        val code = support.rates.createTransactionCode()
        val body =
            support.rates.packageBody(
                code,
                price = price,
                pricing = pricing,
                postingRhythm = rhythm,
                includedInRate = included,
            )
        val id = with(iam) { call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!! }["id"].asString()
        return Package(UUID.fromString(id), code)
    }

    private fun registerHold(
        folio: UUID,
        auth: RequestPostProcessor,
        expiresAt: Instant,
    ): String =
        with(billing) {
            call(POST, "/api/v1/folios/$folio/card-pre-authorizations", auth, frontdesk.preAuthorization(expiresAt))
                .expect(201)["id"]
                .asString()
        }

    /** Roda [block] como o administrador no contexto de segurança (chamada direta à `BillingApi`). */
    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val LISTENER = "StayTransitionsProbeListener"
        val CHECK_OUT_ACTIONS = listOf("reservations.reservation.check-out", "frontdesk.stay.check-out")
    }
}
