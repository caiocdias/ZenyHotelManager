package com.zeny.hotel.endofday

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
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.dao.QueryTimeoutException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * As métricas do fechamento (M6 E10, Task 13): duração por chamada que termina a execução, falhas, execuções `FAILED`
 * e atraso. As asserções olham o aumento (antes e depois), nunca o valor absoluto: o banco isolado acumula execuções
 * `FAILED` de outros testes.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, os apoios de banco, relógio e métricas
class EndOfDayMetricsTest(
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

    private fun durationCount(
        outcome: String,
        resumed: String,
    ): Long =
        meters
            .find("zeny.endofday.run.duration")
            .tags("outcome", outcome, "resumed", resumed)
            .timer()
            ?.count() ?: 0

    private fun failures(): Double = meters.find("zeny.endofday.run.failures").counter()?.count() ?: 0.0

    private fun gauge(name: String): Double = meters.get(name).gauge().value()

    private fun dueAtOfCurrentDate() =
        EndOfDaySchedule.dueAt(
            hotelApi.currentBusinessDate(),
            hotelApi.operationalSettings().endOfDayTime,
            hotelApi.timezone(),
        )

    @Test
    fun `a completed run adds to the completed duration count`() {
        settled {
            val before = durationCount("completed", "false")
            val failuresBefore = failures()

            engine.execute(created().id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            durationCount("completed", "false") shouldBe before + 1
            failures() shouldBe failuresBefore
        }
    }

    @Test
    fun `a failed run adds to the failures, the failed duration and the failed runs gauge`() {
        settled {
            probe.failNext(THREE, 1)
            val before = failures()
            val failedBefore = durationCount("failed", "false")

            engine.execute(created().id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            metrics.refresh()

            failures() shouldBe before + 1
            durationCount("failed", "false") shouldBe failedBefore + 1
            gauge("zeny.endofday.runs.failed") shouldBeGreaterThanOrEqualTo 1.0
        }
    }

    @Test
    fun `finishing a resumed run is measured with resumed true`() {
        settled {
            probe.failNext(THREE, 1)
            val run = created()
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            val before = durationCount("completed", "true")
            tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run.id)).resume(clock.instant()) }

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            durationCount("completed", "true") shouldBe before + 1
        }
    }

    @Test
    fun `a call on an already finished run is not measured`() {
        settled {
            val run = created()
            engine.execute(run.id, WAIT)
            val before = durationCount("completed", "false") + durationCount("completed", "true")

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            durationCount("completed", "false") + durationCount("completed", "true") shouldBe before
        }
    }

    @Test
    fun `two hours after the due time without a run the overdue gauge is at least two hours`() {
        settled {
            clock.advance(Duration.between(clock.instant(), dueAtOfCurrentDate().plus(Duration.ofHours(2))))

            metrics.refresh()

            gauge("zeny.endofday.overdue.seconds") shouldBeGreaterThanOrEqualTo TWO_HOURS_SECONDS
        }
    }

    @Test
    fun `before the due time the overdue gauge is zero`() {
        settled {
            clock.advance(Duration.between(clock.instant(), dueAtOfCurrentDate().minusSeconds(1)))

            metrics.refresh()

            gauge("zeny.endofday.overdue.seconds") shouldBe 0.0
        }
    }

    @Test
    fun `the prometheus endpoint exposes the end of day metrics`() {
        settled {
            probe.failNext(THREE, 1)
            engine.execute(created().id, WAIT)
            metrics.refresh()

            val body =
                mockMvc
                    .get("/actuator/prometheus")
                    .andReturn()
                    .response.contentAsString

            body shouldContain "zeny_endofday_run_duration_seconds_count{"
            body shouldContain "zeny_endofday_run_failures_total{"
            body shouldContain "zeny_endofday_runs_failed{"
            body shouldContain "zeny_endofday_overdue_seconds{"
        }
    }

    @Test
    fun `with no registered step the binder reports no delay and never reads the hotel`() {
        val hotel = mockk<HotelApi>()
        val repository = mockk<EndOfDayRunRepository> { every { countByStatus(RunStatus.FAILED) } returns 0 }
        val binder = EndOfDayMetrics(hotel, repository, EndOfDayStepRegistry(emptyList()), Clock.systemUTC())
        val simple = SimpleMeterRegistry().also { binder.bindTo(it) }

        binder.refresh()

        simple.get("zeny.endofday.overdue.seconds").gauge().value() shouldBe 0.0
        simple.get("zeny.endofday.runs.failed").gauge().value() shouldBe 0.0
        verify(exactly = 0) { hotel.currentBusinessDate() }
    }

    @Test
    fun `when the database does not answer the gauges are NaN`() {
        val hotel = mockk<HotelApi>()
        val repository = mockk<EndOfDayRunRepository>()
        every { repository.countByStatus(RunStatus.FAILED) } throws QueryTimeoutException("sem banco")
        val binder = EndOfDayMetrics(hotel, repository, registry, Clock.systemUTC())
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
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
        const val TWO_HOURS_SECONDS = 7200.0
    }
}
