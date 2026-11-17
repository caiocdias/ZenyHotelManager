package com.zeny.hotel.shared

import com.zeny.hotel.shared.infrastructure.idempotency.IdempotencyKeyPurgeJob
import com.zeny.hotel.shared.infrastructure.scheduling.JobErrorLoggingTaskScheduler
import com.zeny.hotel.testsupport.AsyncListenerProbe
import com.zeny.hotel.testsupport.AsyncProbeEvent
import com.zeny.hotel.testsupport.FailingLockProvider
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.SchedulerThreadProbe
import com.zeny.hotel.testsupport.TransactionAndLockProbesConfiguration
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.support.ScheduledMethodRunnable
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Log das exceções dos jobs (M6 E18, ARCHITECTURE §13 e §16): toda exceção que escapa de uma tarefa do agendador,
 * inclusive a da trava do ShedLock antes do método, sai em `ERROR` com o nome do job e só as classes da exceção e das
 * causas, sem mensagem, pilha nem suprimidas. Os ouvintes assíncronos continuam no `applicationTaskExecutor`.
 */
@IntegrationTest
@Import(TransactionAndLockProbesConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class ScheduledJobErrorLogTest(
    @Autowired @Qualifier("taskScheduler") private val scheduler: ThreadPoolTaskScheduler,
    @Autowired private val locks: FailingLockProvider,
    @Autowired private val context: ApplicationContext,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val listener: AsyncListenerProbe,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `a failing lock is logged with the job name and only the exception classes`(output: CapturedOutput) {
        val job = scheduledRunnable(IDEMPOTENCY_PURGE)
        val failuresBefore = locks.failures
        locks.failFor(IdempotencyKeyPurgeJob.LOCK_NAME) { sentinelFailure() }
        val future = scheduler.scheduleWithFixedDelay(job, Duration.ofDays(1))
        try {
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until {
                locks.failures > failuresBefore &&
                    output.all.contains(DataAccessResourceFailureException::class.java.name)
            }
        } finally {
            future.cancel(false)
            locks.disarm()
        }

        output.all shouldContain
            "Job $IDEMPOTENCY_PURGE falhou: ${DataAccessResourceFailureException::class.java.name} <- " +
            SQLException::class.java.name
        output.all shouldNotContain SENTINEL
    }

    @Test
    fun `a failure in the job body is logged in the same format, even with a cycle of causes`(output: CapturedOutput) {
        val probe = FailingBodyJob()
        val job = ScheduledMethodRunnable(probe, FailingBodyJob::class.java.getMethod("run"))

        runCatching { scheduler.schedule(job, Instant.now()).get(WAIT_SECONDS, TimeUnit.SECONDS) }

        output.all shouldContain
            "Job ${FailingBodyJob::class.java.name}.run falhou: ${IllegalStateException::class.java.name} <- " +
            "${SQLException::class.java.name}${System.lineSeparator()}"
        output.all shouldNotContain SENTINEL
    }

    @Test
    fun `an Error from a job is logged by class only, like an exception`(output: CapturedOutput) {
        val job = ThrowingErrorJob()

        runCatching { scheduler.schedule(job, Instant.now()).get(WAIT_SECONDS, TimeUnit.SECONDS) }

        output.all shouldContain
            "Job ${ThrowingErrorJob.NAME} falhou: ${AssertionError::class.java.name} <- " +
            "${SQLException::class.java.name}${System.lineSeparator()}"
        output.all shouldNotContain SENTINEL
    }

    @Test
    fun `a job whose toString throws is logged under its class name, without the toString failure`(
        output: CapturedOutput,
    ) {
        val job = UnnamableJob()

        runCatching { scheduler.schedule(job, Instant.now()).get(WAIT_SECONDS, TimeUnit.SECONDS) }

        output.all shouldContain
            "Job ${UnnamableJob::class.java.name} falhou: ${IllegalStateException::class.java.name}" +
            System.lineSeparator()
        output.all shouldNotContain SENTINEL
    }

    @Test
    fun `the task scheduler keeps the spring task scheduling settings of the Boot builder`() {
        scheduler.threadNamePrefix shouldBe "scheduling-"
    }

    @Test
    fun `an async listener keeps running on the application executor, outside the job scheduler`(
        output: CapturedOutput,
    ) {
        val marker = "async-${UUID.randomUUID()}"

        transactions.executeWithoutResult { context.publishEvent(AsyncProbeEvent(marker)) }

        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { completedPublicationsWith(marker) == 1 }
        val thread = listener.threadOf(marker).shouldNotBeNull()
        val executor = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)
        thread shouldStartWith executor.threadNamePrefix
        thread shouldNotStartWith scheduler.threadNamePrefix
        output.all shouldNotContain "Job ${AsyncListenerProbe::class.java.name}"
    }

    @Test
    fun `the scheduled methods run on the single task scheduler of the context`() {
        val probe = context.getBean(SchedulerThreadProbe::class.java)

        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { probe.thread != null }

        val schedulers = context.getBeansOfType(TaskScheduler::class.java)
        schedulers.keys shouldBe setOf("taskScheduler")
        schedulers.values.single().shouldBeInstanceOf<JobErrorLoggingTaskScheduler>()
        probe.thread.shouldNotBeNull() shouldStartWith scheduler.threadNamePrefix
    }

    private fun scheduledRunnable(name: String): Runnable =
        context
            .getBeansOfType(ScheduledTaskHolder::class.java)
            .values
            .flatMap { it.scheduledTasks }
            .map { it.task.runnable }
            .single { it.toString() == name }

    private fun completedPublicationsWith(marker: String): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication " +
                    "WHERE completion_date IS NOT NULL AND serialized_event LIKE :marker",
            ).param("marker", "%$marker%")
            .query(Int::class.java)
            .single()

    /** Um corpo de job que falha com o sentinela na mensagem, na causa e numa suprimida, e com as causas em ciclo. */
    class FailingBodyJob {
        fun run() {
            val cause = SQLException("causa $SENTINEL")
            val failure = IllegalStateException("corpo $SENTINEL", cause)
            cause.initCause(failure)
            failure.addSuppressed(RuntimeException("suprimida $SENTINEL"))
            throw failure
        }
    }

    /** Um job que falha com um `Error` (não uma `Exception`), com o sentinela na mensagem e na causa. */
    class ThrowingErrorJob : Runnable {
        override fun run(): Unit = throw AssertionError("erro $SENTINEL", SQLException("causa $SENTINEL"))

        override fun toString() = NAME

        companion object {
            const val NAME = "ThrowingErrorJob.run"
        }
    }

    /** Um job cujo `toString()` também falha, com o sentinela: o nome logado cai na classe. */
    class UnnamableJob : Runnable {
        override fun run(): Unit = error("corpo $SENTINEL")

        @Suppress("ExceptionRaisedInUnexpectedLocation") // o toString() que lança é o cenário do teste
        override fun toString(): String = error("toString $SENTINEL")
    }

    private companion object {
        const val SENTINEL = "SENTINELA-e18-job-log"
        const val WAIT_SECONDS = 20L
        val IDEMPOTENCY_PURGE = "${IdempotencyKeyPurgeJob::class.java.name}.purgeExpiredKeys"

        fun sentinelFailure(): RuntimeException =
            DataAccessResourceFailureException(
                "banco fora: $SENTINEL",
                SQLException("Connection refused $SENTINEL"),
            ).apply { addSuppressed(RuntimeException("suprimida $SENTINEL")) }
    }
}
