package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.endofday.infrastructure.EndOfDayTriggerJob
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * O job do gatilho do fechamento (M6 E10, Task 12): uma falha da rodada fica no log só pelo nome da classe e não
 * escapa para o agendador (que logaria a mensagem); só um erro da máquina virtual que deixa o processo instável escapa.
 */
class EndOfDayTriggerJobTest {
    private val trigger = mockk<EndOfDayTrigger>()
    private val job = EndOfDayTriggerJob(trigger)

    private fun logged(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EndOfDayTriggerJob::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    @Test
    fun `a failed round is logged by the class name only and does not escape`() {
        every { trigger.tick() } throws IllegalStateException("CPF 123.456.789-09 no texto")

        val events = logged { job.triggerEndOfDay() }

        events.map { it.level } shouldContainExactly listOf(Level.ERROR)
        events.single().formattedMessage shouldNotContain "123.456.789-09"
        events.single().formattedMessage shouldNotContain "CPF"
        events.single().throwableProxy shouldBe null
        events.single().formattedMessage.contains(IllegalStateException::class.java.name) shouldBe true
    }

    @Test
    fun `a stack overflow is a failed round`() {
        every { trigger.tick() } throws StackOverflowError("recursão")

        logged { job.triggerEndOfDay() }.map { it.level } shouldContainExactly listOf(Level.ERROR)
    }

    @Test
    fun `an error that leaves the process unstable escapes`() {
        every { trigger.tick() } throws OutOfMemoryError("heap")

        shouldThrow<OutOfMemoryError> { job.triggerEndOfDay() }
    }

    @Test
    fun `a started or resumed run is logged, the other outcomes are not`() {
        TickOutcome.entries.forEach { outcome ->
            every { trigger.tick() } returns outcome

            val events = logged { job.triggerEndOfDay() }

            if (outcome == TickOutcome.STARTED || outcome == TickOutcome.RESUMED) {
                events.map { it.formattedMessage } shouldContainExactly listOf("Gatilho do fechamento: $outcome")
            } else {
                events.shouldBeEmpty()
            }
        }
    }
}
