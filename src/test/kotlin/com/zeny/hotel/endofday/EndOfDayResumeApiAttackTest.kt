package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques à retomada manual pela API (M6 E10, Task 10), pelo testador: o token de aprovação de outra ação, de outra
 * execução ou de outro recurso, a autorização antes de qualquer estado, o id malformado, a execução `RUNNING`
 * interrompida, a trava com outro executor, duas retomadas simultâneas, a idempotência (inclusive de um `409`), a
 * trilha, o tempo real e a resposta sem dado pessoal. No banco isolado; todo teste termina com
 * [EndOfDayTestRuns.settle] e afirma só sobre as próprias execuções.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // os apoios do contexto e um ataque por teste
class EndOfDayResumeApiAttackTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val context: ApplicationContext,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private fun settled(block: () -> Unit) {
        try {
            probe.reset()
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun manager(): TestUser = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))

    private fun resume(
        auth: RequestPostProcessor,
        id: String,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs/$id/resume") {
                with(auth)
                if (key != null) header(IDEMPOTENCY_KEY_HEADER, key)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
            }.andReturn()
            .response

    /** Fecha a data corrente com a terceira etapa falhando: devolve a execução `FAILED`. */
    private fun failedRun(): JsonNode {
        probe.failNext(THREE, 1)
        val response =
            mockMvc
                .post("/api/v1/end-of-day/runs") {
                    with(asUser(manager().id.value))
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"businessDate":"${hotelApi.currentBusinessDate()}"}"""
                }.andReturn()
                .response
        val run = response.body(201)
        run["status"].asString() shouldBe "FAILED"
        return run
    }

    /** Uma execução `RUNNING` sem ninguém rodando: o que uma queda do servidor deixa. */
    private fun interruptedRun(): EndOfDayRun =
        tx
            .execute {
                runs.save(
                    EndOfDayRun.start(
                        hotelApi.currentBusinessDate(),
                        RunTrigger.AUTOMATIC,
                        null,
                        registry.slots,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                    ),
                )
            }.shouldNotBeNull()

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun runStatus(id: String): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(String::class.java)
            .single()

    private fun trail(id: String) =
        jdbc
            .sql(
                "SELECT user_id, approval_id FROM audit.audit_entry " +
                    "WHERE entity_id = :id AND action = 'endofday.run.resume' AND outcome = 'SUCCEEDED'",
            ).param("id", id)
            .query()
            .listOfRows()

    private fun <T> holdingTheLock(block: () -> T): T {
        val held =
            lockProvider
                .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
                .orElseThrow()
        try {
            return block()
        } finally {
            held.unlock()
        }
    }

    /** Um aprovador com `runs.execute` e PIN, e o token que ele aprova para o solicitante na sessão dada. */
    private fun approvalToken(
        requester: TestUser,
        sessionId: UUID,
        resourceRef: String,
        action: String = RESUME,
    ): String {
        synchronizer.synchronize()
        val approver = manager()
        iam.setPin(approver, "4321")
        val response =
            iam.call(
                POST,
                "/api/v1/auth/approvals",
                asUser(requester.id.value, sessionId),
                mapOf(
                    "action" to action,
                    "permission" to EndOfDayPermissions.RUNS_EXECUTE.value,
                    "resourceRef" to resourceRef,
                    "approverLogin" to approver.login,
                    "approverPin" to "4321",
                    "reason" to "Motivo do teste",
                ),
            )
        return response.body(201)["approvalToken"].asString()
    }

    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit

        fun about(id: String) = events.filter { """"id":"$id"""" in it.data }
    }

    private fun connect(frames: Frames): UUID =
        hub.connect(
            StreamSubscription(
                Actor(UserId.new(), DeviceSessionId.new()),
                mapOf(EndOfDayRealtime.TOPIC to PermissionScope.ALL),
                clock.instant().plus(1, ChronoUnit.HOURS),
            ),
            null,
            frames,
        )

    // --- Token de aprovação ---

    @Test
    fun `a token approved for the start action does not serve the resume and changes nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val token = approvalToken(requester, sessionId, "endofday.run:$id", action = START)

            resume(asUser(requester.id.value, sessionId), id, approvalToken = token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runStatus(id) shouldBe "FAILED"
            invocations(date).take(3) shouldContainExactly listOf(1, 1, 1)
        }

    @Test
    fun `a token approved for the resume does not serve the start of the same date`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val token = approvalToken(requester, sessionId, "endofday.run:$id")

            mockMvc
                .post("/api/v1/end-of-day/runs") {
                    with(asUser(requester.id.value, sessionId))
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    header(APPROVAL_TOKEN_HEADER, token)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"businessDate":"$date"}"""
                }.andReturn()
                .response
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `a token approved for one run does not serve another run`() =
        settled {
            val first = failedRun()["id"].asString()
            resume(asUser(manager().id.value), first).body(200)["status"].asString() shouldBe "COMPLETED"
            val second = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val token = approvalToken(requester, sessionId, "endofday.run:$first")

            resume(asUser(requester.id.value, sessionId), second, approvalToken = token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runStatus(second) shouldBe "FAILED"
        }

    @Test
    fun `an unknown token is APPROVAL_TOKEN_INVALID and the run stays FAILED`() =
        settled {
            val id = failedRun()["id"].asString()

            resume(asUser(iam.createUser().id.value), id, approvalToken = "token-inventado")
                .problem(403, "APPROVAL_TOKEN_INVALID")

            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `a used resume token is APPROVAL_TOKEN_USED, not the 409 of the finished run`() =
        settled {
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val token = approvalToken(requester, sessionId, "endofday.run:$id")
            resume(auth, id, approvalToken = token).body(200)["status"].asString() shouldBe "COMPLETED"

            resume(auth, id, approvalToken = token).problem(403, "APPROVAL_TOKEN_USED")
        }

    // --- Autorização antes do estado ---

    @Test
    fun `without the permission a COMPLETED run is APPROVAL_REQUIRED and not a 409`() =
        settled {
            val id = failedRun()["id"].asString()
            resume(asUser(manager().id.value), id).body(200)["status"].asString() shouldBe "COMPLETED"

            resume(asUser(iam.createUser().id.value), id).problem(403, "APPROVAL_REQUIRED")
        }

    @Test
    fun `without the permission a held lock is not revealed`() =
        settled {
            val id = failedRun()["id"].asString()

            holdingTheLock {
                resume(asUser(iam.createUser().id.value), id).problem(403, "APPROVAL_REQUIRED")
            }

            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `without a token the POST is a 401 and the run stays FAILED`() =
        settled {
            val id = failedRun()["id"].asString()

            mockMvc
                .post("/api/v1/end-of-day/runs/$id/resume") {
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                }.andReturn()
                .response
                .problem(401, "UNAUTHENTICATED")

            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `malformed ids are never a 500, with or without the permission`() =
        settled {
            val withPermission = asUser(manager().id.value)
            val without = asUser(iam.createUser().id.value)
            val malformed =
                listOf("not-a-uuid", "123", "0", "null", "%20", "a".repeat(400), "00000000-0000-0000-0000-00000000000g")

            malformed.forEach { id ->
                withClue("id: <$id>") {
                    resume(withPermission, id).status shouldBe 400
                    (resume(without, id).status in 400..499) shouldBe true
                }
            }
        }

    @Test
    fun `the nil UUID and an unknown UUID in upper case are a 404 with the permission`() =
        settled {
            val auth = asUser(manager().id.value)

            resume(auth, "00000000-0000-0000-0000-000000000000").problem(404, "END_OF_DAY_RUN_NOT_FOUND")
            resume(auth, UuidV7.generate().toString().uppercase()).problem(404, "END_OF_DAY_RUN_NOT_FOUND")
        }

    // --- Estado ---

    @Test
    fun `an interrupted RUNNING run is resumed to COMPLETED and each step runs once`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val run = interruptedRun()

            val resumed = resume(asUser(manager().id.value), run.id.value.toString()).body(200)

            resumed["status"].asString() shouldBe "COMPLETED"
            resumed["steps"].values().map { it["status"].asString() } shouldContainExactly STEPS.map { "COMPLETED" }
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `a resume with the lock held by another executor is IN_PROGRESS, runs nothing and stays resumable`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)

            holdingTheLock { resume(auth, id).problem(409, "END_OF_DAY_RUN_IN_PROGRESS") }

            invocations(date).take(3) shouldContainExactly listOf(1, 1, 1)
            runStatus(id) shouldBe "RUNNING"
            resume(auth, id).body(200)["status"].asString() shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 2, 1)
        }

    @Test
    fun `a resume of an interrupted RUNNING run with the lock held is IN_PROGRESS and publishes nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = interruptedRun().id.value.toString()
            val frames = Frames()
            val connection = connect(frames)
            try {
                holdingTheLock { resume(asUser(manager().id.value), id).problem(409, "END_OF_DAY_RUN_IN_PROGRESS") }
                Thread.sleep(SETTLE_MILLIS)

                frames.about(id).size shouldBe 0
                invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
                runStatus(id) shouldBe "RUNNING"
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `a COMPLETED run is NOT_RESUMABLE, even with the lock held, and publishes nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            resume(auth, id).body(200)["status"].asString() shouldBe "COMPLETED"
            val before = invocations(date)
            val frames = Frames()
            val connection = connect(frames)
            try {
                resume(auth, id).problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE")
                holdingTheLock { resume(auth, id).problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE") }
                Thread.sleep(SETTLE_MILLIS)

                frames.about(id).size shouldBe 0
                invocations(date) shouldContainExactly before
                trail(id).size shouldBe 1
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `two simultaneous resumes run each step once and never both execute`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            val gate = CountDownLatch(1)
            probe.holdOn(THREE, gate)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val go = CountDownLatch(1)
                val calls =
                    List(2) {
                        pool.submit<MockHttpServletResponse> {
                            go.await()
                            resume(auth, id)
                        }
                    }
                go.countDown()
                await().atMost(Duration.ofSeconds(20)).until { probe.invocations(THREE, date) >= 2 }
                Thread.sleep(SETTLE_MILLIS)
                gate.countDown()

                val answers = calls.map { it.get(60, TimeUnit.SECONDS) }
                // O motor serializa: a segunda espera a vez (200 já COMPLETED) ou, sem vez, é IN_PROGRESS.
                withClue(answers.map { "${it.status} ${it.getContentAsString(Charsets.UTF_8)}" }) {
                    answers.all { it.status == 200 || it.status == 409 } shouldBe true
                    answers.any { it.status == 200 } shouldBe true
                }
                answers.filter { it.status == 200 }.forEach { it.body(200)["status"].asString() shouldBe "COMPLETED" }
                answers.filter { it.status == 409 }.forEach {
                    it.body(409)["code"].asString() shouldBe "END_OF_DAY_RUN_IN_PROGRESS"
                }
                invocations(date) shouldContainExactly listOf(1, 1, 2, 1)
                runStatus(id) shouldBe "COMPLETED"
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
        }

    // --- Idempotência ---

    @Test
    fun `the same key replays a stored 409 and does not run again`() =
        settled {
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            resume(auth, id).body(200)["status"].asString() shouldBe "COMPLETED"
            val key = UUID.randomUUID().toString()
            val stored = resume(auth, id, key).problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE")

            val repeated = resume(auth, id, key)

            repeated.problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE") shouldBe stored
            repeated.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
        }

    @Test
    fun `the same key replays the stored IN_PROGRESS even after the lock is free`() =
        settled {
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            val key = UUID.randomUUID().toString()
            holdingTheLock { resume(auth, id, key).problem(409, "END_OF_DAY_RUN_IN_PROGRESS") }

            val repeated = resume(auth, id, key)

            repeated.problem(409, "END_OF_DAY_RUN_IN_PROGRESS")
            repeated.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            runStatus(id) shouldBe "RUNNING"
        }

    @Test
    fun `the same key on another run id is IDEMPOTENCY_KEY_REUSED and resumes nothing`() =
        settled {
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            val key = UUID.randomUUID().toString()
            resume(auth, UuidV7.generate().toString(), key).problem(404, "END_OF_DAY_RUN_NOT_FOUND")

            val reused = resume(auth, id, key)

            withClue("a chave guardada era de outra execução: não pode devolver o 404 dela nem retomar esta") {
                reused.problem(422, "IDEMPOTENCY_KEY_REUSED")
            }
            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `the stored APPROVAL_REQUIRED is replayed even with a token, and the token still works with another key`() =
        settled {
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val key = UUID.randomUUID().toString()
            resume(auth, id, key).problem(403, "APPROVAL_REQUIRED")
            val token = approvalToken(requester, sessionId, "endofday.run:$id")

            val replayed = resume(auth, id, key, token)

            replayed.problem(403, "APPROVAL_REQUIRED")
            replayed.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            runStatus(id) shouldBe "FAILED"
            resume(auth, id, approvalToken = token).body(200)["status"].asString() shouldBe "COMPLETED"
        }

    // --- Trilha, tempo real, dado pessoal ---

    @Test
    fun `a resume by a manager leaves one trail entry without approval and refusals leave none`() =
        settled {
            val id = failedRun()["id"].asString()
            val manager = manager()
            val auth = asUser(manager.id.value)
            resume(asUser(iam.createUser().id.value), id).problem(403, "APPROVAL_REQUIRED")
            trail(id).size shouldBe 0

            resume(auth, id).body(200)["status"].asString() shouldBe "COMPLETED"
            resume(auth, id).problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE")

            val rows = trail(id)
            rows.map { it["user_id"] } shouldContainExactly listOf(manager.id.value)
            rows.map { it["approval_id"] } shouldContainExactly listOf(null)
        }

    @Test
    fun `a resume that changes the run publishes it once, with the stored version and only type, id and version`() =
        settled {
            val id = failedRun()["id"].asString()
            val frames = Frames()
            val connection = connect(frames)
            try {
                holdingTheLock { resume(asUser(manager().id.value), id).problem(409, "END_OF_DAY_RUN_IN_PROGRESS") }

                await().atMost(Duration.ofSeconds(5)).until { frames.about(id).size >= 1 }
                Thread.sleep(SETTLE_MILLIS)

                frames.about(id).size shouldBe 1
                val message = json.readTree(frames.about(id).single().data)
                message.propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
                val stored =
                    jdbc
                        .sql("SELECT version FROM endofday.run WHERE id = :id")
                        .param("id", UUID.fromString(id))
                        .query(Long::class.java)
                        .single()
                message["version"].asLong() shouldBe stored
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `the resume response has only the run fields and no personal data after a new failure`() =
        settled {
            val id = failedRun()["id"].asString()
            val user = manager()
            probe.throwNext(THREE, IllegalStateException("Hóspede Maria Souza, CPF 123.456.789-09"))

            val response = resume(asUser(user.id.value), id)
            val run = response.body(200)

            run["status"].asString() shouldBe "FAILED"
            run.propertyNames().toList() shouldContainExactlyInAnyOrder
                listOf(
                    "id",
                    "businessDate",
                    "trigger",
                    "status",
                    "triggeredBy",
                    "startedAt",
                    "finishedAt",
                    "version",
                    "steps",
                )
            val text = response.getContentAsString(Charsets.UTF_8)
            listOf("Maria", "123.456", user.login).forEach { text shouldNotContain it }
        }

    private companion object {
        const val START = "endofday.run.start"
        const val RESUME = "endofday.run.resume"
        const val SETTLE_MILLIS = 400L
        val HELD_FOR: Duration = Duration.ofMinutes(1)
    }
}
