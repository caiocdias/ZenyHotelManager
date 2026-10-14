package com.zeny.hotel.shared.web

import apiexample.ExampleApiConfiguration
import apiexample.ExampleItem
import apiexample.ExampleItemStore
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Convenções da API (ARCHITECTURE §7) exercitadas pelo controller de exemplo, só de teste:
 * erros RFC 9457 com `code` estável, paginação por cursor, `ETag`/`If-Match` e serialização
 * de datas e dinheiro. As requisições são autenticadas (`@WithMockUser`): até o M1, `/api/...`
 * só aceita requisição autenticada, e não há como autenticar de verdade.
 */
@IntegrationTest
@Import(ExampleApiConfiguration::class)
@WithMockUser
class ApiConventionsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val store: ExampleItemStore,
    @Autowired private val jsonMapper: JsonMapper,
) {
    @BeforeEach
    fun cleanUp() {
        store.items.clear()
    }

    // --- Erros RFC 9457 -------------------------------------------------------------------

    @Test
    fun `validation error is a 400 problem with a stable code and field errors`() {
        val problem =
            mockMvc
                .post("/api/v1/examples") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name": "", "price": "10.00", "dueDate": "2026-03-10"}"""
                }.problem(400, "VALIDATION_FAILED")

        problem["instance"].asString() shouldBe "/api/v1/examples"
        problem["title"].asString() shouldBe "Requisição inválida"
        problem["detail"].asString() shouldBe "A requisição tem campos inválidos."
        val errors = problem["errors"].toList()
        errors shouldHaveSize 1
        errors[0]["field"].asString() shouldBe "name"
        errors[0]["code"].asString() shouldBe "NOT_BLANK"
        errors[0]["message"].asString() shouldBe "não deve estar em branco"
    }

    @Test
    fun `missing, null and malformed body fields are field errors`() {
        fun fieldErrorOf(body: String): Pair<String, String> {
            val problem =
                mockMvc
                    .post("/api/v1/examples") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.problem(400, "VALIDATION_FAILED")
            val error = problem["errors"].single()
            return error["field"].asString() to error["code"].asString()
        }

        fieldErrorOf("""{"name": "a", "dueDate": "2026-03-10"}""") shouldBe ("price" to "REQUIRED")
        fieldErrorOf("""{"name": "a", "price": null, "dueDate": "2026-03-10"}""") shouldBe ("price" to "REQUIRED")
        fieldErrorOf("""{"name": "a", "price": "12.345", "dueDate": "2026-03-10"}""") shouldBe
            ("price" to "INVALID_FORMAT")
        fieldErrorOf("""{"name": "a", "price": 12.34, "dueDate": "2026-03-10"}""") shouldBe
            ("price" to "INVALID_FORMAT")
        fieldErrorOf("""{"name": "a", "price": "1.00", "dueDate": "10/03/2026"}""") shouldBe
            ("dueDate" to "INVALID_FORMAT")
    }

    @Test
    fun `unreadable json is a malformed request`() {
        val problem =
            mockMvc
                .post("/api/v1/examples") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name": """
                }.problem(400, "MALFORMED_REQUEST")

        problem.has("errors") shouldBe false
    }

    @Test
    fun `invalid query and path parameters are field errors`() {
        fun fieldErrorOf(path: String): Pair<String, String> {
            val error = mockMvc.get(path).problem(400, "VALIDATION_FAILED")["errors"].single()
            return error["field"].asString() to error["code"].asString()
        }

        fieldErrorOf("/api/v1/examples?limit=0") shouldBe ("limit" to "MIN")
        fieldErrorOf("/api/v1/examples?limit=201") shouldBe ("limit" to "MAX")
        fieldErrorOf("/api/v1/examples?limit=abc") shouldBe ("limit" to "INVALID_FORMAT")
        fieldErrorOf("/api/v1/examples/not-a-uuid") shouldBe ("id" to "INVALID_FORMAT")
        fieldErrorOf("/api/v1/examples/search?q=ab") shouldBe ("q" to "SIZE")
        fieldErrorOf("/api/v1/examples/search") shouldBe ("q" to "REQUIRED")
    }

    @Test
    fun `domain errors map to their status and keep their code and message`() {
        val archived = seed(name = "arquivado").copy(archived = true).also(store::save)
        val item = seed()

        mockMvc.get("/api/v1/examples/${UUID.randomUUID()}").problem(404, "EXAMPLE_NOT_FOUND").let {
            it["detail"].asString() shouldBe "Item de exemplo não encontrado."
            it["title"].asString() shouldBe "Não encontrado"
        }
        mockMvc.post("/api/v1/examples/${archived.id}/archive").problem(409, "EXAMPLE_ALREADY_ARCHIVED")
        mockMvc
            .post("/api/v1/examples/${item.id}/discount") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"percent": "15"}"""
            }.problem(422, "LIMIT_EXCEEDED")["detail"]
            .asString() shouldBe "O desconto passa do limite de 10%."
        mockMvc.get("/api/v1/examples/approval").problem(403, "APPROVAL_REQUIRED")
    }

    @Test
    fun `unexpected errors are a generic 500 without internals`() {
        val result = mockMvc.get("/api/v1/examples/boom")
        val problem = result.problem(500, "INTERNAL_ERROR")
        val body = problem.toString()

        problem["detail"].asString() shouldBe "Erro interno. Tente de novo; se persistir, avise o suporte."
        body shouldNotContain "hunter2"
        body shouldNotContain "senha"
        body shouldNotContain "Exception"
        body shouldNotContain "at apiexample"
        // `type` omitido equivale a "about:blank" (RFC 9457 §3.1.1).
        problem.propertyNames().toSet() shouldBe setOf("title", "status", "detail", "instance", "code")
    }

    @Test
    fun `framework errors also carry a stable code`() {
        mockMvc.get("/api/v1/does-not-exist").problem(404, "NOT_FOUND")
        mockMvc.delete("/api/v1/examples").problem(405, "METHOD_NOT_ALLOWED")
        mockMvc
            .post("/api/v1/examples") {
                contentType = MediaType.TEXT_PLAIN
                content = "x"
            }.problem(415, "UNSUPPORTED_MEDIA_TYPE")
    }

    // --- Paginação por cursor --------------------------------------------------------------

    @Test
    fun `cursor pagination respects the limit and walks every item once with an opaque cursor`() {
        val ids = (1..5).map { seed(name = "item $it").id }

        val first = page("/api/v1/examples?limit=2")
        first.ids() shouldContainExactly ids.take(2)
        val cursor = first["nextCursor"].asString()
        cursor shouldMatch Regex("[A-Za-z0-9_-]+")
        cursor shouldNotContain ids[1].toString()

        val second = page("/api/v1/examples?limit=2&cursor=$cursor")
        second.ids() shouldContainExactly ids.subList(2, 4)

        val last = page("/api/v1/examples?limit=2&cursor=${second["nextCursor"].asString()}")
        last.ids() shouldContainExactly ids.subList(4, 5)
        last["nextCursor"].isNull shouldBe true

        val all = page("/api/v1/examples")
        all.ids() shouldContainExactly ids
        all["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `tampered cursor is rejected with a stable code`() {
        seed()

        mockMvc.get("/api/v1/examples?cursor=bm90LWEtY3Vyc29y").problem(400, "INVALID_CURSOR")
        mockMvc.get("/api/v1/examples?cursor=***").problem(400, "INVALID_CURSOR")
    }

    // --- ETag / If-Match -------------------------------------------------------------------

    @Test
    fun `reads carry the version as etag and stale if-match is a 412`() {
        val item = seed(name = "original")

        mockMvc.get("/api/v1/examples/${item.id}").andExpect {
            status { isOk() }
            header { string(HttpHeaders.ETAG, "\"0\"") }
        }

        mockMvc.put("/api/v1/examples/${item.id}") { update("\"0\"", "primeira") }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ETAG, "\"1\"") }
            jsonPath("$.name") { value("primeira") }
        }

        mockMvc
            .put("/api/v1/examples/${item.id}") { update("\"0\"", "perdida") }
            .problem(412, "PRECONDITION_FAILED")
        mockMvc
            .put("/api/v1/examples/${item.id}") { update("W/\"1\"", "fraca") }
            .problem(412, "PRECONDITION_FAILED")
        mockMvc
            .put(
                "/api/v1/examples/${item.id}",
            ) { update(null, "sem if-match") }
            .problem(428, "PRECONDITION_REQUIRED")

        store.find(item.id).let {
            it.name shouldBe "primeira"
            it.version shouldBe 1L
        }
    }

    @Test
    fun `creation returns location and etag`() {
        mockMvc
            .post("/api/v1/examples") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "novo", "price": "99.90", "dueDate": "2026-12-31"}"""
            }.andExpect {
                status { isCreated() }
                header { string(HttpHeaders.ETAG, "\"0\"") }
                jsonPath("$.price") { value("99.90") }
                jsonPath("$.dueDate") { value("2026-12-31") }
            }.andReturn()
            .response
            .getHeader(HttpHeaders.LOCATION)
            .shouldNotBeNull() shouldStartWith "/api/v1/examples/"
    }

    // --- Serialização ----------------------------------------------------------------------

    @Test
    fun `instants are utc with z, business dates are yyyy-mm-dd and money is a decimal string`() {
        val item =
            seed(
                price = Money.of("1234.50"),
                dueDate = LocalDate.of(2026, 3, 10),
                // 22:30 do dia 9 em São Paulo (fuso da JVM nos testes): sai em UTC, dia 10.
                createdAt = Instant.parse("2026-03-10T01:30:00.123Z"),
            )

        val body =
            json(
                mockMvc
                    .get("/api/v1/examples/${item.id}")
                    .andReturn()
                    .response.contentAsString,
            )

        body["createdAt"].isString shouldBe true
        body["createdAt"].asString() shouldBe "2026-03-10T01:30:00.123Z"
        body["dueDate"].asString() shouldBe "2026-03-10"
        body["price"].isString shouldBe true
        body["price"].asString() shouldBe "1234.50"
        body["id"].asString() shouldBe item.id.toString()
    }

    // --- Contrato --------------------------------------------------------------------------

    @Test
    fun `contract documents money as a decimal string, dates in iso format and errors as problems`() {
        val doc =
            json(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )
        val schemas = doc["components"]["schemas"]

        val item = schemas["ExampleItemResponse"]["properties"]
        item["price"]["type"].asString() shouldBe "string"
        item["price"]["format"].asString() shouldBe "decimal"
        item["createdAt"]["format"].asString() shouldBe "date-time"
        item["dueDate"]["format"].asString() shouldBe "date"
        schemas["DiscountRequest"]["properties"]["percent"]["type"].asString() shouldBe "string"

        schemas["Problem"]["properties"]["code"]["type"].asString() shouldBe "string"
        schemas["Problem"]["required"].values().map { it.asString() }.toSet() shouldBe setOf("title", "status", "code")
        val getItem = doc["paths"]["/api/v1/examples/{id}"]["get"]
        getItem["responses"]["default"]["content"]["application/problem+json"]["schema"]["\$ref"].asString() shouldBe
            "#/components/schemas/Problem"
        val page = schemas["CursorPageExampleItemResponse"]
        page["required"].values().map { it.asString() } shouldContainExactly listOf("items")
        page["properties"]["nextCursor"]["nullable"].asBoolean() shouldBe true
        val listParameters =
            doc["paths"]["/api/v1/examples"]["get"]["parameters"].values().map {
                it["name"].asString()
            }
        listParameters.toSet() shouldBe setOf("cursor", "limit")
    }

    // --- Apoio -----------------------------------------------------------------------------

    private fun seed(
        name: String = "item",
        price: Money = Money.of("10.00"),
        dueDate: LocalDate = LocalDate.of(2026, 3, 10),
        createdAt: Instant = Instant.parse("2026-03-10T01:30:00Z"),
    ): ExampleItem = ExampleItem(UuidV7.generate(), name, price, dueDate, createdAt).also(store::save)

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.update(
        ifMatch: String?,
        name: String,
    ) {
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": "$name", "price": "20.00"}"""
        ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
    }

    private fun page(path: String): JsonNode {
        val response =
            mockMvc
                .get(path)
                .andExpect { status { isOk() } }
                .andReturn()
                .response
        return json(response.contentAsString).also { it["items"].shouldNotBeNull() }
    }

    private fun JsonNode.ids(): List<UUID> = this["items"].values().map { UUID.fromString(it["id"].asString()) }

    private fun json(content: String): JsonNode = jsonMapper.readTree(content)

    /** Confere status, `application/problem+json` e `code`, e devolve o corpo do problema. */
    private fun ResultActionsDsl.problem(
        status: Int,
        code: String,
    ): JsonNode {
        val response =
            andExpect {
                status { isEqualTo(status) }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            }.andReturn().response
        val problem = json(response.getContentAsString(Charsets.UTF_8))
        problem["status"].asInt() shouldBe status
        problem["code"].asString() shouldBe code
        problem["title"].shouldNotBeNull()
        problem.has("trace") shouldBe false
        problem.has("exception") shouldBe false
        problem.get("message").shouldBeNull()
        return problem
    }
}
