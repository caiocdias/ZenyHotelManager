package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsMaintenance
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** A manutenção da fila de impressão (M5 E10, Task 12): reenvio, limite, validade, impressora inativa e expurgo. */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList")
class PrintJobsMaintenanceTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val delivery: PrintAgentJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val maintenance: PrintJobsMaintenance,
    @Autowired private val realm: PrintAgentRealm,
    @Autowired private val clock: MutableClock,
) {
    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Printer(
        val id: UUID,
        val agent: Agent,
    )

    private var advanced: Duration = Duration.ZERO

    /** O relógio é compartilhado pelo contexto: devolve-o ao fim de cada teste. */
    @AfterEach
    fun restoreClock() {
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
    }

    private fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun newAgent(): Agent {
        val json = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente ${suffix()}"))
        val body = jsonMapper.readTree(json.contentAsString)
        return Agent(UUID.fromString(body["id"].asString()), body["token"].asString())
    }

    private fun newPrinter(agent: Agent = newAgent()): Printer {
        val body =
            mapOf(
                "name" to "Imp ${suffix()}",
                "kind" to "A4_PDF",
                "address" to "addr-${suffix()}",
                "agentId" to agent.id,
            )
        val json = iam.call(POST, "/api/v1/printers", iam.asAdmin(), body)
        return Printer(UUID.fromString(jsonMapper.readTree(json.contentAsString)["id"].asString()), agent)
    }

    private fun enqueue(printer: Printer): UUID =
        jobs.enqueue(
            NewPrintJob(printer.id, "frontdesk.registration-card", "%PDF-1.7 SEGREDO-${suffix()}".toByteArray()),
        )

    private fun principalOf(agent: Agent): ServicePrincipal = realm.authenticate(agent.token)!!

    private fun claim(agent: Agent) = delivery.claim(principalOf(agent), CLAIM_LIMIT)

    private fun ack(
        agent: Agent,
        job: UUID,
        result: String = "PRINTED",
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, "/api/v1/print-agents/me/jobs/$job/ack") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}")
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("result" to result))
            }.andReturn()
            .response

    private fun row(id: UUID): Map<String, Any?> =
        jdbc
            .sql(
                "SELECT status, position, attempts, failure_code, delivered_to, finished_at, " +
                    "content IS NULL AS no_content, content_purged_at FROM printing.print_job WHERE id = :id",
            ).param("id", id)
            .query { rs, _ ->
                mapOf(
                    "status" to rs.getString("status"),
                    "position" to rs.getLong("position"),
                    "attempts" to rs.getInt("attempts"),
                    "failure" to rs.getString("failure_code"),
                    "deliveredTo" to rs.getObject("delivered_to"),
                    "finishedAt" to rs.getObject("finished_at"),
                    "noContent" to rs.getBoolean("no_content"),
                    "purgedAt" to rs.getObject("content_purged_at"),
                )
            }.single()

    private fun status(id: UUID) = row(id)["status"]

    private fun asAdmin(block: () -> Unit) {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(UserId(iam.adminId()).toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `a SENT job without ack goes back to PENDING with a new position and is delivered again`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val firstPosition = row(job)["position"] as Long

        advance(Duration.ofMinutes(1))
        maintenance.run()
        status(job) shouldBe "SENT"

        advance(Duration.ofMinutes(1).plusSeconds(1))
        maintenance.run()

        val after = row(job)
        after["status"] shouldBe "PENDING"
        (after["position"] as Long) shouldBeGreaterThan firstPosition
        after["attempts"] shouldBe 1
        after["deliveredTo"] shouldBe null
        claim(printer.agent).map { it.id } shouldBe listOf(job)
        row(job)["attempts"] shouldBe 2
    }

    @Test
    fun `the third claim without ack ends as FAILED ACK_TIMEOUT`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        repeat(2) {
            claim(printer.agent) shouldHaveSize 1
            advance(Duration.ofMinutes(3))
            maintenance.run()
            status(job) shouldBe "PENDING"
        }
        claim(printer.agent) shouldHaveSize 1
        row(job)["attempts"] shouldBe 3

        advance(Duration.ofMinutes(3))
        maintenance.run()

        val after = row(job)
        after["status"] shouldBe "FAILED"
        after["failure"] shouldBe "ACK_TIMEOUT"
        after["finishedAt"] shouldNotBe null
        claim(printer.agent) shouldHaveSize 0
    }

    @Test
    fun `a late ack of a requeued job is 409 and after a new claim the ack is accepted once`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        advance(Duration.ofMinutes(3))
        maintenance.run()
        status(job) shouldBe "PENDING"

        ack(printer.agent, job).status shouldBe 409
        status(job) shouldBe "PENDING"

        claim(printer.agent) shouldHaveSize 1
        ack(printer.agent, job).status shouldBe 204
        ack(printer.agent, job).status shouldBe 409
        status(job) shouldBe "PRINTED"
    }

    @Test
    fun `a PENDING job older than the ttl becomes FAILED EXPIRED and a younger one stays`() {
        val printer = newPrinter()
        val job = enqueue(printer)

        advance(Duration.ofHours(23))
        maintenance.run()
        status(job) shouldBe "PENDING"

        advance(Duration.ofHours(1).plusSeconds(1))
        maintenance.run()
        val after = row(job)
        after["status"] shouldBe "FAILED"
        after["failure"] shouldBe "EXPIRED"
    }

    @Test
    fun `a deactivated printer sends its PENDING and SENT jobs to FAILED PRINTER_INACTIVE and spares the rest`() {
        val agent = newAgent()
        val off = newPrinter(agent)
        val on = newPrinter(agent)
        val sent = enqueue(off)
        claim(agent) // reivindica só o que já existe: o job da impressora desativada
        val printed = enqueue(off)
        claim(agent)
        ack(agent, printed).status shouldBe 204
        val pending = enqueue(off)
        val other = enqueue(on)
        asAdmin { agents.deactivatePrinter(off.id) }

        maintenance.run()

        listOf(sent, pending).forEach {
            row(it)["status"] shouldBe "FAILED"
            row(it)["failure"] shouldBe "PRINTER_INACTIVE"
        }
        status(printed) shouldBe "PRINTED"
        status(other) shouldBe "PENDING"
    }

    @Test
    fun `a deactivated agent sends the jobs of its active printers to FAILED PRINTER_INACTIVE`() {
        val printer = newPrinter()
        val sent = enqueue(printer)
        claim(printer.agent)
        val pending = enqueue(printer)
        asAdmin { agents.deactivateAgent(printer.agent.id) }

        maintenance.run()

        listOf(sent, pending).forEach {
            row(it)["status"] shouldBe "FAILED"
            row(it)["failure"] shouldBe "PRINTER_INACTIVE"
        }
    }

    @Test
    fun `the content of PRINTED and FAILED jobs is erased after the retention and PENDING and SENT keep theirs`() {
        val printer = newPrinter()
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        claim(printer.agent) shouldHaveSize 2
        ack(printer.agent, printed).status shouldBe 204
        ack(printer.agent, failed, "FAILED").status shouldBe 204
        val recent = enqueue(printer)

        advance(Duration.ofHours(23))
        maintenance.run()
        listOf(printed, failed).forEach { row(it)["noContent"] shouldBe false }

        advance(Duration.ofHours(1).plusSeconds(1))
        maintenance.run()

        listOf(printed, failed).forEach {
            row(it)["noContent"] shouldBe true
            row(it)["purgedAt"] shouldNotBe null
        }
        // Ainda PENDING há pouco: expirou agora (FAILED recente), com o conteúdo guardado até a retenção.
        row(recent)["noContent"] shouldBe false
        row(recent)["purgedAt"] shouldBe null
    }

    @Test
    fun `an unfinished job never loses its content and a second run changes nothing`() {
        val printer = newPrinter()
        val printed = enqueue(printer)
        claim(printer.agent)
        ack(printer.agent, printed).status shouldBe 204
        val pending = enqueue(printer)
        advance(Duration.ofHours(25))
        maintenance.run()
        val first = row(printed)["purgedAt"]
        first shouldNotBe null
        val failedPending = row(pending)

        val second = maintenance.run()

        row(printed)["purgedAt"] shouldBe first
        row(pending) shouldBe failedPending
        (second.requeued + second.ackTimedOut + second.expired + second.inactive) shouldBe 0
    }

    @Test
    fun `the maintenance is audited by the system and the trail never carries the content`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent)
        advance(Duration.ofMinutes(3))
        maintenance.run()

        val entries =
            jdbc
                .sql(
                    "SELECT user_id, after_state::text FROM audit.audit_entry " +
                        "WHERE action = 'printing.job.expire-ack' AND entity_id = :id",
                ).param("id", job.toString())
                .query { rs, _ -> rs.getObject("user_id") to rs.getString(2) }
                .list()

        entries shouldHaveSize 1
        entries.single().first shouldBe null
        entries.single().second shouldNotContain "SEGREDO"
    }

    private infix fun Long.shouldBeGreaterThan(other: Long) = (this > other) shouldBe true

    private companion object {
        const val CLAIM_LIMIT = 10
    }
}
