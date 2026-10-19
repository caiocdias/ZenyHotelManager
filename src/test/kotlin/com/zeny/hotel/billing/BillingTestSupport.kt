package com.zeny.hotel.billing

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Apoio dos testes do `billing`: chama a API com `Idempotency-Key` (uma nova por chamada, salvo a informada), abre
 * contas e lança como o administrador (que tem as correções sem teto).
 */
class BillingTestSupport(
    private val iam: IamTestSupport,
    private val mockMvc: MockMvc,
    private val jsonMapper: JsonMapper,
) {
    val admin: RequestPostProcessor get() = iam.asAdmin()

    @Suppress("LongParameterList") // os cabeçalhos opcionais da chamada
    fun call(
        method: HttpMethod,
        path: String,
        auth: RequestPostProcessor,
        body: Any? = null,
        idempotencyKey: String? = UuidV7.generate().toString(),
        approvalToken: String? = null,
        ifMatch: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                with(auth)
                if (method == POST && idempotencyKey != null) header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
                if (ifMatch != null) header(HttpHeaders.IF_MATCH, ifMatch)
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(body)
                }
            }.andReturn()
            .response

    fun MockHttpServletResponse.expect(status: Int): JsonNode = with(iam) { expectStatus(status).shouldNotBeNull() }

    fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { problem(status, code) }

    /** Abre uma conta como o administrador; devolve o id. */
    fun openFolio(
        ownerType: String = "RESERVATION",
        ownerId: UUID? = UuidV7.generate(),
        auth: RequestPostProcessor = admin,
    ): UUID =
        UUID.fromString(call(POST, "/api/v1/folios", auth, folioBody(ownerType, ownerId)).expect(201)["id"].asString())

    fun folioBody(
        ownerType: String,
        ownerId: UUID?,
        payerCompanyId: UUID? = null,
        creditLimit: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "owner" to mapOf("type" to ownerType, "id" to ownerId),
            "payerCompanyId" to payerCompanyId,
            "creditLimit" to creditLimit,
        )

    /** O id do código pré-carregado da chave. */
    fun codeId(key: String): UUID =
        UUID.fromString(
            listAll(
                "/api/v1/transaction-codes",
            ).single { it["key"]?.takeUnless(JsonNode::isNull)?.asString() == key }["id"]
                .asString(),
        )

    /** Lança na conta como [auth]; devolve o lançamento. */
    fun post(
        folio: UUID,
        code: UUID,
        unitPrice: String,
        quantity: String = "1",
        auth: RequestPostProcessor = admin,
    ): JsonNode =
        call(
            POST,
            "/api/v1/folios/$folio/postings",
            auth,
            mapOf("transactionCodeId" to code, "quantity" to BigDecimal(quantity), "unitPrice" to unitPrice),
        ).expect(201)

    fun folio(id: UUID): JsonNode = call(GET, "/api/v1/folios/$id", admin).expect(200)

    /** Cria uma regra de roteamento como [auth] (por códigos ou por grupo); devolve a resposta. */
    @Suppress("LongParameterList") // os campos da regra
    fun createRule(
        source: UUID,
        target: UUID,
        startsOn: LocalDate,
        endsOn: LocalDate? = null,
        codes: List<UUID>? = null,
        group: String? = null,
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse =
        call(
            POST,
            "/api/v1/routing-rules",
            auth,
            mapOf(
                "sourceFolioId" to source,
                "targetFolioId" to target,
                "transactionCodeIds" to codes,
                "revenueGroup" to group,
                "startsOn" to startsOn.toString(),
                "endsOn" to endsOn?.toString(),
            ),
        )

    /** Transfere os lançamentos de [source] para [target] como [auth]. */
    fun transfer(
        source: UUID,
        target: UUID,
        postings: List<String>,
        auth: RequestPostProcessor = admin,
        reason: String? = null,
    ): MockHttpServletResponse =
        call(
            POST,
            "/api/v1/folios/$source/postings/transfer",
            auth,
            mapOf("targetFolioId" to target, "postingIds" to postings, "reason" to reason),
        )

    /** Divide o lançamento entre as partes (conta → percentual ou valor) como [auth]. */
    fun split(
        source: UUID,
        posting: String,
        by: String,
        parts: List<Pair<UUID, String>>,
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse {
        val field = if (by == "PERCENT") "percent" else "amount"
        return call(
            POST,
            "/api/v1/folios/$source/postings/$posting/split",
            auth,
            mapOf("by" to by, "parts" to parts.map { (folio, value) -> mapOf("folioId" to folio, field to value) }),
        )
    }

    /** Cria um código de consumo de A&B com ou sem a taxa de serviço (e o percentual próprio); devolve o id. */
    fun createCode(
        serviceCharge: Boolean,
        rate: String?,
    ): UUID =
        run {
            val code =
                "SC" +
                    UuidV7
                        .generate()
                        .toString()
                        .takeLast(8)
                        .uppercase()
            UUID.fromString(
                call(
                    POST,
                    "/api/v1/transaction-codes",
                    admin,
                    mapOf(
                        "code" to code,
                        "name" to "Consumo $code",
                        "kind" to "CHARGE",
                        "revenueGroup" to "FOOD_BEVERAGE",
                        "serviceChargeApplies" to serviceCharge,
                        "serviceChargeRate" to rate,
                    ),
                ).expect(201)["id"].asString(),
            )
        }

    /** Estorna o lançamento como [auth]. */
    fun reverseWithReason(
        folio: UUID,
        id: String,
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse =
        call(POST, "/api/v1/folios/$folio/postings/$id/reverse", auth, mapOf("reason" to "Não consumiu"))

    /** Remove a taxa de serviço a pedido do cliente como [auth]. */
    fun removeCharge(
        folio: UUID,
        id: String,
        auth: RequestPostProcessor,
        idempotencyKey: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        call(
            POST,
            "/api/v1/folios/$folio/postings/$id/remove-service-charge",
            auth,
            idempotencyKey = idempotencyKey,
        )

    fun JsonNode.uuid(field: String): UUID = UUID.fromString(this[field].asString())

    fun balance(folio: UUID): String = folio(folio)["balance"].asString()

    fun postings(folio: UUID): List<JsonNode> = listAll("/api/v1/folios/$folio/postings")

    /** Todas as páginas de uma listagem como o administrador. */
    fun listAll(path: String): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val separator = if ('?' in path) '&' else '?'
            val page =
                call(
                    GET,
                    "$path${separator}limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(),
                    admin,
                ).expect(200)
            items += page["items"].values()
            cursor = page["nextCursor"]?.takeUnless(JsonNode::isNull)?.asString()
        } while (cursor != null)
        return items
    }

    fun etagOf(path: String): String {
        val response = call(GET, path, admin)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    companion object {
        fun definition(code: PermissionCode) = BillingPermissions.permissions.single { it.code == code }
    }
}
