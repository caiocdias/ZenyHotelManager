package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayMetrics
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.dao.QueryTimeoutException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Ataques às métricas do fechamento (M6 E10, Task 13): resumed e contagem por chamada, nada medido em Busy nem em
 * execução já terminada, o atraso que cresce com o relógio, zera ao fechar a data e vira NaN sem banco, tags de
 * conjunto fechado e os nomes das regras de alerta contra o que o `/actuator/prometheus` expõe. As asserções olham
 * aumentos (antes e depois): o banco isolado acumula execuções de outros testes.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, os apoios de banco, relógio e métricas
class EndOfDayMetricsAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val metrics: EndOfDayMetrics,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val context: ApplicationContext,
) {
    private fun created(): EndOfDayRun {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val date = hotelApi.currentBusinessDate()
        return checkNotNull(
            tx.execute { runs.save(EndOfDayRun.start(date, RunTrigger.AUTOMATIC, null, registry.slots, now)) },
        )
    }

    private fun settled(block: () -> Unit) {
        val originalClock = clock.instant()
        try {
            probe.reset()
            block()
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                clock.advance(Duration.between(clock.instant(), originalClock))
            }
        }
    }

    private fun count(
        outcome: String,
        resumed: String,
    ): Long =
        meters
            .find("zeny.endofday.run.duration")
            .tags("outcome", outcome, "resumed", resumed)
            .timer()
            ?.count() ?: 0

    private fun totalCount(): Long = listOf("completed", "failed").sumOf { o -> count(o, "true") + count(o, "false") }

    private fun failures(): Double = meters.find("zeny.endofday.run.failures").counter()?.count() ?: 0.0

    private fun gauge(name: String): Double = meters.get(name).gauge().value()

    private fun resume(run: EndOfDayRun) =
        tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run.id)).resume(clock.instant()) }

    private fun dueAtOfCurrentDate() =
        EndOfDaySchedule.dueAt(
            hotelApi.currentBusinessDate(),
            hotelApi.operationalSettings().endOfDayTime,
            hotelApi.timezone(),
        )

    private fun moveClockTo(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun scrape(): String =
        mockMvc
            .get("/actuator/prometheus")
            .andReturn()
            .response.contentAsString

    private fun hotelOf(date: LocalDate): HotelApi {
        val hotel = mockk<HotelApi>()
        every { hotel.currentBusinessDate() } returns date
        every { hotel.operationalSettings().endOfDayTime } returns DUE_TIME
        every { hotel.timezone() } returns ZoneOffset.UTC
        return hotel
    }

    @Test
    fun `a clean run of four steps adds exactly one duration sample and no failure`() {
        settled {
            val before = totalCount()
            val failuresBefore = failures()

            engine.execute(created().id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            totalCount() shouldBe before + 1
            failures() shouldBe failuresBefore
        }
    }

    @Test
    fun `a failure in the middle of the run is resumed false and counts one failure`() {
        settled {
            probe.failNext(THREE, 1)
            val before = count("failed", "false")
            val failuresBefore = failures()

            engine.execute(created().id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            count("failed", "false") shouldBe before + 1
            failures() shouldBe failuresBefore + 1
        }
    }

    @Test
    fun `a resumed run that fails again is measured failed with resumed true and counts a second failure`() {
        settled {
            probe.failNext(THREE, 2)
            val run = created()
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            val failedResumedBefore = count("failed", "true")
            val failuresBefore = failures()
            resume(run)

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            count("failed", "true") shouldBe failedResumedBefore + 1
            failures() shouldBe failuresBefore + 1
        }
    }

    @Test
    fun `a failure of the first step then a resume is measured resumed true by the documented inference`() {
        settled {
            probe.failNext(ONE, 1)
            val run = created()
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            val before = count("completed", "true")
            resume(run)

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            count("completed", "true") shouldBe before + 1
        }
    }

    @Test
    fun `calling execute on a failed run that was not resumed measures nothing and counts no failure`() {
        settled {
            probe.failNext(THREE, 1)
            val run = created()
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            val samples = totalCount()
            val failuresBefore = failures()

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            totalCount() shouldBe samples
            failures() shouldBe failuresBefore
        }
    }

    @Test
    fun `a call that gets Busy because another process holds the lock measures nothing`() {
        settled {
            val run = created()
            val samples = totalCount()
            val failuresBefore = failures()
            val held =
                lockProvider
                    .lock(
                        LockConfiguration(
                            ClockProvider.now(),
                            EndOfDayEngine.LOCK_NAME,
                            Duration.ofMinutes(1),
                            Duration.ZERO,
                        ),
                    ).orElseThrow()
            try {
                engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy
            } finally {
                held.unlock()
            }

            totalCount() shouldBe samples
            failures() shouldBe failuresBefore
        }
    }

    @Test
    fun `the overdue gauge grows with the clock between refreshes and reads the exact time since the due time`() {
        settled {
            moveClockTo(dueAtOfCurrentDate().plus(Duration.ofHours(2)))
            metrics.refresh()
            gauge("zeny.endofday.overdue.seconds") shouldBe TWO_HOURS_SECONDS

            clock.advance(Duration.ofHours(1))

            gauge("zeny.endofday.overdue.seconds") shouldBe THREE_HOURS_SECONDS
        }
    }

    @Test
    fun `a failed run on the current date does not stop the overdue gauge`() {
        settled {
            probe.failNext(THREE, 1)
            engine.execute(created().id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            moveClockTo(dueAtOfCurrentDate().plus(Duration.ofHours(2)))

            metrics.refresh()

            gauge("zeny.endofday.overdue.seconds") shouldBeGreaterThanOrEqualTo TWO_HOURS_SECONDS
        }
    }

    @Test
    fun `closing the date brings the overdue gauge back to zero after the refresh`() {
        settled {
            val run = created()
            moveClockTo(dueAtOfCurrentDate().plus(Duration.ofHours(2)))
            metrics.refresh()
            gauge("zeny.endofday.overdue.seconds") shouldBeGreaterThanOrEqualTo TWO_HOURS_SECONDS

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            metrics.refresh()

            gauge("zeny.endofday.overdue.seconds") shouldBe 0.0
        }
    }

    @Test
    fun `the overdue gauge at exactly the due time is zero`() {
        settled {
            moveClockTo(dueAtOfCurrentDate())

            metrics.refresh()

            gauge("zeny.endofday.overdue.seconds") shouldBe 0.0
        }
    }

    @Test
    fun `a completed date past its due time reads zero overdue`() {
        val date = LocalDate.of(2030, 1, 10)
        val due = EndOfDaySchedule.dueAt(date, DUE_TIME, ZoneOffset.UTC)
        val closed = mockk<EndOfDayRun> { every { status } returns RunStatus.COMPLETED }
        val repository =
            mockk<EndOfDayRunRepository> {
                every { countByStatus(RunStatus.FAILED) } returns 0
                every { findByBusinessDate(date) } returns closed
            }
        val binder =
            EndOfDayMetrics(hotelOf(date), repository, registry, Clock.fixed(due.plusSeconds(5 * 3600), ZoneOffset.UTC))
        val simple = SimpleMeterRegistry().also { binder.bindTo(it) }

        binder.refresh()

        simple.get("zeny.endofday.overdue.seconds").gauge().value() shouldBe 0.0
    }

    @Test
    fun `an open date reads the elapsed seconds and the failed runs count`() {
        val date = LocalDate.of(2030, 1, 10)
        val due = EndOfDaySchedule.dueAt(date, DUE_TIME, ZoneOffset.UTC)
        val repository =
            mockk<EndOfDayRunRepository> {
                every { countByStatus(RunStatus.FAILED) } returns 2
                every { findByBusinessDate(date) } returns null
            }
        val binder =
            EndOfDayMetrics(hotelOf(date), repository, registry, Clock.fixed(due.plusSeconds(5400), ZoneOffset.UTC))
        val simple = SimpleMeterRegistry().also { binder.bindTo(it) }

        binder.refresh()

        simple.get("zeny.endofday.overdue.seconds").gauge().value() shouldBe 5400.0
        simple.get("zeny.endofday.runs.failed").gauge().value() shouldBe 2.0
    }

    @Test
    fun `the gauges are NaN when only the hotel read fails and recover on the next good refresh`() {
        val date = LocalDate.of(2030, 1, 10)
        val hotel = mockk<HotelApi>()
        every { hotel.currentBusinessDate() } throws QueryTimeoutException("sem banco") andThen date
        every { hotel.operationalSettings().endOfDayTime } returns DUE_TIME
        every { hotel.timezone() } returns ZoneOffset.UTC
        val repository =
            mockk<EndOfDayRunRepository> {
                every { countByStatus(RunStatus.FAILED) } returns 1
                every { findByBusinessDate(date) } returns null
            }
        val now = EndOfDaySchedule.dueAt(date, DUE_TIME, ZoneOffset.UTC).minusSeconds(1)
        val binder = EndOfDayMetrics(hotel, repository, registry, Clock.fixed(now, ZoneOffset.UTC))
        val simple = SimpleMeterRegistry().also { binder.bindTo(it) }

        binder.refresh()
        simple
            .get("zeny.endofday.overdue.seconds")
            .gauge()
            .value()
            .shouldBeNaN()
        simple
            .get("zeny.endofday.runs.failed")
            .gauge()
            .value()
            .shouldBeNaN()

        binder.refresh()
        simple.get("zeny.endofday.overdue.seconds").gauge().value() shouldBe 0.0
        simple.get("zeny.endofday.runs.failed").gauge().value() shouldBe 1.0
    }

    @Test
    fun `before any refresh the gauges are NaN and never a fake zero`() {
        val binder = EndOfDayMetrics(mockk<HotelApi>(), mockk<EndOfDayRunRepository>(), registry, Clock.systemUTC())
        val simple = SimpleMeterRegistry().also { binder.bindTo(it) }

        simple
            .get("zeny.endofday.overdue.seconds")
            .gauge()
            .value()
            .shouldBeNaN()
        simple
            .get("zeny.endofday.runs.failed")
            .gauge()
            .value()
            .shouldBeNaN()
    }

    @Test
    fun `the exposed series use only closed tag values and carry no personal data`() {
        settled {
            probe.failNext(THREE, 1)
            val run = created()
            engine.execute(run.id, WAIT)
            resume(run)
            engine.execute(run.id, WAIT)
            metrics.refresh()

            val lines = scrape().lines().filter { it.startsWith("zeny_endofday_") }
            lines.shouldNotBeEmpty()
            val labelPattern = Regex("""(\w+)="([^"]*)"""")
            val closedValues =
                mapOf(
                    "application" to null,
                    "outcome" to setOf("completed", "failed"),
                    "resumed" to setOf("true", "false"),
                )
            val violations =
                lines.flatMap { line ->
                    labelPattern.findAll(line.substringAfter('{').substringBefore('}')).mapNotNull {
                        val (name, value) = it.destructured
                        when {
                            name !in closedValues -> "$name=$value"
                            closedValues.getValue(name)?.contains(value) == false -> "$name=$value"
                            else -> null
                        }
                    }
                }
            violations.shouldBeEmpty()
        }
    }

    @Test
    fun `every zeny_endofday series an alert rule reads is exposed by the scrape`() {
        settled {
            probe.failNext(THREE, 1)
            engine.execute(created().id, WAIT)
            metrics.refresh()
            val exposed = scrape().lines().map { it.substringBefore('{').substringBefore(' ') }.toSet()

            val rules = Files.readString(Path.of("docker/prometheus/alerts.yaml"))
            val names = Regex("""zeny_endofday_[a-z_]+""").findAll(rules).map { it.value }.toSet()

            names.shouldNotBeEmpty()
            names.filter { it !in exposed }.shouldBeEmpty()
        }
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
        val DUE_TIME: LocalTime = LocalTime.of(3, 0)
        const val TWO_HOURS_SECONDS = 7200.0
        const val THREE_HOURS_SECONDS = 10800.0
    }
}
