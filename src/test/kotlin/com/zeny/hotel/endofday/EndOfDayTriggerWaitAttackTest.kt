package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunMetrics
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ataques ao gatilho que só dispara o motor e volta (M6 E11, Task 17), no banco isolado com as etapas falsas:
 *
 * - a rodada cujo motor termina dentro da espera volta com o desfecho final já gravado, sem esperar a espera inteira;
 * - a falha de uma etapa dentro da espera;
 * - a rodada que espera o motor atrasado com a trava em outro processo (o `BUSY` é melhor esforço: vira `STARTED`), sem
 *   rodar etapa nem duplicar a execução;
 * - várias rodadas durante uma execução segurada: os pedidos que ficam na fila da thread única do motor, ao rodar, não
 *   executam etapa, não criam execução, não mexem na data e não geram aviso, pendência, log nem métrica espúrios, com a
 *   execução concluindo ou falhando.
 *
 * Usa o gatilho do contexto (`zeny.endofday.trigger.wait=PT1S`) ou, onde o tempo importa, um gatilho com as portas do
 * contexto e espera de 30 s. Datas a partir da `HotelApi.currentBusinessDate()`, sem mudar o fuso; no `finally`, solta
 * portão, bloqueio da thread e trava, e só então `settle` e devolve o relógio.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o gatilho, as portas do contexto e os apoios
class EndOfDayTriggerWaitAttackTest(
    @Autowired private val trigger: EndOfDayTrigger,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val context: ApplicationContext,
    @Autowired @Qualifier("endOfDayExecutor") private val engineThread: ExecutorService,
    @Value("\${zeny.endofday.trigger.wait}") private val contextWait: Duration,
) {
    /** O que um teste observa antes e depois: métricas do motor. */
    private data class Meters(
        val failures: Double,
        val completedFresh: Long,
        val completedResumed: Long,
        val failedFresh: Long,
        val failedResumed: Long,
    )

    private fun meters(): Meters =
        Meters(
            meters.find(EndOfDayRunMetrics.FAILURES).counter()?.count() ?: 0.0,
            duration("completed", "false"),
            duration("completed", "true"),
            duration("failed", "false"),
            duration("failed", "true"),
        )

    private fun duration(
        outcome: String,
        resumed: String,
    ): Long =
        meters
            .find(EndOfDayRunMetrics.DURATION)
            .tags(EndOfDayRunMetrics.OUTCOME_TAG, outcome, EndOfDayRunMetrics.RESUMED_TAG, resumed)
            .timer()
            ?.count() ?: 0

    private fun setClock(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun dueAt(date: LocalDate): Instant =
        EndOfDaySchedule.dueAt(date, hotelApi.operationalSettings().endOfDayTime, hotelApi.timezone())

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single()

    private fun status(date: LocalDate): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(String::class.java)
            .single()

    private fun issueKinds(date: LocalDate): List<String> =
        jdbc
            .sql(
                "SELECT i.kind FROM endofday.run_issue i JOIN endofday.run r ON r.id = i.run_id " +
                    "WHERE r.business_date = :d ORDER BY i.kind",
            ).param("d", date)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun pendingOnEngine(): Int = (engineThread as ThreadPoolExecutor).queue.size

    private fun follower(): TestUser {
        synchronizer.synchronize()
        return iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
        )
    }

    private fun noticesOf(user: TestUser): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", user.id.value)
            .query { rs, _ -> rs.getString(1) }
            .list()

    /** Um gatilho com as portas do contexto e espera de 30 s, para os testes em que a espera não pode vencer. */
    private fun patientTrigger() =
        EndOfDayTrigger(
            hotelApi,
            runs,
            registry,
            commands,
            engine,
            notifications,
            clock,
            Duration.ofHours(24),
            PATIENT_WAIT,
        )

    /** Os eventos de [level] ou acima do motor e do gatilho durante o [block]. */
    private fun logged(
        level: Level,
        block: () -> Unit,
    ): List<ILoggingEvent> {
        val loggers =
            listOf(EndOfDayEngine::class.java, EndOfDayTrigger::class.java)
                .map { LoggerFactory.getLogger(it) as Logger }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        loggers.forEach { it.addAppender(appender) }
        try {
            block()
        } finally {
            loggers.forEach { it.detachAppender(appender) }
        }
        return appender.list.filter { it.level.isGreaterOrEqual(level) }
    }

    /** Roda uma rodada e devolve o desfecho e quanto ela levou. */
    private fun timedTick(target: EndOfDayTrigger = trigger): Pair<TickOutcome, Duration> {
        val begin = System.nanoTime()
        val outcome = target.tick()
        return outcome to Duration.ofNanos(System.nanoTime() - begin)
    }

    /**
     * Um cenário com a data corrente vencida agora: o [block] recebe a data e um portão que, no `finally`, é aberto
     * antes do `settle` (com o bloqueio da thread do motor e a trava de outro processo, se o teste os usou).
     */
    private fun scenario(block: (date: LocalDate, cleanup: MutableList<() -> Unit>) -> Unit) {
        val originalClock = clock.instant()
        val cleanup = mutableListOf<() -> Unit>()
        try {
            probe.reset()
            val date = hotelApi.currentBusinessDate()
            setClock(dueAt(date))
            block(date, cleanup)
        } finally {
            try {
                cleanup.asReversed().forEach { runCatching(it) }
                EndOfDayTestRuns.awaitEngine(context)
                EndOfDayTestRuns.settle(context)
            } finally {
                setClock(originalClock)
            }
        }
    }

    /** Segura a thread do motor com uma tarefa que espera o [release]: o próximo pedido do gatilho fica na fila. */
    private fun occupyEngine(release: CountDownLatch) {
        val running = CountDownLatch(1)
        engineThread.submit {
            running.countDown()
            release.await(GATE_LIMIT_SECONDS, TimeUnit.SECONDS)
        }
        running.await(GATE_LIMIT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    private fun holdEngineLockElsewhere(): SimpleLock =
        lockProvider
            .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
            .orElseThrow()

    /** Espera a etapa [step] da [date] começar (a falsa conta antes de esperar o portão). */
    private fun awaitStarted(
        step: String,
        date: LocalDate,
    ) {
        val limit = System.nanoTime() + Duration.ofSeconds(GATE_LIMIT_SECONDS).toNanos()
        while (probe.invocations(step, date) == 0) {
            check(System.nanoTime() < limit) { "A etapa $step não começou." }
            Thread.onSpinWait()
        }
    }

    @Test
    @Timeout(60)
    fun `a run that ends within the wait is completed when the tick returns without waiting it all`() =
        scenario { date, _ ->
            val (outcome, elapsed) = timedTick(patientTrigger())

            outcome shouldBe TickOutcome.STARTED
            // Sem awaitEngine: a rodada esperou o motor terminar, então o desfecho final já está gravado.
            status(date) shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            hotelApi.currentBusinessDate() shouldBe date.plusDays(1)
            pendingOnEngine() shouldBe 0
            withClue("a rodada levou $elapsed") { elapsed shouldBeLessThan PATIENT_WAIT.dividedBy(2) }
            trigger.tick() shouldBe TickOutcome.NOT_DUE
        }

    @Test
    @Timeout(60)
    fun `a step that fails within the wait leaves the run failed when the tick returns and the next tick waits`() =
        scenario { date, _ ->
            val reader = follower()
            val before = meters()
            probe.failNext(TWO, 1)

            patientTrigger().tick() shouldBe TickOutcome.STARTED

            // Sem awaitEngine: a falha já está gravada quando a rodada volta.
            status(date) shouldBe "FAILED"
            invocations(date) shouldContainExactly listOf(1, 1, 0, 0)
            issueKinds(date) shouldContainExactly listOf("STEP_FAILED")
            noticesOf(reader) shouldContainExactly listOf("STEP_FAILED: $TWO")
            meters().failures shouldBe before.failures + 1
            meters().failedFresh shouldBe before.failedFresh + 1
            hotelApi.currentBusinessDate() shouldBe date

            trigger.tick() shouldBe TickOutcome.WAITING_MANUAL_RESUME
            EndOfDayTestRuns.awaitEngine(context)
            invocations(date) shouldContainExactly listOf(1, 1, 0, 0)
            status(date) shouldBe "FAILED"
        }

    @Test
    @Timeout(60)
    fun `with the engine late and the lock in another process the tick says started but nothing runs or duplicates`() =
        scenario { date, cleanup ->
            val release = CountDownLatch(1)
            cleanup += { release.countDown() }
            val held = holdEngineLockElsewhere()
            val unlocked = AtomicBoolean()
            val unlock = { if (unlocked.compareAndSet(false, true)) held.unlock() }
            cleanup += unlock
            occupyEngine(release)

            val (late, elapsed) = timedTick()

            // O motor não chegou à trava dentro da espera: o BUSY vira STARTED (preocupação 1 do desenvolvedor).
            late shouldBe TickOutcome.STARTED
            elapsed shouldBeGreaterThanOrEqualTo contextWait
            release.countDown()
            val refusals =
                logged(Level.INFO) { EndOfDayTestRuns.awaitEngine(context) }
                    .filter { it.loggerName == EndOfDayEngine::class.java.name }
                    .map { it.formattedMessage }
            withClue(refusals) { refusals.count { "está com outro executor" in it } shouldBe 1 }
            status(date) shouldBe "RUNNING"
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)

            // Com a thread livre (e uma espera que não vence), a mesma trava em outro processo é BUSY, sem rodar nada.
            patientTrigger().tick() shouldBe TickOutcome.BUSY
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)

            unlock()
            trigger.tick() shouldBe TickOutcome.RESUMED
            EndOfDayTestRuns.awaitEngine(context)

            runCount(date) shouldBe 1
            status(date) shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            runCount(date.plusDays(1)) shouldBe 0
        }

    @Test
    @Timeout(90)
    fun `ticks piled up during a held run do nothing when they run after it completes`() =
        scenario { date, cleanup ->
            val reader = follower()
            val gate = CountDownLatch(1)
            cleanup += { gate.countDown() }
            probe.holdOn(TWO, gate)
            val before = meters()
            var piled = 0

            val noise =
                logged(Level.WARN) {
                    trigger.tick() shouldBe TickOutcome.STARTED
                    awaitStarted(TWO, date)
                    repeat(PILED_TICKS) { trigger.tick() shouldBe TickOutcome.RESUMED }
                    piled = pendingOnEngine()
                    probe.invocations(ONE, date) shouldBe 1

                    gate.countDown()
                    EndOfDayTestRuns.awaitEngine(context)
                }

            withClue("pedidos na fila do motor com a execução segurada: $piled") {
                piled shouldBeGreaterThan 0
            }
            noise.map { it.formattedMessage }.shouldBeEmpty()
            pendingOnEngine() shouldBe 0
            runCount(date) shouldBe 1
            status(date) shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            hotelApi.currentBusinessDate() shouldBe date.plusDays(1)
            runCount(date.plusDays(1)) shouldBe 0
            invocations(date.plusDays(1)) shouldContainExactly listOf(0, 0, 0, 0)
            issueKinds(date).shouldBeEmpty()
            noticesOf(reader).shouldBeEmpty()
            meters() shouldBe before.copy(completedFresh = before.completedFresh + 1)
            trigger.tick() shouldBe TickOutcome.NOT_DUE
        }

    @Test
    @Timeout(90)
    fun `ticks piled up during a held run that fails do not repeat the failure nor retry the run`() =
        scenario { date, cleanup ->
            val reader = follower()
            val gate = CountDownLatch(1)
            cleanup += { gate.countDown() }
            probe.holdOn(TWO, gate)
            probe.failNext(TWO, 1)
            val before = meters()

            val warnings =
                logged(Level.WARN) {
                    trigger.tick() shouldBe TickOutcome.STARTED
                    awaitStarted(TWO, date)
                    repeat(PILED_TICKS) { trigger.tick() shouldBe TickOutcome.RESUMED }
                    pendingOnEngine() shouldBeGreaterThan 0

                    gate.countDown()
                    EndOfDayTestRuns.awaitEngine(context)
                }

            withClue(warnings.map { it.formattedMessage }) {
                warnings.size shouldBe 1
                warnings.single().level shouldBe Level.WARN
                warnings.single().loggerName shouldBe EndOfDayEngine::class.java.name
            }
            pendingOnEngine() shouldBe 0
            runCount(date) shouldBe 1
            status(date) shouldBe "FAILED"
            invocations(date) shouldContainExactly listOf(1, 1, 0, 0)
            issueKinds(date) shouldContainExactly listOf("STEP_FAILED")
            noticesOf(reader) shouldContainExactly listOf("STEP_FAILED: $TWO")
            meters() shouldBe
                before.copy(failures = before.failures + 1, failedFresh = before.failedFresh + 1)
            hotelApi.currentBusinessDate() shouldBe date
            trigger.tick() shouldBe TickOutcome.WAITING_MANUAL_RESUME
            invocations(date) shouldContainExactly listOf(1, 1, 0, 0)
        }

    private companion object {
        val PATIENT_WAIT: Duration = Duration.ofSeconds(30)
        val HELD_FOR: Duration = Duration.ofMinutes(5)
        const val GATE_LIMIT_SECONDS = 20L
        const val PILED_TICKS = 5
    }
}
