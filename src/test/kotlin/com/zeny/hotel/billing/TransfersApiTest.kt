package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Transferência e divisão (M3 E05, RF-CTA-05, RN-02, RN-03): pares de saída e entrada vinculados, o lançamento
 * original intacto, a soma exata (inclusive com dízima), as recusas, a idempotência, a trilha, o banco que recusa um
 * par incoerente e duas transferências opostas simultâneas sem deadlock.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class TransfersApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    fun `a transfer creates linked outgoing and incoming postings and leaves the original intact`(): Unit =
        with(billing) {
            val source = openFolio()
            val target = openFolio("GROUP")
            val room = post(source, codeId("ROOM_CHARGE"), "250.00", quantity = "2")
            val laundry = post(source, codeId("LAUNDRY"), "30.00")
            val id = room["id"].asString()
            val before = storedRow(id)

            val result =
                transfer(source, target, listOf(id, laundry["id"].asString()), reason = "Empresa paga").expect(201)
            val transfers = result["transfers"].values().toList()
            transfers.map { it["originalId"].asString() } shouldContainExactly listOf(id, laundry["id"].asString())
            val first = transfers.first()
            val outgoing = first["outgoing"]
            val incoming = first["incoming"]
            outgoing["type"].asString() shouldBe "TRANSFER_OUT"
            outgoing["folioId"].asString() shouldBe source.toString()
            outgoing["correctsId"].asString() shouldBe id
            outgoing["amount"].asString() shouldBe "-500.00"
            outgoing["transferredToPostingId"].asString() shouldBe incoming["id"].asString()
            outgoing["transferredToFolioId"].asString() shouldBe target.toString()
            outgoing["reason"].asString() shouldBe "Empresa paga"
            incoming["type"].asString() shouldBe "TRANSFER_IN"
            incoming["folioId"].asString() shouldBe target.toString()
            incoming["amount"].asString() shouldBe "500.00"
            incoming["transactionCodeId"] shouldBe room["transactionCodeId"]
            incoming["transferredFromPostingId"].asString() shouldBe outgoing["id"].asString()
            incoming["transferredFromFolioId"].asString() shouldBe source.toString()
            incoming["remainingAmount"].asString() shouldBe "500.00"

            // O original continua igual no banco; só o que resta dele mudou.
            storedRow(id) shouldBe before
            call(GET, "/api/v1/folios/$source/postings/$id", admin).expect(200)["remainingAmount"].asString() shouldBe
                "0.00"
            balance(source) shouldBe "0.00"
            balance(target) shouldBe "530.00"

            // Já transferido: nada resta. A entrada vale como lançamento normal no destino (estorno, transferência).
            transfer(source, target, listOf(id)).problem(422, "POSTING_NOTHING_TO_TRANSFER")
            transfer(source, target, listOf(outgoing["id"].asString())).problem(422, "POSTING_NOT_TRANSFERABLE")
            val back = transfer(target, source, listOf(incoming["id"].asString())).expect(201)
            back["transfers"][0]["incoming"]["folioId"].asString() shouldBe source.toString()
            balance(source) shouldBe "500.00"
            balance(target) shouldBe "30.00"
            val laundryIn = transfers[1]["incoming"]["id"].asString()
            call(
                POST,
                "/api/v1/folios/$target/postings/$laundryIn/reverse",
                admin,
                mapOf("reason" to "Cobrado errado"),
            ).expect(201)["amount"].asString() shouldBe "-30.00"
            balance(target) shouldBe "0.00"

            val audited = auditOf("billing.posting.transfer")
            audited.filter { it.first == incoming["id"].asString() }.map { it.second["type"].asString() } shouldBe
                listOf("TRANSFER_IN")
        }

    @Test
    fun `a split by percent with repeating decimals and by amount adds up to exactly the original`(): Unit =
        with(billing) {
            val source = openFolio()
            val b = openFolio()
            val c = openFolio()
            val dinner = post(source, codeId("FOOD_BEVERAGE"), "100.00")["id"].asString()
            val split =
                split(
                    source,
                    dinner,
                    "PERCENT",
                    listOf(source to "33.3333", b to "33.3333", c to "33.3334"),
                ).expect(201)["transfers"].values()
            // A parte da própria conta fica; as outras duas saem.
            split.map { it["incoming"]["folioId"].asString() } shouldContainExactly listOf(b.toString(), c.toString())
            split.map { it["incoming"]["amount"].asString() } shouldContainExactly listOf("33.33", "33.34")
            listOf(balance(source), balance(b), balance(c)) shouldContainExactly listOf("33.33", "33.33", "33.34")
            sumOf(source, b, c) shouldBe BigDecimal("100.00")

            // Por valor, sobre o que resta (33,33), e só se somar exatamente.
            split(source, dinner, "AMOUNT", listOf(b to "10.00", c to "23.32")).problem(422, "SPLIT_TOTAL_MISMATCH")
            split(source, dinner, "AMOUNT", listOf(b to "10.00", c to "23.33")).expect(201)
            listOf(balance(source), balance(b), balance(c)) shouldContainExactly listOf("0.00", "43.33", "56.67")
            sumOf(source, b, c) shouldBe BigDecimal("100.00")

            // Três partes iguais de 10,00 (dízima): 3,34 + 3,33 + 3,33.
            val tea = post(source, codeId("FOOD_BEVERAGE"), "10.00")["id"].asString()
            split(source, tea, "PERCENT", listOf(b to "33.3333", c to "33.3333", source to "33.3334"))
                .expect(201)["transfers"]
                .values()
                .map { it["incoming"]["amount"].asString() } shouldContainExactly listOf("3.33", "3.33")
            call(GET, "/api/v1/folios/$source/postings/$tea", admin).expect(200)["remainingAmount"].asString() shouldBe
                "3.34"
            sumOf(source, b, c) shouldBe BigDecimal("110.00")
        }

    @Test
    fun `transfers and splits are refused with stable codes and leave nothing behind`(): Unit =
        with(billing) {
            val source = openFolio()
            val target = openFolio()
            val closed = openFolio()
            jdbc.sql("UPDATE billing.folio SET status = 'CLOSED' WHERE id = :id").param("id", closed).update()
            val id = post(source, codeId("ROOM_CHARGE"), "90.00")["id"].asString()
            val path = "/api/v1/folios/$source/postings/transfer"
            val body = mapOf("targetFolioId" to target, "postingIds" to listOf(id))

            call(POST, path, admin, body, idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            transfer(source, closed, listOf(id)).problem(409, "FOLIO_CLOSED")
            transfer(source, source, listOf(id)).problem(400, "TRANSFER_INVALID")
            transfer(source, target, listOf(id, id)).problem(400, "TRANSFER_INVALID")
            transfer(source, UuidV7.generate(), listOf(id))
                .problem(400, "UNKNOWN_FOLIO")["errors"][0]["field"]
                .asString() shouldBe "targetFolioId"
            transfer(UuidV7.generate(), target, listOf(id)).problem(404, "FOLIO_NOT_FOUND")
            transfer(target, source, listOf(id)).problem(404, "POSTING_NOT_FOUND")
            split(source, id, "PERCENT", listOf(target to "60", closed to "40")).problem(409, "FOLIO_CLOSED")
            split(source, id, "PERCENT", listOf(target to "60", source to "30")).problem(400, "SPLIT_INVALID")
            split(source, id, "PERCENT", listOf(target to "100")).problem(400, "VALIDATION_FAILED")
            split(source, id, "AMOUNT", listOf(target to "45.00", target to "45.00")).problem(400, "SPLIT_INVALID")
            split(source, id, "PERCENT", listOf(target to "40", source to "20", target to "40"))
                .problem(400, "SPLIT_INVALID")["errors"][0]["field"]
                .asString() shouldBe "parts[2].folioId"
            // No modo por percentual, cada parte informa só o percentual.
            split(source, id, "AMOUNT", listOf(target to "45.00", source to "45.00")).expect(201)
            call(
                POST,
                "/api/v1/folios/$source/postings/$id/split",
                admin,
                mapOf(
                    "by" to "PERCENT",
                    "parts" to listOf(mapOf("folioId" to target, "amount" to "1.00"), mapOf("folioId" to source)),
                ),
            ).problem(400, "SPLIT_INVALID")["errors"][0]["field"].asString() shouldBe "parts[0].percent"

            // Sem a permissão: 403, nada gravado.
            val reader = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL)))
            transfer(source, target, listOf(id), auth = reader).problem(403, "PERMISSION_DENIED")
            // Estornado: 409.
            val other = post(source, codeId("LAUNDRY"), "12.00")["id"].asString()
            call(POST, "/api/v1/folios/$source/postings/$other/reverse", admin, mapOf("reason" to "Errado")).expect(201)
            transfer(source, target, listOf(id, other)).problem(409, "POSTING_ALREADY_REVERSED")
            // Tudo ou nada: o primeiro da lista não foi transferido (só a divisão de 45,00 ficou).
            call(GET, "/api/v1/folios/$source/postings/$id", admin).expect(200)["remainingAmount"].asString() shouldBe
                "45.00"
            postingsIn(target) shouldContainExactly listOf("TRANSFER_IN")
            balance(target) shouldBe "45.00"
        }

    @Test
    fun `the correction ceiling adds up the whole lineage, so splitting or transferring does not escape it`(): Unit =
        with(billing) {
            val capped =
                iam.asTestUser(
                    iam.createUserWith(
                        TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                        TestGrant(
                            definition(BillingPermissions.POSTINGS_REVERSE),
                            ALL,
                            mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("50"))),
                        ),
                        TestGrant(
                            definition(BillingPermissions.POSTINGS_ADJUST),
                            ALL,
                            mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("50"))),
                        ),
                    ),
                )
            // Teto 50, lançamento de 100 dividido 50/50: a primeira metade se estorna sem aprovação, a segunda não.
            val source = openFolio()
            val other = openFolio()
            val dinner = post(source, codeId("FOOD_BEVERAGE"), "100.00")["id"].asString()
            val half =
                split(source, dinner, "PERCENT", listOf(source to "50", other to "50"))
                    .expect(201)["transfers"][0]["incoming"]["id"]
                    .asString()
            reverseAs(source, dinner, capped).expect(201)["approvalId"].isNull shouldBe true
            val required = reverseAs(other, half, capped).problem(403, "APPROVAL_REQUIRED")
            required["limit"]["key"].asString() shouldBe "maxAmount"
            required["limit"]["amount"].asString() shouldBe "100"
            required["resourceRef"].asString() shouldBe "billing.posting:$half"
            balance(other) shouldBe "50.00"

            // Duas transferências em cadeia (A → B → C) e o ajuste no fim da linhagem: conta o que já foi corrigido.
            val a = openFolio()
            val b = openFolio()
            val c = openFolio()
            val stay = post(a, codeId("ROOM_CHARGE"), "100.00")["id"].asString()
            val inB = transfer(a, b, listOf(stay)).expect(201)["transfers"][0]["incoming"]["id"].asString()
            val inC = transfer(b, c, listOf(inB)).expect(201)["transfers"][0]["incoming"]["id"].asString()
            adjustAs(c, inC, "40.00", capped).expect(201)["approvalId"].isNull shouldBe true
            adjustAs(c, inC, "20.00", capped).problem(403, "APPROVAL_REQUIRED")["limit"]["amount"].asString() shouldBe
                "60"
            // O original, todo transferido, não tem o que estornar (e a mensagem diz por quê).
            reverseAs(a, stay, admin)
                .problem(422, "POSTING_NOTHING_TO_REVERSE")["detail"]
                .asString() shouldContain "transferências"
            balance(c) shouldBe "60.00"
        }

    @Test
    fun `two simultaneous corrections in different folios of the same lineage never escape the ceiling`() {
        val capped =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                    TestGrant(
                        definition(BillingPermissions.POSTINGS_REVERSE),
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("50"))),
                    ),
                ),
            )
        val code = billing.codeId("FOOD_BEVERAGE")
        // Várias rodadas: sem a trava da linhagem, as duas leriam o total antes da outra gravar e passariam as duas.
        repeat(ROUNDS) { _ ->
            val a = billing.openFolio()
            val b = billing.openFolio()
            val original = billing.post(a, code, "100.00")["id"].asString()
            val incoming =
                with(billing) {
                    split(a, original, "PERCENT", listOf(a to "50", b to "50"))
                        .expect(201)["transfers"][0]["incoming"]["id"]
                        .asString()
                }
            val results =
                concurrently(listOf({ reverseAs(a, original, capped) }, { reverseAs(b, incoming, capped) }))
            results.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 403)
            with(billing) {
                results
                    .single { it.status == 403 }
                    .problem(403, "APPROVAL_REQUIRED")["limit"]["amount"]
                    .asString() shouldBe "100"
            }
            jdbc
                .sql(
                    "SELECT count(*) FROM billing.posting WHERE posting_type = 'REVERSAL' AND corrects_id IN (:ids)",
                ).param("ids", listOf(UUID.fromString(original), UUID.fromString(incoming)))
                .query(Long::class.java)
                .single() shouldBe 1L
        }
    }

    @Test
    fun `a transfer is idempotent and the transfer permission alone is enough`(): Unit =
        with(billing) {
            val source = openFolio()
            val target = openFolio()
            val id = post(source, codeId("MISCELLANEOUS"), "15.00")["id"].asString()
            val mover =
                iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.POSTINGS_TRANSFER), ALL)))
            val body = mapOf("targetFolioId" to target, "postingIds" to listOf(id))
            val key = UuidV7.generate().toString()
            val path = "/api/v1/folios/$source/postings/transfer"
            val first = call(POST, path, mover, body, idempotencyKey = key).expect(201)
            val again = call(POST, path, mover, body, idempotencyKey = key)
            again.expect(201) shouldBe first
            again.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            postingsIn(target) shouldContainExactly listOf("TRANSFER_IN")
            balance(target) shouldBe "15.00"
        }

    @Test
    fun `the database refuses an outgoing without its incoming and an incoming that does not mirror it`() {
        val source = billing.openFolio()
        val target = billing.openFolio()
        val id = UUID.fromString(billing.post(source, billing.codeId("LAUNDRY"), "20.00")["id"].asString())
        val outgoing = UuidV7.generate()
        val insertOut =
            """
            INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price, amount,
                description, source, business_date, posted_at, corrects_id)
            SELECT :newId, folio_id, transaction_code_id, 'TRANSFER_OUT', 1, -amount, -amount, description, 'MANUAL',
                business_date, now(), id
            FROM billing.posting WHERE id = :id
            """.trimIndent()
        // Saída sem entrada: recusada no commit.
        val refused =
            shouldThrow<Exception> {
                transactions.executeWithoutResult {
                    jdbc
                        .sql(insertOut)
                        .param("newId", outgoing)
                        .param("id", id)
                        .update()
                }
            }
        generateSequence<Throwable>(refused) { it.cause }.mapNotNull { it.message }.joinToString() shouldContain
            "precisa da entrada"
        // Entrada que não repete a saída (valor diferente): recusada.
        shouldThrow<DataAccessException> {
            transactions.executeWithoutResult {
                jdbc
                    .sql(insertOut)
                    .param("newId", outgoing)
                    .param("id", id)
                    .update()
                jdbc
                    .sql(
                        """
                        INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity,
                            unit_price, amount, description, source, business_date, posted_at, transferred_from_id)
                        SELECT :newId, :target, transaction_code_id, 'TRANSFER_IN', 1, 25.00, 25.00, description,
                            'MANUAL', business_date, now(), id
                        FROM billing.posting WHERE id = :out
                        """.trimIndent(),
                    ).param("newId", UuidV7.generate())
                    .param("target", target)
                    .param("out", outgoing)
                    .update()
            }
        }.mostSpecificCause.message shouldContain "sinal trocado"
        // E o par nunca muda depois: nem o dono altera.
        with(billing) { transfer(source, target, listOf(id.toString())).expect(201) }
        shouldThrow<DataAccessException> {
            owner
                .sql("UPDATE billing.posting SET amount = 1 WHERE transferred_from_id IS NOT NULL AND folio_id = :id")
                .param("id", target)
                .update()
        }.mostSpecificCause.message shouldContain "imutáveis"
    }

    @Test
    fun `two opposite transfers between the same folios at the same time finish without deadlock`() {
        val a = billing.openFolio()
        val b = billing.openFolio()
        val code = billing.codeId("MISCELLANEOUS")
        val rounds = 4
        val fromA = (1..rounds).map { billing.post(a, code, "10.00")["id"].asString() }
        val fromB = (1..rounds).map { billing.post(b, code, "1.00")["id"].asString() }
        val results =
            concurrently(
                fromA.map { { billing.transfer(a, b, listOf(it)) } } +
                    fromB.map { { billing.transfer(b, a, listOf(it)) } },
            )
        results.map { it.status }.toSet() shouldBe setOf(201)
        billing.balance(a) shouldBe "4.00"
        billing.balance(b) shouldBe "40.00"

        // A mesma transferência duas vezes ao mesmo tempo: uma passa, a outra não acha nada a transferir.
        val once = billing.post(a, code, "7.00")["id"].asString()
        concurrently(
            List(2) { { billing.transfer(a, b, listOf(once)) } },
        ).map { it.status } shouldContainExactlyInAnyOrder
            listOf(201, 422)
        billing.balance(b) shouldBe "47.00"
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun reverseAs(
        folio: UUID,
        posting: String,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse =
        billing.call(POST, "/api/v1/folios/$folio/postings/$posting/reverse", auth, mapOf("reason" to "Cancelado"))

    private fun adjustAs(
        folio: UUID,
        posting: String,
        amount: String,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/postings/$posting/adjust",
            auth,
            mapOf("amount" to amount, "reason" to "Cobrado a mais"),
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

    private fun sumOf(vararg folios: UUID): BigDecimal =
        jdbc
            .sql("SELECT COALESCE(sum(amount), 0) FROM billing.posting WHERE folio_id IN (:ids)")
            .param("ids", folios.toList())
            .query(BigDecimal::class.java)
            .single()
            .setScale(2)

    private fun postingsIn(folio: UUID): List<String> =
        jdbc
            .sql("SELECT posting_type FROM billing.posting WHERE folio_id = :id ORDER BY posted_at, id")
            .param("id", folio)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private fun storedRow(posting: String): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE id = :id")
            .param("id", UUID.fromString(posting))
            .query()
            .singleRow()

    private fun auditOf(action: String): List<Pair<String, JsonNode>> =
        jdbc
            .sql("SELECT entity_id, after_state::text FROM audit.audit_entry WHERE action = :action")
            .param("action", action)
            .query { rs, _ -> rs.getString(1) to jsonMapper.readTree(rs.getString(2)) }
            .list()

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val ROUNDS = 5
    }
}
