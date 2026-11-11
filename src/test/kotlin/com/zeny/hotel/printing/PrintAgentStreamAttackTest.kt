package com.zeny.hotel.printing

import com.sun.management.HotSpotDiagnosticMXBean
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.application.PrintJobsService
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
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
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
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val STREAM_PATH = "/api/v1/print-agents/me/jobs/stream"
private const val JOBS_PATH = "/api/v1/print-agents/me/jobs"

/** O que os ataques ao stream do agente compartilham: agentes, impressoras, jobs e clientes SSE com limpeza. */
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class PrintAgentStreamAttackSupport {
    @Autowired protected lateinit var iam: IamTestSupport

    @Autowired protected lateinit var jdbc: JdbcClient

    @Autowired protected lateinit var jsonMapper: JsonMapper

    @Autowired protected lateinit var jobs: PrintJobsService

    @Autowired protected lateinit var connections: PrintAgentConnections

    @LocalServerPort protected var port: Int = 0

    protected val baseUrl get() = "http://localhost:$port"
    private val clients = CopyOnWriteArrayList<SseTestClient>()
    protected val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    protected class Agent(
        val id: UUID,
        val token: String,
    )

    protected class Printer(
        val id: UUID,
        val agent: Agent,
    )

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
    }

    protected fun suffix() = UuidV7.generate().toString().takeLast(12)

    protected fun newAgent(): Agent {
        val json = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente ${suffix()}"))
        val body = jsonMapper.readTree(json.contentAsString)
        return Agent(UUID.fromString(body["id"].asString()), body["token"].asString())
    }

    protected fun newPrinter(agent: Agent = newAgent()): Printer {
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

    protected fun enqueue(printer: Printer): UUID =
        jobs.enqueue(
            NewPrintJob(printer.id, "frontdesk.registration-card", "%PDF-1.7 SEGREDO-${suffix()}".toByteArray()),
        )

    protected fun positionOf(job: UUID): Long =
        jdbc
            .sql("SELECT position FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query(Long::class.java)
            .single()

    protected fun statusOf(job: UUID): String =
        jdbc
            .sql("SELECT status FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query(String::class.java)
            .single()

    protected fun open(
        token: String?,
        lastEventId: String? = null,
    ): SseTestClient = SseTestClient.openPath(baseUrl, STREAM_PATH, token, lastEventId).also { clients += it }

    protected fun json(frame: SseFrame): JsonNode = jsonMapper.readTree(frame.data.shouldNotBeNull())

    protected fun jobIds(
        client: SseTestClient,
        count: Int,
    ): List<String> =
        (1..count).map { json(client.nextEvent(Duration.ofSeconds(5)).shouldNotBeNull())["id"].asString() }

    protected fun ack(
        agent: Agent,
        job: UUID,
        body: String,
    ): Int =
        http
            .send(
                HttpRequest
                    .newBuilder(URI.create("$baseUrl$JOBS_PATH/$job/ack"))
                    .header("Authorization", "Bearer ${agent.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    /** O número de virtual threads do laço do stream vivas agora, pelo despejo de threads da JVM. */
    protected fun loopThreads(): Int {
        val dir = Files.createTempDirectory("thread-dump")
        val file = dir.resolve("dump.txt")
        try {
            ManagementFactory
                .getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
            return Files.readString(file).lines().count { it.contains("print-agent-stream") }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }
}

/** Ataques ao stream SSE do agente (M5 E10, Task 11): `Last-Event-ID`, revogação, concorrência e vazamento. */
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
        "zeny.printing.max-connections-per-agent=3",
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
@Suppress("TooManyFunctions")
class PrintAgentStreamAttackTest : PrintAgentStreamAttackSupport() {
    /** Um agente com [sent] jobs entregues (SENT, sem ack) e um PENDING; devolve os ids na ordem de posição. */
    private fun printerWithSentAndPending(sent: Int): Pair<Printer, List<UUID>> {
        val printer = newPrinter()
        val ids = (1..sent).map { enqueue(printer) }
        open(printer.agent.token).also { first ->
            jobIds(first, sent)
            first.close()
        }
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 0 }
        return printer to (ids + enqueue(printer))
    }

    @Test
    fun `a negative Last-Event-ID resends every unacknowledged SENT and delivers the pending`() {
        val (printer, ids) = printerWithSentAndPending(2)

        val client = open(printer.agent.token, "-5")

        client.status shouldBe 200
        jobIds(client, 3) shouldContainExactly ids.map { it.toString() }
    }

    @Test
    fun `a Last-Event-ID beyond the last position resends no SENT but still delivers the pending`() {
        val (printer, ids) = printerWithSentAndPending(2)

        val client = open(printer.agent.token, Long.MAX_VALUE.toString())

        client.status shouldBe 200
        jobIds(client, 1) shouldContainExactly listOf(ids.last().toString())
        client.eventsWithin(Duration.ofMillis(400)).shouldBeEmpty()
    }

    @Test
    fun `an id that overflows a long, text or blank is not an error and nothing is delivered twice`() {
        listOf("99999999999999999999999999", "abc", "", " ", "1;DROP TABLE", "1e3", "0x10").forEach { value ->
            val (printer, ids) = printerWithSentAndPending(1)

            val client = open(printer.agent.token, value)

            withClue(value) {
                client.status shouldBe 200
                jobIds(client, 2) shouldContainExactly ids.map { it.toString() }
                client.eventsWithin(Duration.ofMillis(300)).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a repeated Last-Event-ID header is not a server error and delivers each job once`() {
        val (printer, ids) = printerWithSentAndPending(1)
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$STREAM_PATH"))
                .header("Authorization", "Bearer ${printer.agent.token}")
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "1")
                .header("Last-Event-ID", "2")
                .GET()
                .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val seen =
            CompletableFuture
                .supplyAsync {
                    response
                        .body()
                        .bufferedReader()
                        .lineSequence()
                        .filter { it.startsWith("data:") }
                        .take(2)
                        .toList()
                }.orTimeout(5, TimeUnit.SECONDS)
                .whenComplete { _, _ -> response.body().close() }
                .get()

        response.statusCode() shouldBe 200
        seen.size shouldBe 2
        seen.map { jsonMapper.readTree(it.removePrefix("data:").trim())["id"].asString() } shouldContainExactly
            ids.map { it.toString() }
    }

    @Test
    fun `a printer moved while the old stream is open sends its new jobs only to the new agent`() {
        val printer = newPrinter()
        val newAgent = newAgent()
        val oldStream = open(printer.agent.token)
        val newStream = open(newAgent.token)
        await().atMost(Duration.ofSeconds(5)).until {
            connections.openCount(printer.agent.id) == 1 && connections.openCount(newAgent.id) == 1
        }

        jdbc
            .sql("UPDATE printing.printer SET agent_id = :agent WHERE id = :id")
            .param("agent", newAgent.id)
            .param("id", printer.id)
            .update()
        val job = enqueue(printer)

        jobIds(newStream, 1) shouldContainExactly listOf(job.toString())
        oldStream.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
        oldStream.isEnded shouldBe false
    }

    @Test
    fun `a job SENT to the old agent is not resent to it nor acknowledged by it after the printer moved`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        jobIds(open(printer.agent.token), 1) shouldContainExactly listOf(job.toString())
        val newAgent = newAgent()
        jdbc
            .sql("UPDATE printing.printer SET agent_id = :agent WHERE id = :id")
            .param("agent", newAgent.id)
            .param("id", printer.id)
            .update()

        val reconnect = open(printer.agent.token, "0")

        reconnect.status shouldBe 200
        reconnect.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
        ack(printer.agent, job, "{\"result\":\"PRINTED\"}") shouldBe 404
        statusOf(job) shouldBe "SENT"
    }

    @Test
    fun `a deactivated agent never has a job claimed after the stream ended`() {
        val printer = newPrinter()
        val client = open(printer.agent.token)
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 1 }

        iam.call(POST, "/api/v1/print-agents/${printer.agent.id}/deactivate", iam.asAdmin()).status shouldBe 200
        client.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        val job = enqueue(printer)

        client.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
        statusOf(job) shouldBe "PENDING"
    }

    @Test
    fun `a rotated token ends the old stream, leaves the job pending and the new token receives it`() {
        val printer = newPrinter()
        val old = open(printer.agent.token)
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 1 }

        val rotated =
            jsonMapper
                .readTree(
                    iam
                        .call(
                            POST,
                            "/api/v1/print-agents/${printer.agent.id}/rotate-token",
                            iam.asAdmin(),
                        ).contentAsString,
                )["token"]
                .asString()
        old.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        val job = enqueue(printer)

        old.eventsWithin(Duration.ofMillis(300)).shouldBeEmpty()
        open(printer.agent.token).status shouldBe 401
        jobIds(open(rotated), 1) shouldContainExactly listOf(job.toString())
    }

    @Test
    fun `two streams and a long poll of the same agent never receive the same pending job`() {
        val printer = newPrinter()
        val first = open(printer.agent.token)
        val second = open(printer.agent.token)
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 2 }
        val polled = CopyOnWriteArrayList<String>()
        val stop = AtomicBoolean(false)
        val poller =
            CompletableFuture.runAsync {
                while (!stop.get()) {
                    val body =
                        http
                            .send(
                                HttpRequest
                                    .newBuilder(URI.create("$baseUrl$JOBS_PATH?wait=1"))
                                    .header("Authorization", "Bearer ${printer.agent.token}")
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(),
                            ).body()
                    jsonMapper.readTree(body).values().forEach { polled += it["id"].asString() }
                }
            }
        // Os jobs nascem com os três consumidores já ativos: só o PENDING é disputado (um SENT sem ack reenviado
        // a um stream que abre depois é a reconexão, não a disputa).
        val all = (1..15).map { enqueue(printer) }.map { it.toString() }
        val fromFirst = mutableListOf<SseFrame>()
        val fromSecond = mutableListOf<SseFrame>()
        await().atMost(Duration.ofSeconds(20)).until {
            fromFirst += first.eventsWithin(Duration.ofMillis(100))
            fromSecond += second.eventsWithin(Duration.ofMillis(100))
            fromFirst.size + fromSecond.size + polled.size >= all.size
        }
        fromFirst += first.eventsWithin(Duration.ofMillis(500))
        fromSecond += second.eventsWithin(Duration.ofMillis(500))
        stop.set(true)
        poller.get(10, TimeUnit.SECONDS)

        val received =
            fromFirst.map { json(it)["id"].asString() } + fromSecond.map { json(it)["id"].asString() } + polled
        received shouldContainExactlyInAnyOrder all
        (fromFirst + fromSecond).forEach { json(it)["attempts"].asInt() shouldBe 1 }
    }

    @Test
    fun `one more stream than the limit ends the oldest and the others share what comes next`() {
        val printer = newPrinter()
        val agent = printer.agent
        val oldest = open(agent.token)
        val others = (1..3).map { open(agent.token) }
        oldest.awaitEnd(Duration.ofSeconds(5)) shouldBe true
        others.forEach { it.isEnded shouldBe false }
        connections.openCount(agent.id) shouldBe 3

        val job = enqueue(printer)

        await().atMost(Duration.ofSeconds(5)).until { statusOf(job) == "SENT" }
        oldest.eventsWithin(Duration.ofMillis(200)).shouldBeEmpty()
        others
            .flatMap {
                it.eventsWithin(
                    Duration.ofMillis(400),
                )
            }.map { json(it)["id"].asString() } shouldContainExactly
            listOf(job.toString())
    }

    @Test
    fun `no field of any frame carries the content, the token or the printer address`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val address =
            jdbc
                .sql("SELECT address FROM printing.printer WHERE id = :id")
                .param("id", printer.id)
                .query(String::class.java)
                .single()
        val client = open(printer.agent.token)

        val frames = mutableListOf<SseFrame>()
        frames += client.nextEvent().shouldNotBeNull()
        client.eventsWithin(Duration.ofMillis(700)).let(frames::addAll)

        frames.size shouldBe 1
        val text = frames.joinToString { "${it.id}|${it.event}|${it.data}" }
        text shouldContain job.toString()
        listOf("SEGREDO", "%PDF", printer.agent.token, "zpa_", address, "content", "JVBER").forEach {
            text shouldNotContain it
        }
    }

    @Test
    fun `a user JWT and an anonymous caller get 401 and no connection is registered`() {
        val user = iam.createUser()
        val userJwt = iam.loginOk(user)["accessToken"].asString()
        val adminJwt = iam.loginOk(iam.createUser())["accessToken"].asString()

        listOf(userJwt, adminJwt, null).forEach { open(it).status shouldBe 401 }
    }

    @Test
    fun `an event received by the stream is acknowledged and then never resent`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val first = open(printer.agent.token)
        val frame = first.nextEvent().shouldNotBeNull()
        json(frame)["id"].asString() shouldBe job.toString()

        ack(printer.agent, job, "{\"result\":\"PRINTED\"}") shouldBe 204
        ack(printer.agent, job, "{\"result\":\"PRINTED\"}") shouldBe 409
        first.close()
        val again = open(printer.agent.token)

        statusOf(job) shouldBe "PRINTED"
        again.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
    }

    @Test
    fun `closing every stream leaves no loop thread and a later job is not claimed`() {
        val printer = newPrinter()
        val clients = (1..3).map { open(printer.agent.token) }
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 3 }
        loopThreads() shouldBeGreaterThan 0

        clients.forEach { it.close() }
        await().atMost(Duration.ofSeconds(10)).until { connections.openCount(printer.agent.id) == 0 }
        val after = enqueue(printer)

        await().atMost(Duration.ofSeconds(10)).until { loopThreads() == 0 }
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until { statusOf(after) == "PENDING" }
    }

    @Test
    fun `the stream is still an event stream with the header guards after a reconnection`() {
        val printer = newPrinter()
        val client = open(printer.agent.token, "0")

        client.status shouldBe 200
        client.contentType.shouldNotBeNull() shouldContain "text/event-stream"
        client.headers["x-accel-buffering"] shouldBe listOf("no")
        client.headers["cache-control"].shouldNotBeNull().joinToString() shouldContain "no-store"
    }
}

/** O limite de duração do stream (`stream-max-duration`), com um contexto de duração curta. */
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
        "zeny.printing.stream-max-duration=PT1.5S",
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
class PrintAgentStreamMaxDurationAttackTest : PrintAgentStreamAttackSupport() {
    @Test
    fun `the stream ends at the maximum duration, frees the connection and the agent resumes with Last-Event-ID`() {
        val printer = newPrinter()
        val delivered = enqueue(printer)
        val first = open(printer.agent.token)
        jobIds(first, 1) shouldContainExactly listOf(delivered.toString())

        first.awaitEnd(Duration.ofSeconds(10)) shouldBe true
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 0 }
        val during = enqueue(printer)

        val resumed = open(printer.agent.token, positionOf(delivered).toString())

        // O SENT já recebido (posição = Last-Event-ID) não volta; o job criado no intervalo chega.
        jobIds(resumed, 1) shouldContainExactly listOf(during.toString())
        resumed.eventsWithin(Duration.ofMillis(300)).shouldBeEmpty()
        resumed.awaitEnd(Duration.ofSeconds(10)) shouldBe true
    }

    @Test
    fun `a reconnection without Last-Event-ID after the maximum duration resends the job still unacknowledged`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val first = open(printer.agent.token)
        jobIds(first, 1) shouldContainExactly listOf(job.toString())
        first.awaitEnd(Duration.ofSeconds(10)) shouldBe true

        val resumed = open(printer.agent.token)

        jobIds(resumed, 1) shouldContainExactly listOf(job.toString())
    }
}
