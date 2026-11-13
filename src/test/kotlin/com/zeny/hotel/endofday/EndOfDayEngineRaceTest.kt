package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunMetrics
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.Executors

/**
 * As corridas perdidas do motor (M6 E10, Task 5), que o banco não reproduz de forma determinística: outro executor
 * passou à frente na etapa depois de uma trava vencida. O motor devolve [EndOfDayExecution.Busy], sem pendência nem
 * exceção, e para o laço.
 */
class EndOfDayEngineRaceTest {
    private val run = EndOfDayRun.start(DATE, RunTrigger.AUTOMATIC, null, listOf(StepSlot(STEP, 10)), Instant.EPOCH)
    private val runs = mockk<EndOfDayRunRepository> { every { findById(run.id.value) } returns Optional.of(run) }
    private val transitions =
        mockk<EndOfDayRunTransitions> { every { begin(run.id) } returns StepStart(STEP, 1) }
    private val executor = Executors.newSingleThreadExecutor()
    private val engine =
        EndOfDayEngine(
            runs,
            transitions,
            EndOfDayStepRegistry(listOf(Step)),
            DefaultLockingTaskExecutor { _ -> Optional.of(SimpleLock {}) },
            executor,
            Duration.ofMinutes(5),
            EndOfDayRunMetrics(SimpleMeterRegistry()),
        )

    private object Step : EndOfDayStepHandler {
        override val name = STEP
        override val position = 10

        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    @AfterEach
    fun close() = executor.close()

    @Test
    fun `a complete overtaken by another executor stops the loop with Busy`() {
        every { transitions.complete(run.id, STEP, 1, any()) } returns false

        engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

        verify(exactly = 1) { transitions.begin(run.id) }
        verify(exactly = 0) { transitions.fail(any(), any(), any(), any()) }
    }

    @Test
    fun `a failure whose step is running in another attempt is Busy`() {
        every { transitions.complete(run.id, STEP, 1, any()) } throws IllegalArgumentException("Fulano")
        every { transitions.fail(run.id, STEP, 1, "IllegalArgumentException") } returns false

        engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

        verify(exactly = 1) { transitions.begin(run.id) }
    }

    @Test
    fun `a failure whose step another executor already finished is Busy, not an error`() {
        every { transitions.complete(run.id, STEP, 1, any()) } throws IllegalArgumentException("Fulano")
        every { transitions.fail(run.id, STEP, 1, any()) } throws IllegalStateException("A etapa não está RUNNING")

        engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

        verify(exactly = 1) { transitions.begin(run.id) }
    }

    private companion object {
        const val STEP = "fake-one"
        val DATE: LocalDate = LocalDate.of(2026, 1, 1)
        val WAIT: Duration = Duration.ofSeconds(10)
    }
}
