package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch

/**
 * O gatilho automático só dispara o motor e volta (M6 E11, Task 17; decisão H12, item 4): espera a execução no máximo
 * `zeny.endofday.trigger.wait` (`PT1S` nos testes do banco isolado) e, passado isso, devolve `STARTED`/`RESUMED` com o
 * motor seguindo na thread dele, sob a trava `end-of-day-run`. A rodada seguinte acha a execução `RUNNING` e não a
 * duplica. No banco isolado, com as etapas falsas.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
class EndOfDayTriggerWaitTest(
    @Autowired private val trigger: EndOfDayTrigger,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val context: ApplicationContext,
) {
    private fun setClock(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun status(date: LocalDate): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(String::class.java)
            .single()

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    @Test
    @Timeout(60)
    fun `the trigger returns while the run goes on and the next tick does not duplicate it`() {
        val originalClock = clock.instant()
        val gate = CountDownLatch(1)
        try {
            probe.reset()
            val date = hotelApi.currentBusinessDate()
            setClock(EndOfDaySchedule.dueAt(date, hotelApi.operationalSettings().endOfDayTime, hotelApi.timezone()))
            probe.holdOn(TWO, gate)

            val begin = System.nanoTime()
            trigger.tick() shouldBe TickOutcome.STARTED
            val elapsed = Duration.ofNanos(System.nanoTime() - begin)

            // Bem abaixo dos 30 s do RED (o tick que esperava a execução inteira) e folgado para máquina carregada.
            withClue("o tick esperou $elapsed") { elapsed shouldBeLessThan Duration.ofSeconds(10) }
            status(date) shouldBe "RUNNING"

            trigger.tick() shouldBeIn listOf(TickOutcome.BUSY, TickOutcome.RESUMED)
            probe.invocations(ONE, date) shouldBe 1

            gate.countDown()
            EndOfDayTestRuns.awaitEngine(context)

            status(date) shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        } finally {
            gate.countDown()
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                setClock(originalClock)
            }
        }
    }
}
