package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ADVANCE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.EndOfDayRunStep
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Ataques ao motor do fechamento (M6 E10, Task 5), no mesmo contexto do [EndOfDayEngineTest] (banco isolado e etapas
 * falsas do [EndOfDayTestConfiguration]): RN-21 com chamadas concorrentes e trava de outro processo, corridas perdidas
 * provadas no banco (o "outro executor" entra pela fila da trava de linha da execução entre duas transições do motor),
 * retomada depois de queda, falha por código sem a mensagem, ator sistema e `StillRunning` sem execução órfã. Todo
 * teste que cria execução termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, as transições, os repositórios e os apoios de teste
class EndOfDayEngineAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val transitions: EndOfDayRunTransitions,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    private val pool = Executors.newCachedThreadPool()

    @BeforeEach
    fun resetProbe() = probe.reset()

    @AfterEach
    fun closePool() = pool.shutdownNow().let { }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun created(prepare: EndOfDayRun.(Instant) -> Unit = {}): EndOfDayRun {
        val now = now()
        return tx
            .execute {
                val run =
                    EndOfDayRun.start(hotelApi.currentBusinessDate(), RunTrigger.AUTOMATIC, null, registry.slots, now)
                run.prepare(now)
                runs.save(run)
            }.shouldNotBeNull()
    }

    private fun EndOfDayRun.completeNext(now: Instant) {
        val started = beginNext(registry.slots, now).started.shouldNotBeNull()
        completeStep(started.name, "DONE", now)
    }

    private fun settled(block: () -> Unit) {
        try {
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun inspect(
        id: EndOfDayRunId,
        check: EndOfDayRun.() -> Unit,
    ) = tx.executeWithoutResult { _ -> runs.lockForUpdate(id).shouldNotBeNull().check() }

    private fun EndOfDayRun.step(name: String): EndOfDayRunStep = steps.single { it.name == name }

    private fun versionOf(id: EndOfDayRunId): Long = runs.findById(id.value).orElseThrow().version

    private fun statusOf(id: EndOfDayRunId): RunStatus = runs.findById(id.value).orElseThrow().status

    private fun issuesOf(id: EndOfDayRunId): List<EndOfDayIssue> = issues.findByRunIdOrderByCreatedAtAscIdAsc(id.value)

    private fun invocationsOf(run: EndOfDayRun): List<Int> = STEPS.map { probe.invocations(it, run.businessDate) }

    private fun <T> background(work: () -> T): Future<T> = pool.submit<T> { work() }

    private fun <T> Future<T>.result(): T = get(LIMIT.toSeconds(), TimeUnit.SECONDS)

    private fun holdLock() =
        lockProvider
            .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
            .orElseThrow()

    /** A trava `end-of-day-run` está livre: pega e solta na hora. */
    private fun lockIsFree(): Boolean =
        lockProvider
            .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
            .map {
                it.unlock()
                true
            }.orElse(false)

    /** Espera haver [count] sessões do banco isolado paradas na trava de linha de uma execução. */
    private fun awaitRunRowWaiters(count: Int) =
        await().atMost(LIMIT).until {
            jdbc
                .sql(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE '%FROM endofday.run WHERE id%'",
                ).query(Long::class.java)
                .single() >= count
        }

    @Test
    fun `two concurrent executions of the same run from different threads run each step once and both finish`() {
        probe.sleepOn(TWO, Duration.ofMillis(500))
        val run = created()
        settled {
            val start = CyclicBarrier(2)
            val first = background { start.await().let { engine.execute(run.id, WAIT) } }
            val second = background { start.await().let { engine.execute(run.id, WAIT) } }

            first.result() shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            second.result() shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            invocationsOf(run) shouldContainExactly listOf(1, 1, 1, 1)
            inspect(run.id) { steps.map { it.attempts } shouldContainExactly listOf(1, 1, 1, 1) }
        }
    }

    @Test
    fun `a lock held by another process does not restart a step interrupted by a crash`() {
        val run =
            created { now ->
                completeNext(now)
                beginNext(registry.slots, now)
            }
        val version = versionOf(run.id)
        settled {
            val held = holdLock()
            try {
                engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

                versionOf(run.id) shouldBe version
                inspect(run.id) {
                    step(TWO).status shouldBe StepStatus.RUNNING
                    step(TWO).attempts shouldBe 1
                }
                invocationsOf(run) shouldContainExactly listOf(0, 0, 0, 0)
            } finally {
                held.unlock()
            }
        }
    }

    @Test
    @Timeout(90)
    fun `an executor overtaken between begin and complete returns Busy and never runs the step`() {
        val run = created()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        settled {
            try {
                // Outra transação segura a linha da execução: o begin do motor espera na fila dela.
                val holder =
                    background {
                        tx.executeWithoutResult { _ ->
                            runs.lockForUpdate(run.id).shouldNotBeNull()
                            locked.countDown()
                            release.await(LIMIT.toSeconds(), TimeUnit.SECONDS)
                        }
                    }
                locked.await(LIMIT.toSeconds(), TimeUnit.SECONDS) shouldBe true
                val mine = background { engine.execute(run.id, WAIT) }
                awaitRunRowWaiters(1)
                // O "outro executor" (trava vencida) entra na fila atrás do begin do motor e antes do complete dele.
                val other = background { transitions.begin(run.id) }
                awaitRunRowWaiters(2)
                release.countDown()
                holder.result()

                other.result() shouldBe StepStart(ONE, 2)
                mine.result() shouldBe EndOfDayExecution.Busy
                probe.invocations(ONE, run.businessDate) shouldBe 0
                issuesOf(run.id).shouldBeEmpty()
                inspect(run.id) {
                    status shouldBe RunStatus.RUNNING
                    step(ONE).status shouldBe StepStatus.RUNNING
                    step(ONE).attempts shouldBe 2
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    @Timeout(90)
    fun `a failure overtaken by a restart of the step returns Busy without a failure issue`() {
        val gate = CountDownLatch(1)
        probe.holdOn(ONE, gate)
        probe.throwNext(ONE, IllegalStateException("Hóspede Fulano"))
        val run = created()
        settled {
            try {
                val mine = background { engine.execute(run.id, WAIT) }
                await().atMost(LIMIT).until { probe.invocations(ONE, run.businessDate) == 1 }
                // A etapa roda dentro do complete (linha travada): o outro executor espera e entra antes do fail.
                val other = background { transitions.begin(run.id) }
                awaitRunRowWaiters(1)
                gate.countDown()

                other.result() shouldBe StepStart(ONE, 2)
                mine.result() shouldBe EndOfDayExecution.Busy
                issuesOf(run.id).shouldBeEmpty()
                inspect(run.id) {
                    status shouldBe RunStatus.RUNNING
                    step(ONE).status shouldBe StepStatus.RUNNING
                    step(ONE).attempts shouldBe 2
                    step(ONE).result.shouldBeNull()
                }
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    @Timeout(90)
    fun `a failure of a step another executor already completed returns Busy without a failure issue`() {
        val gate = CountDownLatch(1)
        probe.holdOn(ONE, gate)
        probe.throwNext(ONE, IllegalStateException("Hóspede Fulano"))
        val run = created()
        settled {
            try {
                val mine = background { engine.execute(run.id, WAIT) }
                await().atMost(LIMIT).until { probe.invocations(ONE, run.businessDate) == 1 }
                val other =
                    background {
                        tx.executeWithoutResult { _ ->
                            val overtaken = runs.lockForUpdate(run.id).shouldNotBeNull()
                            overtaken.beginNext(registry.slots, now()).started shouldBe StepStart(ONE, 2)
                            overtaken.completeStep(ONE, "DONE", now())
                        }
                    }
                awaitRunRowWaiters(1)
                gate.countDown()
                other.result()

                mine.result() shouldBe EndOfDayExecution.Busy
                issuesOf(run.id).shouldBeEmpty()
                inspect(run.id) {
                    status shouldBe RunStatus.RUNNING
                    step(ONE).status shouldBe StepStatus.COMPLETED
                    step(ONE).attempts shouldBe 2
                }
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `a domain failure is recorded by its code and its message reaches neither issue, trail nor logs`() {
        val marker = "Fulano-${UuidV7.generate()}"
        probe.throwNext(THREE, BusinessRuleException(PERSONAL_CODE, "Hóspede $marker, CPF 123.456.789-09"))
        val run = created()
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(captured)
        settled {
            try {
                engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            } finally {
                root.detachAppender(captured)
            }

            issuesOf(run.id).map { listOf(it.kind, it.stepName, it.reference, it.message) } shouldContainExactly
                listOf(listOf(EndOfDayRunTransitions.STEP_FAILED, THREE, THREE, PERSONAL_CODE.value))
            inspect(run.id) { step(THREE).result shouldBe PERSONAL_CODE.value }
            for (table in listOf("audit.audit_entry", "endofday.run_step", "endofday.run_issue")) {
                jdbc
                    .sql("SELECT count(*) FROM $table t WHERE t::text LIKE :marker")
                    .param("marker", "%$marker%")
                    .query(Long::class.java)
                    .single() shouldBe 0L
            }
            val warnings =
                captured.list.filter { it.loggerName == EndOfDayEngine::class.java.name && it.level == Level.WARN }
            warnings shouldHaveSize 1
            warnings.single().formattedMessage shouldContain run.id.value.toString()
            warnings.single().formattedMessage shouldContain THREE
            warnings.single().formattedMessage shouldContain PERSONAL_CODE.value
            warnings.single().throwableProxy.shouldBeNull()
            captured.list.filter { it.level.isGreaterOrEqual(Level.INFO) && it.mentions(marker) }.shouldBeEmpty()
        }
    }

    @Test
    fun `every transition of a failing run started from an authenticated request is recorded by the system actor`() {
        probe.failNext(THREE, 1)
        val run = created()
        settled {
            actingAs(UserId.new()) { engine.execute(run.id, WAIT) } shouldBe
                EndOfDayExecution.Finished(RunStatus.FAILED)

            val ids = listOf(run.id.value) + issuesOf(run.id).map { it.id }
            val entries =
                jdbc
                    .sql(
                        "SELECT action, entity_type, user_id, device_session_id FROM audit.audit_entry " +
                            "WHERE entity_id IN (:ids)",
                    ).param("ids", ids.map { it.toString() })
                    .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getObject(3), rs.getObject(4)) }
                    .list()
            entries.map { it[0] to it[1] } shouldContain ("endofday.run-step.fail" to "endofday.run")
            entries.map { it[0] to it[1] } shouldContain ("endofday.run-step.fail" to "endofday.run-issue")
            entries.map { it[2] to it[3] }.toSet() shouldBe setOf(null to null)
        }
    }

    @Test
    fun `an Error thrown by a step escapes, releases the lock and leaves the run resumable from that step`() {
        probe.throwNext(TWO, NotImplementedError("Hóspede Fulano"))
        val run = created()
        settled {
            shouldThrow<NotImplementedError> { engine.execute(run.id, WAIT) }

            lockIsFree() shouldBe true
            issuesOf(run.id).shouldBeEmpty()
            inspect(run.id) {
                status shouldBe RunStatus.RUNNING
                step(TWO).status shouldBe StepStatus.RUNNING
                step(TWO).attempts shouldBe 1
            }

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            invocationsOf(run) shouldContainExactly listOf(1, 2, 1, 1)
            inspect(run.id) { step(TWO).attempts shouldBe 2 }
        }
    }

    @Test
    fun `StillRunning that fails in background records the failure and releases the lock`() {
        probe.sleepOn(TWO, Duration.ofSeconds(1))
        probe.failNext(THREE, 1)
        val run = created()
        settled {
            engine.execute(run.id, Duration.ofMillis(100)) shouldBe EndOfDayExecution.StillRunning

            await().atMost(LIMIT).until { statusOf(run.id) == RunStatus.FAILED }
            await().atMost(LIMIT).until { lockIsFree() }
            issuesOf(run.id).map { it.kind } shouldContainExactly listOf(EndOfDayRunTransitions.STEP_FAILED)
        }
    }

    @Test
    fun `a call after StillRunning waits for the run in the engine thread instead of getting Busy`() {
        probe.sleepOn(TWO, Duration.ofSeconds(1))
        val run = created()
        settled {
            engine.execute(run.id, Duration.ofMillis(100)) shouldBe EndOfDayExecution.StillRunning

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            invocationsOf(run) shouldContainExactly listOf(1, 1, 1, 1)
            lockIsFree() shouldBe true
        }
    }

    @Test
    fun `an unknown run fails without keeping the lock`() {
        shouldThrow<IllegalStateException> { engine.execute(EndOfDayRunId.new(), WAIT) }

        lockIsFree() shouldBe true
    }

    @Test
    fun `a crash in the last step is resumed and advances the business date exactly once`() {
        val run =
            created { now ->
                repeat(3) { completeNext(now) }
                beginNext(registry.slots, now).started shouldBe StepStart(ADVANCE, 1)
            }
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            invocationsOf(run) shouldContainExactly listOf(0, 0, 0, 1)
            hotelApi.currentBusinessDate() shouldBe run.businessDate.plusDays(1)
            inspect(run.id) {
                step(ADVANCE).attempts shouldBe 2
                status shouldBe RunStatus.COMPLETED
            }
        }
    }

    @Test
    fun `a crash after resuming a failed step keeps counting its attempts`() {
        val run =
            created { now ->
                repeat(2) { completeNext(now) }
                beginNext(registry.slots, now).started shouldBe StepStart(THREE, 1)
                failStep(THREE, "IllegalStateException", now)
                resume(now)
                beginNext(registry.slots, now).started shouldBe StepStart(THREE, 2)
            }
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            invocationsOf(run) shouldContainExactly listOf(0, 0, 1, 1)
            inspect(run.id) {
                steps.map { it.attempts } shouldContainExactly listOf(1, 1, 3, 1)
                steps.map { it.status }.toSet() shouldBe setOf(StepStatus.COMPLETED)
            }
        }
    }

    private fun ILoggingEvent.mentions(marker: String): Boolean {
        val messages = generateSequence<IThrowableProxy>(throwableProxy) { it.cause }.map { it.message.orEmpty() }
        return formattedMessage.orEmpty().contains(marker) || messages.any { it.contains(marker) }
    }

    private fun <T> actingAs(
        user: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.toString())
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

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(30)
        val LIMIT: Duration = Duration.ofSeconds(30)
        val HELD_FOR: Duration = Duration.ofMinutes(1)
        val PERSONAL_CODE = ErrorCode("TEST_PERSONAL_FAILURE")
    }
}
