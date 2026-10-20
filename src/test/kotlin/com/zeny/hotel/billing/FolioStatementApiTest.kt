package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * O extrato da conta em JSON (M3 E10, RF-CTA-12 parte de dados; decisão do usuário em 2026-10-20): num cenário com
 * lançamento de outro dia, estorno, ajuste, taxa de serviço, transferência, caução capturada e liberada, recebimento
 * com troco, depósito e reembolso, as linhas agrupadas por data operacional somam exatamente o saldo da conta; a
 * prévia para o hóspede tem todas as linhas, sem usuário, motivo, aprovação, NSU, autorização nem outro dado interno.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class FolioStatementApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val clock: Clock,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    @Suppress("LongMethod") // o cenário completo e as conferências de cada parte do extrato
    fun `the statement groups every line by business date and adds up exactly to the folio balance`(): Unit =
        with(billing) {
            val scenario = scenario()
            val folio = scenario.folio
            val today = hotelApi.currentBusinessDate()
            val response = call(GET, "/api/v1/folios/$folio/statement", admin).expect(200)

            response["audience"].asString() shouldBe "INTERNAL"
            response["folio"]["id"].asString() shouldBe folio.toString()
            response["folio"]["status"].asString() shouldBe "OPEN"
            response["chargesTotal"].asString() shouldBe "435.00"
            response["paymentsTotal"].asString() shouldBe "310.00"
            response["balance"].asString() shouldBe "125.00"
            balance(folio) shouldBe "125.00"
            withClue("a soma das linhas é o saldo, exatamente") {
                lines(response).fold(Money.ZERO) { sum, line -> sum + Money.of(line["amount"].asString()) } shouldBe
                    Money.of(balance(folio))
            }

            val days = response["days"].values()
            days.map { it["businessDate"].asString() } shouldContainExactly
                listOf(today.minusDays(1).toString(), today.toString())
            days.map { listOf(it["charges"], it["payments"], it["balanceAtEnd"]).map(JsonNode::asString) } shouldBe
                listOf(listOf("100.00", "0.00", "100.00"), listOf("335.00", "310.00", "125.00"))
            withClue("em cada dia, em ordem de instante") {
                days.forEach { day ->
                    val instants = day["lines"].values().map { Instant.parse(it["at"].asString()) }
                    instants shouldBe instants.sorted()
                }
            }

            val postings = lines(response).filter { it["kind"].asString() == "POSTING" }
            postings.map { it["postingType"].asString() } shouldContainExactlyInAnyOrder
                listOf(
                    "REGULAR",
                    "REGULAR",
                    "REGULAR",
                    "REGULAR",
                    "REGULAR",
                    "SERVICE_CHARGE",
                    "REVERSAL",
                    "ADJUSTMENT",
                    "TRANSFER_OUT",
                )
            val charge = postings.single { it["postingType"].asString() == "SERVICE_CHARGE" }
            charge["amount"].asString() shouldBe "5.00"
            charge["serviceChargeRate"].asString() shouldBe "10.0000"
            charge["serviceChargeForPostingId"].asString() shouldBe scenario.item
            charge["revenueGroup"].asString() shouldBe "SERVICE_CHARGE"
            val reversal = postings.single { it["postingType"].asString() == "REVERSAL" }
            reversal["amount"].asString() shouldBe "-40.00"
            reversal["correctsPostingId"].asString() shouldBe scenario.laundry
            reversal["internal"]["reason"].asString() shouldBe LAUNDRY_REASON
            reversal["internal"]["userId"].asString() shouldBe iam.adminId().toString()
            postings.single { it["postingType"].asString() == "ADJUSTMENT" }["amount"].asString() shouldBe "-20.00"
            val outgoing = postings.single { it["postingType"].asString() == "TRANSFER_OUT" }
            outgoing["amount"].asString() shouldBe "-30.00"
            outgoing["internal"]["transferredToFolioId"].asString() shouldBe scenario.other.toString()
            outgoing["internal"]["reason"].asString() shouldBe TRANSFER_REASON

            val payments = lines(response).filter { it["kind"].asString() == "PAYMENT" }
            payments.map { it["description"].asString() to it["amount"].asString() } shouldContainExactlyInAnyOrder
                listOf(
                    "Captura de caução — Cartão de crédito" to "-200.00",
                    "Pagamento — Dinheiro" to "-100.00",
                    "Depósito — Pix" to "-20.00",
                    "Reembolso — Dinheiro" to "10.00",
                )
            val capture = payments.single { !it["cardPreAuthorizationId"].isNull }
            capture["cardPreAuthorizationId"].asString() shouldBe scenario.preAuthorization
            capture["cardLast4"].asString() shouldBe "4242"
            capture["internal"]["nsu"].asString() shouldBe CAPTURE_NSU
            capture["internal"]["cashierShiftId"].asString() shouldBe scenario.shift.toString()
            val cash = payments.single { it["description"].asString() == "Pagamento — Dinheiro" }
            cash["tendered"].asString() shouldBe "150.00"
            cash["change"].asString() shouldBe "50.00"
            payments.single { it["paymentType"].asString() == "REFUND" }["internal"]["reason"].asString() shouldBe
                REFUND_REASON

            val preAuthorization = response["preAuthorizations"].values().single()
            preAuthorization["status"].asString() shouldBe "RELEASED"
            preAuthorization["capturedAmount"].asString() shouldBe "200.00"
            preAuthorization["releasedAmount"].asString() shouldBe "300.00"
            preAuthorization["nsu"].asString() shouldBe PRE_AUTHORIZATION_NSU
            response["reopenings"].values() shouldBe emptyList()

            withClue("a outra conta tem a entrada da transferência, e o extrato dela soma o saldo dela") {
                val other = call(GET, "/api/v1/folios/${scenario.other}/statement", admin).expect(200)
                val incoming = lines(other).single()
                incoming["postingType"].asString() shouldBe "TRANSFER_IN"
                incoming["amount"].asString() shouldBe "30.00"
                incoming["internal"]["transferredFromFolioId"].asString() shouldBe folio.toString()
                other["balance"].asString() shouldBe balance(scenario.other)
            }
        }

    @Test
    fun `the guest preview keeps every line and the totals but no user, reason, approval nor terminal data`(): Unit =
        with(billing) {
            val scenario = scenario()
            val response = call(GET, "/api/v1/folios/${scenario.folio}/statement?audience=GUEST", admin)
            val guest = response.expect(200)
            guest["audience"].asString() shouldBe "GUEST"
            guest["balance"].asString() shouldBe "125.00"
            lines(guest).size shouldBe LINES
            lines(guest).forEach { it["internal"].isNull shouldBe true }
            lines(guest).fold(Money.ZERO) { sum, line -> sum + Money.of(line["amount"].asString()) } shouldBe
                Money.of("125.00")
            guest["reopenings"].isNull shouldBe true
            val preAuthorization = guest["preAuthorizations"].values().single()
            preAuthorization["cardLast4"].asString() shouldBe "4242"
            listOf("nsu", "authorizationCode", "registeredBy", "releasedBy").forEach {
                preAuthorization[it].isNull shouldBe true
            }

            val body = response.getContentAsString(Charsets.UTF_8)
            body shouldContain "Captura de caução"
            listOf(
                LAUNDRY_REASON,
                ADJUSTMENT_REASON,
                TRANSFER_REASON,
                REFUND_REASON,
                PRE_AUTHORIZATION_NSU,
                PRE_AUTHORIZATION_CODE,
                CAPTURE_NSU,
                DEPOSIT_NSU,
                iam.adminId().toString(),
                scenario.cashier.toString(),
                scenario.shift.toString(),
                scenario.other.toString(),
            ).forEach { secret -> withClue(secret) { body shouldNotContain secret } }
        }

    @Test
    fun `the statement needs folios read, a known audience and an existing folio`(): Unit =
        with(billing) {
            val folio = openFolio()
            val stranger = iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL))
            call(GET, "/api/v1/folios/$folio/statement", iam.asTestUser(stranger)).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/folios/$folio/statement?audience=HOTEL", admin).status shouldBe 400
            call(GET, "/api/v1/folios/${UuidV7.generate()}/statement", admin).problem(404, "FOLIO_NOT_FOUND")
            val empty = call(GET, "/api/v1/folios/$folio/statement", admin).expect(200)
            empty["days"].values() shouldBe emptyList()
            empty["balance"].asString() shouldBe "0.00"
        }

    // --- Apoio -----------------------------------------------------------------------------------------

    private data class Scenario(
        val folio: UUID,
        val other: UUID,
        val item: String,
        val laundry: String,
        val preAuthorization: String,
        val cashier: UUID,
        val shift: UUID,
    )

    /**
     * A conta com: uma diária do dia anterior (inserida direto, com a data operacional de ontem), uma diária de 300
     * ajustada em 20, um consumo de 50 com taxa de 10% (5), uma lavanderia de 40 estornada, um avulso de 30
     * transferido para outra conta; uma caução de 500 capturada em 200 e liberada; um recebimento de 100 em dinheiro
     * com 150 entregues, um depósito de 20 no Pix e um reembolso de 10 do dinheiro. Lançado 435, recebido 310, saldo
     * 125.
     */
    @Suppress("LongMethod") // o cenário inteiro
    private fun scenario(): Scenario =
        with(billing) {
            val folio = openFolio()
            val other = openFolio()
            insertYesterdayRoom(folio)
            val room = post(folio, codeId("ROOM_CHARGE"), "300.00")
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "50.00")
            val laundry = post(folio, codeId("LAUNDRY"), "40.00")
            call(
                POST,
                "/api/v1/folios/$folio/postings/${laundry["id"].asString()}/reverse",
                admin,
                mapOf("reason" to LAUNDRY_REASON),
            ).expect(201)
            call(
                POST,
                "/api/v1/folios/$folio/postings/${room["id"].asString()}/adjust",
                admin,
                mapOf("amount" to "20.00", "reason" to ADJUSTMENT_REASON),
            ).expect(201)
            val misc = post(folio, codeId("MISCELLANEOUS"), "30.00")
            transfer(folio, other, listOf(misc["id"].asString()), reason = TRANSFER_REASON).expect(201)

            val cashier =
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
            val auth = iam.asTestUser(cashier)
            val shift = openShift(auth)
            val preAuthorization =
                call(
                    POST,
                    "/api/v1/folios/$folio/card-pre-authorizations",
                    auth,
                    mapOf(
                        "amount" to "500.00",
                        "expiresAt" to
                            clock
                                .instant()
                                .plus(5, ChronoUnit.DAYS)
                                .truncatedTo(ChronoUnit.SECONDS)
                                .toString(),
                        "nsu" to PRE_AUTHORIZATION_NSU,
                        "authorizationCode" to PRE_AUTHORIZATION_CODE,
                        "cardBrand" to "VISA",
                        "cardLast4" to "4242",
                    ),
                ).expect(201)["id"].asString()
            call(
                POST,
                "/api/v1/folios/$folio/card-pre-authorizations/$preAuthorization/capture",
                auth,
                mapOf("amount" to "200.00", "nsu" to CAPTURE_NSU),
            ).expect(201)
            call(POST, "/api/v1/folios/$folio/card-pre-authorizations/$preAuthorization/release", auth).expect(200)
            val cash = pay(folio, auth, mapOf("method" to "CASH", "amount" to "100.00", "tendered" to "150.00"))
            pay(folio, auth, mapOf("method" to "PIX", "amount" to "20.00", "deposit" to true, "nsu" to DEPOSIT_NSU))
                .expect(201)
            call(
                POST,
                "/api/v1/folios/$folio/payments/${cash.expect(201)["id"].asString()}/refund",
                auth,
                mapOf("amount" to "10.00", "reason" to REFUND_REASON),
            ).expect(201)
            Scenario(
                folio,
                other,
                item["id"].asString(),
                laundry["id"].asString(),
                preAuthorization,
                cashier.id.value,
                shift,
            )
        }

    /** Uma diária de 100 com a data operacional de ontem (inserida pelo dono, como se lançada no dia anterior). */
    private fun insertYesterdayRoom(folio: UUID) {
        owner
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at)
                SELECT :id, :folio, c.id, 'REGULAR', 1, 100, 100, c.name, 'END_OF_DAY', :yesterday,
                    now() - interval '1 day'
                FROM billing.transaction_code c WHERE c.system_key = 'ROOM_CHARGE'
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("folio", folio)
            .param("yesterday", hotelApi.currentBusinessDate().minusDays(1))
            .update()
    }

    private fun lines(statement: JsonNode): List<JsonNode> = statement["days"].values().flatMap { it["lines"].values() }

    private companion object {
        const val LINES = 13
        const val LAUNDRY_REASON = "Hóspede não usou a lavanderia"
        const val ADJUSTMENT_REASON = "Desconto de cortesia da gerência"
        const val TRANSFER_REASON = "Avulso vai para a conta da empresa"
        const val REFUND_REASON = "Troco devolvido a mais"
        const val PRE_AUTHORIZATION_NSU = "004512"
        const val PRE_AUTHORIZATION_CODE = "A1B2C3"
        const val CAPTURE_NSU = "778899"
        const val DEPOSIT_NSU = "PIX55443"
    }
}
