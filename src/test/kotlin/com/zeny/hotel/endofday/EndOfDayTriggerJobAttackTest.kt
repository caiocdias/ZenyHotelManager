package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayTriggerJob
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * Ataques ao job do gatilho do fechamento (M6 E10, Task 12): a falha da rodada não vaza a mensagem (nem a da causa) em
 * nenhum nível de log, mesmo com o logger em `TRACE` (o nível efetivo do logger depende do último contexto que subiu
 * na JVM, então o teste o fixa); e o job é agendado pelas propriedades e travado pelo ShedLock `end-of-day-trigger`.
 */
class EndOfDayTriggerJobAttackTest {
    private val trigger = mockk<EndOfDayTrigger>()
    private val job = EndOfDayTriggerJob(trigger)

    private fun loggedAtTrace(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EndOfDayTriggerJob::class.java) as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        return appender.list
    }

    @Test
    fun `a failed round leaks neither its message nor its cause at any log level`() {
        every { trigger.tick() } throws
            IllegalStateException("hóspede Maria", RuntimeException("CPF 123.456.789-09 na causa"))

        val events = loggedAtTrace { job.triggerEndOfDay() }

        events.map { it.level } shouldContainExactly listOf(Level.ERROR)
        events.forEach { event ->
            event.throwableProxy.shouldBeNull()
            val texts = listOf(event.formattedMessage) + event.argumentArray.orEmpty().map { it.toString() }
            texts.forEach {
                it shouldNotContain "Maria"
                it shouldNotContain "123.456.789-09"
            }
        }
    }

    @Test
    fun `a domain conflict escaping the tick is logged by its class only`() {
        every { trigger.tick() } throws EndOfDayRunInProgressException()

        val events = loggedAtTrace { job.triggerEndOfDay() }

        events.map { it.formattedMessage } shouldContainExactly
            listOf("Gatilho do fechamento: a rodada falhou (${EndOfDayRunInProgressException::class.java.name})")
        events.single().throwableProxy.shouldBeNull()
    }

    @Test
    fun `the job is scheduled by the trigger properties under the end-of-day-trigger lock`() {
        val method = EndOfDayTriggerJob::class.java.getMethod("triggerEndOfDay")

        method.returnType shouldBe Void.TYPE
        val scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled::class.java).shouldNotBeNull()
        scheduled.fixedDelayString shouldBe "\${zeny.endofday.trigger.interval}"
        scheduled.initialDelayString shouldBe "\${zeny.endofday.trigger.initial-delay}"
        scheduled.cron shouldBe ""
        val lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock::class.java).shouldNotBeNull()
        lock.name shouldBe "end-of-day-trigger"
        lock.lockAtMostFor shouldBe "PT10M"
        EndOfDayTriggerJob.LOCK_NAME shouldBe "end-of-day-trigger"
        AnnotatedElementUtils.findMergedAnnotation(method, Transactional::class.java).shouldBeNull()
        AnnotatedElementUtils
            .findMergedAnnotation(EndOfDayTriggerJob::class.java, Transactional::class.java)
            .shouldBeNull()
    }
}
