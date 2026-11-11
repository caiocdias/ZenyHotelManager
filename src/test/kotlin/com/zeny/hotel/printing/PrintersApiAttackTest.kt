package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Ataques do testador à administração de impressoras (M5 E10, Task 6; D-E10-7 e D-E10-8): `kind` fixo, corpo e campos
 * inválidos sem eco, `If-Match` em todas as formas, referências, papéis reais e o anônimo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class PrintersApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    private fun MockHttpServletResponse.isProblem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { this@isProblem.problem(status, code) }

    private fun MockHttpServletResponse.okJson(): JsonNode = with(iam) { this@okJson.ok() }

    private fun MockHttpServletResponse.expectJson(status: Int): JsonNode? =
        with(iam) { this@expectJson.expectStatus(status) }

    private val path = "/api/v1/printers"

    private fun newAgent(): String =
        iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Ag ${UUID.randomUUID()}")).let {
            iam.run { it.expectJson(201)!!["id"].asString() }
        }

    private fun body(
        agentId: String,
        name: String = "Balcão",
        kind: String = "THERMAL_ESCPOS",
        address: String = "tcp://10.0.0.5:9100",
    ) = mapOf("name" to name, "kind" to kind, "address" to address, "agentId" to agentId)

    private fun newPrinter(agentId: String = newAgent()): JsonNode =
        iam.call(POST, path, iam.asAdmin(), body(agentId)).let { iam.run { it.expectJson(201)!! } }

    private fun change(
        agentId: String,
        name: String = "Novo",
        address: String = "tcp://10.0.0.9:9100",
    ) = mapOf("name" to name, "address" to address, "agentId" to agentId)

    private fun etag(id: String) = iam.call(GET, "$path/$id", iam.asAdmin()).getHeader(HttpHeaders.ETAG)!!

    private fun raw(
        method: HttpMethod,
        uri: String,
        json: String,
        ifMatch: String? = null,
    ) = mockMvc
        .request(method, uri) {
            with(iam.asAdmin())
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (ifMatch != null) header(HttpHeaders.IF_MATCH, ifMatch)
        }.andReturn()
        .response

    private fun userWithTemplate(template: RoleTemplate) =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    @Test
    fun `If-Match that is weak, a list, a star, unquoted, empty or huge is 412 and changes nothing`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val good = etag(id)
        val version = good.trim('"')
        listOf(
            "W/$good",
            "$good, \"999\"",
            "\"999\", $good",
            "*",
            version,
            "\"\"",
            "\"abc\"",
            "\"${"9".repeat(25)}\"",
            "\"-1\"",
        ).forEach { header ->
            val response = iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId), ifMatch = header)
            withClue("If-Match `$header` -> ${response.status} ${response.contentAsString}") {
                response.status shouldBe 412
            }
        }
        iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId), ifMatch = "   ").status shouldBe 428
        iam.call(GET, "$path/$id", iam.asAdmin()).let { iam.run { it.okJson()["name"].asString() } } shouldBe "Balcão"
        etag(id) shouldBe good
    }

    @Test
    fun `a stale If-Match after a successful PUT is 412 and a fresh one succeeds and bumps the ETag`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val first = etag(id)

        val updated = iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId, name = "Dois"), ifMatch = first)

        updated.status shouldBe 200
        val second = updated.getHeader(HttpHeaders.ETAG)!!
        (second == first) shouldBe false
        etag(id) shouldBe second
        iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId, name = "Três"), ifMatch = first).status shouldBe 412
        iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId, name = "Três"), ifMatch = second).status shouldBe 200
    }

    @Test
    fun `PUT on an unknown printer is 404 PRINTER_NOT_FOUND, and so are deactivate and malformed ids never 500`() {
        val agentId = newAgent()
        val missing = UUID.randomUUID()
        iam
            .call(
                PUT,
                "$path/$missing",
                iam.asAdmin(),
                change(agentId),
                ifMatch = "\"1\"",
            ).isProblem(404, "PRINTER_NOT_FOUND")
        iam.call(POST, "$path/$missing/deactivate", iam.asAdmin()).isProblem(404, "PRINTER_NOT_FOUND")
        iam.call(GET, "$path/$missing", iam.asAdmin()).isProblem(404, "PRINTER_NOT_FOUND")
        listOf("me", "ME", "-", "---", "abc", "0123456789abcdef0123456789abcdef", "zzzz").forEach { id ->
            listOf(GET to "$path/$id", PUT to "$path/$id", POST to "$path/$id/deactivate").forEach { (method, uri) ->
                val response = iam.call(method, uri, iam.asAdmin(), change(agentId), ifMatch = "\"1\"")
                withClue("$method $uri -> ${response.status} ${response.contentAsString}") {
                    (response.status in setOf(400, 404, 405)) shouldBe true
                }
            }
        }
    }

    @Test
    fun `kind is closed on create and fixed on update, in any spelling`() {
        val agentId = newAgent()
        listOf("LASER", "thermal_escpos", "", "a4_pdf", "A4-PDF").forEach { kind ->
            val response = iam.call(POST, path, iam.asAdmin(), body(agentId, kind = kind))
            withClue("kind `$kind` -> ${response.status} ${response.contentAsString}") { response.status shouldBe 400 }
            if (kind.isNotBlank()) response.contentAsString shouldNotContain "\"$kind\""
        }
        raw(POST, path, """{"name":"x","kind":null,"address":"a","agentId":"$agentId"}""").status shouldBe 400
        raw(POST, path, """{"name":"x","kind":7,"address":"a","agentId":"$agentId"}""").status shouldBe 400

        val a4 = iam.call(POST, path, iam.asAdmin(), body(agentId, kind = "A4_PDF"))
        iam.run { a4.expectJson(201)!!["kind"].asString() } shouldBe "A4_PDF"
        val id = iam.json(a4)["id"].asString()
        listOf("THERMAL_ESCPOS", "LASER", null).forEach { kind ->
            val json = """{"name":"Novo","address":"tcp://1.1.1.1:9","agentId":"$agentId","kind":${kind?.let {
                "\"$it\""
            }}}"""
            val response = raw(PUT, "$path/$id", json, etag(id))
            withClue("PUT com kind `$kind` -> ${response.status} ${response.contentAsString}") {
                response.status shouldBe 200
                iam.json(response)["kind"].asString() shouldBe "A4_PDF"
            }
        }
        iam.call(GET, "$path/$id", iam.asAdmin()).let { iam.run { it.okJson()["kind"].asString() } } shouldBe "A4_PDF"
    }

    @Test
    fun `malformed or incomplete bodies are 400, never 500, on POST and PUT`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val before = jdbc.sql("SELECT count(*) FROM printing.printer").query(Long::class.java).single()
        val bad =
            listOf(
                "{",
                "",
                "[]",
                "null",
                "{}",
                """{"name":"x"}""",
                """{"name":"x","kind":"A4_PDF","address":"a"}""",
                """{"name":"x","kind":"A4_PDF","address":"a","agentId":"not-a-uuid"}""",
                """{"name":"x","kind":"A4_PDF","address":"a","agentId":null}""",
                """{"name":null,"kind":"A4_PDF","address":"a","agentId":"$agentId"}""",
                """{"name":"x","kind":"A4_PDF","address":null,"agentId":"$agentId"}""",
            )
        bad.forEach { json ->
            val response = raw(POST, path, json)
            withClue("POST `$json` -> ${response.status} ${response.contentAsString}") { response.status shouldBe 400 }
        }
        val badPuts = listOf("{", "", "[]", "{}", """{"name":"x"}""", """{"name":"x","address":"a","agentId":"zz"}""")
        badPuts.forEach { json ->
            val response = raw(PUT, "$path/$id", json, etag(id))
            withClue("PUT `$json` -> ${response.status} ${response.contentAsString}") { response.status shouldBe 400 }
        }
        jdbc.sql("SELECT count(*) FROM printing.printer").query(Long::class.java).single() shouldBe before
    }

    @Test
    fun `invalid names and addresses on PUT are 400 on the field without echoing the value`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val blank = 0x200B.toChar()
        val nbsp = 0xA0.toChar()
        val tab = '\t'
        listOf("", "  ", "a${blank}b", "$nbsp", "x".repeat(101), "a${tab}b").forEach { name ->
            val response = iam.call(PUT, "$path/$id", iam.asAdmin(), change(agentId, name = name), ifMatch = etag(id))
            withClue("nome `${name.length}` -> ${response.status} ${response.contentAsString}") {
                val problem = iam.run { response.isProblem(400, "VALIDATION_FAILED") }
                problem["errors"].values().map { it["field"].asString() } shouldBe listOf("name")
                if (name.isNotEmpty()) response.contentAsString shouldNotContain name
            }
        }
        val nul = 0.toChar()
        listOf("", "a b", "a${blank}b", "a${nbsp}b", "x".repeat(256), "a${tab}b", "a${nul}b").forEach { address ->
            val response =
                iam.call(
                    PUT,
                    "$path/$id",
                    iam.asAdmin(),
                    change(agentId, address = address),
                    ifMatch = etag(id),
                )
            withClue("endereço de ${address.length} -> ${response.status} ${response.contentAsString}") {
                val problem = iam.run { response.isProblem(400, "VALIDATION_FAILED") }
                problem["errors"].values().map { it["field"].asString() } shouldBe listOf("address")
                if (address.isNotEmpty()) response.contentAsString shouldNotContain address
            }
        }
        iam.call(GET, "$path/$id", iam.asAdmin()).let { iam.run { it.okJson()["name"].asString() } } shouldBe "Balcão"
    }

    @Test
    fun `invalid create values never echo and a 255 character address and a 100 character name are accepted`() {
        val agentId = newAgent()
        val blank = 0x200B.toChar()
        val nbsp = 0xA0.toChar()
        listOf("a${nbsp}b", "tcp://x y", "a${blank}b").forEach { address ->
            val response = iam.call(POST, path, iam.asAdmin(), body(agentId, address = address))
            response.status shouldBe 400
            response.contentAsString shouldNotContain address
        }
        iam
            .call(
                POST,
                path,
                iam.asAdmin(),
                body(agentId, name = "n".repeat(100), address = "a".repeat(255)),
            ).status shouldBe
            201
    }

    @Test
    fun `PUT on an inactive printer is 422, an unknown agent 400 and an inactive agent 422, changing nothing`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val tag = etag(id)
        iam
            .call(PUT, "$path/$id", iam.asAdmin(), change(UUID.randomUUID().toString()), ifMatch = tag)
            .isProblem(400, "UNKNOWN_PRINT_AGENT")
        val dead = newAgent()
        iam.call(POST, "/api/v1/print-agents/$dead/deactivate", iam.asAdmin()).status shouldBe 200
        iam.call(PUT, "$path/$id", iam.asAdmin(), change(dead), ifMatch = tag).isProblem(422, "PRINT_AGENT_INACTIVE")
        etag(id) shouldBe tag

        val deactivated = iam.call(POST, "$path/$id/deactivate", iam.asAdmin())
        deactivated.status shouldBe 200
        val afterDeactivate = deactivated.getHeader(HttpHeaders.ETAG)!!
        iam
            .call(PUT, "$path/$id", iam.asAdmin(), change(agentId), ifMatch = afterDeactivate)
            .isProblem(422, "PRINTER_INACTIVE")
        iam.call(POST, "$path/$id/deactivate", iam.asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe afterDeactivate
    }

    @Test
    fun `a PUT keeping the same inactive agent is allowed only when the agent did not change`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        iam.call(POST, "/api/v1/print-agents/$agentId/deactivate", iam.asAdmin()).status shouldBe 200

        val response =
            iam.call(
                PUT,
                "$path/$id",
                iam.asAdmin(),
                change(agentId, name = "Mesmo agente"),
                ifMatch = etag(id),
            )

        withClue("o agente continua o mesmo: ${response.contentAsString}") { response.status shouldBe 200 }
    }

    @Test
    fun `manager and receptionist read printers with an ETag and a user with no role gets 403 before any 404`() {
        val agentId = newAgent()
        val id = newPrinter(agentId)["id"].asString()
        val none = iam.createUser()
        iam.call(GET, path, iam.asTestUser(none)).isProblem(403, "PERMISSION_DENIED")
        iam.call(GET, "$path/${UUID.randomUUID()}", iam.asTestUser(none)).isProblem(403, "PERMISSION_DENIED")
        listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST).forEach { template ->
            val user = userWithTemplate(template)
            iam.call(GET, "$path/$id", iam.asTestUser(user)).getHeader(HttpHeaders.ETAG) shouldBe etag(id)
            iam.call(GET, "$path/${UUID.randomUUID()}", iam.asTestUser(user)).isProblem(404, "PRINTER_NOT_FOUND")
            iam
                .call(PUT, "$path/${UUID.randomUUID()}", iam.asTestUser(user), change(agentId), ifMatch = "\"1\"")
                .isProblem(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `anonymous and garbage bearer callers are 401 on every printer route`() {
        val id = newPrinter()["id"].asString()
        listOf(GET to path, POST to path, GET to "$path/$id", PUT to "$path/$id", POST to "$path/$id/deactivate")
            .forEach { (method, uri) ->
                mockMvc
                    .request(method, uri)
                    .andReturn()
                    .response.status shouldBe 401
                mockMvc
                    .request(method, uri) { header(HttpHeaders.AUTHORIZATION, "Bearer garbage") }
                    .andReturn()
                    .response.status shouldBe 401
            }
    }

    @Test
    fun `the printer response carries no token and no agent secret`() {
        val created = newPrinter()
        val id = created["id"].asString()
        val texts =
            listOf(
                created.toString(),
                iam.call(GET, "$path/$id", iam.asAdmin()).contentAsString,
                iam.call(GET, path, iam.asAdmin()).contentAsString,
            )
        texts.forEach {
            it shouldNotContain "token"
            it shouldNotContain "zpa_"
            it shouldNotContain "Hash"
        }
        created.propertyNames().toSet() shouldBe setOf("id", "name", "kind", "address", "agentId", "active")
    }
}
