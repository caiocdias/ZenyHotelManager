package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques à retomada que não espera a etapa (M6 E11, Task 18), pelo testador: o `lock_timeout` da retomada só vale
 * para a espera da linha da execução (uma espera curta passa, uma longa é `409 END_OF_DAY_RUN_IN_PROGRESS` sem efeito
 * nem aprovação gasta), as gravações depois da trava não esperam a trava que um item de etapa segura, o limite não
 * sobra na conexão devolvida ao pool (depois do commit e depois do `409`) e duas retomadas simultâneas de uma execução
 * `FAILED` retomam uma vez, sem repetir etapa nem responder 500.
 *
 * "Segurar a linha" aqui é uma transação do teste com a mesma trava que a transação de uma etapa segura
 * (`FOR NO KEY UPDATE`), ou a trava que o `INSERT` de uma pendência por item pega (`FOR KEY SHARE`, pela FK). No banco
 * isolado, com as etapas falsas; todo teste termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto e um ataque por teste
class EndOfDayResumeLockAttackTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: Clock,
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
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs/$id/resume") {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
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

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun row(id: String): Map<String, Any?> =
        jdbc
            .sql("SELECT status, version FROM endofday.run WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query()
            .singleRow()

    private fun steps(id: String): List<String> =
        jdbc
            .sql(
                "SELECT name || ':' || status || ':' || attempts FROM endofday.run_step WHERE run_id = :id " +
                    "ORDER BY position",
            ).param("id", UUID.fromString(id))
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun trail(id: String) =
        jdbc
            .sql(
                "SELECT user_id, approval_id FROM audit.audit_entry " +
                    "WHERE entity_id = :id AND action = 'endofday.run.resume' AND outcome = 'SUCCEEDED'",
            ).param("id", id)
            .query()
            .listOfRows()

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    private fun lockTimeout(): String =
        jdbc.sql("SELECT current_setting('lock_timeout')").query(String::class.java).single()

    /**
     * O `lock_timeout` de uma transação nova na conexão [pid] do pool, pedida nesta mesma thread (o Hikari devolve à
     * thread a conexão que ela usou por último). Falha se a conexão não voltar em [SAME_CONNECTION_TRIES] transações.
     */
    private fun lockTimeoutOnConnection(pid: Int): String {
        repeat(SAME_CONNECTION_TRIES) {
            val seen = tx.execute { if (backendPid() == pid) lockTimeout() else null }
            if (seen != null) return seen
        }
        error("A conexão $pid não voltou a esta thread em $SAME_CONNECTION_TRIES transações.")
    }

    /**
     * Segura a linha da execução [id] numa transação de outra thread com a trava [mode] (depois de [prepare]), roda o
     * [block] e solta a trava no fim dele ou depois de [holdAtMost], o que vier antes; a transação é desfeita.
     */
    private fun <T> holdingTheRun(
        id: String,
        mode: String = "FOR NO KEY UPDATE",
        holdAtMost: Duration = HOLD_LIMIT,
        prepare: () -> Unit = {},
        block: () -> T,
    ): T {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val holding =
            holder.submit {
                tx.executeWithoutResult { status ->
                    jdbc
                        .sql("SELECT id FROM endofday.run WHERE id = :id $mode")
                        .param("id", UUID.fromString(id))
                        .query()
                        .listOfRows()
                    prepare()
                    locked.countDown()
                    release.await(holdAtMost.toMillis(), TimeUnit.MILLISECONDS)
                    status.setRollbackOnly()
                }
            }
        try {
            locked.await(LOCKED_WITHIN.toMillis(), TimeUnit.MILLISECONDS) shouldBe true
            return block()
        } finally {
            release.countDown()
            holding.get(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
            holder.shutdownNow()
        }
    }

    private fun <T> actingAs(
        user: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.value.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    /** Um aprovador com `runs.execute` e PIN, e o token que ele aprova para o solicitante na sessão dada. */
    private fun approvalToken(
        requester: TestUser,
        sessionId: UUID,
        resourceRef: String,
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
                    "action" to "endofday.run.resume",
                    "permission" to EndOfDayPermissions.RUNS_EXECUTE.value,
                    "resourceRef" to resourceRef,
                    "approverLogin" to approver.login,
                    "approverPin" to "4321",
                    "reason" to "Motivo do teste",
                ),
            )
        return response.body(201)["approvalToken"].asString()
    }

    private fun elapsedSince(started: Long): Duration = Duration.ofNanos(System.nanoTime() - started)

    // --- A espera pela linha da execução ---

    @Test
    @Timeout(60)
    fun `a resume that waits less than the lock limit for the run gets it and resumes`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()

            val started = System.nanoTime()
            val response = holdingTheRun(id, holdAtMost = SHORT_HOLD) { resume(asUser(manager().id.value), id) }
            val elapsed = elapsedSince(started)

            response.body(200)["status"].asString() shouldBe "COMPLETED"
            elapsed shouldBeGreaterThanOrEqualTo SHORT_HOLD.minusMillis(MARGIN_MILLIS)
            invocations(date) shouldContainExactly listOf(1, 1, 2, 1)
            trail(id) shouldHaveSize 1
        }

    @Test
    @Timeout(60)
    fun `a resume behind a run held past the limit is IN_PROGRESS, changes nothing and spends no approval`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val token = approvalToken(requester, sessionId, "endofday.run:$id")
            val before = row(id)
            val stepsBefore = steps(id)

            val started = System.nanoTime()
            val refused = holdingTheRun(id) { resume(auth, id, token) }
            val elapsed = elapsedSince(started)

            refused.problem(409, "END_OF_DAY_RUN_IN_PROGRESS")
            elapsed shouldBeGreaterThanOrEqualTo LOCK_LIMIT.minusMillis(MARGIN_MILLIS)
            elapsed shouldBeLessThan RESPONDS_WITHIN
            row(id) shouldBe before
            steps(id) shouldContainExactly stepsBefore
            trail(id) shouldHaveSize 0
            invocations(date) shouldContainExactly listOf(1, 1, 1, 0)

            // O token não foi gasto: a recusa desfez a transação inteira, inclusive o uso da aprovação.
            resume(auth, id, token).body(200)["status"].asString() shouldBe "COMPLETED"
            trail(id).single()["approval_id"].shouldNotBeNull()
        }

    // --- Foco 1: nenhuma espera depois da trava da linha ---

    @Test
    @Timeout(60)
    fun `the writes after the lock never wait on the key-share lock an item transaction holds`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()

            // Uma transação de item (REQUIRES_NEW) aberta: o INSERT da pendência pega FOR KEY SHARE na linha da
            // execução pela FK. A retomada trava (FOR NO KEY UPDATE), volta a execução e a etapa e grava a trilha.
            val response =
                holdingTheRun(id, mode = "FOR KEY SHARE", prepare = { insertItemIssue(id) }) {
                    resume(asUser(manager().id.value), id)
                }

            response.body(200)["status"].asString() shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 2, 1)
            trail(id) shouldHaveSize 1
        }

    private fun insertItemIssue(runId: String) {
        jdbc
            .sql(
                "INSERT INTO endofday.run_issue (id, run_id, step_name, kind, reference, message, resolved, " +
                    "created_at) VALUES (:id, :run, :step, 'TEST_ITEM', 'item-1', 'Item processado.', false, :at)",
            ).param("id", UuidV7.generate())
            .param("run", UUID.fromString(runId))
            .param("step", THREE)
            .param("at", clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
            .update()
    }

    // --- Foco 2: o limite não sobra na conexão ---

    @Test
    @Timeout(60)
    fun `the lock limit of a resume ends with its transaction and is not left on the pooled connection`() =
        settled {
            val baseline = tx.execute { lockTimeout() }.shouldNotBeNull()
            val id = EndOfDayRunId(UUID.fromString(failedRun()["id"].asString()))
            val user = manager().id

            val (pid, inside) =
                actingAs(user) {
                    tx.execute { _ ->
                        commands.resumeManually(id, null)
                        backendPid() to lockTimeout()
                    }
                }.shouldNotBeNull()

            inside shouldBe "5s"
            lockTimeoutOnConnection(pid) shouldBe baseline
        }

    @Test
    @Timeout(60)
    fun `the lock limit of a refused resume is not left on the pooled connection either`() =
        settled {
            val baseline = tx.execute { lockTimeout() }.shouldNotBeNull()
            val raw = failedRun()["id"].asString()
            val id = EndOfDayRunId(UUID.fromString(raw))
            val user = manager().id

            val (pid, refusal) =
                holdingTheRun(raw) {
                    actingAs(user) {
                        tx.execute { status ->
                            val pid = backendPid()
                            val refusal = runCatching { commands.resumeManually(id, null) }.exceptionOrNull()
                            status.setRollbackOnly()
                            pid to refusal
                        }
                    }.shouldNotBeNull()
                }

            refusal.shouldBeInstanceOf<EndOfDayRunInProgressException>()
            lockTimeoutOnConnection(pid) shouldBe baseline
            row(raw)["status"] shouldBe "FAILED"
        }

    // --- Foco 3: duas retomadas simultâneas ---

    @Test
    @Timeout(120)
    fun `two simultaneous resumes of a FAILED run resume it once and a resume during the held retry is IN_PROGRESS`() =
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
                await().atMost(LOCKED_WITHIN).until { probe.invocations(THREE, date) == 2 }

                // Com a nova tentativa da etapa segurando a linha, uma terceira retomada não espera a etapa.
                val started = System.nanoTime()
                resume(auth, id).problem(409, "END_OF_DAY_RUN_IN_PROGRESS")
                elapsedSince(started) shouldBeLessThan RESPONDS_WITHIN
                Thread.sleep(LOCK_LIMIT.toMillis())
                gate.countDown()

                val answers = calls.map { it.get(ANSWER_LIMIT.toMillis(), TimeUnit.MILLISECONDS) }
                withClue(answers.map { "${it.status} ${it.getContentAsString(Charsets.UTF_8)}" }) {
                    answers.all { it.status == 200 || it.status == 409 } shouldBe true
                    answers.any { it.status == 200 } shouldBe true
                }
                answers.filter { it.status == 409 }.forEach { it.problem(409, "END_OF_DAY_RUN_IN_PROGRESS") }
                answers.filter { it.status == 200 }.forEach { it.body(200)["status"].asString() shouldBe "COMPLETED" }
                trail(id) shouldHaveSize 1
                invocations(date) shouldContainExactly listOf(1, 1, 2, 1)
                row(id)["status"] shouldBe "COMPLETED"
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
        }

    private companion object {
        /** O `lock_timeout` da retomada (`EndOfDayRunCommands.RESUME_LOCK_TIMEOUT`). */
        val LOCK_LIMIT: Duration = Duration.ofSeconds(5)
        val SHORT_HOLD: Duration = Duration.ofSeconds(2)
        val HOLD_LIMIT: Duration = Duration.ofSeconds(30)
        val LOCKED_WITHIN: Duration = Duration.ofSeconds(20)
        val RESPONDS_WITHIN: Duration = Duration.ofSeconds(10)
        val ANSWER_LIMIT: Duration = Duration.ofSeconds(60)
        const val MARGIN_MILLIS = 500L
        const val SAME_CONNECTION_TRIES = 20
    }
}
