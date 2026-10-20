package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Roteamento (M3 E05, RF-CTA-04): a regra vigente na data operacional leva o lançamento (pela API HTTP e pela
 * [BillingApi]) à conta de destino com a origem registrada, fora do período ele fica; a regra por código prevalece
 * sobre a por grupo; a cadeia segue até a conta sem regra; sobreposição `409`, ciclo `422` e destino encerrado `409`;
 * edição com `If-Match`, desativação e reativação; permissões, idempotência e trilha.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class RoutingApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a posting whose code is in a rule in force goes to the target folio with the origin recorded`(): Unit =
        with(billing) {
            val reservation = openFolio()
            val company = openFolio("GROUP")
            val room = codeId("ROOM_CHARGE")
            val response = createRule(reservation, company, today, today.plusDays(3), codes = listOf(room))
            val rule = response.expect(201)
            response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/routing-rules/${rule["id"].asString()}"
            response.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            rule["sourceFolioId"].asString() shouldBe reservation.toString()
            rule["targetFolioId"].asString() shouldBe company.toString()
            rule["transactionCodeIds"].values().map { it.asString() } shouldContainExactly listOf(room.toString())
            rule["revenueGroup"].isNull shouldBe true
            rule["active"].asBoolean() shouldBe true

            val postResponse =
                call(
                    POST,
                    "/api/v1/folios/$reservation/postings",
                    admin,
                    mapOf("transactionCodeId" to room, "unitPrice" to "300.00"),
                )
            val routed = postResponse.expect(201)
            routed["folioId"].asString() shouldBe company.toString()
            routed["routedFromFolioId"].asString() shouldBe reservation.toString()
            routed["routingRuleId"].asString() shouldBe rule["id"].asString()
            routed["type"].asString() shouldBe "REGULAR"
            postResponse.getHeader(HttpHeaders.LOCATION) shouldBe
                "/api/v1/folios/$company/postings/${routed["id"].asString()}"
            stored(routed["id"].asString(), "routed_from_folio_id") shouldBe reservation
            balance(reservation) shouldBe "0.00"
            balance(company) shouldBe "300.00"

            // Outro código: fica na origem.
            val laundry = post(reservation, codeId("LAUNDRY"), "20.00")
            laundry["folioId"].asString() shouldBe reservation.toString()
            laundry["routedFromFolioId"].isNull shouldBe true
            balance(reservation) shouldBe "20.00"

            // Pela BillingApi (inclusive com o sistema como ator).
            val viaApi =
                billingApi.post(
                    FolioId(reservation),
                    NewPosting(TransactionCodeId(room), Money.of("310.00"), source = PostingSource.END_OF_DAY),
                )
            viaApi.folioId shouldBe FolioId(company)
            viaApi.routedFromFolioId shouldBe FolioId(reservation)
            viaApi.routingRuleId shouldBe RoutingRuleId(UUID.fromString(rule["id"].asString()))
            balance(company) shouldBe "610.00"

            // A trilha registra a origem e a cadeia.
            val audit = auditAfter(routed["id"].asString(), "billing.posting.create")
            audit["folioId"].asString() shouldBe company.toString()
            audit["routedFromFolioId"].asString() shouldBe reservation.toString()
            audit["routingRuleIds"].values().map { it.asString() } shouldContainExactly listOf(rule["id"].asString())
        }

    @Test
    fun `outside the period of the rule the posting stays on the origin`(): Unit =
        with(billing) {
            val origin = openFolio()
            val past = openFolio()
            val future = openFolio()
            val food = codeId("FOOD_BEVERAGE")
            createRule(origin, past, today.minusDays(10), today.minusDays(1), codes = listOf(food)).expect(201)
            createRule(origin, future, today.plusDays(1), null, codes = listOf(food)).expect(201)
            val posting = post(origin, food, "45.00")
            posting["folioId"].asString() shouldBe origin.toString()
            posting["routedFromFolioId"].isNull shouldBe true
            listOf(balance(origin), balance(past), balance(future)) shouldContainExactly listOf("45.00", "0.00", "0.00")
        }

    @Test
    fun `a rule by code prevails over a rule by revenue group, and the chain goes on to the last folio`(): Unit =
        with(billing) {
            val a = openFolio()
            val master = openFolio("GROUP")
            val minibar = openFolio()
            val company = openFolio("EVENT")
            val custom = createCode("FRIGO", "FOOD_BEVERAGE")
            createRule(a, master, today, null, group = "FOOD_BEVERAGE").expect(201)
            createRule(a, minibar, today, null, codes = listOf(custom)).expect(201)
            // A cadeia: o frigobar leva o código à empresa.
            createRule(minibar, company, today, null, codes = listOf(custom)).expect(201)

            val drink = post(a, custom, "12.00")
            drink["folioId"].asString() shouldBe company.toString()
            drink["routedFromFolioId"].asString() shouldBe a.toString()
            val dinner = post(a, codeId("FOOD_BEVERAGE"), "80.00")
            dinner["folioId"].asString() shouldBe master.toString()
            post(a, codeId("ROOM_CHARGE"), "200.00")["folioId"].asString() shouldBe a.toString()
            listOf(balance(a), balance(master), balance(minibar), balance(company)) shouldContainExactly
                listOf("200.00", "80.00", "0.00", "12.00")
            auditAfter(drink["id"].asString(), "billing.posting.create")["routingRuleIds"].values().size shouldBe 2
        }

    @Test
    fun `overlapping rules of the same level are refused with 409, a rule forming a cycle with 422`(): Unit =
        with(billing) {
            val a = openFolio()
            val b = openFolio()
            val c = openFolio()
            val room = codeId("ROOM_CHARGE")
            val food = codeId("FOOD_BEVERAGE")
            createRule(a, b, today, today.plusDays(5), codes = listOf(room, food)).expect(201)
            createRule(a, c, today.plusDays(5), null, codes = listOf(food)).problem(409, "ROUTING_RULE_OVERLAP")
            createRule(a, c, today.plusDays(6), null, codes = listOf(food)).expect(201)
            createRule(a, b, today, null, group = "LAUNDRY").expect(201)
            createRule(a, c, today.minusDays(3), today, group = "LAUNDRY").problem(409, "ROUTING_RULE_OVERLAP")
            // Código × grupo não é sobreposição: a por código prevalece.
            createRule(a, c, today, null, group = "ROOM").expect(201)

            // Ciclos: B → A, B → C → A e A → A.
            createRule(b, a, today, null, codes = listOf(room)).problem(422, "ROUTING_RULE_CYCLE")
            createRule(c, a, today.plusDays(100), null, group = "FOOD_BEVERAGE").problem(422, "ROUTING_RULE_CYCLE")
            createRule(a, a, today, null, codes = listOf(codeId("TOURISM_FEE"))).problem(422, "ROUTING_RULE_CYCLE")
            // Sem lançamento em comum com o caminho: aceita.
            createRule(b, a, today, null, codes = listOf(codeId("TOURISM_FEE"))).expect(201)
            jdbc
                .sql("SELECT count(*) FROM billing.routing_rule WHERE source_folio_id IN (:ids)")
                .param("ids", listOf(a, b, c))
                .query(Long::class.java)
                .single() shouldBe 5L
        }

    @Test
    fun `a closed target refuses a new rule and a routed posting with 409 ROUTING_TARGET_CLOSED`(): Unit =
        with(billing) {
            val origin = openFolio()
            val target = openFolio()
            val closed = openFolio()
            val room = codeId("ROOM_CHARGE")
            createRule(origin, target, today, null, codes = listOf(room)).expect(201)
            close(closed)
            close(target)
            createRule(origin, closed, today, null, group = "FEES").problem(409, "ROUTING_TARGET_CLOSED")
            call(
                POST,
                "/api/v1/folios/$origin/postings",
                admin,
                mapOf(
                    "transactionCodeId" to room,
                    "unitPrice" to "1.00",
                ),
            ).problem(409, "ROUTING_TARGET_CLOSED")
            // A origem continua aberta e recebe o que não é roteado.
            post(origin, codeId("LAUNDRY"), "5.00")["folioId"].asString() shouldBe origin.toString()
            // Conta pedida encerrada: FOLIO_CLOSED, com ou sem regra.
            close(origin)
            call(
                POST,
                "/api/v1/folios/$origin/postings",
                admin,
                mapOf(
                    "transactionCodeId" to room,
                    "unitPrice" to "1.00",
                ),
            ).problem(409, "FOLIO_CLOSED")
            postingsIn(target) shouldBe 0L
        }

    @Test
    fun `a rule is edited with If-Match, deactivated and reactivated with the checks, never deleted`(): Unit =
        with(billing) {
            val a = openFolio()
            val b = openFolio()
            val c = openFolio()
            val room = codeId("ROOM_CHARGE")
            val rule = createRule(a, b, today, null, codes = listOf(room)).expect(201)
            val id = rule["id"].asString()
            val path = "/api/v1/routing-rules/$id"
            val body =
                mapOf("targetFolioId" to c, "revenueGroup" to "ROOM", "startsOn" to today.toString(), "endsOn" to null)
            call(PUT, path, admin, body).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, admin, body, ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")
            val edited = call(PUT, path, admin, body, ifMatch = etagOf(path)).expect(200)
            edited["targetFolioId"].asString() shouldBe c.toString()
            edited["revenueGroup"].asString() shouldBe "ROOM"
            edited["transactionCodeIds"].values().size shouldBe 0
            post(a, room, "100.00")["folioId"].asString() shouldBe c.toString()

            // Os dois critérios juntos ou nenhum: 400.
            call(PUT, path, admin, body + mapOf("transactionCodeIds" to listOf(room)), ifMatch = etagOf(path))
                .problem(400, "ROUTING_RULE_INVALID")
            call(PUT, path, admin, body - "revenueGroup", ifMatch = etagOf(path)).problem(400, "ROUTING_RULE_INVALID")
            call(PUT, path, admin, body + mapOf("endsOn" to today.minusDays(1).toString()), ifMatch = etagOf(path))
                .problem(400, "ROUTING_RULE_INVALID")["errors"][0]["field"]
                .asString() shouldBe "endsOn"

            call(POST, "$path/deactivate", admin).expect(200)["active"].asBoolean() shouldBe false
            post(a, room, "100.00")["folioId"].asString() shouldBe a.toString()
            // Com outra regra ativa no lugar, a reativação sobreporia: 409.
            createRule(a, b, today, null, group = "ROOM").expect(201)
            call(POST, "$path/reactivate", admin).problem(409, "ROUTING_RULE_OVERLAP")
            call(GET, path, admin).expect(200)["active"].asBoolean() shouldBe false

            call(GET, "/api/v1/routing-rules?sourceFolioId=$a&active=false", admin)
                .expect(200)["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(id)
            call(GET, "/api/v1/routing-rules/${UuidV7.generate()}", admin).problem(404, "ROUTING_RULE_NOT_FOUND")
            // Nada é apagado.
            call(DELETE, path, admin).status shouldBe 405
        }

    @Test
    fun `rules need billing routing manage, readers list them, and the creation is idempotent and audited`(): Unit =
        with(billing) {
            val a = openFolio()
            val b = openFolio()
            val reader = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL)))
            createRule(a, b, today, null, group = "OTHER", auth = reader).problem(403, "PERMISSION_DENIED")
            val manager =
                iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.ROUTING_MANAGE), ALL)))
            val body =
                mapOf(
                    "sourceFolioId" to a,
                    "targetFolioId" to b,
                    "revenueGroup" to "OTHER",
                    "startsOn" to today.toString(),
                )
            call(POST, "/api/v1/routing-rules", manager, body, idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            val key = UuidV7.generate().toString()
            val first = call(POST, "/api/v1/routing-rules", manager, body, idempotencyKey = key).expect(201)
            val again = call(POST, "/api/v1/routing-rules", manager, body, idempotencyKey = key)
            again.expect(201)["id"] shouldBe first["id"]
            again.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            call(GET, "/api/v1/routing-rules?sourceFolioId=$a", manager).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/routing-rules?sourceFolioId=$a", reader)
                .expect(200)["items"]
                .values()
                .map { it["id"] } shouldContainExactly listOf(first["id"])

            // Conta ou código inexistente: 400 com o campo.
            createRule(UuidV7.generate(), b, today, null, group = "OTHER")
                .problem(400, "UNKNOWN_FOLIO")["errors"][0]["field"]
                .asString() shouldBe "sourceFolioId"
            createRule(a, UuidV7.generate(), today, null, group = "OTHER")
                .problem(400, "UNKNOWN_FOLIO")["errors"][0]["field"]
                .asString() shouldBe "targetFolioId"
            createRule(a, b, today, null, codes = listOf(codeId("ROOM_CHARGE"), UuidV7.generate()))
                .problem(400, "UNKNOWN_TRANSACTION_CODE")["errors"][0]["field"]
                .asString() shouldBe "transactionCodeIds[1]"

            val created = auditAfter(first["id"].asString(), "billing.routing-rule.create")
            created["sourceFolioId"].asString() shouldBe a.toString()
            created["revenueGroup"].asString() shouldBe "OTHER"
        }

    @Test
    fun `routed postings in opposite directions at the same time finish without deadlock`() {
        val a = billing.openFolio()
        val b = billing.openFolio()
        val room = billing.codeId("ROOM_CHARGE")
        val laundry = billing.codeId("LAUNDRY")
        with(billing) {
            createRule(a, b, today, null, codes = listOf(room)).expect(201)
            createRule(b, a, today, null, codes = listOf(laundry)).expect(201)
        }
        val rounds = 4
        val results =
            concurrently(
                (1..rounds).map { { postingTo(a, room, "10.00") } } +
                    (1..rounds).map { { postingTo(b, laundry, "1.00") } },
            )
        results.map { it.status }.toSet() shouldBe setOf(201)
        billing.balance(a) shouldBe "4.00"
        billing.balance(b) shouldBe "40.00"
    }

    @Test
    fun `two opposite rules created at the same time never form a cycle - the rule writes are serialized`() {
        val a = billing.openFolio()
        val b = billing.openFolio()
        val room = billing.codeId("ROOM_CHARGE")
        val results =
            concurrently(
                listOf(
                    { billing.createRule(a, b, today, null, codes = listOf(room)) },
                    { billing.createRule(b, a, today, null, group = "ROOM") },
                ),
            )
        results.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 422)
        with(billing) { results.single { it.status == 422 }.problem(422, "ROUTING_RULE_CYCLE") }
        // E o lançamento segue a única regra gravada, sem voltar.
        val first = postingTo(a, room, "1.00")
        val second = postingTo(b, room, "1.00")
        listOf(first.status, second.status) shouldBe listOf(201, 201)
        (billing.balance(a).toBigDecimal() + billing.balance(b).toBigDecimal()).toPlainString() shouldBe "2.00"
    }

    @Test
    fun `a posting waits for a rule write in progress and follows the rule as it is committed`() {
        val origin = billing.openFolio()
        val target = billing.openFolio()
        val room = billing.codeId("ROOM_CHARGE")
        val rule = with(billing) { createRule(origin, target, today, null, codes = listOf(room)).expect(201)["id"] }
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // Uma escrita de regra em andamento (a trava exclusiva das regras), que desativa a regra.
            val writer =
                pool.submit {
                    transactions.executeWithoutResult {
                        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('billing.routing-rules'))").query().singleRow()
                        jdbc
                            .sql("UPDATE billing.routing_rule SET active = false WHERE id = :id")
                            .param("id", UUID.fromString(rule.asString()))
                            .update()
                        locked.countDown()
                        release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    }
                }
            locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val posting = pool.submit(Callable { postingTo(origin, room, "10.00") })
            // O lançamento espera a escrita terminar, em vez de ler a regra ainda ativa.
            shouldThrow<TimeoutException> { posting.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
            release.countDown()
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            with(billing) {
                posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).expect(201)["folioId"].asString() shouldBe
                    origin.toString()
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the manager, finance and receptionist templates manage routing and transfer postings`() {
        val grants =
            jdbc
                .sql(
                    """
                    SELECT r.template_key, g.permission_code FROM iam.role r
                    JOIN iam.role_grant g ON g.role_id = r.id
                    WHERE r.template_key IN ('MANAGER', 'FINANCE', 'RECEPTIONIST', 'FNB_CASHIER')
                      AND g.permission_code IN ('billing.routing.manage', 'billing.postings.transfer')
                    """.trimIndent(),
                ).query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .list()
        grants shouldContainExactlyInAnyOrder
            listOf("MANAGER", "FINANCE", "RECEPTIONIST").flatMap { template ->
                listOf(template to "billing.routing.manage", template to "billing.postings.transfer")
            }
        BillingPermissions.permissions
            .filter { it.code in setOf(BillingPermissions.ROUTING_MANAGE, BillingPermissions.POSTINGS_TRANSFER) }
            .map { it.sensitive } shouldBe listOf(false, false)
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun postingTo(
        folio: UUID,
        code: UUID,
        unitPrice: String,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/postings",
            billing.admin,
            mapOf("transactionCodeId" to code, "unitPrice" to unitPrice),
        )

    private fun concurrently(calls: List<() -> MockHttpServletResponse>): List<MockHttpServletResponse> {
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

    private fun BillingTestSupport.createCode(
        prefix: String,
        group: String,
    ): UUID =
        UUID.fromString(
            call(
                POST,
                "/api/v1/transaction-codes",
                admin,
                mapOf(
                    "code" to "$prefix-${UuidV7.generate().toString().takeLast(SUFFIX).uppercase()}",
                    "name" to prefix,
                    "kind" to "CHARGE",
                    "revenueGroup" to group,
                ),
            ).expect(201)["id"].asString(),
        )

    /** Encerra a conta direto no banco (o encerramento pela API é da E10). */
    private fun close(folio: UUID) {
        jdbc
            .sql("UPDATE billing.folio SET status = 'CLOSED', closed_on = opened_on, closed_at = now() WHERE id = :id")
            .param("id", folio)
            .update() shouldBe 1
    }

    private fun postingsIn(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE folio_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun stored(
        posting: String,
        column: String,
    ): UUID =
        jdbc
            .sql("SELECT $column FROM billing.posting WHERE id = :id")
            .param("id", UUID.fromString(posting))
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .single()

    private fun auditAfter(
        entityId: String,
        action: String,
    ): JsonNode =
        jsonMapper.readTree(
            jdbc
                .sql("SELECT after_state::text FROM audit.audit_entry WHERE entity_id = :id AND action = :action")
                .param("id", entityId)
                .param("action", action)
                .query(String::class.java)
                .single(),
        )

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val WAIT_MILLIS = 700L
        const val SUFFIX = 8
    }
}
