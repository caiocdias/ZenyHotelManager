package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ADVANCE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TEST_ITEM
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
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * O motor do fechamento (M6 E10, Task 5) no banco isolado, com as etapas falsas do [EndOfDayTestConfiguration]: cada
 * teste cria a execução da data operacional corrente com as etapas do registro e termina com
 * [EndOfDayTestRuns.settle], que fecha a data (as execuções completas a avançam pela `fake-advance`). As asserções
 * olham só a execução do próprio teste.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, os repositórios e os apoios de transação, JDBC, trava e relógio
class EndOfDayEngineTest(
    @Autowired private val engine: EndOfDayEngine,
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
    @BeforeEach
    fun resetProbe() = probe.reset()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    /** Cria a execução da data corrente com as [steps], depois de prepará-la com [prepare] (sem a trava do motor). */
    private fun created(
        steps: List<StepSlot> = registry.slots,
        prepare: EndOfDayRun.(Instant) -> Unit = {},
    ): EndOfDayRun {
        val now = now()
        return tx
            .execute {
                val run = EndOfDayRun.start(hotelApi.currentBusinessDate(), RunTrigger.AUTOMATIC, null, steps, now)
                run.prepare(now)
                runs.save(run)
            }.shouldNotBeNull()
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

    private fun statusOf(id: EndOfDayRunId): RunStatus = runs.findById(id.value).orElseThrow().status

    private fun versionOf(id: EndOfDayRunId): Long = runs.findById(id.value).orElseThrow().version

    private fun resume(id: EndOfDayRunId) =
        tx.executeWithoutResult { _ -> runs.lockForUpdate(id).shouldNotBeNull().resume(now()) }

    private fun issuesOf(id: EndOfDayRunId): List<EndOfDayIssue> = issues.findByRunIdOrderByCreatedAtAscIdAsc(id.value)

    private fun invocationsOf(run: EndOfDayRun): List<Int> = STEPS.map { probe.invocations(it, run.businessDate) }

    private fun lockIsFree(): Boolean =
        lockProvider
            .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
            .map {
                it.unlock()
                true
            }.orElse(false)

    private fun ListAppender<ILoggingEvent>.errors(): Int = list.count { it.level == Level.ERROR }

    /** Os eventos do log do motor durante o [block], que recebe o apêndice para esperar por eles. */
    private fun capturingEngineLog(block: (ListAppender<ILoggingEvent>) -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EndOfDayEngine::class.java) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
            block(captured)
        } finally {
            logger.detachAppender(captured)
        }
        return captured.list.toList()
    }

    @Test
    fun `runs all steps in order and records start, finish and result of each`() {
        val run = created()
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            inspect(run.id) {
                status shouldBe RunStatus.COMPLETED
                steps.map { it.name to it.status } shouldContainExactly STEPS.map { it to StepStatus.COMPLETED }
                steps.forEach {
                    it.startedAt.shouldNotBeNull() shouldBeLessThanOrEqualTo it.finishedAt.shouldNotBeNull()
                    it.result shouldBe "DONE"
                    it.attempts shouldBe 1
                }
                steps.zipWithNext().forEach { (previous, next) ->
                    next.startedAt.shouldNotBeNull() shouldBeGreaterThanOrEqualTo previous.finishedAt.shouldNotBeNull()
                }
            }
            invocationsOf(run) shouldContainExactly listOf(1, 1, 1, 1)
        }
    }

    @Test
    fun `a failure in the third step leaves the run resumable and resuming does not rerun the first two`() {
        probe.failNext(THREE, 1)
        val run = created()
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            inspect(run.id) {
                status shouldBe RunStatus.FAILED
                step(THREE).status shouldBe StepStatus.FAILED
                step(ADVANCE).status shouldBe StepStatus.PENDING
            }
            issuesOf(run.id).map { listOf(it.kind, it.stepName, it.reference, it.message) } shouldContainExactly
                listOf(listOf(EndOfDayRunTransitions.STEP_FAILED, THREE, THREE, "IllegalStateException"))

            resume(run.id)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            invocationsOf(run) shouldContainExactly listOf(1, 1, 2, 1)
            inspect(run.id) {
                step(THREE).attempts shouldBe 2
                steps.map { it.status }.toSet() shouldBe setOf(StepStatus.COMPLETED)
            }
        }
    }

    @Test
    fun `a crash mid-run is resumed from the interrupted step`() {
        val run =
            created { now ->
                beginNext(registry.slots, now)
                completeStep(ONE, "DONE", now)
                beginNext(registry.slots, now)
            }
        settled {
            inspect(run.id) { step(TWO).status shouldBe StepStatus.RUNNING }
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            invocationsOf(run) shouldContainExactly listOf(0, 1, 1, 1)
            inspect(run.id) {
                step(ONE).attempts shouldBe 1
                step(TWO).attempts shouldBe 2
                step(TWO).status shouldBe StepStatus.COMPLETED
            }
        }
    }

    @Test
    fun `a busy lock returns Busy and touches nothing`() {
        val run = created()
        val version = versionOf(run.id)
        val held =
            lockProvider
                .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
                .orElseThrow()
        try {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

            versionOf(run.id) shouldBe version
            inspect(run.id) {
                status shouldBe RunStatus.RUNNING
                steps.map { it.status }.toSet() shouldBe setOf(StepStatus.PENDING)
            }
            invocationsOf(run) shouldContainExactly listOf(0, 0, 0, 0)
        } finally {
            held.unlock()
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a completed run is not executed again`() {
        val run = created()
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            val version = versionOf(run.id)

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            versionOf(run.id) shouldBe version
            invocationsOf(run) shouldContainExactly listOf(1, 1, 1, 1)
        }
    }

    @Test
    fun `steps run as the system actor`() {
        val run = created()
        settled {
            actingAs(UserId.new()) { engine.execute(run.id, WAIT) } shouldBe
                EndOfDayExecution.Finished(RunStatus.COMPLETED)

            val users =
                jdbc
                    .sql(
                        "SELECT user_id FROM audit.audit_entry " +
                            "WHERE entity_id = :id AND action = 'endofday.run-step.complete'",
                    ).param("id", run.id.value.toString())
                    .query { rs, _ -> rs.getObject(1) }
                    .list()
            users shouldHaveSize STEPS.size
            users.toSet() shouldBe setOf(null)
        }
    }

    @Test
    @Timeout(30)
    fun `per-item work in its own transaction survives a caught item failure`() {
        probe.itemsOn(TWO, items = 3, failingItem = 2)
        val run = created()
        settled {
            engine.execute(run.id, ITEMS_WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            val items = issuesOf(run.id).filter { it.kind == TEST_ITEM }
            items.map { listOf(it.stepName, it.reference, it.message) } shouldContainExactlyInAnyOrder
                listOf(
                    listOf(TWO, "item-1", "Item processado."),
                    listOf(TWO, "item-2", "Item falhou: TEST_ITEM_FAILED"),
                    listOf(TWO, "item-3", "Item processado."),
                )
            inspect(run.id) { step(TWO).status shouldBe StepStatus.COMPLETED }
        }
    }

    @Test
    fun `an issue raised before the step fails is rolled back with it`() {
        probe.raiseIssueOn(THREE, "TEST_PENDING")
        probe.failNext(THREE, 1)
        val run = created()
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            issuesOf(run.id).map { it.kind } shouldContainExactly listOf(EndOfDayRunTransitions.STEP_FAILED)
        }
    }

    @Test
    fun `a step removed by a deploy does not block the run`() {
        val run = created(registry.slots + StepSlot(GONE, GONE_POSITION))
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            inspect(run.id) {
                step(GONE).status shouldBe StepStatus.SKIPPED
                steps.filter { it.name != GONE }.map { it.status }.toSet() shouldBe setOf(StepStatus.COMPLETED)
            }
            issuesOf(run.id).map { Triple(it.kind, it.stepName, it.reference) } shouldContainExactly
                listOf(Triple(EndOfDayRunTransitions.STEP_UNREGISTERED, GONE, GONE))
        }
    }

    @Test
    fun `a failed run whose failed step left the registry is resumed to completion`() {
        val slots = registry.slots + StepSlot(GONE, GONE_POSITION)
        val run =
            created(slots) { now ->
                repeat(3) {
                    val started = beginNext(slots, now).started.shouldNotBeNull()
                    completeStep(started.name, "DONE", now)
                }
                beginNext(slots, now).started.shouldNotBeNull().name shouldBe GONE
                failStep(GONE, "IllegalStateException", now)
            }
        settled {
            statusOf(run.id) shouldBe RunStatus.FAILED
            resume(run.id)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            invocationsOf(run) shouldContainExactly listOf(0, 0, 0, 1)
            inspect(run.id) {
                step(GONE).status shouldBe StepStatus.SKIPPED
                step(ADVANCE).status shouldBe StepStatus.COMPLETED
            }
        }
    }

    @Test
    fun `waitAtMost elapses with StillRunning and the run completes in background`() {
        probe.sleepOn(TWO, Duration.ofSeconds(2))
        val run = created()
        settled {
            engine.execute(run.id, Duration.ofMillis(100)) shouldBe EndOfDayExecution.StillRunning

            await().atMost(WAIT).until { statusOf(run.id) == RunStatus.COMPLETED }
            invocationsOf(run) shouldContainExactly listOf(1, 1, 1, 1)
        }
    }

    @Test
    fun `a StackOverflowError in a step fails the step by its class name like any other error`() {
        probe.throwNext(TWO, StackOverflowError("Hóspede Fulano"))
        val run = created()
        settled {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            issuesOf(run.id).map { listOf(it.kind, it.reference, it.message) } shouldContainExactly
                listOf(listOf(EndOfDayRunTransitions.STEP_FAILED, TWO, "StackOverflowError"))
            inspect(run.id) { step(TWO).status shouldBe StepStatus.FAILED }
        }
    }

    @Test
    fun `a virtual machine error in a step escapes and leaves the run running for the restart`() {
        probe.throwNext(TWO, InternalError("Hóspede Fulano"))
        val run = created()
        settled {
            shouldThrow<InternalError> { engine.execute(run.id, WAIT) }

            issuesOf(run.id).shouldBeEmpty()
            inspect(run.id) {
                status shouldBe RunStatus.RUNNING
                step(TWO).status shouldBe StepStatus.RUNNING
            }
        }
    }

    @Test
    fun `a failure outside the steps after StillRunning is logged once as ERROR by its class, without its message`() {
        val marker = "Fulano-${UuidV7.generate()}"
        probe.sleepOn(TWO, Duration.ofSeconds(1))
        probe.throwNext(TWO, InternalError("Hóspede $marker"))
        val run = created()
        settled {
            val events =
                capturingEngineLog {
                    engine.execute(run.id, Duration.ofMillis(100)) shouldBe EndOfDayExecution.StillRunning
                    await().atMost(WAIT).until {
                        lockIsFree() && statusOf(run.id) == RunStatus.RUNNING &&
                            it.errors() > 0
                    }
                }

            val errors = events.filter { it.level == Level.ERROR }
            errors shouldHaveSize 1
            errors.single().formattedMessage shouldContain run.id.value.toString()
            errors.single().formattedMessage shouldContain InternalError::class.java.name
            errors.single().throwableProxy.shouldBeNull()
            events.filter { it.level.isGreaterOrEqual(Level.INFO) }.map { it.formattedMessage }.forEach {
                it shouldNotContain marker
            }
        }
    }

    @Test
    fun `a handled step failure is a WARN and never an ERROR`() {
        probe.failNext(THREE, 1)
        val run = created()
        settled {
            val events =
                capturingEngineLog {
                    engine.execute(
                        run.id,
                        WAIT,
                    ) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
                }

            events.filter { it.level == Level.WARN } shouldHaveSize 1
            events.filter { it.level == Level.ERROR }.shouldBeEmpty()
        }
    }

    @Test
    fun `the end-of-day executors replace neither the application task executor nor the job scheduler`() {
        context.getBean("applicationTaskExecutor").shouldBeInstanceOf<ThreadPoolTaskExecutor>()
        context.getBean("taskScheduler").shouldBeInstanceOf<TaskScheduler>()
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
        val ITEMS_WAIT: Duration = Duration.ofSeconds(20)
        val HELD_FOR: Duration = Duration.ofMinutes(1)
        const val GONE = "fake-gone"
        const val GONE_POSITION = 35
    }
}
