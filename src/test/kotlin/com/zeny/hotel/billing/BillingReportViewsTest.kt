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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.util.UUID

/**
 * As views `report_*` do `billing` (M3 E10, ARCHITECTURE §11; nomes e colunas decididos pelo usuário em 2026-10-20):
 * o contrato das colunas, só `SELECT` para o papel da aplicação, os fatos de um cenário (lançamentos com taxa de
 * serviço e estorno, recebimentos no cartão e em dinheiro com reembolso, um turno fechado com a contagem e a conta
 * encerrada), o saldo que se refaz pelas views, nenhum texto livre nem NSU ou autorização nos valores e a consulta por
 * um papel que só lê as views (as tabelas, não).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class BillingReportViewsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    fun `the billing views are the published contract, only readable by the application role`() {
        views() shouldContainExactlyInAnyOrder VIEW_COLUMNS.keys.toList()
        VIEW_COLUMNS.forEach { (view, columns) ->
            withClue(view) {
                columnsOf(view) shouldContainExactly columns
                columnsOf(view).filter { column -> FORBIDDEN.any { column.contains(it) } } shouldBe emptyList()
                PRIVILEGES.filter { privilegeOn("billing.$view", it) } shouldBe listOf("SELECT")
            }
        }
    }

    @Test
    @Suppress("LongMethod") // o cenário e as conferências de cada view
    fun `the views carry the facts of a folio and a shift, rebuild the balance and hold no free text`(): Unit =
        with(billing) {
            val folio = openFolio()
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "50.00")
            val laundry = post(folio, codeId("LAUNDRY"), "40.00")
            call(
                POST,
                "/api/v1/folios/$folio/postings/${laundry["id"].asString()}/reverse",
                admin,
                mapOf("reason" to REVERSAL_REASON),
            ).expect(201)
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
            val shift = openShift(auth, float = "100.00")
            pay(
                folio,
                auth,
                mapOf(
                    "method" to "DEBIT_CARD",
                    "amount" to "55.00",
                    "nsu" to NSU,
                    "authorizationCode" to AUTHORIZATION,
                    "cardBrand" to "MASTERCARD",
                    "cardLast4" to "1111",
                ),
            ).expect(201)
            val cash = pay(folio, auth, mapOf("method" to "CASH", "amount" to "10.00")).expect(201)
            call(
                POST,
                "/api/v1/folios/$folio/payments/${cash["id"].asString()}/refund",
                auth,
                mapOf("amount" to "10.00", "reason" to REFUND_REASON),
            ).expect(201)
            call(
                POST,
                "/api/v1/cashier-shifts/$shift/close",
                auth,
                mapOf("counts" to listOf(mapOf("method" to "CASH", "amount" to "95.00"))),
            ).expect(200)
            call(POST, "/api/v1/folios/$folio/close", admin).expect(200)
            val today = hotelApi.currentBusinessDate()

            val postings = rows("report_postings", "folio_id", folio)
            postings.map { it["posting_type"] } shouldContainExactlyInAnyOrder
                listOf("REGULAR", "SERVICE_CHARGE", "REGULAR", "REVERSAL")
            val charge = postings.single { it["posting_type"] == "SERVICE_CHARGE" }
            charge["pass_through"] shouldBe true
            charge["revenue_group"] shouldBe "SERVICE_CHARGE"
            charge["service_charge_for_id"] shouldBe UUID.fromString(item["id"].asString())
            charge["service_charge_rate"].toString() shouldBe "10.0000"
            postings.filter { it["posting_type"] != "SERVICE_CHARGE" }.forEach { it["pass_through"] shouldBe false }
            postings.single { it["posting_type"] == "REVERSAL" }["corrects_id"] shouldBe
                UUID.fromString(laundry["id"].asString())
            postings.forEach { it["business_date"].toString() shouldBe today.toString() }

            val payments = rows("report_payments", "folio_id", folio)
            payments.map { it["payment_type"] to it["net_amount"].toString() } shouldContainExactlyInAnyOrder
                listOf("PAYMENT" to "55.00", "PAYMENT" to "10.00", "REFUND" to "-10.00")
            val card = payments.single { it["method"] == "DEBIT_CARD" }
            card["card_brand"] shouldBe "MASTERCARD"
            card["card_last4"] shouldBe "1111"
            card["cashier_shift_id"] shouldBe shift
            card["deposit"] shouldBe false

            withClue("o saldo se refaz pelas views: lançamentos menos o recebido líquido") {
                val posted = postings.fold(Money.ZERO) { sum, row -> sum + Money.of(row["amount"].toString()) }
                val received = payments.fold(Money.ZERO) { sum, row -> sum + Money.of(row["net_amount"].toString()) }
                posted.toString() shouldBe "55.00"
                (posted - received).toString() shouldBe balance(folio)
            }

            val folioRow = rows("report_folios", "folio_id", folio).single()
            folioRow["status"] shouldBe "CLOSED"
            folioRow["closed_on"].toString() shouldBe today.toString()
            folioRow["owner_type"] shouldBe "RESERVATION"

            val shiftRow = rows("report_cashier_shifts", "shift_id", shift).single()
            shiftRow["status"] shouldBe "CLOSED"
            shiftRow["operator_id"] shouldBe cashier.id.value
            shiftRow["opening_float"].toString() shouldBe "100.00"
            shiftRow["blind_close"] shouldBe true
            val counts = rows("report_cashier_counts", "shift_id", shift)
            counts.map { it["method"] } shouldContainExactlyInAnyOrder PaymentMethod.entries.map { it.name }
            val cashCount = counts.single { it["method"] == "CASH" }
            cashCount["declared"].toString() shouldBe "95.00"
            cashCount["expected"].toString() shouldBe "100.00"
            cashCount["difference"].toString() shouldBe "-5.00"
            shiftRow["difference_total"].toString() shouldBe
                counts.fold(Money.ZERO) { sum, row -> sum + Money.of(row["difference"].toString()) }.toString()

            withClue("nenhum valor das views tem texto livre, NSU ou autorização") {
                val text =
                    listOf("report_postings", "report_payments", "report_folios").joinToString("\n") {
                        json(it, "folio_id", folio)
                    } + json("report_cashier_shifts", "shift_id", shift) +
                        json("report_cashier_counts", "shift_id", shift)
                listOf(REVERSAL_REASON, REFUND_REASON, NSU, AUTHORIZATION).forEach { text shouldNotContain it }
            }

            withClue("um papel que só lê as views consulta-as; as tabelas internas, não (42501)") {
                withViewOnlyRole { client ->
                    VIEW_COLUMNS.keys.forEach { view ->
                        client.sql("SELECT count(*) FROM billing.$view").query(Long::class.java).single()
                    }
                    client
                        .sql("SELECT status FROM billing.report_folios WHERE folio_id = :id")
                        .param("id", folio)
                        .query(String::class.java)
                        .single() shouldBe "CLOSED"
                    for (table in listOf("posting", "payment", "folio", "cashier_shift", "folio_reopening")) {
                        val error =
                            shouldThrow<DataAccessException> {
                                client.sql("SELECT count(*) FROM billing.$table").query(Long::class.java).single()
                            }
                        (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
                    }
                }
            }
        }

    // --- Apoio -----------------------------------------------------------------------------------------

    private fun views(): List<String> =
        jdbc
            .sql(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE n.nspname = 'billing' AND c.relkind = 'v'",
            ).query(String::class.java)
            .list()
            .filterNotNull()

    private fun columnsOf(view: String): List<String> =
        jdbc
            .sql(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'billing' AND table_name = :view ORDER BY ordinal_position",
            ).param("view", view)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun privilegeOn(
        relation: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, :relation, :privilege)")
            .param("relation", relation)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun rows(
        view: String,
        column: String,
        id: UUID,
    ): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM billing.$view WHERE $column = :id")
            .param("id", id)
            .query()
            .listOfRows()

    private fun json(
        view: String,
        column: String,
        id: UUID,
    ): String =
        jdbc
            .sql("SELECT coalesce(string_agg(row_to_json(v)::text, ' '), '') FROM billing.$view v WHERE $column = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    /**
     * Roda [block] numa conexão do dono com `SET ROLE` para um papel novo, sem login, que só tem `USAGE` no schema e
     * `SELECT` nas views (a view roda com os privilégios do dono dela). Removido no fim.
     */
    private fun withViewOnlyRole(block: (JdbcClient) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "billing_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            owner.sql("GRANT USAGE ON SCHEMA billing TO $role").update()
            VIEW_COLUMNS.keys.forEach { owner.sql("GRANT SELECT ON billing.$it TO $role").update() }
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                block(JdbcClient.create(SingleConnectionDataSource(connection, true)))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private companion object {
        const val REVERSAL_REASON = "Hóspede contestou a lavanderia"
        const val REFUND_REASON = "Pagamento lançado em duplicidade"
        const val NSU = "123456"
        const val AUTHORIZATION = "AUTH77"
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")

        /** Nada de texto livre nem de dado da maquininha além da bandeira e dos 4 últimos dígitos. */
        val FORBIDDEN = listOf("description", "reason", "nsu", "authorization_code", "card_number", "cvv", "note")

        val VIEW_COLUMNS =
            mapOf(
                "report_folios" to
                    listOf(
                        "folio_id",
                        "folio_number",
                        "owner_type",
                        "owner_id",
                        "payer_company_id",
                        "status",
                        "credit_limit",
                        "opened_on",
                        "opened_at",
                        "closed_on",
                        "closed_at",
                        "reopened_on",
                        "reopened_at",
                    ),
                "report_postings" to
                    listOf(
                        "posting_id",
                        "folio_id",
                        "posting_type",
                        "transaction_code_id",
                        "transaction_code",
                        "transaction_code_name",
                        "transaction_code_kind",
                        "revenue_group",
                        "pass_through",
                        "quantity",
                        "unit_price",
                        "amount",
                        "business_date",
                        "posted_at",
                        "posted_by",
                        "source",
                        "corrects_id",
                        "transferred_from_id",
                        "routed_from_folio_id",
                        "routing_rule_id",
                        "service_charge_for_id",
                        "service_charge_rate",
                        "triggered_by_id",
                        "approval_id",
                        "approved_by",
                    ),
                "report_payments" to
                    listOf(
                        "payment_id",
                        "folio_id",
                        "payment_type",
                        "method",
                        "amount",
                        "net_amount",
                        "tendered",
                        "change_given",
                        "deposit",
                        "cashier_shift_id",
                        "business_date",
                        "received_at",
                        "received_by",
                        "payment_intent_id",
                        "refund_of_id",
                        "card_pre_authorization_id",
                        "card_brand",
                        "card_last4",
                        "approval_id",
                        "approved_by",
                    ),
                "report_cashier_shifts" to
                    listOf(
                        "shift_id",
                        "shift_number",
                        "cash_point_id",
                        "cash_point_name",
                        "operator_id",
                        "status",
                        "blind_close",
                        "opening_float",
                        "opened_on",
                        "opened_at",
                        "closed_on",
                        "closed_at",
                        "difference_total",
                    ),
                "report_cashier_counts" to
                    listOf(
                        "shift_id",
                        "cash_point_id",
                        "operator_id",
                        "closed_on",
                        "closed_at",
                        "method",
                        "declared",
                        "expected",
                        "difference",
                    ),
            )
    }
}
