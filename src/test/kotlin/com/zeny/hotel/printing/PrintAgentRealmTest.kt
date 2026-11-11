package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentStore
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** O realm do agente: o token próprio autentica só o prefixo `/api/v1/print-agents/me/` (M5 E10, Task 7). */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os colaboradores injetados do teste
class PrintAgentRealmTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val realm: PrintAgentRealm,
    @Autowired private val store: PrintAgentStore,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val admin = "/api/v1/print-agents"
    private val identity = "/api/v1/print-agents/me/identity"

    private class Agent(
        val id: String,
        val token: String,
    )

    private fun newAgent(name: String = "Agente balcão"): Agent {
        val body = iam.call(POST, admin, iam.asAdmin(), mapOf("name" to name))
        val json = jsonMapper.readTree(body.contentAsString)
        return Agent(json["id"].asString(), json["token"].asString())
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

    private fun lastSeen(id: String): Instant? =
        jdbc
            .sql("SELECT last_seen_at FROM printing.print_agent WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(Timestamp::class.java)
            .optional()
            .orElse(null)
            ?.toInstant()

    /** O banco guarda microssegundos (arredonda): o instante do relógio confere com 1 µs de folga. */
    private fun Instant?.shouldBeNow() {
        (this != null && Duration.between(this, clock.instant()).abs() <= Duration.ofNanos(1_000)) shouldBe true
    }

    @Test
    fun `the agent token on the identity endpoint is 200 with id and name`() {
        val agent = newAgent("Agente identidade")

        val response = send(GET, identity, "Bearer ${agent.token}")

        response.status shouldBe 200
        val json = jsonMapper.readTree(response.contentAsString)
        json["id"].asString() shouldBe agent.id
        json["name"].asString() shouldBe "Agente identidade"
        response.contentAsString shouldNotContain agent.token
    }

    @Test
    fun `the agent token on the administration endpoints is 401`() {
        val agent = newAgent()

        listOf(GET to admin, GET to "$admin/${agent.id}", POST to "$admin/${agent.id}/rotate-token").forEach { (m, p) ->
            send(m, p, "Bearer ${agent.token}").status shouldBe 401
        }
    }

    @Test
    fun `the agent token never rotates itself through the me path`() {
        val agent = newAgent()

        listOf("$admin/me/rotate-token", "$admin/me/deactivate", "$admin/me").forEach { path ->
            val status = send(POST, path, "Bearer ${agent.token}").status
            status shouldNotBe 200
            status shouldNotBe 500
        }
        send(GET, identity, "Bearer ${agent.token}").status shouldBe 200
    }

    @Test
    fun `a user jwt on the agent path is 401`() {
        val user = iam.createUser()
        val access = iam.loginOk(user)["accessToken"].asString()

        send(GET, identity, "Bearer $access").status shouldBe 401
    }

    @Test
    fun `a revoked agent and the token before a rotation are 401`() {
        val revoked = newAgent("Revogado")
        send(GET, identity, "Bearer ${revoked.token}").status shouldBe 200
        iam.call(POST, "$admin/${revoked.id}/deactivate", iam.asAdmin()).status shouldBe 200
        send(GET, identity, "Bearer ${revoked.token}").status shouldBe 401

        val rotated = newAgent("Rotacionado")
        val rotation = iam.call(POST, "$admin/${rotated.id}/rotate-token", iam.asAdmin())
        val newToken = jsonMapper.readTree(rotation.contentAsString)["token"].asString()
        send(GET, identity, "Bearer ${rotated.token}").status shouldBe 401
        send(GET, identity, "Bearer $newToken").status shouldBe 200
    }

    @Test
    fun `malformed or foreign tokens are 401 and an unknown zpa token too`() {
        val agent = newAgent()
        val zeroWidth = 0x200B.toChar()

        listOf(
            null,
            "Bearer ",
            "Bearer wrongprefix_${agent.token.removePrefix("zpa_")}",
            "Bearer ${agent.token}$zeroWidth",
            "Bearer ${agent.token} ${agent.token}",
            "Bearer zpa_" + "A".repeat(43),
            "Basic ${agent.token}",
        ).forEach { authorization ->
            send(GET, identity, authorization).status shouldBe 401
        }
    }

    @Test
    fun `last seen is written on first use and not rewritten within a minute`() {
        val agent = newAgent()
        lastSeen(agent.id) shouldBe null

        send(GET, identity, "Bearer ${agent.token}").status shouldBe 200
        val first = lastSeen(agent.id)!!
        first.shouldBeNow()

        clock.advance(Duration.ofSeconds(30))
        send(GET, identity, "Bearer ${agent.token}").status shouldBe 200
        lastSeen(agent.id) shouldBe first

        clock.advance(Duration.ofSeconds(31))
        send(GET, identity, "Bearer ${agent.token}").status shouldBe 200
        lastSeen(agent.id).shouldBeNow()
    }

    @Test
    fun `contacts never change the registration version`() {
        val agent = newAgent("Agente versão")
        val before =
            iam.call(GET, "$admin/${agent.id}", iam.asAdmin()).let {
                jsonMapper.readTree(it.contentAsString)["version"].asLong()
            }

        repeat(3) {
            clock.advance(Duration.ofMinutes(2))
            send(GET, identity, "Bearer ${agent.token}").status shouldBe 200
            lastSeen(agent.id).shouldBeNow()
        }

        val after =
            jsonMapper
                .readTree(
                    iam.call(GET, "$admin/${agent.id}", iam.asAdmin()).contentAsString,
                )["version"]
                .asLong()
        after shouldBe before
    }

    @Test
    fun `a contact between the read and the flush of an admin write does not fail it nor get overwritten`() {
        val agent = newAgent("Agente corrida")
        val id = UUID.fromString(agent.id)
        clock.advance(Duration.ofMinutes(2))

        TransactionTemplate(transactionManager).execute {
            val loaded = store.byId(id)!!
            loaded.rename("Agente corrida 2")
            // O contato chega e grava em outra transação antes do flush do administrador.
            realm.authenticate(agent.token) shouldNotBe null
            store.flush()
        }

        lastSeen(agent.id).shouldBeNow()
        jdbc
            .sql("SELECT name FROM printing.print_agent WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single() shouldBe "Agente corrida 2"
    }

    @Test
    fun `the audit trail shows the emission count going up on a rotation, unmasked`() {
        val agent = newAgent("Agente época")
        iam.call(POST, "$admin/${agent.id}/rotate-token", iam.asAdmin()).status shouldBe 200

        val row =
            jdbc
                .sql(
                    "SELECT before_state->>'emissionCount' AS b, after_state->>'emissionCount' AS a " +
                        "FROM audit.audit_entry WHERE action = 'printing.agent.rotate-token' AND entity_id = :id",
                ).param("id", agent.id)
                .query()
                .singleRow()

        row["b"] shouldBe "1"
        row["a"] shouldBe "2"
    }

    @Test
    fun `the contract has the agent scheme only on the me operations and no public operation`() {
        val doc =
            jsonMapper.readTree(
                mockMvc
                    .request(GET, "/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )

        doc["components"]["securitySchemes"]["printAgentToken"]["bearerFormat"].asString() shouldBe "opaque"
        val bySchemes =
            doc["paths"].properties().flatMap { (path, item) ->
                item.properties().map { (_, operation) ->
                    path to operation["security"]?.values()?.flatMap { it.propertyNames().toList() }
                }
            }
        bySchemes.filter { (_, s) -> s == listOf("printAgentToken") }.map { it.first }.toSet() shouldBe
            setOf(
                "/api/v1/print-agents/me/identity",
                "/api/v1/print-agents/me/jobs",
                "/api/v1/print-agents/me/jobs/{id}/ack",
                "/api/v1/print-agents/me/jobs/{id}/content",
                "/api/v1/print-agents/me/jobs/stream",
            )
        bySchemes
            .filter { (path, _) -> path.startsWith("/api/v1/print-agents/me") }
            .forEach { (_, s) -> s shouldBe listOf("printAgentToken") }
        bySchemes
            .filter { (path, _) -> path.startsWith("/api/v1/print-agents") && !path.contains("/me") }
            .forEach { (_, s) -> s shouldBe null }
    }
}
