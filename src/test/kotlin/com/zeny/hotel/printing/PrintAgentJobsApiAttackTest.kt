package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques do testador à entrega dos jobs ao agente (M5 E10, Task 10; D-E10-3 a D-E10-6, Focus 2 e 3): posse
 * por agente sem vazar a existência, ack único mesmo concorrente, validação do corpo, limite de conexões, trilha de
 * sistema sem conteúdo nem token e nada de conteúdo em log. Dados próprios em cada teste (banco compartilhado) e
 * nenhuma espera fixa para ordenar passos.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // um teste por ataque
class PrintAgentJobsApiAttackTest(
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

    private fun column(
        column: String,
        id: UUID,
    ): String? =
        jdbc
            .sql("SELECT CAST($column AS text) FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun statusOf(id: UUID): String = column("status", id)!!

    private fun principalOf(agent: Agent): ServicePrincipal = realm.authenticate(agent.token)!!

    private fun send(
        method: HttpMethod,
        path: String,
        token: String?,
        rawBody: String? = null,
        type: MediaType = MediaType.APPLICATION_JSON,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                if (rawBody != null) {
                    contentType = type
                    content = rawBody
                }
            }.andReturn()
            .response

    private fun startPoll(
        agent: Agent,
        wait: Int,
    ): MvcResult =
        mockMvc
            .request(GET, "$base?wait=$wait") { header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}") }
            .andReturn()

    private fun finish(result: MvcResult): MockHttpServletResponse {
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    private fun poll(
        agent: Agent,
        wait: Int = 0,
    ): MockHttpServletResponse = finish(startPoll(agent, wait))

    private fun json(response: MockHttpServletResponse): JsonNode = jsonMapper.readTree(response.contentAsString)

    private fun idsOf(response: MockHttpServletResponse): List<UUID> =
        json(response).values().map { UUID.fromString(it["id"].asString()) }

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
            jsonMapper.writeValueAsString(
                listOfNotNull("result" to result, failureCode?.let { "failureCode" to it }).toMap(),
            ),
        )

    private fun content(
        agent: Agent,
        job: UUID,
    ) = send(GET, "$base/$job/content", agent.token)

    private fun problem(response: MockHttpServletResponse): Pair<String?, String?> =
        json(response).let { it["code"]?.asString() to it["detail"]?.asString() }

    private fun <T> race(
        threads: Int,
        task: (Int) -> T,
    ): List<T> {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (0 until threads).map { n ->
                    pool.submit<T> {
                        gate.await()
                        task(n)
                    }
                }
            gate.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun trail(
        job: UUID,
        action: String,
    ): List<String> =
        jdbc
            .sql(
                "SELECT user_id IS NULL AS system, CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                    "FROM audit.audit_entry WHERE action = :action AND entity_id = :id ORDER BY occurred_at, id",
            ).param("action", action)
            .param("id", job.toString())
            .query { rs, _ -> "${rs.getBoolean("system")}|${rs.getString("b")}|${rs.getString("a")}" }
            .list()

    private fun movePrinter(
        printer: Printer,
        to: Agent,
    ) {
        jdbc
            .sql("UPDATE printing.printer SET agent_id = :agent WHERE id = :id")
            .param("agent", to.id)
            .param("id", printer.id)
            .update() shouldBe 1
    }

    // ---- posse -------------------------------------------------------------------------------------------------

    @Test
    fun `another agent never gets a job of mine, whatever its state, and the answer is the same as for a missing id`() {
        val mine = newPrinter()
        val other = newAgent()
        val pending = enqueue(mine)
        val sent = enqueue(mine)
        val printed = enqueue(mine)
        val failed = enqueue(mine)
        delivery.claim(principalOf(mine.agent), 20) // os 4 viram SENT
        ack(mine.agent, printed, "PRINTED").status shouldBe 204
        ack(mine.agent, failed, "FAILED", "PAPER_JAM").status shouldBe 204
        val stillPending = enqueue(mine)
        val absent = UUID.randomUUID()

        listOf(pending, sent, printed, failed, stillPending).forEach { id ->
            withClue("job $id") {
                val read = content(other, id)
                val confirm = ack(other, id, "PRINTED")
                read.status shouldBe 404
                confirm.status shouldBe 404
                problem(read) shouldBe problem(content(other, absent))
                problem(confirm) shouldBe problem(ack(other, absent, "PRINTED"))
            }
        }
        idsOf(poll(other)).shouldBeEmpty()
        statusOf(sent) shouldBe "SENT"
        statusOf(printed) shouldBe "PRINTED"
        statusOf(failed) shouldBe "FAILED"
        statusOf(stillPending) shouldBe "PENDING"
        trail(sent, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `an agent that does not own a delivered job cannot make it 409 either, only 404`() {
        val mine = newPrinter()
        val other = newAgent()
        val job = enqueue(mine)
        delivery.claim(principalOf(mine.agent), 1)
        ack(mine.agent, job, "PRINTED").status shouldBe 204

        ack(other, job, "PRINTED").status shouldBe 404
        ack(other, job, "FAILED", "X").status shouldBe 404
        ack(mine.agent, job, "PRINTED").status shouldBe 409
    }

    @Test
    fun `a printer moved after the claim leaves the sent job unreadable and unconfirmable for both agents`() {
        val printer = newPrinter()
        val newOwner = newAgent()
        val sent = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        val pending = enqueue(printer)
        movePrinter(printer, newOwner)

        listOf(printer.agent, newOwner).forEach { agent ->
            content(agent, sent).status shouldBe 404
            delivery.unacknowledged(principalOf(agent), 0).map { it.id }.shouldBeEmpty()
        }
        ack(printer.agent, sent, "PRINTED").status shouldBe 404 // a impressora já não é dele
        ack(newOwner, sent, "PRINTED").status shouldBe 409 // a impressora é dele, o job foi entregue ao outro
        idsOf(poll(printer.agent)).shouldBeEmpty()
        idsOf(poll(newOwner)) shouldContainExactly listOf(pending)
        statusOf(sent) shouldBe "SENT"
        trail(sent, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `a printer deactivated between the claim and the ack still lets its agent read and confirm the sent job`() {
        val printer = newPrinter()
        val sent = enqueue(printer)
        val pending = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        iam.call(POST, "/api/v1/printers/${printer.id}/deactivate", iam.asAdmin()).status shouldBe 200

        content(printer.agent, sent).status shouldBe 200
        idsOf(poll(printer.agent)).shouldBeEmpty() // o PENDING de impressora inativa não é entregue
        ack(printer.agent, sent, "PRINTED").status shouldBe 204
        statusOf(sent) shouldBe "PRINTED"
        content(printer.agent, pending).status shouldBe 404
    }

    @Test
    fun `unacknowledged returns only own sent jobs of own printers after the position, in order`() {
        val printer = newPrinter()
        val other = newPrinter()
        val first = enqueue(printer)
        val second = enqueue(printer)
        val acked = enqueue(printer)
        val foreign = enqueue(other)
        val principal = principalOf(printer.agent)
        delivery.claim(principal, 3)
        delivery.claim(principalOf(other.agent), 1)
        val stillPending = enqueue(printer)
        ack(printer.agent, acked, "PRINTED").status shouldBe 204

        val all = delivery.unacknowledged(principal, 0)
        all.map { it.id } shouldContainExactly listOf(first, second)
        delivery.unacknowledged(principal, all[0].position).map { it.id } shouldContainExactly listOf(second)
        delivery.unacknowledged(principal, all[1].position).shouldBeEmpty()
        (all.map { it.id } + stillPending + foreign).toSet() shouldHaveSize 4
        delivery.unacknowledged(principalOf(other.agent), 0).map { it.id } shouldContainExactly listOf(foreign)
    }

    // ---- credencial entre chamadas -----------------------------------------------------------------------------

    @Test
    fun `a deactivated agent is 401 on every delivery route and its sent job stays sent`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/deactivate", iam.asAdmin()).status shouldBe 200

        send(GET, "$base?wait=0", printer.agent.token).status shouldBe 401
        content(printer.agent, job).status shouldBe 401
        ack(printer.agent, job, "PRINTED").status shouldBe 401
        statusOf(job) shouldBe "SENT"
        trail(job, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `a rotated token makes the old one 401 on every route and the new one works on the same job`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        val rotated =
            iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/rotate-token", iam.asAdmin())
        val newToken = jsonMapper.readTree(rotated.contentAsString)["token"].asString()
        val renewed = Agent(printer.agent.id, newToken)

        send(GET, "$base?wait=0", printer.agent.token).status shouldBe 401
        content(printer.agent, job).status shouldBe 401
        ack(printer.agent, job, "PRINTED").status shouldBe 401
        statusOf(job) shouldBe "SENT"
        content(renewed, job).status shouldBe 200
        ack(renewed, job, "PRINTED").status shouldBe 204
    }

    @Test
    fun `an agent deactivated during a long poll ends it with 401 and releases the connection`() {
        val printer = newPrinter()
        val pending = startPoll(printer.agent, 20)
        pending.request.isAsyncStarted shouldBe true
        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/deactivate", iam.asAdmin()).status shouldBe 200

        val response = finish(pending)

        response.status shouldBe 401
        connections.openCount(printer.agent.id) shouldBe 0
    }

    @Test
    fun `a user jwt on the delivery routes is 401`() {
        val user = iam.createUser()
        val jwt = iam.loginOk(user)["accessToken"].asString()
        val job = UUID.randomUUID()

        send(GET, "$base?wait=0", jwt).status shouldBe 401
        content(Agent(UUID.randomUUID(), jwt), job).status shouldBe 401
        ack(Agent(UUID.randomUUID(), jwt), job, "PRINTED").status shouldBe 401
    }

    // ---- ack: repetido, concorrente, estados ---------------------------------------------------------------------

    @Test
    fun `a double ack is 409, keeps the first result and writes a single trail entry`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)

        ack(printer.agent, job, "PRINTED").status shouldBe 204
        ack(printer.agent, job, "PRINTED").status shouldBe 409
        ack(printer.agent, job, "FAILED", "PAPER_JAM").status shouldBe 409

        statusOf(job) shouldBe "PRINTED"
        column("failure_code", job) shouldBe null
        trail(job, "printing.job.ack") shouldHaveSize 1
    }

    @Test
    fun `two concurrent acks of the same job have exactly one winner and one trail entry`() {
        val printer = newPrinter()
        repeat(6) { round ->
            val job = enqueue(printer)
            delivery.claim(principalOf(printer.agent), 1)

            val statuses =
                race(2) { n ->
                    if (n ==
                        0
                    ) {
                        ack(printer.agent, job, "PRINTED").status
                    } else {
                        ack(printer.agent, job, "FAILED", "JAM").status
                    }
                }

            withClue("round $round") {
                statuses.sorted() shouldContainExactly listOf(204, 409)
                val winner = if (statuses[0] == 204) "PRINTED" else "FAILED"
                statusOf(job) shouldBe winner
                column("failure_code", job) shouldBe if (winner == "FAILED") "JAM" else null
                trail(job, "printing.job.ack") shouldHaveSize 1
            }
        }
    }

    @Test
    fun `many concurrent acks of the same job still give one 204`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)

        val statuses = race(8) { ack(printer.agent, job, "PRINTED").status }

        statuses.count { it == 204 } shouldBe 1
        statuses.count { it == 409 } shouldBe 7
        trail(job, "printing.job.ack") shouldHaveSize 1
    }

    @Test
    fun `acknowledging a pending job is refused and leaves it pending with no trail`() {
        val printer = newPrinter()
        val pending = enqueue(printer)

        ack(printer.agent, pending, "PRINTED").status shouldBe 409
        ack(printer.agent, pending, "FAILED", "X").status shouldBe 409

        statusOf(pending) shouldBe "PENDING"
        trail(pending, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `acknowledging a job already failed by the system is 409 and does not change it`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        jdbc
            .sql(
                "UPDATE printing.print_job SET status = 'FAILED', failure_code = 'EXPIRED', " +
                    "finished_at = now() WHERE id = :id",
            ).param("id", job)
            .update() shouldBe 1

        ack(printer.agent, job, "PRINTED").status shouldBe 409

        statusOf(job) shouldBe "FAILED"
        column("failure_code", job) shouldBe "EXPIRED"
    }

    @Test
    fun `a printed ack drops a failure code and a failed ack keeps the exact code`() {
        val printer = newPrinter()
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        val longest = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 3)
        val code50 = "A" + "B".repeat(49)

        ack(printer.agent, printed, "PRINTED", "PAPER_JAM").status shouldBe 204
        ack(printer.agent, failed, "FAILED").status shouldBe 204
        ack(printer.agent, longest, "FAILED", code50).status shouldBe 204

        column("failure_code", printed) shouldBe null
        column("failure_code", failed) shouldBe null
        column("failure_code", longest) shouldBe code50
    }

    // ---- ack: entradas inválidas ---------------------------------------------------------------------------------

    @Test
    fun `an invalid failure code is 400 INVALID_FAILURE_CODE and the job stays sent and unlogged`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        val invalid =
            listOf(
                "paper_jam",
                "A".repeat(51),
                "1ABC",
                "_ABC",
                "A B",
                "A-B",
                "",
                " ",
                "PAPER\nJAM",
                "PAPER_JAM\n",
                "ÁBC",
                "A\u0000B",
                "'; DROP TABLE printing.print_job;--",
                "<script>",
                "X".repeat(10_000),
            )

        invalid.forEach { code ->
            withClue("failureCode ${code.take(30)}") {
                val response = ack(printer.agent, job, "FAILED", code)
                response.status shouldBe 400
                problem(response).first shouldBe "INVALID_FAILURE_CODE"
            }
        }
        statusOf(job) shouldBe "SENT"
        column("failure_code", job) shouldBe null
        trail(job, "printing.job.ack").shouldBeEmpty()
        ack(printer.agent, job, "FAILED", "A").status shouldBe 204
    }

    @Test
    fun `an invalid ack body is 400 or 415, never 500, and the job stays sent`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)
        val url = "$base/$job/ack"
        val bad400 =
            listOf(
                "",
                "{",
                "{}",
                "[]",
                "null",
                "\"PRINTED\"",
                "{\"result\":null}",
                "{\"result\":\"printed\"}",
                "{\"result\":\"MAYBE\"}",
                "{\"result\":\"\"}",
                "{\"result\":[\"PRINTED\"]}",
                "{\"result\":\"FAILED\",\"failureCode\":{\"a\":1}}",
                "{\"result\":\"FAILED\",\"failureCode\":[\"A\"]}",
            )

        bad400.forEach { body ->
            withClue("body $body") { send(POST, url, printer.agent.token, body).status shouldBe 400 }
        }
        send(POST, url, printer.agent.token, "{\"result\":\"PRINTED\"}", MediaType.TEXT_PLAIN).status shouldBe 415
        send(POST, url, printer.agent.token).status shouldBe 400 // sem corpo nem tipo
        statusOf(job) shouldBe "SENT"
        trail(job, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `a numeric result is 400 and never read as the enum ordinal`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        delivery.claim(principalOf(printer.agent), 1)

        listOf("0", "1", "0.0", "1.0").forEach { number ->
            withClue("result $number") {
                send(POST, "$base/$job/ack", printer.agent.token, "{\"result\":$number}").status shouldBe 400
            }
        }
        statusOf(job) shouldBe "SENT"
        trail(job, "printing.job.ack").shouldBeEmpty()
    }

    @Test
    fun `a job id that is not a uuid is never 200 nor 500`() {
        val agent = newAgent()

        listOf("deadbeef", "not-a-uuid", "0", "-", "..%2F..", "%00").forEach { id ->
            withClue("id $id") {
                (send(GET, "$base/$id/content", agent.token).status in 400..404) shouldBe true
                (send(POST, "$base/$id/ack", agent.token, "{\"result\":\"PRINTED\"}").status in 400..404) shouldBe true
            }
        }
    }

    @Test
    fun `wrong methods on the delivery routes are client errors`() {
        val agent = newAgent()
        val job = UUID.randomUUID()

        listOf(
            POST to base,
            HttpMethod.DELETE to base,
            POST to "$base/$job/content",
            GET to "$base/$job/ack",
            HttpMethod.PUT to "$base/$job/ack",
        ).forEach { (method, path) ->
            withClue("$method $path") { (send(method, path, agent.token).status in 400..499) shouldBe true }
        }
    }

    // ---- long-poll -------------------------------------------------------------------------------------------------

    @Test
    fun `the wait is accepted from 0 to 30 and refused outside, empty and not numeric`() {
        val printer = newPrinter()
        enqueue(printer)
        enqueue(printer)

        // Há job pendente: wait=30 responde na hora com o job, sem esperar os 30 segundos.
        val started = System.nanoTime()
        val thirty = poll(printer.agent, 30)
        (System.nanoTime() - started) shouldBeLessThan TimeUnit.SECONDS.toNanos(10)
        thirty.status shouldBe 200
        idsOf(thirty) shouldHaveSize 2
        poll(printer.agent, 0).status shouldBe 200
        listOf("-1", "31", "abc", "1.5", "2147483648", "-2147483649", "1e1", "%20", "9".repeat(30)).forEach {
            withClue("wait=$it") { send(GET, "$base?wait=$it", printer.agent.token).status shouldBe 400 }
        }
        send(GET, "$base?wait=0&wait=99", printer.agent.token).status.let { (it == 200 || it == 400) shouldBe true }
        send(GET, "$base?wait=", printer.agent.token).status shouldBe 200
    }

    @Test
    fun `a refused wait opens no connection`() {
        val agent = newAgent()

        repeat(5) { send(GET, "$base?wait=31", agent.token).status shouldBe 400 }
        send(GET, base, null).status shouldBe 401

        connections.openCount(agent.id) shouldBe 0
    }

    @Test
    fun `a poll after the claim returns nothing and does not claim again nor bump the attempts`() {
        val printer = newPrinter()
        val job = enqueue(printer)

        idsOf(poll(printer.agent)) shouldContainExactly listOf(job)
        idsOf(poll(printer.agent)).shouldBeEmpty()
        idsOf(poll(printer.agent)).shouldBeEmpty()

        column("attempts", job) shouldBe "1"
        statusOf(job) shouldBe "SENT"
        trail(job, "printing.job.claim") shouldHaveSize 1
    }

    @Test
    fun `a claim takes the oldest jobs first within the limit and a non positive limit is refused`() {
        val printer = newPrinter()
        val ids = (1..4).map { enqueue(printer) }
        val principal = principalOf(printer.agent)

        delivery.claim(principal, 2).map { it.id } shouldContainExactly ids.take(2)
        delivery.claim(principal, 5).map { it.id } shouldContainExactly ids.drop(2)
        shouldThrow<IllegalArgumentException> { delivery.claim(principal, 0) }
        shouldThrow<IllegalArgumentException> { delivery.claim(principal, -1) }
    }

    @Test
    fun `concurrent claims of many threads give each job to exactly one caller`() {
        val printer = newPrinter()
        val created = (1..5).map { enqueue(printer) }
        val principal = principalOf(printer.agent)

        val claimed = race(16) { _ -> delivery.claim(principal, 1).map { job -> job.id } }.flatten()

        claimed shouldHaveSize created.size
        claimed shouldContainExactlyInAnyOrder created
        created.forEach {
            statusOf(it) shouldBe "SENT"
            column("attempts", it) shouldBe "1"
            trail(it, "printing.job.claim") shouldHaveSize 1
        }
    }

    @Test
    fun `concurrent claims of two agents never cross the jobs of their printers`() {
        val a = newPrinter()
        val b = newPrinter()
        val ofA = (1..4).map { enqueue(a) }
        val ofB = (1..4).map { enqueue(b) }
        val principals = listOf(principalOf(a.agent), principalOf(b.agent))

        val results = race(8) { n -> n % 2 to delivery.claim(principals[n % 2], 1).map { it.id } }

        results.filter { it.first == 0 }.flatMap { it.second } shouldContainExactlyInAnyOrder ofA
        results.filter { it.first == 1 }.flatMap { it.second } shouldContainExactlyInAnyOrder ofB
    }

    @Test
    fun `a long poll and concurrent claims of the same agent never deliver the same job twice`() {
        repeat(3) {
            val printer = newPrinter()
            val waiting = startPoll(printer.agent, 10)
            waiting.request.isAsyncStarted shouldBe true
            val principal = principalOf(printer.agent)
            val created = mutableListOf<UUID>()
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(5)
            try {
                val claimers =
                    (1..4).map {
                        pool.submit<List<UUID>> {
                            gate.await()
                            (1..4).flatMap { delivery.claim(principal, 1).map { job -> job.id } }
                        }
                    }
                gate.countDown()
                repeat(8) { created += enqueue(printer) }
                val delivered =
                    idsOf(finish(waiting)) + claimers.flatMap { claimer -> claimer.get(60, TimeUnit.SECONDS) }
                val leftovers =
                    generateSequence {
                        delivery
                            .claim(
                                principal,
                                20,
                            ).map { it.id }
                            .ifEmpty { null }
                    }.flatten()

                (delivered + leftovers.toList()) shouldContainExactlyInAnyOrder created
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `two simultaneous long polls of the same agent never deliver the same job twice`() {
        val printer = newPrinter()
        val created = (1..10).map { enqueue(printer) }

        val answers = race(2) { idsOf(poll(printer.agent, 1)) }

        val delivered = answers.flatten()
        delivered.toSet().size shouldBe delivered.size
        (delivered + delivery.claim(principalOf(printer.agent), 20).map { it.id }) shouldContainExactlyInAnyOrder
            created
    }

    // ---- limite de conexões ----------------------------------------------------------------------------------------

    @Test
    fun `a third long poll evicts the oldest at once and the evicted one claims nothing created later`() {
        val printer = newPrinter()
        val first = startPoll(printer.agent, 30)
        val second = startPoll(printer.agent, 5)
        connections.openCount(printer.agent.id) shouldBe 2

        val third = startPoll(printer.agent, 5)

        val started = System.nanoTime()
        val evicted = finish(first)
        (System.nanoTime() - started) shouldBeLessThan TimeUnit.SECONDS.toNanos(10)
        evicted.status shouldBe 200
        evicted.contentAsString shouldBe "[]"
        val job = enqueue(printer)
        val delivered = idsOf(finish(second)) + idsOf(finish(third))
        delivered shouldContainExactly listOf(job)
        connections.openCount(printer.agent.id) shouldBe 0
    }

    @Test
    fun `the limit is per agent and a poll of another agent does not evict`() {
        val a = newAgent()
        val b = newAgent()
        val first = startPoll(a, 2)
        val second = startPoll(a, 2)
        val other = startPoll(b, 2)

        connections.openCount(a.id) shouldBe 2
        connections.openCount(b.id) shouldBe 1
        listOf(first, second, other).forEach { finish(it).status shouldBe 200 }
        connections.openCount(a.id) shouldBe 0
        connections.openCount(b.id) shouldBe 0
    }

    @Test
    fun `many sequential polls leave no connection registered`() {
        val agent = newAgent()

        repeat(10) { poll(agent, 0).status shouldBe 200 }
        repeat(3) { send(GET, "$base?wait=99", agent.token).status shouldBe 400 }

        connections.openCount(agent.id) shouldBe 0
    }

    // ---- conteúdo -------------------------------------------------------------------------------------------------

    @Test
    fun `the content is byte for byte what was sent, for every byte value and a large pdf, and reading keeps it`() {
        val pdfPrinter = newPrinter(kind = "A4_PDF")
        val rawPrinter = newPrinter(kind = "THERMAL_ESCPOS")
        val everyByte = ByteArray(256) { it.toByte() }
        val bigPdf = "%PDF-1.7".toByteArray() + ByteArray(1_000_000) { (it * 31).toByte() }
        val looksLikePdf = "%PDF-1.4 mas é térmico".toByteArray()
        val rawJob = enqueue(rawPrinter, everyByte)
        val rawPdfLike = enqueue(rawPrinter, looksLikePdf)
        val bigJob = enqueue(pdfPrinter, bigPdf)
        delivery.claim(principalOf(rawPrinter.agent), 5)
        delivery.claim(principalOf(pdfPrinter.agent), 5)

        repeat(2) {
            val raw = content(rawPrinter.agent, rawJob)
            raw.contentType shouldBe "application/octet-stream"
            raw.contentAsByteArray.contentEquals(everyByte) shouldBe true
            content(rawPrinter.agent, rawPdfLike).contentAsByteArray.contentEquals(looksLikePdf) shouldBe true
            val big = content(pdfPrinter.agent, bigJob)
            big.contentType shouldBe "application/pdf"
            big.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            big.contentAsByteArray.contentEquals(bigPdf) shouldBe true
        }
        statusOf(rawJob) shouldBe "SENT"
        statusOf(bigJob) shouldBe "SENT"
    }

    @Test
    fun `the stored content is sealed and the delivered job list never carries it`() {
        val printer = newPrinter()
        val marker = "SEGREDO-LISTA-${suffix()}"
        val job = enqueue(printer, pdf(marker))

        val list = poll(printer.agent)

        list.contentAsString shouldNotContain marker
        list.contentAsString shouldNotContain Base64.getEncoder().encodeToString(pdf(marker))
        list.contentAsString shouldNotContain "content\""
        val stored =
            jdbc
                .sql("SELECT content FROM printing.print_job WHERE id = :id")
                .param("id", job)
                .query(ByteArray::class.java)
                .single()
        String(stored, Charsets.ISO_8859_1) shouldNotContain marker
    }

    // ---- trilha e log ----------------------------------------------------------------------------

    @Test
    fun `claim and ack entries are of the system, carry no content nor token and refused acks add none`() {
        val printer = newPrinter()
        val marker = "MARCADOR-${suffix()}"
        val job = enqueue(printer, pdf(marker))
        val other = newAgent()
        poll(printer.agent).status shouldBe 200
        ack(other, job, "PRINTED").status shouldBe 404
        ack(printer.agent, job, "FAILED", "invalido").status shouldBe 400
        ack(printer.agent, job, "FAILED", "PAPER_JAM").status shouldBe 204
        ack(printer.agent, job, "PRINTED").status shouldBe 409

        val entries = trail(job, "printing.job.claim") + trail(job, "printing.job.ack")

        entries shouldHaveSize 2
        entries.forEach {
            it.startsWith("true|") shouldBe true
            it shouldNotContain marker
            it shouldNotContain printer.agent.token
            it shouldNotContain other.token
            it shouldNotContain "\"content\""
            it shouldNotContain "token"
        }
        trail(job, "printing.job.claim").single().contains(printer.agent.id.toString()) shouldBe true
        trail(job, "printing.job.ack").single().contains("FAILED") shouldBe true
    }

    @Test
    fun `claim of several jobs gives each its own entry with the delivering agent`() {
        val printer = newPrinter()
        val ids = (1..3).map { enqueue(printer) }

        delivery.claim(principalOf(printer.agent), 3)

        ids.forEach {
            val entry = trail(it, "printing.job.claim").single()
            entry.startsWith("true|") shouldBe true
            entry shouldContain "SENT"
        }
    }

    @Test
    fun `no content nor token reaches the log across poll, content, ack and refusals`(output: CapturedOutput) {
        val printer = newPrinter()
        val marker = "LOGSEGREDO${suffix()}"
        val other = newAgent()
        val job = enqueue(printer, pdf(marker))
        val bytes = Base64.getEncoder().encodeToString(pdf(marker))

        poll(printer.agent).status shouldBe 200
        content(printer.agent, job).status shouldBe 200
        content(other, job).status shouldBe 404
        ack(printer.agent, job, "FAILED", "texto livre").status shouldBe 400
        ack(printer.agent, job, "PRINTED").status shouldBe 204
        ack(printer.agent, job, "PRINTED").status shouldBe 409
        send(GET, "$base?wait=0", "${printer.agent.token}x").status shouldBe 401

        val log = output.all
        log shouldNotContain marker
        log shouldNotContain bytes
        log shouldNotContain printer.agent.token
        log shouldNotContain other.token
        log shouldNotContain "%PDF-1.7 SEGREDO"
    }
}
