package com.zeny.hotel.shared.infrastructure.scheduling

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture

/**
 * O agendador de todos os `@Scheduled` (ARCHITECTURE §13): um [ThreadPoolTaskScheduler] em que toda exceção que escapa
 * de uma tarefa, inclusive a da trava do ShedLock com o banco fora, que acontece antes do método, sai em `ERROR` como
 * `Job <Classe.método> falhou: <Classe>[ <- <Causa>…]`. Só o nome do job e as classes da exceção e das causas: sem a
 * mensagem, que pode trazer valores do banco (§16), sem *stack trace* e sem as suprimidas. Um ciclo de causas para na
 * primeira repetida. Vale para todo job, sem código por job.
 *
 * - **O nome logado é o `toString()` do `Runnable`.** Para um `@Scheduled`, o Spring dá `<Classe>.<método>`; uma tarefa
 *   avulsa sai com o `toString()` dela (o da classe, se ele não for sobrescrito ou lançar).
 * - **Um `Future` avulso tem a exceção logada e engolida:** `execute`, `submit(Runnable)` e os `schedule…` passam pelo
 *   mesmo tratamento, então o `Future` de uma tarefa que falhou termina sem erro. Quem precisa do resultado ou da falha
 *   usa `submit(Callable)`, que não passa por aqui. Um job repetido segue no próximo horário, como no padrão do Spring.
 */
class JobErrorLoggingTaskScheduler : ThreadPoolTaskScheduler() {
    override fun execute(task: Runnable) = super.execute(JobErrorLogging(task))

    override fun submit(task: Runnable): Future<*> = super.submit(JobErrorLogging(task))

    override fun schedule(
        task: Runnable,
        trigger: Trigger,
    ): ScheduledFuture<*>? = super.schedule(JobErrorLogging(task), trigger)

    override fun schedule(
        task: Runnable,
        startTime: Instant,
    ): ScheduledFuture<*> = super.schedule(JobErrorLogging(task), startTime)

    override fun scheduleAtFixedRate(
        task: Runnable,
        startTime: Instant,
        period: Duration,
    ): ScheduledFuture<*> = super.scheduleAtFixedRate(JobErrorLogging(task), startTime, period)

    override fun scheduleAtFixedRate(
        task: Runnable,
        period: Duration,
    ): ScheduledFuture<*> = super.scheduleAtFixedRate(JobErrorLogging(task), period)

    override fun scheduleWithFixedDelay(
        task: Runnable,
        startTime: Instant,
        delay: Duration,
    ): ScheduledFuture<*> = super.scheduleWithFixedDelay(JobErrorLogging(task), startTime, delay)

    override fun scheduleWithFixedDelay(
        task: Runnable,
        delay: Duration,
    ): ScheduledFuture<*> = super.scheduleWithFixedDelay(JobErrorLogging(task), delay)
}

/** A tarefa [job] com a falha logada só pelas classes, e engolida. O `toString()` é o do [job]. */
internal class JobErrorLogging(
    private val job: Runnable,
) : Runnable {
    @Suppress("TooGenericExceptionCaught") // toda falha do job, inclusive Error, sai pelo log sem a mensagem
    override fun run() {
        try {
            job.run()
        } catch (failure: Throwable) {
            log.error("Job {} falhou: {}", nameOf(job), classChain(failure))
        }
    }

    override fun toString(): String = job.toString()

    private companion object {
        val log: Logger = LoggerFactory.getLogger(JobErrorLoggingTaskScheduler::class.java)

        @Suppress("TooGenericExceptionCaught") // um toString() que lança não pode esconder a falha do job
        fun nameOf(job: Runnable): String =
            try {
                job.toString()
            } catch (_: Throwable) {
                job.javaClass.name
            }

        /** `A <- B <- C`: a classe da falha e das causas, parando na primeira causa repetida (ciclo). */
        fun classChain(failure: Throwable): String {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            return generateSequence(failure) { it.cause }
                .takeWhile { seen.add(it) }
                .joinToString(" <- ") { it.javaClass.name }
        }
    }
}
