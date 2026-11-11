package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UnauthenticatedException
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A entrega dos jobs ao agente: long-poll, conteúdo, ack e limite de conexões (M5 E10, Task 10). */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList")
class PrintAgentJobsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val delivery: PrintAgentJobsService,
    @Autowired private val connections: PrintAgentConnections,
    @Autowired private val realm: PrintAgentRealm,
) {
    private val base = "/api/v1/print-agents/me/jobs"

    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Printer(
        val id: UUID,
        val agent: Agent,
    )

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun newAgent(): Agent {
        val json = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente ${suffix()}"))
        val body = jsonMapper.readTree(json.contentAsString)
        return Agent(UUID.fromString(body["id"].asString()), body["token"].asString())
    }

    private fun newPrinter(
        agent: Agent = newAgent(),
        kind: String = "A4_PDF",
    ): Printer {
        val body =
            mapOf(
                "name" to "Imp ${suffix()}",
                "kind" to kind,
                "address" to "addr-${suffix()}",
                "agentId" to agent.id,
            )
        val json = iam.call(POST, "/api/v1/printers", iam.asAdmin(), body)
        return Printer(UUID.fromString(jsonMapper.readTree(json.contentAsString)["id"].asString()), agent)
    }

    private fun pdf(marker: String = suffix()) = "%PDF-1.7 SEGREDO-$marker".toByteArray()

    private fun enqueue(
        printer: Printer,
        content: ByteArray = pdf(),
    ): UUID = jobs.enqueue(NewPrintJob(printer.id, "frontdesk.registration-card", content))

    private fun statusOf(id: UUID): String =
        jdbc
            .sql(
                "SELECT status FROM printing.print_job WHERE id = :id",
            ).param("id", id)
            .query(String::class.java)
            .single()

    private fun principalOf(agent: Agent): ServicePrincipal = realm.authenticate(agent.token)!!

    private fun send(
        method: HttpMethod,
        path: String,
        token: String?,
        body: Any? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                if (body != null) {
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(body)
                }
            }.andReturn()
            .response

    /** Abre o long-poll e devolve o resultado ainda não concluído (a resposta assíncrona pode demorar). */
    private fun startPoll(
        agent: Agent,
        wait: Int,
    ): MvcResult =
        mockMvc
            .request(GET, "$base?wait=$wait") { header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}") }
            .andReturn()

    private fun finish(result: MvcResult): MockHttpServletResponse {
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult // espera, com prazo, a resposta assíncrona
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    private fun poll(
        agent: Agent,
        wait: Int = 0,
    ): MockHttpServletResponse = finish(startPoll(agent, wait))

    private fun bodyOf(response: MockHttpServletResponse): JsonNode = jsonMapper.readTree(response.contentAsString)

    private fun idsOf(response: MockHttpServletResponse): List<String> =
        bodyOf(response).values().map { it["id"].asString() }

    private fun ack(
        agent: Agent,
        job: UUID,
        result: String,
        failureCode: String? = null,
    ): MockHttpServletResponse =
        send(
            POST,
            "$base/$job/ack",
            agent.token,
            listOfNotNull(
                "result" to result,
                failureCode?.let {
                    "failureCode" to
                        it
                },
            ).toMap(),
        )

    @Test
    fun `an agent receives only the jobs of its own printers and another agent does not`() {
        val a = newPrinter()
        val b = newPrinter()
        val jobA = enqueue(a)
        val jobB = enqueue(b)

        val forA = poll(a.agent)
        val forB = poll(b.agent)

        forA.status shouldBe 200
        idsOf(forA) shouldContainExactly listOf(jobA.toString())
        val first = bodyOf(forA).values().single()
        first["printerId"].asString() shouldBe a.id.toString()
        first["documentType"].asString() shouldBe "frontdesk.registration-card"
        first["mediaType"].asString() shouldBe "application/pdf"
        first["attempts"].asInt() shouldBe 1
        forA.contentAsString shouldNotContain "SEGREDO"
        idsOf(forB) shouldContainExactly listOf(jobB.toString())
        statusOf(jobA) shouldBe "SENT"
    }

    @Test
    fun `another agent cannot read the content nor acknowledge a job that is not its own`() {
        val a = newPrinter()
        val other = newAgent()
        val job = enqueue(a)
        poll(a.agent)

        send(GET, "$base/$job/content", other.token).status shouldBe 404
        ack(other, job, "PRINTED").status shouldBe 404
        ack(other, UUID.randomUUID(), "PRINTED").status shouldBe 404
        statusOf(job) shouldBe "SENT"
    }

    @Test
    fun `wait zero without a job is 200 and an empty list`() {
        val agent = newPrinter().agent

        val response = poll(agent, 0)

        response.status shouldBe 200
        response.contentAsString shouldBe "[]"
    }

    @Test
    fun `a long poll that waits receives the job created during the wait`() {
        val printer = newPrinter()
        val pending = startPoll(printer.agent, 10)
        pending.request.isAsyncStarted shouldBe true

        val job = enqueue(printer)
        val response = finish(pending)

        response.status shouldBe 200
        idsOf(response) shouldContainExactly listOf(job.toString())
    }

    @Test
    fun `a long poll without a job ends with an empty list when the wait is over`() {
        val response = poll(newPrinter().agent, 1)

        response.status shouldBe 200
        response.contentAsString shouldBe "[]"
    }

    @Test
    fun `concurrent claims of the same agent never take the same job`() {
        val printer = newPrinter()
        val created = (1..6).map { enqueue(printer) }.toSet()
        val principal = principalOf(printer.agent)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..8).map {
                    pool.submit<List<UUID>> {
                        gate.await()
                        delivery.claim(principal, 2).map { it.id }
                    }
                }
            gate.countDown()
            val claimed = futures.flatMap { it.get(30, TimeUnit.SECONDS) }

            claimed shouldHaveSize created.size
            claimed.toSet() shouldBe created
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `acknowledging with PRINTED or FAILED changes the status and a finished job is 409`() {
        val printer = newPrinter()
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        poll(printer.agent)

        ack(printer.agent, printed, "PRINTED").status shouldBe 204
        ack(printer.agent, failed, "FAILED", "PAPER_JAM").status shouldBe 204

        statusOf(printed) shouldBe "PRINTED"
        statusOf(failed) shouldBe "FAILED"
        jdbc
            .sql("SELECT failure_code FROM printing.print_job WHERE id = :id")
            .param("id", failed)
            .query(String::class.java)
            .single() shouldBe "PAPER_JAM"
        ack(printer.agent, printed, "PRINTED").status shouldBe 409
        ack(printer.agent, failed, "PRINTED").status shouldBe 409
    }

    @Test
    fun `acknowledging a pending job or with an invalid result or failure code is refused`() {
        val printer = newPrinter()
        val pending = enqueue(printer)

        ack(printer.agent, pending, "PRINTED").status shouldBe 409
        poll(printer.agent)
        ack(printer.agent, pending, "MAYBE").status shouldBe 400
        ack(printer.agent, pending, "FAILED", "texto livre").status shouldBe 400
        statusOf(pending) shouldBe "SENT"
    }

    @Test
    fun `a job of an own printer that is not SENT to the agent is 409 and one of a foreign printer is 404`() {
        val printer = newPrinter()
        val pending = enqueue(printer)
        val finished = enqueue(printer)
        poll(printer.agent)
        ack(printer.agent, finished, "PRINTED").status shouldBe 204
        jdbc
            .sql("UPDATE printing.print_job SET status = 'PENDING', delivered_to = NULL WHERE id = :id")
            .param("id", pending)
            .update()

        ack(printer.agent, pending, "PRINTED").status shouldBe 409
        ack(printer.agent, finished, "PRINTED").status shouldBe 409
        ack(newAgent(), pending, "PRINTED").status shouldBe 404
        statusOf(pending) shouldBe "PENDING"
    }

    @Test
    fun `a numeric or unknown result is 400 on the field`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        poll(printer.agent)

        listOf("0", "1", "0.0", "1.0", "\"MAYBE\"", "null").forEach {
            send(POST, "$base/$job/ack", printer.agent.token, "{\"result\":$it}").status shouldBe 400
        }
        statusOf(job) shouldBe "SENT"
    }

    @Test
    fun `a printer moved to another agent gives the pending job to the new agent and the old ack is 404`() {
        val printer = newPrinter()
        val sent = enqueue(printer)
        poll(printer.agent)
        val pending = enqueue(printer)
        val newAgent = newAgent()
        jdbc
            .sql("UPDATE printing.printer SET agent_id = :agent WHERE id = :id")
            .param("agent", newAgent.id)
            .param("id", printer.id)
            .update()

        idsOf(poll(printer.agent)) shouldBe emptyList()
        idsOf(poll(newAgent)) shouldContainExactly listOf(pending.toString())

        ack(printer.agent, sent, "PRINTED").status shouldBe 404
        send(GET, "$base/$sent/content", printer.agent.token).status shouldBe 404
        send(GET, "$base/$sent/content", newAgent.token).status shouldBe 404
        statusOf(sent) shouldBe "SENT"
    }

    @Test
    fun `the content of an own SENT job comes with the media type and equal to what was sent`() {
        val pdfPrinter = newPrinter(kind = "A4_PDF")
        val rawPrinter = newPrinter(kind = "THERMAL_ESCPOS")
        val pdfBytes = pdf()
        val rawBytes = byteArrayOf(0x1b, 0x40, 0x00, 0x7f, -1)
        val pdfJob = enqueue(pdfPrinter, pdfBytes)
        val rawJob = enqueue(rawPrinter, rawBytes)
        poll(pdfPrinter.agent)
        poll(rawPrinter.agent)

        val pdfResponse = send(GET, "$base/$pdfJob/content", pdfPrinter.agent.token)
        val rawResponse = send(GET, "$base/$rawJob/content", rawPrinter.agent.token)

        pdfResponse.status shouldBe 200
        pdfResponse.contentType shouldBe "application/pdf"
        pdfResponse.contentAsByteArray.contentEquals(pdfBytes) shouldBe true
        pdfResponse.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        rawResponse.contentType shouldBe "application/octet-stream"
        rawResponse.contentAsByteArray.contentEquals(rawBytes) shouldBe true
    }

    @Test
    fun `the content of a pending or printed job is 404`() {
        val printer = newPrinter()
        val pending = enqueue(printer)
        val printed = enqueue(printer)
        poll(printer.agent) // reivindica os dois
        ack(printer.agent, printed, "PRINTED").status shouldBe 204
        val stillPending = enqueue(printer)

        send(GET, "$base/$stillPending/content", printer.agent.token).status shouldBe 404
        send(GET, "$base/$printed/content", printer.agent.token).status shouldBe 404
        send(GET, "$base/$pending/content", printer.agent.token).status shouldBe 200
        send(GET, "$base/${UUID.randomUUID()}/content", printer.agent.token).status shouldBe 404
    }

    @Test
    fun `a wait outside 0 to 30 is 400`() {
        val agent = newAgent()

        listOf("-1", "31", "abc").forEach {
            send(GET, "$base?wait=$it", agent.token).status shouldBe 400
        }
    }

    @Test
    fun `a third long poll of the same agent makes the oldest answer an empty list at once`() {
        val agent = newAgent()
        val first = startPoll(agent, 3)
        val second = startPoll(agent, 3)
        connections.openCount(agent.id) shouldBe 2

        val third = startPoll(agent, 3)

        val evicted = finish(first)
        evicted.status shouldBe 200
        evicted.contentAsString shouldBe "[]"
        connections.openCount(agent.id) shouldBe 2
        finish(second).status shouldBe 200
        finish(third).status shouldBe 200
        connections.openCount(agent.id) shouldBe 0
    }

    @Test
    fun `a deactivated agent or one with another credential stamp is 401 in the service`() {
        val printer = newPrinter()
        val principal = principalOf(printer.agent)
        enqueue(printer)

        shouldThrow<UnauthenticatedException> { delivery.claim(principal.copy(credentialStamp = "outro"), 1) }
        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/rotate-token", iam.asAdmin()).status shouldBe 200
        shouldThrow<UnauthenticatedException> { delivery.claim(principal, 1) }
        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/deactivate", iam.asAdmin()).status shouldBe 200
        shouldThrow<UnauthenticatedException> { delivery.unacknowledged(principal, 0) }
    }

    @Test
    fun `the trail of the claim and of the ack is of the system and has no content nor token`() {
        val printer = newPrinter()
        val job = enqueue(printer, pdf("MARCADOR"))
        poll(printer.agent)
        ack(printer.agent, job, "PRINTED").status shouldBe 204

        val rows =
            jdbc
                .sql(
                    "SELECT action, user_id IS NULL AS system, CAST(before_state AS text) AS b, " +
                        "CAST(after_state AS text) AS a FROM audit.audit_entry " +
                        "WHERE action IN ('printing.job.claim', 'printing.job.ack') AND entity_id = :id " +
                        "ORDER BY occurred_at, id",
                ).param("id", job.toString())
                .query {
                    rs,
                    _,
                    ->
                    listOf(rs.getString("action"), rs.getBoolean("system"), "${rs.getString("b")}${rs.getString("a")}")
                }.list()

        rows.map { it[0] } shouldContainExactly listOf("printing.job.claim", "printing.job.ack")
        rows.forEach {
            it[1] shouldBe true
            (it[2] as String) shouldNotContain "MARCADOR"
            (it[2] as String) shouldNotContain printer.agent.token
            (it[2] as String) shouldNotContain "content"
        }
        (rows[0][2] as String).contains(printer.agent.id.toString()) shouldBe true
    }

    @Test
    fun `an empty poll leaves no trail entry`() {
        val agent = newAgent()
        val before =
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry WHERE action = 'printing.job.claim'",
                ).query(Long::class.java)
                .single()

        poll(agent, 0)

        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE action = 'printing.job.claim'",
            ).query(Long::class.java)
            .single() shouldBe
            before
    }

    @Test
    fun `without a token the delivery endpoints are 401`() {
        send(GET, base, null).status shouldBe 401
        send(GET, "$base/${UUID.randomUUID()}/content", null).status shouldBe 401
        send(POST, "$base/${UUID.randomUUID()}/ack", null, mapOf("result" to "PRINTED")).status shouldBe 401
    }
}
