package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.billing.application.PaymentSucceededListener
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.payments.PaymentSucceeded
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pagamentos manuais, depósitos e reembolsos (M3 E08, RF-CTA-06, 07, 10 e 15, RF-PAG-03, H1, H13, RNF-10): o turno de
 * caixa aberto obrigatório, vários pagamentos com troco, o esperado do turno, a intenção `MANUAL` com o
 * `PaymentSucceeded` no outbox e o listener que não duplica, o reembolso sensível com o limite cumulativo e nunca acima
 * do pago (nem em dois simultâneos), o depósito marcado, o `CITY_LEDGER` recusado, a imutabilidade no banco, a
 * idempotência e nenhum dado de cartão completo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta; os beans que eles usam
class PaymentsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val listener: PaymentSucceededListener,
    @Autowired private val failedPublications: FailedEventPublications,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val receive = definition(BillingPermissions.PAYMENTS_RECEIVE)
    private val refund = definition(BillingPermissions.PAYMENTS_REFUND)
    private val folioRead = definition(BillingPermissions.FOLIOS_READ)
    private val operate = definition(BillingPermissions.CASHIER_OPERATE)
    private val shiftsRead = definition(BillingPermissions.SHIFTS_READ)

    // --- Turno de caixa e recebimento -------------------------------------------------------------

    @Test
    fun `a payment without an open cashier shift is refused, with one it lands in the folio and the shift`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            val cashier = cashier()

            pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "100.00"))
                .problem(422, "CASHIER_SHIFT_REQUIRED")
            paymentRows(folio) shouldBe 0L
            intentRows(folio) shouldBe 0L

            val shift = openShift(cashier)
            val paid = pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "100.00", "nsu" to "e2e9"))
            withClue(paid.getContentAsString(Charsets.UTF_8)) { paid.status shouldBe 201 }
            paid.getHeader("Location").shouldNotBeNull() shouldContain "/api/v1/folios/$folio/payments/"
            val payment = paid.expect(201)
            payment["type"].asString() shouldBe "PAYMENT"
            payment["method"].asString() shouldBe "PIX"
            payment["amount"].asString() shouldBe "100.00"
            payment["nsu"].asString() shouldBe "E2E9"
            payment["deposit"].asBoolean() shouldBe false
            payment["cashierShiftId"].asString() shouldBe shift.toString()
            payment["receivedBy"].asString() shouldBe cashier.user.id.toString()
            payment["businessDate"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            payment["refundedAmount"].asString() shouldBe "0.00"
            payment["refundableAmount"].asString() shouldBe "100.00"
            balance(folio) shouldBe "200.00"

            val shiftView = call(GET, "/api/v1/cashier-shifts/$shift", admin).expect(200)
            shiftView.expected("PIX")["payments"].asString() shouldBe "100.00"
            shiftView.expected("PIX")["expected"].asString() shouldBe "100.00"

            val intent =
                jdbc
                    .sql(
                        "SELECT status, provider, amount, purpose, target_type, target_id, provider_reference " +
                            "FROM payments.payment_intent WHERE id = :id",
                    ).param("id", payment.uuid("paymentIntentId"))
                    .query { rs, _ ->
                        listOf(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getBigDecimal(3).toPlainString(),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getObject(6, UUID::class.java).toString(),
                            rs.getString(7),
                        )
                    }.single()
            intent shouldContainExactly
                listOf("SUCCEEDED", "MANUAL", "100.00", "billing.folio-payment", "billing.folio", "$folio", "E2E9")
        }

    @Test
    @Suppress("LongMethod") // vários pagamentos, troco, esperado por forma, fechamento e as recusas de formato
    fun `several payments with cash change keep the balance and the shift expected exact`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            val cashier = cashier()
            val shift = openShift(cashier, float = "50.00")

            val cash =
                pay(folio, cashier.auth, mapOf("method" to "CASH", "amount" to "100.00", "tendered" to "150.00"))
                    .expect(201)
            cash["tendered"].asString() shouldBe "150.00"
            cash["change"].asString() shouldBe "50.00"
            val card =
                pay(
                    folio,
                    cashier.auth,
                    mapOf(
                        "method" to "CREDIT_CARD",
                        "amount" to "150.00",
                        "nsu" to "123456",
                        "authorizationCode" to "ab12cd",
                        "cardBrand" to " visa ",
                        "cardLast4" to "4242",
                    ),
                ).expect(201)
            card["authorizationCode"].asString() shouldBe "AB12CD"
            card["cardBrand"].asString() shouldBe "VISA"
            card["cardLast4"].asString() shouldBe "4242"
            card["change"].isNull shouldBe true
            pay(folio, cashier.auth, mapOf("method" to "DEBIT_CARD", "amount" to "30.00")).expect(201)
            balance(folio) shouldBe "20.00"
            withClue("o valor pode passar do saldo devedor: a conta fica credora") {
                pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "50.00")).expect(201)
                balance(folio) shouldBe "-30.00"
            }
            listAll("/api/v1/folios/$folio/payments").map { it["method"].asString() } shouldContainExactly
                listOf("CASH", "CREDIT_CARD", "DEBIT_CARD", "PIX")

            withClue("num turno cego aberto, o operador não vê o esperado (nem os pagamentos por forma)") {
                val current = call(GET, "/api/v1/cashier-shifts/current", cashier.auth).expect(200)
                current["expected"].isNull shouldBe true
                current.toString() shouldNotContain "\"payments\""
            }
            val reviewed = call(GET, "/api/v1/cashier-shifts/$shift", admin).expect(200)
            withClue("o dinheiro soma só o valor aplicado, nunca o entregue") {
                reviewed.expected("CASH")["expected"].asString() shouldBe "150.00"
            }
            reviewed.expected("CREDIT_CARD")["expected"].asString() shouldBe "150.00"
            reviewed.expected("DEBIT_CARD")["expected"].asString() shouldBe "30.00"
            reviewed.expected("PIX")["expected"].asString() shouldBe "50.00"

            fun refused(
                body: Map<String, Any?>,
                field: String,
            ) = pay(folio, cashier.auth, body).problem(400, "PAYMENT_INVALID")["errors"][0]["field"].asString() shouldBe
                field
            refused(mapOf("method" to "CASH", "amount" to "100.00", "tendered" to "90.00"), "tendered")
            refused(mapOf("method" to "PIX", "amount" to "10.00", "tendered" to "20.00"), "tendered")
            refused(mapOf("method" to "CASH", "amount" to "10.00", "nsu" to "1234"), "nsu")
            refused(mapOf("method" to "PIX", "amount" to "10.00", "cardLast4" to "1234"), "cardLast4")
            refused(mapOf("method" to "DEBIT_CARD", "amount" to "10.00", "cardLast4" to "123"), "cardLast4")
            refused(mapOf("method" to "DEBIT_CARD", "amount" to "10.00", "cardBrand" to "V1SA"), "cardBrand")
            refused(mapOf("method" to "PIX", "amount" to "0.00"), "amount")
            refused(mapOf("method" to "PIX", "amount" to "1000000.01"), "amount")

            val closed =
                call(
                    POST,
                    "/api/v1/cashier-shifts/$shift/close",
                    cashier.auth,
                    mapOf(
                        "counts" to
                            listOf(
                                mapOf("method" to "CASH", "amount" to "150.00"),
                                mapOf("method" to "CREDIT_CARD", "amount" to "150.00"),
                                mapOf("method" to "DEBIT_CARD", "amount" to "30.00"),
                                mapOf("method" to "PIX", "amount" to "50.00"),
                            ),
                    ),
                ).expect(200)
            closed["shift"]["hasDifference"].asBoolean() shouldBe false
            closed["shift"]["expectedTotal"].asString() shouldBe "380.00"
            withClue("turno fechado não recebe mais nada") {
                pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "1.00"))
                    .problem(422, "CASHIER_SHIFT_REQUIRED")
            }
        }

    @Test
    fun `a payment on a closed or missing folio is refused, CITY_LEDGER is not available yet`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            pay(folio, cashier.auth, mapOf("method" to "CITY_LEDGER", "amount" to "10.00"))
                .problem(422, "PAYMENT_METHOD_NOT_AVAILABLE")
            pay(folio, cashier.auth, mapOf("method" to "BITCOIN", "amount" to "10.00")).status shouldBe 400
            pay(UuidV7.generate(), cashier.auth, mapOf("method" to "PIX", "amount" to "10.00"))
                .problem(404, "FOLIO_NOT_FOUND")
            owner.sql("UPDATE billing.folio SET status = 'CLOSED' WHERE id = :id").param("id", folio).update()
            pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "10.00")).problem(409, "FOLIO_CLOSED")
            paymentRows(folio) shouldBe 0L
            intentRows(folio) shouldBe 0L
            val reader = iam.createUserWith(TestGrant(folioRead, ALL), TestGrant(operate, ALL))
            pay(folio, iam.asTestUser(reader), mapOf("method" to "PIX", "amount" to "10.00"))
                .problem(403, "PERMISSION_DENIED")
        }

    // --- Depósito ---------------------------------------------------------------------------------

    @Test
    fun `a deposit before arrival is marked as such, also on its refund`(): Unit =
        with(billing) {
            val folio = openFolio(ownerType = "RESERVATION")
            val cashier = cashier(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            openShift(cashier)
            val deposit =
                pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "300.00", "deposit" to true))
                    .expect(201)
            deposit["deposit"].asBoolean() shouldBe true
            balance(folio) shouldBe "-300.00"
            jdbc
                .sql("SELECT purpose FROM payments.payment_intent WHERE id = :id")
                .param("id", deposit.uuid("paymentIntentId"))
                .query(String::class.java)
                .single() shouldBe "billing.folio-deposit"
            val refunded = refundPayment(folio, deposit["id"].asString(), "100.00", cashier.auth).expect(201)
            refunded["deposit"].asBoolean() shouldBe true
            jdbc
                .sql("SELECT count(*) FROM billing.payment WHERE folio_id = :id AND deposit")
                .param("id", folio)
                .query(Long::class.java)
                .single() shouldBe 2L
            balance(folio) shouldBe "-200.00"
        }

    // --- Outbox e listener (H1) -------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a publicação, a reentrega pelo outbox, a chamada direta e as anomalias
    fun `PaymentSucceeded goes to the outbox and the billing listener never registers the payment twice`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val payment = pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "80.00")).expect(201)
            val intentId = payment.uuid("paymentIntentId")

            val publication = awaitPublication(intentId) { it == "COMPLETED" }
            jdbc
                .sql("SELECT listener_id, event_type FROM shared.event_publication WHERE id = :id")
                .param("id", publication)
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
                .let { (listenerId, eventType) ->
                    listenerId shouldContain "PaymentSucceededListener"
                    eventType shouldBe PaymentSucceeded::class.java.name
                }

            withClue("a reentrega pelo outbox não registra outro pagamento") {
                jdbc
                    .sql(
                        "UPDATE shared.event_publication SET status = 'FAILED', completion_date = NULL " +
                            "WHERE id = :id",
                    ).param("id", publication)
                    .update() shouldBe 1
                failedPublications.resubmit(
                    ResubmissionOptions.defaults().withFilter { it.identifier == publication },
                )
                awaitPublication(intentId) { it == "COMPLETED" } shouldBe publication
                paymentsOfIntent(intentId) shouldBe 1L
            }

            val event =
                PaymentSucceeded(
                    intentId,
                    "billing.folio-payment",
                    "billing.folio",
                    folio,
                    Money.of("80.00"),
                    "MANUAL",
                    null,
                    Instant.now(),
                )
            // O listener sem o proxy (o @ApplicationModuleListener é assíncrono): roda aqui, e a falha aparece.
            val direct = AopTestUtils.getTargetObject<PaymentSucceededListener>(listener)
            direct.on(event)
            direct.on(event)
            paymentsOfIntent(intentId) shouldBe 1L
            paymentRows(folio) shouldBe 1L

            withClue("outro destino é ignorado; a intenção da conta sem pagamento fica falhando, visível") {
                direct.on(event.copy(intentId = UuidV7.generate(), targetType = "pos.tab"))
                shouldThrow<IllegalStateException> { direct.on(event.copy(intentId = UuidV7.generate())) }
            }
        }

    // --- Reembolso --------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a recusa, a aprovação, o vínculo, a trilha e o esperado do turno
    fun `a refund needs approval, is linked to the payment, audited with both users and never above the paid`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "200.00")
            val requester = cashier()
            val shift = openShift(requester)
            val card =
                pay(
                    folio,
                    requester.auth,
                    mapOf("method" to "CREDIT_CARD", "amount" to "200.00", "cardBrand" to "Elo", "cardLast4" to "9876"),
                ).expect(201)
            val id = card["id"].asString()

            refundPayment(folio, id, "200.01", requester.auth).problem(422, "REFUND_EXCEEDS_REFUNDABLE")
            val required = refundPayment(folio, id, "50.00", requester.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "billing.payment.refund"
            required["permission"].asString() shouldBe "billing.payments.refund"
            required["resourceRef"].asString() shouldBe "billing.payment:$id"
            required["limit"]["key"].asString() shouldBe "maxAmount"
            required["limit"]["amount"].asString() shouldBe "50"
            refundRows(id) shouldBe 0L

            val approver =
                approverWith(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            val approval = approve(requester, required, approver).expect(201)
            val approvalId = UUID.fromString(approval["approvalId"].asString())
            val refunded =
                refundPayment(folio, id, "50.00", requester.auth, approval["approvalToken"].asString()).expect(201)
            refunded["type"].asString() shouldBe "REFUND"
            refunded["method"].asString() shouldBe "CREDIT_CARD"
            refunded["amount"].asString() shouldBe "50.00"
            refunded["refundOfPaymentId"].asString() shouldBe id
            refunded["cardLast4"].asString() shouldBe "9876"
            refunded["cardBrand"].asString() shouldBe "ELO"
            refunded["reason"].asString() shouldBe "Cancelamento dentro da política"
            refunded["approvalId"].asString() shouldBe approvalId.toString()
            refunded["approvedBy"].asString() shouldBe approver.user.id.toString()
            refunded["receivedBy"].asString() shouldBe requester.user.id.toString()
            refunded["cashierShiftId"].asString() shouldBe shift.toString()
            refunded["refundedAmount"].isNull shouldBe true

            val original = call(GET, "/api/v1/folios/$folio/payments/$id", admin).expect(200)
            original["refundedAmount"].asString() shouldBe "50.00"
            original["refundableAmount"].asString() shouldBe "150.00"
            balance(folio) shouldBe "50.00"
            call(GET, "/api/v1/cashier-shifts/$shift", admin)
                .expect(200)
                .expected("CREDIT_CARD")["expected"]
                .asString() shouldBe "150.00"

            val entries =
                jdbc
                    .sql(
                        "SELECT entity_type, user_id FROM audit.audit_entry " +
                            "WHERE action = 'billing.payment.refund' AND approval_id = :id",
                    ).param("id", approvalId)
                    .query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }
                    .list()
            entries.map { it.first } shouldContainExactlyInAnyOrder listOf("billing.payment", "iam.approval")
            entries.forEach { it.second shouldBe requester.user.id.value }

            withClue("acima do reembolsável, 422 antes da aprovação; um reembolso não se reembolsa") {
                refundPayment(folio, id, "150.01", requester.auth).problem(422, "REFUND_EXCEEDS_REFUNDABLE")
                refundPayment(folio, refunded["id"].asString(), "1.00", requester.auth)
                    .problem(422, "PAYMENT_NOT_REFUNDABLE")
                refundPayment(folio, UuidV7.generate().toString(), "1.00", requester.auth)
                    .problem(404, "PAYMENT_NOT_FOUND")
                refundPayment(openFolio(), id, "1.00", requester.auth).problem(404, "PAYMENT_NOT_FOUND")
            }
            withClue("sem billing.folios.read, 403 antes de ler a conta") {
                val outsider = iam.createUserWith(TestGrant(receive, ALL), TestGrant(operate, ALL))
                refundPayment(folio, id, "1.00", iam.asTestUser(outsider)).problem(403, "PERMISSION_DENIED")
            }
        }

    @Test
    fun `the refund limit is cumulative per payment and a cash refund leaves the drawer of who refunds`(): Unit =
        with(billing) {
            val folio = openFolio()
            val receiver = cashier()
            val receiverShift = openShift(receiver, float = "10.00")
            val paid = pay(folio, receiver.auth, mapOf("method" to "CASH", "amount" to "200.00")).expect(201)
            val id = paid["id"].asString()

            val ceiling = mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(Money.of("100.00").amount))
            val refunder = cashier(TestGrant(refund, ALL, ceiling))
            val refunderShift = openShift(refunder, float = "100.00")
            withClue("dentro do teto, sem aprovação") {
                refundPayment(folio, id, "60.00", refunder.auth).expect(201)["approvalId"].isNull shouldBe true
            }
            withClue("o segundo passa o teto somado ao primeiro: pede aprovação com o total") {
                refundPayment(folio, id, "60.00", refunder.auth)
                    .problem(403, "APPROVAL_REQUIRED")["limit"]["amount"]
                    .asString() shouldBe "120"
            }
            withClue("o dinheiro sai da gaveta de quem devolveu, não da de quem recebeu") {
                call(GET, "/api/v1/cashier-shifts/$refunderShift", admin)
                    .expect(200)
                    .expected("CASH")["expected"]
                    .asString() shouldBe "40.00"
                call(GET, "/api/v1/cashier-shifts/$receiverShift", admin)
                    .expect(200)
                    .expected("CASH")["expected"]
                    .asString() shouldBe "210.00"
            }
            refundPayment(folio, id, "1.00", refunder.auth, nsu = "1234").problem(400, "PAYMENT_INVALID")
        }

    @Test
    fun `two simultaneous refunds never go beyond the paid amount, nor does a refund inserted directly`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            openShift(cashier)
            val paid = pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "100.00")).expect(201)
            val id = paid["id"].asString()

            val results =
                concurrently(
                    { refundPayment(folio, id, "60.00", cashier.auth) },
                    { refundPayment(folio, id, "60.00", cashier.auth) },
                )
            results.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 422)
            results.single { it.status == 422 }.problem(422, "REFUND_EXCEEDS_REFUNDABLE")
            refundedTotal(id) shouldBe "60.00"

            withClue("o banco também recusa um reembolso acima do pago, inserido direto pelo dono") {
                shouldThrow<DataAccessException> {
                    owner
                        .sql(
                            """
                            INSERT INTO billing.payment (id, folio_id, payment_type, method, amount, deposit,
                                refund_of_id, reason, cashier_shift_id, business_date, received_at, received_by)
                            SELECT :newId, folio_id, 'REFUND', method, 40.01, deposit, id, 'Direto', cashier_shift_id,
                                business_date, now(), received_by
                            FROM billing.payment WHERE id = :id
                            """.trimIndent(),
                        ).param("newId", UuidV7.generate())
                        .param("id", UUID.fromString(id))
                        .update()
                }.mostSpecificCause.message shouldContain "acima do valor pago"
            }
            refundedTotal(id) shouldBe "60.00"
        }

    @Test
    fun `simultaneous refunds by two operators on their own shifts are held by the folio lock`(): Unit =
        with(billing) {
            // Achado B1 do verificador: cada operador trava o próprio turno, então só a trava da conta os enfileira.
            val folio = openFolio()
            val receiver = cashier()
            openShift(receiver)
            val id =
                pay(
                    folio,
                    receiver.auth,
                    mapOf("method" to "PIX", "amount" to "100.00"),
                ).expect(201)["id"].asString()
            val unlimited = mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)
            val first = cashier(TestGrant(refund, ALL, unlimited))
            val second = cashier(TestGrant(refund, ALL, unlimited))
            val firstShift = openShift(first)
            val secondShift = openShift(second)
            firstShift shouldNotBe secondShift

            val results =
                concurrently(
                    { refundPayment(folio, id, "60.00", first.auth) },
                    { refundPayment(folio, id, "60.00", second.auth) },
                )
            withClue(results.map { it.getContentAsString(Charsets.UTF_8) }) {
                results.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 422)
            }
            results.single { it.status == 422 }.problem(422, "REFUND_EXCEEDS_REFUNDABLE")
            refundedTotal(id) shouldBe "60.00"
            refundRows(id) shouldBe 1L
        }

    @Test
    @Suppress("LongMethod") // o reembolso no cartão, o esperado negativo, as recusas e o fechamento
    fun `a card refund in a shift without card receipts closes with a negative count and no difference`(): Unit =
        with(billing) {
            // Achado M1 do verificador (decisão do usuário na revisão da E08): fora do dinheiro, o declarado pode ser
            // negativo (o líquido do relatório da maquininha); o dinheiro continua de 0 para cima.
            val folio = openFolio()
            val receiver = cashier()
            openShift(receiver)
            val card =
                pay(folio, receiver.auth, mapOf("method" to "CREDIT_CARD", "amount" to "50.00", "cardLast4" to "4242"))
                    .expect(201)
            val refunder = cashier(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            val shift = openShift(refunder)
            refundPayment(folio, card["id"].asString(), "50.00", refunder.auth).expect(201)
            call(GET, "/api/v1/cashier-shifts/$shift", admin)
                .expect(200)
                .expected("CREDIT_CARD")["expected"]
                .asString() shouldBe "-50.00"

            val path = "/api/v1/cashier-shifts/$shift/close"

            fun close(vararg counts: Pair<String, String>) =
                call(
                    POST,
                    path,
                    refunder.auth,
                    mapOf("counts" to counts.map { mapOf("method" to it.first, "amount" to it.second) }),
                )
            withClue("dinheiro negativo continua recusado") {
                close("CASH" to "-1.00").problem(400, "CASHIER_COUNT_INVALID")["errors"][0]["field"].asString() shouldBe
                    "counts[0].amount"
            }
            withClue("fora do dinheiro, o teto absoluto continua") {
                close("CREDIT_CARD" to "-1000000.01").problem(400, "CASHIER_COUNT_INVALID")
            }
            val closed = close("CREDIT_CARD" to "-50.00").expect(200)["shift"]
            closed["hasDifference"].asBoolean() shouldBe false
            closed["differenceTotal"].asString() shouldBe "0.00"
            val count = closed["counts"].values().single { it["method"].asString() == "CREDIT_CARD" }
            count["declared"].asString() shouldBe "-50.00"
            count["difference"].asString() shouldBe "0.00"
            jdbc
                .sql(
                    "SELECT count(*) FROM notifications.notification " +
                        "WHERE type = 'billing.cashier-shift-difference' AND title LIKE :number",
                ).param("number", "%${closed["number"].asString()}")
                .query(Long::class.java)
                .single() shouldBe 0L
        }

    // --- Imutabilidade, idempotência e dados de cartão --------------------------------------------

    @Test
    fun `payments can never be updated nor deleted, by the application role or by the owner`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val id = pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "10.00")).expect(201).uuid("id")

            listOf(
                "UPDATE billing.payment SET amount = 1 WHERE id = :id",
                "DELETE FROM billing.payment WHERE id = :id",
            ).forEach { sql ->
                withClue("papel da aplicação: $sql") {
                    shouldThrow<DataAccessException> { jdbc.sql(sql).param("id", id).update() }
                        .mostSpecificCause.message shouldContain "permission denied"
                }
                withClue("dono: $sql") {
                    shouldThrow<DataAccessException> { owner.sql(sql).param("id", id).update() }
                        .mostSpecificCause.message shouldContain "imutáveis"
                }
            }
            shouldThrow<DataAccessException> { jdbc.sql("TRUNCATE billing.payment").update() }
                .mostSpecificCause.message shouldContain "permission denied"
            withClue("a intenção confirmada também não muda nem é apagada") {
                val intent =
                    jdbc
                        .sql("SELECT payment_intent_id FROM billing.payment WHERE id = :id")
                        .param("id", id)
                        .query(UUID::class.java)
                        .single()
                shouldThrow<DataAccessException> {
                    owner
                        .sql("UPDATE payments.payment_intent SET amount = 1 WHERE id = :id")
                        .param("id", intent)
                        .update()
                }.mostSpecificCause.message shouldContain "não muda"
                shouldThrow<DataAccessException> {
                    owner.sql("DELETE FROM payments.payment_intent WHERE id = :id").param("id", intent).update()
                }.mostSpecificCause.message shouldContain "nunca são apagados"
            }
        }

    @Test
    fun `receiving and refunding need an Idempotency-Key, and a replay never records twice`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier(TestGrant(refund, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited)))
            openShift(cashier)
            val body = mapOf("method" to "PIX", "amount" to "70.00")
            pay(folio, cashier.auth, body, idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            val key = UuidV7.generate().toString()
            val first = pay(folio, cashier.auth, body, idempotencyKey = key).expect(201)
            val replay = pay(folio, cashier.auth, body, idempotencyKey = key)
            replay.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            replay.expect(201)["id"] shouldBe first["id"]
            paymentRows(folio) shouldBe 1L
            intentRows(folio) shouldBe 1L

            val id = first["id"].asString()
            refundPayment(folio, id, "10.00", cashier.auth, idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            val refundKey = UuidV7.generate().toString()
            refundPayment(folio, id, "10.00", cashier.auth, idempotencyKey = refundKey).expect(201)
            refundPayment(folio, id, "10.00", cashier.auth, idempotencyKey = refundKey)
                .getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            refundRows(id) shouldBe 1L
        }

    @Test
    @Suppress("LongMethod") // cada forma recusada, o campo desconhecido e as chaves do contrato
    fun `no full card number nor CVV is accepted, stored or published`(): Unit =
        with(billing) {
            val folio = openFolio()
            val cashier = cashier()
            openShift(cashier)
            val pan = "4111111111111111"
            listOf(
                mapOf("nsu" to pan) to "nsu",
                mapOf("authorizationCode" to "4111111111111") to "authorizationCode",
                mapOf("nsu" to "A4111111111111B") to "nsu",
                mapOf("nsu" to "4111-1111-1111-1111") to "nsu",
                mapOf("cardLast4" to pan) to "cardLast4",
                mapOf("cardBrand" to pan) to "cardBrand",
            ).forEach { (fields, field) ->
                withClue(fields) {
                    pay(folio, cashier.auth, mapOf("method" to "CREDIT_CARD", "amount" to "10.00") + fields)
                        .problem(400, "PAYMENT_INVALID")["errors"][0]["field"]
                        .asString() shouldBe field
                }
            }
            withClue("um campo desconhecido com o cartão completo é ignorado: não vai a lugar nenhum") {
                val paid =
                    pay(
                        folio,
                        cashier.auth,
                        mapOf(
                            "method" to "CREDIT_CARD",
                            "amount" to "10.00",
                            "cardLast4" to "1111",
                            "cardNumber" to pan,
                            "cvv" to "123",
                        ),
                    ).expect(201)
                paid.toString() shouldNotContain pan
                jdbc
                    .sql("SELECT p::text FROM billing.payment p WHERE id = :id")
                    .param("id", paid.uuid("id"))
                    .query(String::class.java)
                    .single() shouldNotContain pan
                jdbc
                    .sql("SELECT count(*) FROM audit.audit_entry WHERE after_state::text LIKE :pan")
                    .param("pan", "%$pan%")
                    .query(Long::class.java)
                    .single() shouldBe 0L
                jdbc
                    .sql("SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :pan")
                    .param("pan", "%$pan%")
                    .query(Long::class.java)
                    .single() shouldBe 0L
            }
            withClue("o contrato não tem campo de número de cartão nem de CVV (só bandeira e últimos 4 dígitos)") {
                val keys =
                    Files
                        .readAllLines(Path.of("api/openapi.yaml"))
                        .map { it.trim() }
                        .filter { PROPERTY_KEY.containsMatchIn(it) }
                        .map { it.substringBefore(':').lowercase() }
                        .toSet()
                withClue(
                    "além da bandeira e dos últimos 4, só o vínculo e o schema da caução (E09)",
                ) {
                    keys.filter { it.startsWith("card") } shouldContainExactlyInAnyOrder
                        listOf("cardbrand", "cardlast4", "cardpreauthorizationid", "cardpreauthorizationresponse")
                }
                keys.filter { key -> key == "pan" || SECRET_CARD_KEYS.any { key.contains(it) } } shouldBe emptyList()
            }
        }

    @Test
    fun `manager and finance refund without a ceiling, receptionist receives but always asks for approval`() {
        val grants =
            jdbc
                .sql(
                    """
                    SELECT r.template_key, g.permission_code, l.unlimited FROM iam.role r
                    JOIN iam.role_grant g ON g.role_id = r.id
                    LEFT JOIN iam.role_grant_limit l ON l.grant_id = g.id AND l.limit_key = 'maxAmount'
                    WHERE r.template_key IN ('MANAGER', 'FINANCE', 'RECEPTIONIST', 'FNB_CASHIER')
                      AND g.permission_code IN ('billing.payments.receive', 'billing.payments.refund')
                    """.trimIndent(),
                ).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBoolean(3)) }
                .list()
        grants shouldContainExactlyInAnyOrder
            listOf(
                Triple("MANAGER", "billing.payments.receive", false),
                Triple("FINANCE", "billing.payments.receive", false),
                Triple("RECEPTIONIST", "billing.payments.receive", false),
                Triple("MANAGER", "billing.payments.refund", true),
                Triple("FINANCE", "billing.payments.refund", true),
            )
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Quem opera o caixa numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private inner class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

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

    private fun openShift(
        operator: Operator,
        float: String = "0.00",
    ): UUID =
        with(billing) {
            val cashPoint =
                call(
                    POST,
                    "/api/v1/cash-points",
                    admin,
                    mapOf("name" to "Caixa ${UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)}"),
                ).expect(201).uuid("id")
            call(
                POST,
                "/api/v1/cashier-shifts",
                operator.auth,
                mapOf("cashPointId" to cashPoint, "openingFloat" to float),
            ).expect(201).uuid("id")
        }

    private fun pay(
        folio: UUID,
        auth: RequestPostProcessor,
        body: Map<String, Any?>,
        idempotencyKey: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        billing.call(POST, "/api/v1/folios/$folio/payments", auth, body, idempotencyKey = idempotencyKey)

    @Suppress("LongParameterList") // os campos opcionais do reembolso
    private fun refundPayment(
        folio: UUID,
        payment: String,
        amount: String,
        auth: RequestPostProcessor,
        approvalToken: String? = null,
        idempotencyKey: String? = UuidV7.generate().toString(),
        nsu: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/payments/$payment/refund",
            auth,
            mapOf("amount" to amount, "reason" to "Cancelamento dentro da política", "nsu" to nsu),
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
        )

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Operator,
        required: JsonNode,
        approver: Approver,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "limit" to
                    mapOf(
                        "key" to required["limit"]["key"].asString(),
                        "amount" to required["limit"]["amount"].asString(),
                    ),
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Conferido com o hóspede",
            ),
        )

    private fun JsonNode.expected(method: String): JsonNode =
        this["expected"].values().single { it["method"].asString() == method }

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

    private fun paymentsOfIntent(intent: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.payment WHERE payment_intent_id = :id")
            .param("id", intent)
            .query(Long::class.java)
            .single()

    private fun refundRows(payment: String): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.payment WHERE refund_of_id = :id")
            .param("id", UUID.fromString(payment))
            .query(Long::class.java)
            .single()

    private fun refundedTotal(payment: String): String =
        jdbc
            .sql("SELECT COALESCE(sum(amount), 0) FROM billing.payment WHERE refund_of_id = :id")
            .param("id", UUID.fromString(payment))
            .query { rs, _ -> rs.getBigDecimal(1).setScale(2).toPlainString() }
            .single()

    /** Espera a publicação do `PaymentSucceeded` da intenção ficar na [status] pedida; devolve o id dela. */
    private fun awaitPublication(
        intent: UUID,
        status: (String) -> Boolean,
    ): UUID {
        var found: UUID? = null
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            jdbc
                .sql(
                    "SELECT id, status FROM shared.event_publication " +
                        "WHERE event_type = :type AND serialized_event LIKE :intent",
                ).param("type", PaymentSucceeded::class.java.name)
                .param("intent", "%$intent%")
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
                .list()
                .singleOrNull()
                ?.takeIf { status(it.second) }
                ?.also { found = it.first } != null
        }
        return checkNotNull(found)
    }

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
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        val PROPERTY_KEY = Regex("^[A-Za-z0-9_]+:")
        val SECRET_CARD_KEYS = listOf("cvv", "cvc", "securitycode", "cardnumber", "card_number")
    }
}
