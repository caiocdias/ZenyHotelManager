package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Caução registrada manualmente (M3 E09, RF-CTA-08, H13, RNF-10; decisões do usuário em 2026-10-19): o registro que
 * não mexe no saldo, a captura parcial que gera o pagamento e deixa o restante capturável, a liberação registrada da
 * sobra, a captura total, a captura acima do autorizado, de caução vencida (pelo relógio, calculada na leitura) e sem
 * turno, as capturas simultâneas, as cauções a vencer, a integridade no banco, a idempotência e a trilha.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta; os beans que eles usam
class CardPreAuthorizationsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val clock: MutableClock,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val receive = definition(BillingPermissions.PAYMENTS_RECEIVE)
    private val refund = definition(BillingPermissions.PAYMENTS_REFUND)
    private val folioRead = definition(BillingPermissions.FOLIOS_READ)
    private val operate = definition(BillingPermissions.CASHIER_OPERATE)
    private val shiftsRead = definition(BillingPermissions.SHIFTS_READ)

    // --- Registro ------------------------------------------------------------------------------------

    @Test
    fun `a registered pre-authorization never changes the folio balance and needs no cashier shift`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            val etag = etagOf("/api/v1/folios/$folio")
            val cashier = cashier()
            val expiresAt = inDays(7)

            val response =
                register(
                    folio,
                    cashier.auth,
                    "500.00",
                    expiresAt,
                    nsu = "004512",
                    authorizationCode = "a1b2c3",
                    cardBrand = " master card ",
                    cardLast4 = "4242",
                )
            withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
            response.getHeader(HttpHeaders.LOCATION).shouldNotBeNull()
            response.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            val created = response.expect(201)
            created["status"].asString() shouldBe "ACTIVE"
            created["amount"].asString() shouldBe "500.00"
            created["capturedAmount"].asString() shouldBe "0.00"
            created["capturableAmount"].asString() shouldBe "500.00"
            created["releasedAmount"].isNull shouldBe true
            created["expiresAt"].asString() shouldBe expiresAt.toString()
            created["nsu"].asString() shouldBe "004512"
            created["authorizationCode"].asString() shouldBe "A1B2C3"
            created["cardBrand"].asString() shouldBe "MASTER CARD"
            created["cardLast4"].asString() shouldBe "4242"
            created["businessDate"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            created["registeredBy"].asString() shouldBe cashier.user.id.toString()
            created["capturePaymentIds"].size() shouldBe 0

            withClue("a caução não capturada não altera o saldo nem a versão da conta, e nada vai aos pagamentos") {
                balance(folio) shouldBe "300.00"
                etagOf("/api/v1/folios/$folio") shouldBe etag
                paymentRows(folio) shouldBe 0L
                intentRows(folio) shouldBe 0L
            }
            val id = created["id"].asString()
            call(GET, "/api/v1/folios/$folio/card-pre-authorizations/$id", cashier.auth)
                .expect(200)["status"]
                .asString() shouldBe "ACTIVE"
            listAll("/api/v1/folios/$folio/card-pre-authorizations").map { it["id"].asString() } shouldContainExactly
                listOf(id)
            call(GET, "/api/v1/folios/${openFolio()}/card-pre-authorizations/$id", admin)
                .problem(404, "PRE_AUTHORIZATION_NOT_FOUND")
        }

    @Test
    fun `registration refuses bad data, any full card number, a closed folio and users without permission`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()

            fun refused(
                field: String,
                amount: String = "100.00",
                expiresAt: Instant = inDays(1),
                nsu: String? = null,
                authorizationCode: String? = "AUT1",
                cardLast4: String? = null,
                cardBrand: String? = null,
            ) = register(folio, cashier.auth, amount, expiresAt, nsu, authorizationCode, cardBrand, cardLast4)
                .problem(400, "PRE_AUTHORIZATION_INVALID")["errors"][0]["field"]
                .asString() shouldBe field

            refused("amount", amount = "0.00")
            refused("amount", amount = "1000000.01")
            refused("expiresAt", expiresAt = clock.instant().minusSeconds(1))
            refused("expiresAt", expiresAt = clock.instant().plus(Duration.ofDays(90)).plusSeconds(60))
            refused("authorizationCode", authorizationCode = null)
            refused("authorizationCode", authorizationCode = "4111111111111111")
            refused("nsu", nsu = "5555 4444 3333 1111".replace(" ", ""))
            refused("nsu", nsu = "12345678901234")
            refused("cardLast4", cardLast4 = "123")
            refused("cardLast4", cardLast4 = "4111111111111111")
            refused("cardBrand", cardBrand = "V1SA")

            withClue("um campo de cartão completo desconhecido é ignorado: não vai à resposta nem ao banco") {
                val pan = "4111111111111111"
                val body =
                    mapOf(
                        "amount" to "10.00",
                        "expiresAt" to inDays(1).toString(),
                        "authorizationCode" to "OK1",
                        "cardNumber" to pan,
                        "cvv" to "123",
                    )
                val created =
                    call(POST, "/api/v1/folios/$folio/card-pre-authorizations", cashier.auth, body).expect(201)
                created.toString() shouldNotContain pan
                jdbc
                    .sql("SELECT row_to_json(a)::text FROM billing.card_pre_authorization a WHERE id = :id")
                    .param("id", created.uuid("id"))
                    .query(String::class.java)
                    .single() shouldNotContain pan
            }

            val outsider = iam.createUserWith(TestGrant(folioRead, ALL))
            register(folio, iam.asTestUser(outsider), "10.00", inDays(1)).problem(403, "PERMISSION_DENIED")
            register(UuidV7.generate(), cashier.auth, "10.00", inDays(1)).problem(404, "FOLIO_NOT_FOUND")
            closeFolio(folio)
            register(folio, cashier.auth, "10.00", inDays(1)).problem(409, "FOLIO_CLOSED")
        }

    // --- Captura e liberação -------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o fluxo inteiro: duas capturas parciais, a recusa acima do autorizado e a liberação
    fun `a partial capture generates the payment, the rest stays capturable and the leftover is released`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "450.00")
            val cashier = cashier()
            val shift = openShift(cashier)
            val id =
                register(folio, cashier.auth, "500.00", inDays(5), cardBrand = "VISA", cardLast4 = "1234")
                    .expect(201)["id"]
                    .asString()

            val first = capture(folio, id, cashier.auth, "200.00", nsu = "998877")
            withClue(first.getContentAsString(Charsets.UTF_8)) { first.status shouldBe 201 }
            val captured = first.expect(201)
            val payment = captured["payment"]
            first.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/folios/$folio/payments/${payment["id"].asString()}"
            payment["type"].asString() shouldBe "PAYMENT"
            payment["method"].asString() shouldBe "CREDIT_CARD"
            payment["amount"].asString() shouldBe "200.00"
            payment["nsu"].asString() shouldBe "998877"
            payment["cardBrand"].asString() shouldBe "VISA"
            payment["cardLast4"].asString() shouldBe "1234"
            payment["deposit"].asBoolean() shouldBe false
            payment["cardPreAuthorizationId"].asString() shouldBe id
            payment["cashierShiftId"].asString() shouldBe shift.toString()
            captured["preAuthorization"]["status"].asString() shouldBe "PARTIALLY_CAPTURED"
            captured["preAuthorization"]["capturedAmount"].asString() shouldBe "200.00"
            captured["preAuthorization"]["capturableAmount"].asString() shouldBe "300.00"
            captured["preAuthorization"]["capturePaymentIds"].values().map { it.asString() } shouldContainExactly
                listOf(payment["id"].asString())
            withClue("a captura é um recebimento: entra no saldo e no esperado do turno, no cartão de crédito") {
                balance(folio) shouldBe "250.00"
                call(GET, "/api/v1/cashier-shifts/$shift", admin)
                    .expect(200)["expected"]
                    .values()
                    .single { it["method"].asString() == "CREDIT_CARD" }["expected"]
                    .asString() shouldBe "200.00"
                intentPurpose(payment.uuid("paymentIntentId")) shouldBe "billing.folio-capture"
            }

            withClue("o restante continua capturável: uma segunda captura parcial") {
                val second = capture(folio, id, cashier.auth, "100.00").expect(201)
                second["preAuthorization"]["status"].asString() shouldBe "PARTIALLY_CAPTURED"
                second["preAuthorization"]["capturedAmount"].asString() shouldBe "300.00"
                second["payment"]["nsu"].isNull shouldBe true
                balance(folio) shouldBe "150.00"
            }
            withClue("a caução citada por outra conta não existe nela") {
                capture(openFolio(), id, cashier.auth, "1.00").problem(404, "PRE_AUTHORIZATION_NOT_FOUND")
            }
            withClue("acima do que ainda está autorizado, 422 sem efeito") {
                capture(folio, id, cashier.auth, "200.01").problem(422, "CAPTURE_EXCEEDS_AUTHORIZED")
                paymentRows(folio) shouldBe 2L
                balance(folio) shouldBe "150.00"
            }

            val released = call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth).expect(200)
            released["status"].asString() shouldBe "RELEASED"
            released["capturedAmount"].asString() shouldBe "300.00"
            released["releasedAmount"].asString() shouldBe "200.00"
            released["capturableAmount"].asString() shouldBe "0.00"
            released["releasedBy"].asString() shouldBe cashier.user.id.toString()
            released["capturePaymentIds"].size() shouldBe 2
            withClue("a liberação é registrada e não mexe no saldo") {
                balance(folio) shouldBe "150.00"
                jdbc
                    .sql(
                        "SELECT amount FROM billing.card_pre_authorization_release " +
                            "WHERE card_pre_authorization_id = :id",
                    ).param("id", UUID.fromString(id))
                    .query { rs, _ -> rs.getBigDecimal(1).toPlainString() }
                    .single() shouldBe "200.00"
            }
            withClue("liberada, não se captura nem se libera de novo") {
                capture(folio, id, cashier.auth, "1.00").problem(409, "PRE_AUTHORIZATION_SETTLED")
                call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth)
                    .problem(409, "PRE_AUTHORIZATION_SETTLED")
            }
            withClue("a trilha registra o registro, as capturas e a liberação, sem os dados da maquininha") {
                val actions =
                    jdbc
                        .sql(
                            "SELECT action, after_state::text FROM audit.audit_entry WHERE entity_id = :id " +
                                "AND entity_type = 'billing.card-pre-authorization' ORDER BY occurred_at, id",
                        ).param("id", id)
                        .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                        .list()
                actions.map { it.first } shouldContainExactly
                    listOf(
                        "billing.card-pre-authorization.register",
                        "billing.card-pre-authorization.capture",
                        "billing.card-pre-authorization.capture",
                        "billing.card-pre-authorization.release",
                    )
                actions.forEach { (_, state) ->
                    state shouldNotContain "1234"
                    state shouldNotContain "998877"
                }
            }
        }

    @Test
    fun `a total capture settles the pre-authorization and a captured payment can still be refunded`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            openShift(cashier)
            val id = register(folio, cashier.auth, "180.00", inDays(2)).expect(201)["id"].asString()

            val captured = capture(folio, id, cashier.auth, "180.00").expect(201)
            captured["preAuthorization"]["status"].asString() shouldBe "CAPTURED"
            captured["preAuthorization"]["capturableAmount"].asString() shouldBe "0.00"
            balance(folio) shouldBe "-180.00"
            capture(folio, id, cashier.auth, "0.01").problem(409, "PRE_AUTHORIZATION_SETTLED")
            call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth)
                .problem(409, "PRE_AUTHORIZATION_SETTLED")
            withClue("capturada por inteiro, a validade não importa mais") {
                moveClock(Duration.ofDays(3)) {
                    call(GET, "$base$folio/card-pre-authorizations/$id", admin)
                        .expect(200)["status"]
                        .asString() shouldBe "CAPTURED"
                }
            }
            withClue("o recebimento da captura é um pagamento como os outros: pode ser reembolsado") {
                call(
                    POST,
                    "$base$folio/payments/${captured["payment"]["id"].asString()}/refund",
                    cashier.auth,
                    mapOf("amount" to "30.00", "reason" to "Consumo cobrado a mais"),
                ).expect(201)["method"].asString() shouldBe "CREDIT_CARD"
                balance(folio) shouldBe "-150.00"
                call(GET, "$base$folio/card-pre-authorizations/$id", admin)
                    .expect(200)["capturedAmount"]
                    .asString() shouldBe "180.00"
            }
        }

    @Test
    @Suppress("LongMethod") // vencida: leitura, captura recusada sem efeito e a liberação aceita depois da validade
    fun `an expired pre-authorization is computed on read, refuses capture and can still be released`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val expiresAt = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
            val id = register(folio, cashier.auth, "300.00", expiresAt).expect(201)["id"].asString()
            capture(folio, id, cashier.auth, "100.00").expect(201)
            val zone = hotelApi.timezone()
            val expiryDate = LocalDate.ofInstant(expiresAt, zone)

            fun expiringIds(): List<String> =
                listAll("/api/v1/card-pre-authorizations/expiring?from=$expiryDate&to=$expiryDate")
                    .map { it["id"].asString() }

            val untilExpiry = Duration.between(clock.instant(), expiresAt)
            moveClock(untilExpiry) {
                withClue("no instante da validade, já vencida (agora ≥ a validade)") {
                    val read = call(GET, "$base$folio/card-pre-authorizations/$id", admin).expect(200)
                    read["status"].asString() shouldBe "EXPIRED"
                    read["capturableAmount"].asString() shouldBe "0.00"
                    read["capturedAmount"].asString() shouldBe "100.00"
                }
                capture(folio, id, cashier.auth, "50.00").problem(422, "PRE_AUTHORIZATION_EXPIRED")
                withClue("a captura recusada não grava nada: a expiração não é escrita") {
                    storedStatus(id) shouldBe "PARTIALLY_CAPTURED"
                    paymentRows(folio) shouldBe 1L
                    balance(folio) shouldBe "-100.00"
                }
                expiringIds().contains(id) shouldBe true
            }
            moveClock(untilExpiry.plus(Duration.ofHours(2))) {
                withClue("depois da validade, a liberação da sobra é aceita e a caução termina RELEASED") {
                    val released =
                        call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth).expect(200)
                    released["status"].asString() shouldBe "RELEASED"
                    released["releasedAmount"].asString() shouldBe "200.00"
                    released["capturedAmount"].asString() shouldBe "100.00"
                    released["capturableAmount"].asString() shouldBe "0.00"
                    storedStatus(id) shouldBe "RELEASED"
                    balance(folio) shouldBe "-100.00"
                }
                capture(folio, id, cashier.auth, "50.00").problem(409, "PRE_AUTHORIZATION_SETTLED")
                withClue("liberada, sai da consulta das cauções a vencer") { expiringIds().contains(id) shouldBe false }
            }
            call(GET, "$base$folio/card-pre-authorizations/$id", admin)
                .expect(200)["status"]
                .asString() shouldBe "RELEASED"
        }

    @Test
    fun `a capture without an open cashier shift records nothing, registration and release need none`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            val id = register(folio, cashier.auth, "200.00", inDays(3)).expect(201)["id"].asString()

            capture(folio, id, cashier.auth, "50.00").problem(422, "CASHIER_SHIFT_REQUIRED")
            paymentRows(folio) shouldBe 0L
            intentRows(folio) shouldBe 0L
            storedStatus(id) shouldBe "ACTIVE"

            withClue("sem a permissão de receber, 403 antes do turno") {
                val reader = iam.createUserWith(TestGrant(folioRead, ALL))
                capture(folio, id, iam.asTestUser(reader), "50.00").problem(403, "PERMISSION_DENIED")
                call(POST, "$base$folio/card-pre-authorizations/$id/release", iam.asTestUser(reader))
                    .problem(403, "PERMISSION_DENIED")
            }
            val released = call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth).expect(200)
            released["releasedAmount"].asString() shouldBe "200.00"
        }

    @Test
    fun `two simultaneous captures never go beyond the authorized amount`() {
        repeat(ROUNDS) {
            with(billing) {
                val folio = openFolio()
                val first = cashier()
                val second = cashier()
                openShift(first)
                openShift(second)
                val id = register(folio, first.auth, "500.00", inDays(2)).expect(201)["id"].asString()

                val responses =
                    concurrently(
                        { capture(folio, id, first.auth, "300.00") },
                        { capture(folio, id, second.auth, "300.00") },
                    )
                responses.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 422)
                responses.single { it.status == 422 }.problem(422, "CAPTURE_EXCEEDS_AUTHORIZED")
                capturedTotal(id) shouldBe "300.00"
                storedCaptured(id) shouldBe "300.00"
                balance(folio) shouldBe "-300.00"
            }
        }
    }

    @Test
    fun `a capture or a release in the same idempotency key happens once, and the key is required`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val key = UuidV7.generate().toString()
            val body = mapOf("amount" to "50.00", "expiresAt" to inDays(1).toString(), "authorizationCode" to "X1")
            val path = "$base$folio/card-pre-authorizations"
            val created = call(POST, path, cashier.auth, body, idempotencyKey = key).expect(201)
            val replayed = call(POST, path, cashier.auth, body, idempotencyKey = key)
            replayed.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            replayed.expect(201)["id"].asString() shouldBe created["id"].asString()
            listAll("/api/v1/folios/$folio/card-pre-authorizations").size shouldBe 1
            call(POST, path, cashier.auth, body, idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")

            val id = created["id"].asString()
            val captureKey = UuidV7.generate().toString()
            capture(folio, id, cashier.auth, "20.00", idempotencyKey = captureKey).expect(201)
            capture(folio, id, cashier.auth, "20.00", idempotencyKey = captureKey)
                .getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            paymentRows(folio) shouldBe 1L
            capture(folio, id, cashier.auth, "20.00", idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            call(POST, "$path/$id/release", cashier.auth, idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        }

    // --- Cauções a vencer ----------------------------------------------------------------------------

    @Test
    fun `the expiring query returns the open pre-authorizations that expire in the period`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val zone = hotelApi.timezone()
            val today = LocalDate.ofInstant(clock.instant(), zone)

            fun at(
                days: Long,
                hour: Int = 12,
            ): Instant =
                today
                    .plusDays(days)
                    .atTime(hour, 0)
                    .atZone(zone)
                    .toInstant()

            fun registered(expiresAt: Instant) =
                register(folio, cashier.auth, "100.00", expiresAt).expect(201)["id"].asString()

            val tomorrow = registered(at(1))
            val partial = registered(at(2, hour = 23)).also { capture(folio, it, cashier.auth, "40.00").expect(201) }
            val captured = registered(at(2)).also { capture(folio, it, cashier.auth, "100.00").expect(201) }
            val released =
                registered(at(2)).also {
                    call(POST, "$base$folio/card-pre-authorizations/$it/release", cashier.auth).expect(200)
                }
            val later = registered(at(4, hour = 0))

            fun expiring(
                from: LocalDate,
                to: LocalDate,
            ): List<JsonNode> =
                listAll("/api/v1/card-pre-authorizations/expiring?from=$from&to=$to")
                    .filter { it["folioId"].asString() == folio.toString() }

            expiring(today, today.plusDays(2)).map { it["id"].asString() } shouldContainExactly
                listOf(tomorrow, partial)
            expiring(today.plusDays(2), today.plusDays(4)).map { it["id"].asString() } shouldContainExactly
                listOf(partial, later)
            expiring(today.plusDays(2), today.plusDays(2)).single()["capturableAmount"].asString() shouldBe "60.00"
            withClue("as encerradas (capturadas por inteiro ou liberadas) nunca aparecem") {
                expiring(today, today.plusDays(10)).map { it["id"].asString() }.let {
                    it.contains(captured) shouldBe false
                    it.contains(released) shouldBe false
                }
            }
            withClue("a que já venceu no período vem como EXPIRED") {
                moveClock(Duration.between(clock.instant(), at(1)).plusMinutes(1)) {
                    expiring(today, today.plusDays(1)).single()["status"].asString() shouldBe "EXPIRED"
                }
            }
            withClue("a BillingApi devolve o mesmo para o fechamento do dia (M6)") {
                billingApi
                    .cardPreAuthorizationsExpiring(at(0, hour = 0), at(3, hour = 0))
                    .filter { it.folioId == FolioId(folio) }
                    .map { it.id.toString() } shouldContainExactly listOf(tomorrow, partial)
            }
            call(GET, "/api/v1/card-pre-authorizations/expiring?from=$today&to=${today.minusDays(1)}", admin)
                .problem(400, "PRE_AUTHORIZATION_INVALID")["errors"][0]["field"]
                .asString() shouldBe "to"
            val outsider = iam.createUserWith(TestGrant(receive, ALL))
            call(GET, "/api/v1/card-pre-authorizations/expiring?to=$today", iam.asTestUser(outsider))
                .problem(403, "PERMISSION_DENIED")
        }

    // --- Integridade no banco ------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cada recusa do banco: captura, linha da caução, pagamento e liberação
    fun `the database keeps captures, releases and the pre-authorization row coherent`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            val shift = openShift(cashier)
            val id = register(folio, cashier.auth, "100.00", inDays(2)).expect(201)["id"].asString()
            val payment = capture(folio, id, cashier.auth, "60.00").expect(201)["payment"]

            withClue("uma captura inserida direto acima do autorizado é recusada pelo trigger") {
                shouldThrow<DataAccessException> { insertCapture(folio, id, shift, "40.01") }
            }
            withClue("uma captura sem a caução atualizada é recusada no commit") {
                shouldThrow<DataAccessException> { insertCapture(folio, id, shift, "10.00") }
            }
            withClue("o capturado não muda sem a captura, nem diminui; os dados da caução não mudam") {
                shouldThrow<DataAccessException> {
                    owner
                        .sql(
                            "UPDATE billing.card_pre_authorization SET captured_amount = 70, version = version + 1 " +
                                "WHERE id = :id",
                        ).param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    owner
                        .sql("UPDATE billing.card_pre_authorization SET captured_amount = 0 WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    owner
                        .sql("UPDATE billing.card_pre_authorization SET amount = 1000 WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    jdbc
                        .sql("DELETE FROM billing.card_pre_authorization WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    owner
                        .sql("UPDATE billing.card_pre_authorization SET status = 'RELEASED' WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .update()
                }
            }
            withClue("a captura é um recebimento no cartão de crédito, nunca depósito nem reembolso") {
                shouldThrow<DataAccessException> {
                    jdbc
                        .sql("UPDATE billing.payment SET amount = 1 WHERE id = :id")
                        .param("id", payment.uuid("id"))
                        .update()
                }
            }
            call(POST, "$base$folio/card-pre-authorizations/$id/release", cashier.auth).expect(200)
            withClue("a liberação é append-only, para o papel da aplicação e para o dono") {
                shouldThrow<DataAccessException> {
                    jdbc
                        .sql(
                            "UPDATE billing.card_pre_authorization_release SET amount = 1 " +
                                "WHERE card_pre_authorization_id = :id",
                        ).param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    owner
                        .sql("DELETE FROM billing.card_pre_authorization_release WHERE card_pre_authorization_id = :id")
                        .param("id", UUID.fromString(id))
                        .update()
                }
                shouldThrow<DataAccessException> {
                    owner
                        .sql(
                            "UPDATE billing.card_pre_authorization SET status = 'PARTIALLY_CAPTURED', " +
                                "version = version + 1 WHERE id = :id",
                        ).param("id", UUID.fromString(id))
                        .update()
                }
            }
            withClue("liberada, uma captura direta é recusada") {
                shouldThrow<DataAccessException> { insertCapture(folio, id, shift, "1.00") }
            }
            storedCaptured(id) shouldBe "60.00"
        }

    // --- BillingApi: cauções em aberto e liberação sem permissão (M5 E04, H12) ------------------------

    @Test
    fun `the billing api lists the unsettled holds of a folio, expired included`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val soon = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
            val active = register(folio, cashier.auth, "100.00", inDays(5)).expect(201)["id"].asString()
            val partial = register(folio, cashier.auth, "100.00", inDays(5)).expect(201)["id"].asString()
            val expiring = register(folio, cashier.auth, "100.00", soon).expect(201)["id"].asString()
            val released = register(folio, cashier.auth, "100.00", inDays(5)).expect(201)["id"].asString()
            capture(folio, partial, cashier.auth, "40.00").expect(201)
            call(POST, "$base$folio/card-pre-authorizations/$released/release", cashier.auth).expect(200)

            billingApi.unsettledCardPreAuthorizations(FolioId(UUID.randomUUID())) shouldBe emptyList()
            moveClock(Duration.ofHours(2)) {
                val listed = billingApi.unsettledCardPreAuthorizations(FolioId(folio))
                listed.map { it.id.toString() } shouldContainExactlyInAnyOrder listOf(active, partial, expiring)
                listed.associate { it.id.toString() to it.status.name } shouldBe
                    mapOf(active to "ACTIVE", partial to "PARTIALLY_CAPTURED", expiring to "EXPIRED")
            }
        }

    @Test
    @Suppress("LongMethod") // sem permissão, vencida, com a conta encerrada, repetida e de outra conta
    fun `the billing api releases a hold without the permission, even expired or on a closed folio`(): Unit =
        with(billing) {
            val folio = openFolio()
            val other = openFolio()
            val cashier = cashier()
            val soon = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
            val active = register(folio, cashier.auth, "100.00", inDays(5)).expect(201)["id"].asString()
            val expired = register(folio, cashier.auth, "200.00", soon).expect(201)["id"].asString()
            val foreign = register(other, cashier.auth, "50.00", inDays(5)).expect(201)["id"].asString()
            val user = iam.createUserWith(TestGrant(folioRead, ALL))

            fun release(
                id: String,
                folioId: UUID = folio,
            ) = actingAs(user.id) {
                billingApi.releaseCardPreAuthorization(FolioId(folioId), CardPreAuthorizationId(UUID.fromString(id)))
            }

            release(active).status shouldBe CardPreAuthorizationStatus.RELEASED
            storedStatus(active) shouldBe "RELEASED"
            withClue("a trilha da liberação leva o usuário que chamou") {
                jdbc
                    .sql(
                        "SELECT user_id FROM audit.audit_entry WHERE entity_id = :id " +
                            "AND action = 'billing.card-pre-authorization.release'",
                    ).param("id", active)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .single() shouldBe user.id.value
            }
            closeFolio(folio)
            moveClock(Duration.ofHours(2)) {
                withClue("vencida e com a conta encerrada, também termina RELEASED") {
                    release(expired).status shouldBe CardPreAuthorizationStatus.RELEASED
                    storedStatus(expired) shouldBe "RELEASED"
                }
            }
            shouldThrow<DomainException> { release(active) }.code shouldBe BillingErrorCodes.PRE_AUTHORIZATION_SETTLED
            withClue("o id de outra conta e o inexistente são 404") {
                shouldThrow<DomainException> { release(foreign) }.code shouldBe
                    BillingErrorCodes.PRE_AUTHORIZATION_NOT_FOUND
                shouldThrow<DomainException> { release(UUID.randomUUID().toString()) }.code shouldBe
                    BillingErrorCodes.PRE_AUTHORIZATION_NOT_FOUND
            }
            storedStatus(foreign) shouldBe "ACTIVE"
        }

    // --- Apoio ---------------------------------------------------------------------------------------

    private val base = "/api/v1/folios/"

    /** Quem opera o caixa numa sessão de dispositivo fixa. */
    private inner class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private fun cashier(vararg extra: TestGrant): Operator =
        Operator(
            iam.createUserWith(
                TestGrant(receive, ALL),
                TestGrant(folioRead, ALL),
                TestGrant(operate, ALL),
                TestGrant(shiftsRead, ALL),
                *extra,
            ),
        )

    private fun openShift(operator: Operator): UUID = openShiftAs(operator.auth)

    private fun openShiftAs(auth: RequestPostProcessor): UUID =
        with(billing) {
            val cashPoint =
                call(
                    POST,
                    "/api/v1/cash-points",
                    admin,
                    mapOf("name" to "Caução ${UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)}"),
                ).expect(201).uuid("id")
            call(POST, "/api/v1/cashier-shifts", auth, mapOf("cashPointId" to cashPoint, "openingFloat" to "0.00"))
                .expect(201)
                .uuid("id")
        }

    @Suppress("LongParameterList") // os campos opcionais da caução
    private fun register(
        folio: UUID,
        auth: RequestPostProcessor,
        amount: String,
        expiresAt: Instant,
        nsu: String? = null,
        authorizationCode: String? = "AUT${(PIN_MIN..PIN_MAX).random()}",
        cardBrand: String? = null,
        cardLast4: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "$base$folio/card-pre-authorizations",
            auth,
            mapOf(
                "amount" to amount,
                "expiresAt" to expiresAt.toString(),
                "nsu" to nsu,
                "authorizationCode" to authorizationCode,
                "cardBrand" to cardBrand,
                "cardLast4" to cardLast4,
            ),
        )

    @Suppress("LongParameterList") // os campos opcionais da captura
    private fun capture(
        folio: UUID,
        id: String,
        auth: RequestPostProcessor,
        amount: String,
        nsu: String? = null,
        idempotencyKey: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "$base$folio/card-pre-authorizations/$id/capture",
            auth,
            mapOf("amount" to amount, "nsu" to nsu),
            idempotencyKey = idempotencyKey,
        )

    /** Roda [block] com o ator [userId] no contexto de segurança (chamada direta à `BillingApi`, fora do `MockMvc`). */
    private fun <T> actingAs(
        userId: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun inDays(days: Long): Instant =
        clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS)

    /** Avança o relógio da aplicação, roda [block] e o devolve (o contexto é compartilhado). */
    private fun moveClock(
        duration: Duration,
        block: () -> Unit,
    ) {
        clock.advance(duration)
        try {
            block()
        } finally {
            clock.advance(duration.negated())
        }
    }

    private fun closeFolio(folio: UUID) {
        owner
            .sql("UPDATE billing.folio SET status = 'CLOSED', closed_on = opened_on, closed_at = now() WHERE id = :id")
            .param("id", folio)
            .update()
    }

    /** Insere direto, como o dono, um recebimento que captura a caução (sem mexer na linha dela). */
    private fun insertCapture(
        folio: UUID,
        id: String,
        shift: UUID,
        amount: String,
    ) {
        // Sem transação explícita: o commit automático do comando roda também os triggers adiados.
        owner
            .sql(
                """
                INSERT INTO billing.payment (id, folio_id, payment_type, method, amount, deposit, payment_intent_id,
                    cashier_shift_id, business_date, received_at, received_by, card_pre_authorization_id)
                SELECT :paymentId, :folio, 'PAYMENT', 'CREDIT_CARD', CAST(:amount AS numeric), false, :intent, s.id,
                    current_date, now(), s.operator_id, :id
                FROM billing.cashier_shift s WHERE s.id = :shift
                """.trimIndent(),
            ).param("paymentId", UuidV7.generate())
            .param("folio", folio)
            .param("amount", amount)
            .param("intent", UuidV7.generate())
            .param("shift", shift)
            .param("id", UUID.fromString(id))
            .update()
    }

    private fun paymentRows(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.payment WHERE folio_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun intentRows(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM payments.payment_intent WHERE target_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun intentPurpose(intent: UUID): String =
        jdbc
            .sql("SELECT purpose FROM payments.payment_intent WHERE id = :id")
            .param("id", intent)
            .query(String::class.java)
            .single()

    private fun storedStatus(id: String): String =
        jdbc
            .sql("SELECT status FROM billing.card_pre_authorization WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(String::class.java)
            .single()

    private fun storedCaptured(id: String): String =
        jdbc
            .sql("SELECT captured_amount FROM billing.card_pre_authorization WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query { rs, _ -> rs.getBigDecimal(1).toPlainString() }
            .single()

    private fun capturedTotal(id: String): String =
        jdbc
            .sql("SELECT COALESCE(sum(amount), 0) FROM billing.payment WHERE card_pre_authorization_id = :id")
            .param("id", UUID.fromString(id))
            .query { rs, _ -> rs.getBigDecimal(1).setScale(2).toPlainString() }
            .single()

    /** Roda as [calls] em requisições simultâneas e devolve as respostas, na mesma ordem. */
    private fun concurrently(vararg calls: () -> MockHttpServletResponse): List<MockHttpServletResponse> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(calls.size)
        try {
            return calls
                .map { call ->
                    pool.submit(
                        Callable {
                            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                            call()
                        },
                    )
                }.also { start.countDown() }
                .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val SUFFIX_LENGTH = 8
        const val ROUNDS = 5
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}
