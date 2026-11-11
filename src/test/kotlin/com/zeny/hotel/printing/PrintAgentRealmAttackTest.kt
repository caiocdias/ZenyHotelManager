package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Ataques do testador ao realm do agente de impressão (M5 E10, Task 7; D-E10-1, D-E10-2, D-E10-9, D-E10-11 e Review
 * Focus 5): o token só vale inteiro, ativo e no prefixo `/api/v1/print-agents/me/`; o contato (`last_seen_at`) é
 * gravado no máximo uma vez por minuto e nunca por requisição recusada; a trilha não guarda token nem hash.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PrintAgentRealmAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val admin = "/api/v1/print-agents"
    private val identity = "/api/v1/print-agents/me/identity"

    private class Agent(
        val id: String,
        val token: String,
    )

    private fun newAgent(name: String = "Agente realm ${UUID.randomUUID()}"): Agent {
        val json = jsonMapper.readTree(iam.call(POST, admin, iam.asAdmin(), mapOf("name" to name)).contentAsString)
        return Agent(json["id"].asString(), json["token"].asString())
    }

    private fun rotate(agent: Agent): Agent {
        val json = jsonMapper.readTree(iam.call(POST, "$admin/${agent.id}/rotate-token", iam.asAdmin()).contentAsString)
        return Agent(agent.id, json["token"].asString())
    }

    private fun send(
        method: HttpMethod,
        path: String,
        authorization: String?,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                if (authorization != null) header(HttpHeaders.AUTHORIZATION, authorization)
            }.andReturn()
            .response

    private fun whoAmI(token: String) = send(GET, identity, "Bearer $token")

    private fun lastSeen(id: String): Instant? =
        jdbc
            .sql("SELECT last_seen_at FROM printing.print_agent WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(Timestamp::class.java)
            .optional()
            .orElse(null)
            ?.toInstant()

    private fun storedHash(id: String): String? =
        jdbc
            .sql("SELECT token_hash FROM printing.print_agent WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun contactRows(): Long =
        jdbc
            .sql("SELECT count(*) FROM printing.print_agent WHERE last_seen_at IS NOT NULL")
            .query(Long::class.java)
            .single()

    @Test
    fun `a deactivated agent is 401 and its refused request records no contact`() {
        val agent = newAgent()
        iam.call(POST, "$admin/${agent.id}/deactivate", iam.asAdmin()).status shouldBe 200

        whoAmI(agent.token).status shouldBe 401
        lastSeen(agent.id) shouldBe null
        storedHash(agent.id) shouldBe null
    }

    @Test
    fun `a rotated token is 401 and does not touch the agent while the new one works`() {
        val agent = newAgent()
        val rotated = rotate(agent)
        lastSeen(agent.id) shouldBe null

        whoAmI(agent.token).status shouldBe 401
        lastSeen(agent.id) shouldBe null

        whoAmI(rotated.token).status shouldBe 200
        lastSeen(agent.id) shouldNotBe null
    }

    @Test
    fun `a rotated twice agent accepts only the latest token`() {
        val agent = newAgent()
        val second = rotate(agent)
        val third = rotate(second)

        listOf(agent.token, second.token).forEach { whoAmI(it).status shouldBe 401 }
        whoAmI(third.token).status shouldBe 200
    }

    @Test
    fun `each agent token is the identity of its own agent and never of another`() {
        val first = newAgent("Primeiro ${UUID.randomUUID()}")
        val second = newAgent("Segundo ${UUID.randomUUID()}")

        jsonMapper.readTree(whoAmI(first.token).contentAsString)["id"].asString() shouldBe first.id
        jsonMapper.readTree(whoAmI(second.token).contentAsString)["id"].asString() shouldBe second.id

        iam.call(POST, "$admin/${first.id}/deactivate", iam.asAdmin()).status shouldBe 200
        whoAmI(first.token).status shouldBe 401
        whoAmI(second.token).status shouldBe 200
    }

    @Test
    fun `the stored hash sent as the token, with or without the prefix, is 401`() {
        val agent = newAgent()
        val hash = storedHash(agent.id)!!
        hash shouldBe PrintAgent.hashOf(agent.token)

        listOf(hash, "zpa_$hash", hash.uppercase(), "zpa_" + hash.take(43)).forEach {
            withClue(it.take(8)) { whoAmI(it).status shouldBe 401 }
        }
        whoAmI(agent.token).status shouldBe 200
    }

    @Test
    fun `tokens that are almost right are all 401`() {
        val agent = newAgent()
        val body = agent.token.removePrefix("zpa_")
        val zeroWidth = 0x200B.toChar()
        val noBreakSpace = 0xA0.toChar()
        val bom = 0xFEFF.toChar()
        val lastChar = agent.token.last()
        val otherLast = if (lastChar == 'A') 'B' else 'A'

        listOf(
            "zpa_" + "A".repeat(43),
            "zpa_" + UUID.randomUUID().toString().replace("-", ""),
            "zpa_",
            body,
            "ZPA_$body",
            "Zpa_$body",
            agent.token.dropLast(1),
            agent.token.take(10),
            agent.token + "A",
            agent.token + "=",
            agent.token.dropLast(1) + otherLast,
            "$zeroWidth${agent.token}",
            "${agent.token}$zeroWidth",
            "${agent.token}$noBreakSpace",
            "${agent.token}$bom",
            "zpa_$zeroWidth$body",
            "${agent.token} ",
            " ${agent.token}",
            "${agent.token} ${agent.token}",
            "${agent.token}\t",
            "zpa_ $body",
        ).forEach { token ->
            val response = send(GET, identity, "Bearer $token")
            withClue("token '${token.take(6)}…' len=${token.length} -> ${response.status}") {
                response.status shouldBe 401
            }
        }
        listOf("Bearer  ${agent.token}", "Bearer\t${agent.token}", "Token ${agent.token}", agent.token).forEach {
            withClue("header '${it.take(10)}…'") { send(GET, identity, it).status shouldBe 401 }
        }
        lastSeen(agent.id) shouldBe null
        whoAmI(agent.token).status shouldBe 200
    }

    @Test
    fun `a repeated bearer header and a header with the token twice are 401`() {
        val agent = newAgent()

        val repeated =
            mockMvc
                .request(GET, identity) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}")
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}")
                }.andReturn()
                .response
        repeated.status shouldBe 401
        send(GET, identity, "Bearer Bearer ${agent.token}").status shouldBe 401
    }

    @Test
    fun `the agent token is 401 on every administration route and on routes of other modules`() {
        val agent = newAgent()
        val id = agent.id
        val printerId = UUID.randomUUID()
        val routes =
            listOf(
                GET to admin,
                POST to admin,
                GET to "$admin/$id",
                POST to "$admin/$id/rotate-token",
                POST to "$admin/$id/deactivate",
                GET to "/api/v1/printers",
                POST to "/api/v1/printers",
                GET to "/api/v1/printers/$printerId",
                PUT to "/api/v1/printers/$printerId",
                POST to "/api/v1/printers/$printerId/deactivate",
                GET to "/api/v1/auth/sessions",
                GET to "/api/v1/rooms",
                GET to "/api/v1/hotel",
                GET to "/api/v1/stream",
                GET to "/api/v1/does-not-exist",
                DELETE to "$admin/$id",
            )
        routes.forEach { (method, uri) ->
            val response = send(method, uri, "Bearer ${agent.token}")
            withClue("$method $uri -> ${response.status}") { response.status shouldBe 401 }
            response.contentAsString shouldNotContain agent.token
        }
        lastSeen(agent.id) shouldBe null
        iam.call(GET, "$admin/$id", iam.asAdmin()).status shouldBe 200
        storedHash(id) shouldBe PrintAgent.hashOf(agent.token)
    }

    @Test
    fun `the agent token never reaches a user route through the me prefix with another method or path`() {
        val agent = newAgent()
        val paths =
            listOf(
                "$admin/me",
                "$admin/me/",
                "$admin/me/rotate-token",
                "$admin/me/deactivate",
                "$admin/me/%2e%2e/${agent.id}",
                "$admin/me/../${agent.id}",
                "$admin/me/identity/x",
                "$admin/me//identity",
                "$admin/me;a=b/identity",
                "$admin/ME/identity",
                "$admin//me/identity",
            )
        paths.forEach { path ->
            listOf(GET, POST, PUT, DELETE).forEach { method ->
                val response = send(method, path, "Bearer ${agent.token}")
                withClue("$method $path -> ${response.status} ${response.contentAsString.take(120)}") {
                    if (response.status == 200) {
                        // o caminho normalizado é a própria identidade (GET) e responde só o agente do token
                        method shouldBe GET
                        jsonMapper.readTree(response.contentAsString)["id"].asString() shouldBe agent.id
                    }
                    response.status shouldNotBe 500
                    response.contentAsString shouldNotContain "\"token\""
                }
            }
        }
        storedHash(agent.id) shouldBe PrintAgent.hashOf(agent.token)
    }

    @Test
    fun `a user jwt, admin included, on the agent path is 401`() {
        val user = iam.createUser()
        val userJwt = iam.loginOk(user)["accessToken"].asString()
        // Administrador próprio: o admin semente tem a senha trocada pelo InitialAdminTest no banco compartilhado.
        val agentsManage = PrintingPermissions.permissions.single { it.code == PrintingPermissions.AGENTS_MANAGE }
        val adminUser = iam.createUserWith(TestGrant(agentsManage, PermissionScope.ALL))
        val adminJwt = iam.loginOk(adminUser)["accessToken"].asString()
        adminJwt shouldNotBe null

        listOf(userJwt, adminJwt).forEach { jwt ->
            send(GET, identity, "Bearer $jwt").status shouldBe 401
            send(POST, "$admin/me/rotate-token", "Bearer $jwt").status shouldBe 401
        }
        iam.call(GET, identity, iam.asAdmin()).status shouldBe 401
        send(GET, identity, null).status shouldBe 401
        send(GET, admin, "Bearer $adminJwt").status shouldNotBe 401
    }

    @Test
    fun `last seen is written at most once a minute with a stopped clock and inside the limit`() {
        val agent = newAgent()
        whoAmI(agent.token).status shouldBe 200
        val first = lastSeen(agent.id)!!

        repeat(5) { whoAmI(agent.token).status shouldBe 200 }
        lastSeen(agent.id) shouldBe first

        clock.advance(Duration.ofSeconds(59))
        whoAmI(agent.token).status shouldBe 200
        lastSeen(agent.id) shouldBe first

        clock.advance(Duration.ofSeconds(2))
        whoAmI(agent.token).status shouldBe 200
        val second = lastSeen(agent.id)!!
        (second.isAfter(first)) shouldBe true
    }

    @Test
    fun `a clock going backwards neither fails the request nor rewinds last seen`() {
        val agent = newAgent()
        whoAmI(agent.token).status shouldBe 200
        val first = lastSeen(agent.id)!!

        clock.advance(Duration.ofMinutes(-10))
        try {
            whoAmI(agent.token).status shouldBe 200
            withClue("o relógio voltou: o último contato não pode recuar") { lastSeen(agent.id) shouldBe first }
        } finally {
            clock.advance(Duration.ofMinutes(10))
        }
        whoAmI(agent.token).status shouldBe 200
    }

    @Test
    fun `refused requests record no contact on any agent`() {
        val agent = newAgent()
        val other = newAgent()
        val before = contactRows()

        listOf(
            "Bearer zpa_" + "A".repeat(43),
            "Bearer ${agent.token}x",
            "Bearer ${PrintAgent.hashOf(agent.token)}",
            "Bearer garbage",
        ).forEach { send(GET, identity, it).status shouldBe 401 }
        send(GET, admin, "Bearer ${agent.token}").status shouldBe 401
        send(GET, identity, null).status shouldBe 401

        contactRows() shouldBe before
        lastSeen(agent.id) shouldBe null
        lastSeen(other.id) shouldBe null
    }

    @Test
    fun `many concurrent first requests of the same agent all succeed and write a single contact`() {
        val agent = newAgent()
        val threads = 24
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val statuses =
                pool
                    .invokeAll(List(threads * 2) { Callable { whoAmI(agent.token).status } })
                    .map { it.get() }
            withClue("statuses: ${statuses.groupingBy { it }.eachCount()}") {
                statuses.all { it == 200 } shouldBe true
            }
        } finally {
            pool.shutdownNow()
        }
        lastSeen(agent.id) shouldNotBe null
    }

    @Test
    fun `concurrent requests racing with a rotation are only 200 or 401, never 500`() {
        val agent = newAgent()
        whoAmI(agent.token).status shouldBe 200
        clock.advance(Duration.ofMinutes(2))
        val pool = Executors.newFixedThreadPool(16)
        try {
            val calls = List(32) { Callable { whoAmI(agent.token).status } }
            val rotation = pool.submit(Callable { rotate(agent) })
            val statuses = pool.invokeAll(calls).map { it.get() }
            val latest = rotation.get()
            withClue("statuses: ${statuses.groupingBy { it }.eachCount()}") {
                statuses.all { it == 200 || it == 401 } shouldBe true
            }
            whoAmI(agent.token).status shouldBe 401
            whoAmI(latest.token).status shouldBe 200
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the audit trail and the log hold neither the token nor its hash`(output: CapturedOutput) {
        val agent = newAgent()
        whoAmI(agent.token).status shouldBe 200
        send(GET, identity, "Bearer ${agent.token}x").status shouldBe 401
        val rotated = rotate(agent)
        whoAmI(agent.token).status shouldBe 401
        whoAmI(rotated.token).status shouldBe 200
        val trailBefore = trailCount(agent.id)

        repeat(3) { whoAmI(rotated.token).status shouldBe 200 }
        trailCount(agent.id) shouldBe trailBefore

        val trail =
            jdbc
                .sql("SELECT row_to_json(a)::text FROM audit.audit_entry a WHERE a.entity_id = :id")
                .param("id", agent.id)
                .query(String::class.java)
                .list()
                .joinToString("\n")
        withClue("a trilha do agente tem a criação e a rotação") { trail shouldNotBe "" }
        listOf(agent.token, rotated.token, PrintAgent.hashOf(agent.token), PrintAgent.hashOf(rotated.token)).forEach {
            trail shouldNotContain it
            trail shouldNotContain it.removePrefix("zpa_")
            output.all shouldNotContain it
            output.all shouldNotContain it.removePrefix("zpa_")
        }
    }

    private fun trailCount(id: String): Long =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    @Test
    fun `the published contract has the agent scheme only under the me prefix and no empty security`() {
        val contract = Yaml.mapper().readValue(Path.of("api/openapi.yaml").toFile(), OpenAPI::class.java)
        val live =
            jsonMapper.readTree(
                mockMvc
                    .request(GET, "/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )
        contract.components.securitySchemes["printAgentToken"]!!.scheme shouldBe "bearer"
        contract.components.securitySchemes["printAgentToken"]!!.bearerFormat shouldBe "opaque"
        live["components"]["securitySchemes"]["printAgentToken"]["scheme"].asString() shouldBe "bearer"

        val operations =
            contract.paths.flatMap { (uri, item) -> item.readOperationsMap().map { (m, op) -> Triple(m, uri, op) } }
        operations
            .filter { (_, uri, _) -> uri.startsWith("/api/v1/print-agents/me") }
            .forEach { (m, uri, op) ->
                withClue("$m $uri") { op.security.map { it.keys.toList() } shouldBe listOf(listOf("printAgentToken")) }
            }
        operations
            .filter { (_, uri, _) -> !uri.startsWith("/api/v1/print-agents/me") }
            .forEach { (m, uri, op) ->
                withClue("$m $uri") { (op.security.orEmpty().any { "printAgentToken" in it.keys }) shouldBe false }
            }
        operations
            .filter { (_, uri, _) -> uri.startsWith("/api/v1/print-agents") || uri.startsWith("/api/v1/printers") }
            .forEach { (m, uri, op) -> withClue("$m $uri") { (op.security?.isEmpty() == true) shouldBe false } }
    }
}
