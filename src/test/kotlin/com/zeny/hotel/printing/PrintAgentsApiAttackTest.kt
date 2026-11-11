package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Ataques do testador à administração de agentes de impressão (M5 E10, Task 6; D-E10-2, D-E10-7 e D-E10-8): o token só
 * na criação e na rotação, a repetição do `POST`, o `{id}` malformado e o `me`, o corpo inválido, o nome sem eco,
 * os papéis reais e o anônimo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PrintAgentsApiAttackTest(
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

    private val path = "/api/v1/print-agents"

    private fun create(name: String = "Agente ataque ${UUID.randomUUID()}"): JsonNode =
        with(iam) { call(POST, path, asAdmin(), mapOf("name" to name)).expectJson(201)!! }

    private fun raw(
        method: HttpMethod,
        uri: String,
        json: String? = null,
        type: MediaType = MediaType.APPLICATION_JSON,
    ) = mockMvc
        .request(method, uri) {
            with(iam.asAdmin())
            if (json != null) {
                contentType = type
                content = json
            }
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
    fun `the token appears in no header, no log, no audit entry and no later read`(output: CapturedOutput) {
        val created = create()
        val id = created["id"].asString()
        val first = created["token"].asString()
        val rotated = with(iam) { call(POST, "$path/$id/rotate-token", asAdmin()) }
        val second = iam.run { rotated.okJson()["token"].asString() }

        val reads =
            listOf(
                iam.call(GET, "$path/$id", iam.asAdmin()),
                iam.call(GET, path, iam.asAdmin()),
                iam.call(POST, "$path/$id/deactivate", iam.asAdmin()),
                iam.call(POST, "$path/$id/rotate-token", iam.asAdmin()),
            )
        withClue("a rotação de um agente inativo é problema, sem token") {
            reads.last().status shouldBe 422
            listOf(first, second).forEach { reads.last().contentAsString shouldNotContain it }
        }
        reads.take(3).forEach { response ->
            listOf(first, second).forEach { response.contentAsString shouldNotContain it }
            response.headerNames.forEach { header ->
                listOf(first, second).forEach { response.getHeaders(header).joinToString() shouldNotContain it }
            }
        }
        (first == second) shouldBe false
        listOf(first, second).forEach {
            output.all shouldNotContain it
            val audit =
                jdbc
                    .sql("SELECT row_to_json(a)::text FROM audit.audit_entry a WHERE a.entity_id::text = :id")
                    .param("id", id)
                    .query(String::class.java)
                    .list()
            audit.isNotEmpty() shouldBe true
            audit.forEach { row -> row shouldNotContain it }
        }
    }

    @Test
    fun `both create and rotate are no-store and the stored hash is not the token`() {
        val created = create()
        val id = created["id"].asString()
        val token = created["token"].asString()

        iam.call(POST, "$path/$id/rotate-token", iam.asAdmin()).getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"

        val stored =
            jdbc
                .sql("SELECT row_to_json(p)::text FROM printing.print_agent p WHERE p.id = :id")
                .param("id", UUID.fromString(id))
                .query(String::class.java)
                .single()
        stored shouldNotContain token
    }

    @Test
    fun `repeating the create POST, even with the same Idempotency-Key, makes two agents with two tokens`() {
        val name = "Agente repetido ${UUID.randomUUID()}"
        val key = UUID.randomUUID().toString()

        fun once() =
            mockMvc
                .request(POST, path) {
                    with(iam.asAdmin())
                    header("Idempotency-Key", key)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"$name"}"""
                }.andReturn()
                .response

        val a = once()
        val b = once()

        a.status shouldBe 201
        b.status shouldBe 201
        val (first, second) = iam.json(a) to iam.json(b)
        (first["id"].asString() == second["id"].asString()) shouldBe false
        (first["token"].asString() == second["token"].asString()) shouldBe false
        b.getHeader("Idempotent-Replayed") shouldBe null
        val listed =
            iam
                .call(GET, path, iam.asAdmin())
                .let { iam.run { it.okJson() } }
                .values()
                .map { it["id"].asString() }
        listed shouldContain first["id"].asString()
        listed shouldContain second["id"].asString()
    }

    @Test
    fun `me and any malformed id never reach the admin routes and never answer 500`() {
        val before = jdbc.sql("SELECT count(*) FROM printing.print_agent").query(Long::class.java).single()
        val ids =
            listOf(
                "me",
                "ME",
                "Me",
                "-",
                "---",
                "abc",
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef0123456789abcdef0123456789abcdef",
                "zzzz",
                "%00",
                "1-1-1-1-1",
            )
        ids.forEach { id ->
            listOf(
                GET to "$path/$id",
                POST to "$path/$id/rotate-token",
                POST to "$path/$id/deactivate",
            ).forEach { (method, uri) ->
                val response = iam.call(method, uri, iam.asAdmin())
                // `me` (minúsculo) é do realm do agente (cadeia de serviço, Task 4): JWT de usuário ali é 401.
                val allowed = if (id == "me" || id == "ME") setOf(400, 401, 404, 405) else setOf(400, 404, 405)
                withClue("$method $uri -> ${response.status} ${response.contentAsString}") {
                    (response.status in allowed) shouldBe true
                    response.contentAsString shouldNotContain "\"token\""
                }
            }
        }
        listOf("me", "ME").forEach { id ->
            val response = iam.call(GET, "$path/$id", iam.asAdmin())
            response.contentAsString shouldNotContain "PRINT_AGENT_NOT_FOUND"
            iam.call(POST, "$path/$id/rotate-token", iam.asAdmin()).contentAsString shouldNotContain "\"token\""
        }
        jdbc.sql("SELECT count(*) FROM printing.print_agent").query(Long::class.java).single() shouldBe before
    }

    @Test
    fun `a well formed but unknown id is 404 with the agent code in lower and upper case hex`() {
        val id = UUID.randomUUID().toString()
        listOf(id, id.uppercase()).forEach {
            iam.call(GET, "$path/$it", iam.asAdmin()).isProblem(404, "PRINT_AGENT_NOT_FOUND")
        }
        val agent = create()["id"].asString()
        iam.call(GET, "$path/${agent.uppercase()}", iam.asAdmin()).okJson()["id"].asString() shouldBe agent
    }

    @Test
    fun `malformed bodies are 400 or 415, never 500, and create nothing`() {
        val before = jdbc.sql("SELECT count(*) FROM printing.print_agent").query(Long::class.java).single()
        listOf("{", "", "[]", "\"x\"", "null", "{\"name\":null}", "{\"name\":[\"a\"]}", "{\"name\":{\"a\":1}}", "{}")
            .forEach { body ->
                val response = raw(POST, path, body)
                withClue("corpo `$body` -> ${response.status} ${response.contentAsString}") {
                    response.status shouldBe 400
                }
            }
        raw(POST, path, """{"name":"x"}""", MediaType.TEXT_PLAIN).status shouldBe 415
        jdbc.sql("SELECT count(*) FROM printing.print_agent").query(Long::class.java).single() shouldBe before
    }

    @Test
    fun `extra fields on create are ignored and cannot set the token, the state or the id`() {
        val chosenId = UUID.randomUUID().toString()
        val response =
            raw(
                POST,
                path,
                """{"name":"Agente extra ${UUID.randomUUID()}","active":false,"token":"zpa_mine","id":"$chosenId",""" +
                    """"version":99,"tokenHash":"aa"}""",
            )

        response.status shouldBe 201
        val body = iam.json(response)
        body["active"].asBoolean() shouldBe true
        (body["id"].asString() == chosenId) shouldBe false
        (body["token"].asString() == "zpa_mine") shouldBe false
        (body["version"].asLong() == 99L) shouldBe false
    }

    @Test
    fun `an invalid name is 400 on the name field and the value is never echoed`() {
        val blank = 0x200B.toChar()
        val bidi = 0x202E.toChar()
        val nbsp = 0xA0.toChar()
        val names =
            listOf(
                "ab${blank}cd",
                "$blank",
                "ab${bidi}cd",
                "a${0.toChar()}b",
                "ab\tcd",
                "a".repeat(101),
                "$nbsp",
            )
        names.forEach { name ->
            val response = iam.call(POST, path, iam.asAdmin(), mapOf("name" to name))
            withClue("nome de ${name.length} caracteres -> ${response.status} ${response.contentAsString}") {
                val problem = iam.run { response.isProblem(400, "VALIDATION_FAILED") }
                problem["errors"].values().map { it["field"].asString() } shouldBe listOf("name")
                response.contentAsString shouldNotContain name
            }
        }
    }

    @Test
    fun `a name of exactly 100 characters and one with accents are accepted`() {
        iam.call(POST, path, iam.asAdmin(), mapOf("name" to "a".repeat(100))).status shouldBe 201
        val accented = iam.call(POST, path, iam.asAdmin(), mapOf("name" to "Recepção ${UUID.randomUUID()}"))
        accented.status shouldBe 201
    }

    @Test
    fun `a user with no role, the manager and the receptionist are 403 before any 404 on every route`() {
        val users =
            listOf(
                iam.createUser(),
                userWithTemplate(RoleTemplate.MANAGER),
                userWithTemplate(RoleTemplate.RECEPTIONIST),
            )
        val missing = UUID.randomUUID()
        users.forEach { user ->
            listOf(
                GET to path,
                GET to "$path/$missing",
                POST to "$path/$missing/rotate-token",
                POST to "$path/$missing/deactivate",
            ).forEach { (method, uri) ->
                iam
                    .call(
                        method,
                        uri,
                        iam.asTestUser(user),
                    ).isProblem(403, "PERMISSION_DENIED")
                    .toString() shouldNotContain
                    "PRINT_AGENT_NOT_FOUND"
            }
        }
    }

    @Test
    fun `anonymous and garbage bearer callers are 401 on every agent route and an agent token opens none`() {
        val created = create()
        val id = created["id"].asString()
        val routes =
            listOf(
                GET to path,
                POST to path,
                GET to "$path/$id",
                POST to "$path/$id/rotate-token",
                POST to "$path/$id/deactivate",
            )
        routes.forEach { (method, uri) ->
            mockMvc
                .request(method, uri)
                .andReturn()
                .response.status shouldBe 401
            listOf("garbage", created["token"].asString()).forEach { bearer ->
                val response =
                    mockMvc
                        .request(method, uri) { header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") }
                        .andReturn()
                        .response
                withClue("$method $uri com bearer ${bearer.take(4)} -> ${response.status}") {
                    response.status shouldBe 401
                }
            }
        }
        iam.call(GET, "$path/$id", iam.asAdmin()).let { iam.run { it.okJson()["active"].asBoolean() } } shouldBe true
    }

    @Test
    fun `deactivating twice is 200 both times and does not bump the version`() {
        val id = create()["id"].asString()
        val first = iam.call(POST, "$path/$id/deactivate", iam.asAdmin()).let { iam.run { it.okJson() } }
        val second = iam.call(POST, "$path/$id/deactivate", iam.asAdmin()).let { iam.run { it.okJson() } }

        second["active"].asBoolean() shouldBe false
        second["version"].asLong() shouldBe first["version"].asLong()
    }
}
