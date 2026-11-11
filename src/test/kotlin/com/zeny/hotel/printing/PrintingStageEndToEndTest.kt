package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.application.PrintJobsMaintenance
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FILES_PURGE_DEFERRED
import com.zeny.hotel.testsupport.FNRH_SEND_DEFERRED
import com.zeny.hotel.testsupport.GUESTS_REKEY_DEFERRED
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PRINTING_MAINTENANCE_DEFERRED
import com.zeny.hotel.testsupport.PROVISIONAL_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.SseTestClient
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Passada de etapa da M5 E10, ponta a ponta pela API HTTP num servidor de porta aleatória e com o `MutableClock`: o
 * administrador cadastra agentes e impressoras; um job nasce pela rota manual e outro pela `PrintingApi`; cada agente
 * recebe pelo SSE e pelo long-poll só os próprios, lê o conteúdo, confirma; o job sem confirmação é reenviado depois do
 * timeout e o conteúdo some depois da guarda. Também a transferência de impressora pelo `PUT` com o stream do agente
 * antigo aberto, o `stop()` do registro encerrando um stream vivo e a fronteira entre o token do agente e o JWT.
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
        PRINTING_MAINTENANCE_DEFERRED,
        "zeny.printing.delivery-poll-interval=PT0.1S",
        "zeny.printing.stream-heartbeat=PT0.3S",
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
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList")
class PrintingStageEndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val printing: PrintingApi,
    @Autowired private val maintenance: PrintJobsMaintenance,
    @Autowired private val connections: PrintAgentConnections,
    @Autowired private val clock: MutableClock,
    @LocalServerPort private val port: Int,
) {
    private val baseUrl get() = "http://localhost:$port"
    private val streamPath = "/api/v1/print-agents/me/jobs/stream"
    private val me = "/api/v1/print-agents/me"
    private val clients = CopyOnWriteArrayList<SseTestClient>()
    private var advanced: Duration = Duration.ZERO

    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Printer(
        val id: UUID,
        val agent: Agent,
    )

    /** O relógio é compartilhado pelo contexto: devolve-o e fecha os streams ao fim de cada teste. */
    @AfterEach
    fun cleanUp() {
        clients.forEach { it.close() }
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
    }

    private fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun newAgent(): Agent {
        val response = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente ${suffix()}"))
        response.status shouldBe 201
        val body = jsonMapper.readTree(response.contentAsString)
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
        val response = iam.call(POST, "/api/v1/printers", iam.asAdmin(), body)
        response.status shouldBe 201
        return Printer(UUID.fromString(jsonMapper.readTree(response.contentAsString)["id"].asString()), agent)
    }

    private fun pdf(marker: String) = "%PDF-1.7 SEGREDO-$marker-${suffix()}".toByteArray()

    private fun viaApi(
        printer: Printer,
        content: ByteArray,
    ): UUID = printing.enqueue(NewPrintJob(printer.id, "frontdesk.registration-card", content))

    /** O job pela rota manual, como o Gerente faria (o administrador tem a permissão). */
    private fun viaManualRoute(
        printer: Printer,
        bytes: ByteArray,
    ): UUID {
        val body =
            mapOf(
                "printerId" to printer.id,
                "documentType" to "manual.test-page",
                "content" to Base64.getEncoder().encodeToString(bytes),
            )
        val response =
            mockMvc
                .request(POST, "/api/v1/print-jobs") {
                    with(iam.asAdmin())
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(body)
                }.andReturn()
                .response
        response.status shouldBe 201
        return UUID.fromString(jsonMapper.readTree(response.contentAsString)["id"].asString())
    }

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
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(body)
                }
            }.andReturn()
            .response

    private fun finish(result: MvcResult): MockHttpServletResponse {
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    private fun longPoll(agent: Agent): List<JsonNode> {
        val result =
            mockMvc
                .request(GET, "$me/jobs?wait=0") { header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}") }
                .andReturn()
        val response = finish(result)
        response.status shouldBe 200
        return jsonMapper.readTree(response.contentAsString).values().toList()
    }

    private fun ack(
        agent: Agent,
        job: UUID,
        result: String = "PRINTED",
    ) = send(POST, "$me/jobs/$job/ack", agent.token, mapOf("result" to result))

    private fun column(
        job: UUID,
        sql: String,
    ): Any? =
        jdbc
            .sql("SELECT $sql FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query {
                rs,
                _,
                ->
                rs.getObject(1)
            }.single()

    private fun statusOf(job: UUID) = column(job, "status") as String

    private fun positionOf(job: UUID) = column(job, "position") as Long

    private fun open(agent: Agent): SseTestClient =
        SseTestClient.openPath(baseUrl, streamPath, agent.token).also { clients += it }

    private fun idOf(frame: com.zeny.hotel.testsupport.SseFrame?) =
        jsonMapper.readTree(frame.shouldNotBeNull().data.shouldNotBeNull())["id"].asString()

    @Test
    @Suppress("LongMethod")
    fun `the full flow, from the admin registration to the purge of the content, through the HTTP API`() {
        val admin = iam.asAdmin()
        val printerA = newPrinter()
        val printerB = newPrinter()
        val contentA1 = pdf("A1")
        val contentA2 = pdf("A2")
        val jobA1 = viaManualRoute(printerA, contentA1)
        val jobA2 = viaApi(printerA, contentA2)
        val jobB1 = viaManualRoute(printerB, pdf("B1"))
        val firstPositionA2 = positionOf(jobA2)

        // O agente A recebe pelo SSE só os próprios jobs, em ordem de posição; o job do B não chega.
        val streamA = open(printerA.agent)
        streamA.status shouldBe 200
        val first = streamA.nextEvent().shouldNotBeNull()
        val second = streamA.nextEvent().shouldNotBeNull()
        first.id shouldBe positionOf(jobA1).toString()
        idOf(first) shouldBe jobA1.toString()
        idOf(second) shouldBe jobA2.toString()
        streamA.eventsWithin(Duration.ofMillis(400)).none { idOf(it) == jobB1.toString() } shouldBe true

        // O agente B, pelo long-poll, recebe só o do B.
        longPoll(printerB.agent).map { it["id"].asString() } shouldContainExactly listOf(jobB1.toString())
        longPoll(printerA.agent).shouldBeEmpty()

        // O conteúdo só vai ao agente a que o job foi entregue, íntegro; o outro agente tem 404.
        val contentResponse = send(GET, "$me/jobs/$jobA1/content", printerA.agent.token)
        contentResponse.status shouldBe 200
        contentResponse.contentType shouldBe "application/pdf"
        contentResponse.contentAsByteArray.toList() shouldBe contentA1.toList()
        send(GET, "$me/jobs/$jobA1/content", printerB.agent.token).status shouldBe 404
        ack(printerB.agent, jobA1).status shouldBe 404
        statusOf(jobA1) shouldBe "SENT"

        // O conteúdo e o token ficam cifrados e com hash no banco.
        val stored = column(jobA1, "content") as ByteArray
        String(stored, Charsets.ISO_8859_1) shouldNotContain "SEGREDO"
        stored.toList() shouldNotBe contentA1.toList()
        val tokenHash =
            jdbc
                .sql("SELECT token_hash FROM printing.print_agent WHERE id = :id")
                .param("id", printerA.agent.id)
                .query(String::class.java)
                .single()
        tokenHash shouldNotBe printerA.agent.token
        tokenHash shouldNotContain printerA.agent.token

        // A confirma o primeiro; o segundo fica sem confirmação.
        ack(printerA.agent, jobA1).status shouldBe 204
        statusOf(jobA1) shouldBe "PRINTED"
        val listed = iam.call(GET, "/api/v1/print-jobs?printerId=${printerA.id}", admin)
        listed.status shouldBe 200
        val items = jsonMapper.readTree(listed.contentAsString)["items"].values().toList()
        items.first { it["id"].asString() == jobA1.toString() }["status"].asString() shouldBe "PRINTED"
        items.none { it.has("content") } shouldBe true
        listed.contentAsString shouldNotContain "SEGREDO"

        // Sem confirmação, depois do timeout, o job volta à fila com posição nova e o stream aberto o reenvia.
        advance(Duration.ofMinutes(3))
        maintenance.run()
        val redelivered = streamA.nextEvent().shouldNotBeNull()
        idOf(redelivered) shouldBe jobA2.toString()
        redelivered.id!!.toLong() shouldBeGreaterThan firstPositionA2
        jsonMapper.readTree(redelivered.data)["attempts"].asInt() shouldBe 2
        ack(printerA.agent, jobA2).status shouldBe 204
        statusOf(jobA2) shouldBe "PRINTED"

        // O agente aparece com o último contato e nunca com o token.
        val agentView = iam.call(GET, "/api/v1/print-agents/${printerA.agent.id}", admin)
        agentView.status shouldBe 200
        jsonMapper.readTree(agentView.contentAsString)["lastSeenAt"].isNull shouldBe false
        agentView.contentAsString shouldNotContain printerA.agent.token

        // Depois da guarda, o conteúdo some; o job do B, nunca confirmado, venceu; e o agente não lê mais o conteúdo.
        advance(Duration.ofHours(24).plusMinutes(1))
        maintenance.run()
        for (job in listOf(jobA1, jobA2)) {
            column(job, "content IS NULL") shouldBe true
            column(job, "content_purged_at").shouldNotBeNull()
            statusOf(job) shouldBe "PRINTED"
        }
        statusOf(jobB1) shouldBe "FAILED"
        column(jobB1, "failure_code") shouldBe "EXPIRED"
        send(GET, "$me/jobs/$jobA1/content", printerA.agent.token).status shouldBe 404
    }

    @Test
    fun `a printer moved by PUT with the old agent stream open delivers to the new agent only`() {
        val oldAgent = newAgent()
        val newAgent = newAgent()
        val printer = newPrinter(oldAgent)
        val oldStream = open(oldAgent)
        val newStream = open(newAgent)
        val before = viaApi(printer, pdf("P1"))
        idOf(oldStream.nextEvent()) shouldBe before.toString()

        val admin = iam.asAdmin()
        val etag = iam.call(GET, "/api/v1/printers/${printer.id}", admin).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val put =
            iam.call(
                PUT,
                "/api/v1/printers/${printer.id}",
                admin,
                mapOf("name" to "Movida ${suffix()}", "address" to "addr-${suffix()}", "agentId" to newAgent.id),
                ifMatch = etag,
            )
        put.status shouldBe 200
        jsonMapper.readTree(put.contentAsString)["agentId"].asString() shouldBe newAgent.id.toString()

        // Depois da transferência o job novo vai ao agente novo e o stream do antigo fica mudo.
        val after = viaApi(printer, pdf("P2"))
        idOf(newStream.nextEvent()) shouldBe after.toString()
        oldStream.eventsWithin(Duration.ofMillis(600)).shouldBeEmpty()
        // O agente antigo não confirma nem lê o que recebeu antes da troca (404, sem vazar a existência).
        ack(oldAgent, before).status shouldBe 404
        send(GET, "$me/jobs/$before/content", oldAgent.token).status shouldBe 404
        statusOf(before) shouldBe "SENT"

        // O que ficou sem confirmação com o agente antigo é reenviado ao novo depois do timeout.
        advance(Duration.ofMinutes(3))
        maintenance.run()
        val redelivered = listOf(newStream.nextEvent(), newStream.nextEvent()).map { idOf(it) }
        redelivered shouldContain before.toString()
        redelivered shouldContain after.toString()
        oldStream.eventsWithin(Duration.ofMillis(400)).shouldBeEmpty()
        ack(newAgent, before).status shouldBe 204
        statusOf(before) shouldBe "PRINTED"
    }

    @Test
    fun `stopping the connection registry ends a live stream over HTTP and the stream works again after the restart`() {
        val agent = newAgent()
        val live = open(agent)
        live.status shouldBe 200
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 1 }
        try {
            connections.stop()

            live.awaitEnd(Duration.ofSeconds(5)) shouldBe true
            await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 0 }
            // Com o servidor parando, um stream novo já nasce encerrado.
            open(agent).awaitEnd(Duration.ofSeconds(5)) shouldBe true
        } finally {
            connections.start()
        }
        val again = open(agent)
        again.status shouldBe 200
        again.awaitEnd(Duration.ofMillis(500)) shouldBe false
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(agent.id) == 1 }
    }

    @Test
    fun `agent token and user JWT do not cross over and read access is read only`() {
        val agent = newAgent()
        val reader =
            iam.createUserWith(
                TestGrant(
                    PrintingPermissions.permissions.single {
                        it.code ==
                            PrintingPermissions.JOBS_READ
                    },
                    ALL,
                ),
            )
        val jwt = iam.loginOk(reader)["accessToken"].asString()
        val asReader = iam.asTestUser(reader)

        send(GET, "/api/v1/print-jobs", agent.token).status shouldBe 401
        send(GET, "/api/v1/printers", agent.token).status shouldBe 401
        send(POST, "/api/v1/print-agents/${agent.id}/rotate-token", agent.token).status shouldBe 401
        send(GET, "$me/jobs?wait=0", jwt).status shouldBe 401
        send(GET, "$me/jobs?wait=0", null).status shouldBe 401

        iam.call(GET, "/api/v1/printers", asReader).status shouldBe 200
        iam.call(GET, "/api/v1/print-jobs", asReader).status shouldBe 200
        iam.call(POST, "/api/v1/print-agents", asReader, mapOf("name" to "x")).status shouldBe 403
        val manual =
            mapOf(
                "printerId" to UUID.randomUUID(),
                "documentType" to "manual.test-page",
                "content" to "JVBERi0=",
            )
        mockMvc
            .request(POST, "/api/v1/print-jobs") {
                with(asReader)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(manual)
            }.andReturn()
            .response.status shouldBe 403
    }
}
