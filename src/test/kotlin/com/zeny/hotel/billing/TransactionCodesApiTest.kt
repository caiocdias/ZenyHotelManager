package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Códigos de lançamento (M3 E04, RF-CTA-02, H2, H4, H5): os oito pré-carregados com a chave, o cadastro com
 * `If-Match`, o código curto único, o tipo e o grupo fixos, as permissões, a trilha e nenhuma exclusão.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class TransactionCodesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    fun `the eight default codes exist after the migrations, with their key, kind and revenue group`() {
        val defaults =
            billing
                .listAll("/api/v1/transaction-codes")
                .filter { !it["key"].isNull }
                .associateBy { it["key"].asString() }
        defaults.keys shouldBe TransactionCodeKey.entries.map { it.name }.toSet()

        fun expect(
            key: TransactionCodeKey,
            code: String,
            kind: String,
            group: String,
            serviceCharge: Boolean = false,
        ) = withClue(key) {
            val node = defaults.getValue(key.name)
            node["code"].asString() shouldBe code
            node["kind"].asString() shouldBe kind
            node["revenueGroup"].asString() shouldBe group
            node["serviceChargeApplies"].asBoolean() shouldBe serviceCharge
            node["serviceChargeRate"].isNull shouldBe true
        }
        expect(TransactionCodeKey.ROOM_CHARGE, "DIARIA", "CHARGE", "ROOM")
        expect(TransactionCodeKey.CANCELLATION_FEE, "MULTA-CANC", "PENALTY", "FEES")
        expect(TransactionCodeKey.NO_SHOW_FEE, "MULTA-NOSHOW", "PENALTY", "FEES")
        expect(TransactionCodeKey.SERVICE_CHARGE, "TX-SERVICO", "SERVICE_CHARGE", "SERVICE_CHARGE")
        expect(TransactionCodeKey.FOOD_BEVERAGE, "AB", "CHARGE", "FOOD_BEVERAGE", serviceCharge = true)
        expect(TransactionCodeKey.LAUNDRY, "LAVANDERIA", "CHARGE", "LAUNDRY")
        expect(TransactionCodeKey.TOURISM_FEE, "TX-TURISMO", "TAX", "TAXES")
        expect(TransactionCodeKey.MISCELLANEOUS, "AVULSO", "CHARGE", "OTHER")
    }

    @Test
    fun `a code is created, edited with If-Match, deactivated and reactivated, and audited`(): Unit =
        with(billing) {
            val code = "FRIGO-${suffix()}"
            val created =
                call(POST, "/api/v1/transaction-codes", admin, createBody(code.lowercase(), "Frigobar"))
                    .also {
                        it.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
                    }.expect(201)
            val id = UUID.fromString(created["id"].asString())
            created["code"].asString() shouldBe code
            created["key"].isNull shouldBe true
            created["kind"].asString() shouldBe "CHARGE"
            created["serviceChargeRate"].asString() shouldBe "12.0000"

            val path = "/api/v1/transaction-codes/$id"
            call(PUT, path, admin, updateBody(code, "Frigobar do quarto")).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, admin, updateBody(code, "Frigobar do quarto"), ifMatch = "\"9\"")
                .problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, admin, updateBody(code, "Frigobar do quarto"), ifMatch = "\"0\"").expect(200)
            updated["name"].asString() shouldBe "Frigobar do quarto"
            updated["serviceChargeApplies"].asBoolean() shouldBe false
            updated["serviceChargeRate"].isNull shouldBe true
            // O tipo e o grupo ficam (o corpo da edição nem os tem).
            updated["kind"].asString() shouldBe "CHARGE"
            updated["revenueGroup"].asString() shouldBe "FOOD_BEVERAGE"

            call(POST, "$path/deactivate", admin).expect(200)["active"].asBoolean() shouldBe false
            call(POST, "$path/reactivate", admin).expect(200)["active"].asBoolean() shouldBe true
            billing
                .listAll(
                    "/api/v1/transaction-codes?active=true",
                ).any { it["id"].asString() == id.toString() } shouldBe
                true

            auditActions(id) shouldContainExactly
                listOf(
                    "billing.transaction-code.create",
                    "billing.transaction-code.update",
                    "billing.transaction-code.deactivate",
                    "billing.transaction-code.reactivate",
                )
        }

    @Test
    fun `the short code is unique and the service charge stays coherent with the kind`(): Unit =
        with(billing) {
            val code = "DUP-${suffix()}"
            call(POST, "/api/v1/transaction-codes", admin, createBody(code, "Um")).expect(201)
            call(POST, "/api/v1/transaction-codes", admin, createBody(code.lowercase(), "Dois"))
                .problem(409, "TRANSACTION_CODE_ALREADY_IN_USE")
            // O pré-carregado também é dono do seu código.
            call(POST, "/api/v1/transaction-codes", admin, createBody("DIARIA", "Outra diária"))
                .problem(409, "TRANSACTION_CODE_ALREADY_IN_USE")
            val penalty =
                createBody("MULTA-${suffix()}", "Multa", kind = "PENALTY", group = "FEES") +
                    mapOf("serviceChargeRate" to null)
            call(POST, "/api/v1/transaction-codes", admin, penalty)
                .problem(400, "TRANSACTION_CODE_INVALID")["errors"][0]["field"]
                .asString() shouldBe "serviceChargeApplies"
            call(
                POST,
                "/api/v1/transaction-codes",
                admin,
                createBody("X-${suffix()}", "X") + mapOf("serviceChargeRate" to "100.5"),
            ).problem(400, "TRANSACTION_CODE_INVALID")
            call(POST, "/api/v1/transaction-codes", admin, createBody("com espaço", "X"))
                .problem(400, "BILLING_DATA_INVALID")
            call(POST, "/api/v1/transaction-codes", admin, mapOf("code" to "Y", "name" to "Y"))
                .problem(400, "VALIDATION_FAILED")
        }

    @Test
    fun `reading needs billing codes read and writing needs billing codes manage`(): Unit =
        with(billing) {
            val stranger = iam.asTestUser(iam.createUser())
            call(GET, "/api/v1/transaction-codes", stranger).problem(403, "PERMISSION_DENIED")
            val reader = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL)))
            call(GET, "/api/v1/transaction-codes", reader).expect(200)
            call(POST, "/api/v1/transaction-codes", reader, createBody("R-${suffix()}", "R"))
                .problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `codes and folios are never deleted, not even by the application role`() {
        val code =
            with(billing) {
                UUID.fromString(
                    call(
                        POST,
                        "/api/v1/transaction-codes",
                        admin,
                        createBody("DEL-${suffix()}", "Del"),
                    ).expect(201)["id"]
                        .asString(),
                )
            }
        val folio = billing.openFolio()
        for ((sql, id) in listOf(
            "DELETE FROM billing.transaction_code WHERE id = :id" to code,
            "DELETE FROM billing.folio WHERE id = :id" to folio,
        )) {
            withClue(sql) {
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult { jdbc.sql(sql).param("id", id).update() }
                }.mostSpecificCause.message shouldContain "nunca são apagados"
            }
        }
        billing.call(GET, "/api/v1/transaction-codes/$code", billing.admin).status shouldBe 200
        billing.call(GET, "/api/v1/folios/$folio", billing.admin).status shouldBe 200
        with(billing) {
            call(
                GET,
                "/api/v1/transaction-codes/${UuidV7.generate()}",
                admin,
            ).problem(404, "TRANSACTION_CODE_NOT_FOUND")
        }
    }

    private fun suffix(): String =
        UuidV7
            .generate()
            .toString()
            .takeLast(SUFFIX)
            .uppercase()

    private fun createBody(
        code: String,
        name: String,
        kind: String = "CHARGE",
        group: String = "FOOD_BEVERAGE",
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "name" to name,
            "kind" to kind,
            "revenueGroup" to group,
            "serviceChargeApplies" to (kind == "CHARGE" || kind == "PENALTY"),
            "serviceChargeRate" to if (kind == "CHARGE") "12" else null,
            "fiscalClassification" to "Serviço 1.03",
        )

    private fun updateBody(
        code: String,
        name: String,
    ): Map<String, Any?> = mapOf("code" to code, "name" to name, "serviceChargeApplies" to false)

    private fun auditActions(entityId: UUID): List<String> =
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val SUFFIX = 8
    }
}
