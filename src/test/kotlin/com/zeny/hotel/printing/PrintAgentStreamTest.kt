package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FILES_PURGE_DEFERRED
import com.zeny.hotel.testsupport.FNRH_SEND_DEFERRED
import com.zeny.hotel.testsupport.GUESTS_REKEY_DEFERRED
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PROVISIONAL_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.SseFrame
import com.zeny.hotel.testsupport.SseTestClient
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * O stream SSE do agente de impressão (`GET /api/v1/print-agents/me/jobs/stream`, M5 E10, Task 11), com um cliente
 * SSE de verdade num servidor de porta aleatória: ordem e `id` dos eventos, `Last-Event-ID`, impressora transferida,
 * agente revogado ou com token trocado, *heartbeat* e limite de conexões. Os intervalos são encurtados por propriedade
 * e a espera é sempre a leitura do evento com prazo.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        TEST_MIGRATIONS,
        RESUBMISSION_JOB_DEFERRED,
        OUTBOX_PURGE_DEFERRED,
        FILES_PURGE_DEFERRED,
        GUESTS_REKEY_DEFERRED,
        INVENTORY_HORIZON_DEFERRED,
        PROVISIONAL_EXPIRY_DEFERRED,
        FNRH_SEND_DEFERRED,
        "zeny.printing.delivery-poll-interval=PT0.1S",
        "zeny.printing.stream-heartbeat=PT0.3S",
        "zeny.printing.max-connections-per-agent=2",
    ],
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList")
class PrintAgentStreamTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val delivery: PrintAgentJobsService,
    @Autowired private val connections: PrintAgentConnections,
    @Autowired private val realm: PrintAgentRealm,
    @LocalServerPort private val port: Int,
) {
    private val baseUrl get() = "http://localhost:$port"
    private val path = "/api/v1/print-agents/me/jobs/stream"
    private val clients = CopyOnWriteArrayList<SseTestClient>()

    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Printer(
        val id: UUID,
        val agent: Agent,
    )

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
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

    private fun positionOf(job: UUID): Long =
        jdbc
            .sql("SELECT position FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query(Long::class.java)
            .single()

    private fun open(
        token: String?,
        lastEventId: Long? = null,
    ): SseTestClient = SseTestClient.openPath(baseUrl, path, token, lastEventId?.toString()).also { clients += it }

    private fun json(frame: SseFrame): JsonNode = jsonMapper.readTree(frame.data.shouldNotBeNull())

    /** Os ids dos próximos [count] eventos `job` (com prazo cada um). */
    private fun jobIds(
        client: SseTestClient,
        count: Int,
    ): List<String> =
        (1..count).map { json(client.nextEvent(Duration.ofSeconds(5)).shouldNotBeNull())["id"].asString() }

    @Test
    fun `connecting delivers the pending jobs of the agent in position order with the position as event id`() {
        val printer = newPrinter()
        val first = enqueue(printer)
        val second = enqueue(printer)

        val client = open(printer.agent.token)
        client.status shouldBe 200
        val a = client.nextEvent().shouldNotBeNull()
        val b = client.nextEvent().shouldNotBeNull()

        a.event shouldBe "job"
        a.id shouldBe positionOf(first).toString()
        b.id shouldBe positionOf(second).toString()
        json(a)["id"].asString() shouldBe first.toString()
        json(b)["id"].asString() shouldBe second.toString()
    }

    @Test
    fun `the event carries only the delivery fields and never the content`() {
        val printer = newPrinter()
        val job = enqueue(printer)

        val frame = open(printer.agent.token).nextEvent().shouldNotBeNull()

        val data = json(frame)
        data.propertyNames().toList() shouldContainExactly
            listOf("id", "printerId", "documentType", "mediaType", "attempts", "createdAt")
        data["printerId"].asString() shouldBe printer.id.toString()
        data["documentType"].asString() shouldBe "frontdesk.registration-card"
        data["mediaType"].asString() shouldBe "application/pdf"
        data["attempts"].asInt() shouldBe 1
        frame.data.shouldNotBeNull() shouldNotContain "SEGREDO"
        jdbc
            .sql("SELECT status FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query(String::class.java)
            .single() shouldBe "SENT"
    }

    @Test
    fun `the response is an event stream that proxies do not buffer and caches do not keep`() {
        val client = open(newAgent().token)

        client.status shouldBe 200
        client.contentType.shouldNotBeNull() shouldContain "text/event-stream"
        client.headers["x-accel-buffering"] shouldBe listOf("no")
        client.headers["cache-control"].shouldNotBeNull().joinToString() shouldContain "no-store"
    }

    @Test
    fun `a job created after connecting arrives through the stream`() {
        val printer = newPrinter()
        val client = open(printer.agent.token)
        client.eventsWithin(Duration.ofMillis(300)).shouldBeEmpty()

        val job = enqueue(printer)

        json(client.nextEvent().shouldNotBeNull())["id"].asString() shouldBe job.toString()
    }

    @Test
    fun `another agent does not receive the job of this one`() {
        val mine = newPrinter()
        val other = newAgent()
        val stranger = open(other.token)
        val own = open(mine.agent.token)

        val job = enqueue(mine)

        json(own.nextEvent().shouldNotBeNull())["id"].asString() shouldBe job.toString()
        stranger.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
    }

    @Test
    fun `reconnecting with Last-Event-ID resends the unacknowledged SENT after it and still delivers older pending`() {
        val printer = newPrinter()
        val principal = realm.authenticate(printer.agent.token)!!
        val j1 = enqueue(printer)
        val j2 = enqueue(printer)
        val j3 = enqueue(printer)
        delivery.claim(principal, 10).map { it.id } shouldContainExactly listOf(j1, j2, j3)
        // j1 volta a PENDING com posição menor que o Last-Event-ID: o critério não filtra PENDING por posição.
        jdbc
            .sql("UPDATE printing.print_job SET status = 'PENDING', delivered_to = NULL WHERE id = :id")
            .param("id", j1)
            .update()

        val client = open(printer.agent.token, lastEventId = positionOf(j2))

        jobIds(client, 2) shouldContainExactly listOf(j3.toString(), j1.toString())
        client.eventsWithin(Duration.ofMillis(400)).shouldBeEmpty()
    }

    @Test
    fun `without Last-Event-ID the unacknowledged SENT are all resent and the acknowledged ones are not`() {
        val printer = newPrinter()
        val principal = realm.authenticate(printer.agent.token)!!
        val done = enqueue(printer)
        val unacked = enqueue(printer)
        delivery.claim(principal, 10)
        delivery.acknowledge(principal, done, printed = true, failureCode = null)

        val client = open(printer.agent.token)

        jobIds(client, 1) shouldContainExactly listOf(unacked.toString())
        client.eventsWithin(Duration.ofMillis(400)).shouldBeEmpty()
    }

    @Test
    fun `a printer moved to another agent delivers its pending jobs to the new one even before its Last-Event-ID`() {
        val printer = newPrinter()
        val pending = enqueue(printer)
        val newAgent = newAgent()
        jdbc
            .sql("UPDATE printing.printer SET agent_id = :agent WHERE id = :id")
            .param("agent", newAgent.id)
            .param("id", printer.id)
            .update()
        val oldStream = open(printer.agent.token)

        val client = open(newAgent.token, lastEventId = positionOf(pending) + 1_000)

        jobIds(client, 1) shouldContainExactly listOf(pending.toString())
        oldStream.eventsWithin(Duration.ofMillis(400)).shouldBeEmpty()
    }

    @Test
    fun `a deactivated agent with the stream open gets nothing more and the stream ends`() {
        val printer = newPrinter()
        val client = open(printer.agent.token)
        client.status shouldBe 200
        client.eventsWithin(Duration.ofMillis(200)).shouldBeEmpty()

        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/deactivate", iam.asAdmin()).status shouldBe 200
        enqueue(printer)

        client.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        client.eventsWithin(Duration.ofMillis(200)).shouldBeEmpty()
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 0 }
    }

    @Test
    fun `an agent whose token was rotated with the stream open gets nothing more and the stream ends`() {
        val printer = newPrinter()
        val client = open(printer.agent.token)
        client.status shouldBe 200
        client.eventsWithin(Duration.ofMillis(200)).shouldBeEmpty()

        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/rotate-token", iam.asAdmin()).status shouldBe 200
        enqueue(printer)

        client.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        client.eventsWithin(Duration.ofMillis(200)).shouldBeEmpty()
    }

    @Test
    fun `an idle stream sends heartbeat comments`() {
        val client = open(newAgent().token)

        var heartbeat: SseFrame? = null
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (heartbeat == null && System.nanoTime() < deadline) {
            heartbeat = client.next(Duration.ofSeconds(1))?.takeIf { it.isComment }
        }

        heartbeat.shouldNotBeNull().comment shouldBe "heartbeat"
    }

    @Test
    fun `a third stream of the same agent ends the oldest one and keeps the other two`() {
        val agent = newAgent()
        val oldest = open(agent.token)
        val middle = open(agent.token)
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 2 }

        val newest = open(agent.token)

        oldest.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        middle.isEnded shouldBe false
        newest.isEnded shouldBe false
        connections.openCount(agent.id) shouldBe 2
    }

    @Test
    fun `closing the client releases the connection of the agent`() {
        val agent = newAgent()
        val client = open(agent.token)
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 1 }

        client.close()

        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 0 }
    }

    @Test
    fun `the stream needs the token of an active agent`() {
        open(null).status shouldBe 401
        open("zpa_invalido").status shouldBe 401
        val agent = newAgent()
        iam.call(POST, "/api/v1/print-agents/${agent.id}/deactivate", iam.asAdmin()).status shouldBe 200
        open(agent.token).status shouldBe 401
    }
}
