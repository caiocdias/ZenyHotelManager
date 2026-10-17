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
