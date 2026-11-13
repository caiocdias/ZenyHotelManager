package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** O que [EndOfDayEngine.execute] devolve. */
sealed interface EndOfDayExecution {
    /** A execução terminou, agora ou antes, no [status] (`COMPLETED` ou `FAILED`). */
    data class Finished(
        val status: RunStatus,
    ) : EndOfDayExecution

    /** Outro executor tem a trava ou passou à frente nesta execução; nada foi feito por esta chamada. */
    data object Busy : EndOfDayExecution

    /** O prazo de espera passou e a execução continua na thread do motor. */
    data object StillRunning : EndOfDayExecution
}

/**
 * O motor do fechamento do dia (M6 E10; RN-21): roda as etapas de uma execução, em ordem, sob a trava do ShedLock
 * `end-of-day-run` (com *keep-alive*), na thread única `end-of-day` (sem contexto de segurança: tudo o que as etapas
 * fazem é do ator sistema). Age só sobre a execução recebida.
 *
 * Sob a trava: execução `COMPLETED` ou `FAILED` devolve [EndOfDayExecution.Finished] sem rodar nada (retomar uma
 * `FAILED` é o `resume` do domínio, feito por quem retoma antes de chamar [execute]); `RUNNING` (nova ou interrompida
 * por uma queda) roda o laço [EndOfDayRunTransitions.begin] → [EndOfDayRunTransitions.complete] até não sobrar etapa.
 * A exceção ou o erro de uma etapa a falha ([EndOfDayRunTransitions.fail]) pelo código, nunca pela mensagem, e a
 * execução para `FAILED` à espera da retomada manual; só um erro da máquina virtual que deixa o processo instável
 * (`OutOfMemoryError`, `InternalError`) escapa e deixa a execução `RUNNING`. Uma etapa que saiu do registro é pulada
 * pela reconciliação do `begin`.
 *
 * O executor tem uma thread só: um segundo [execute] no mesmo processo espera na fila (e encontra a execução já
 * terminada) em vez de receber [EndOfDayExecution.Busy]. `Busy` vem só de outro processo segurando a trava, ou de
 * outro executor que passou à frente depois de uma trava vencida (a transição devolve que a etapa já não está na
 * tentativa deste executor).
 */
@Component
class EndOfDayEngine(
    private val runs: EndOfDayRunRepository,
    private val transitions: EndOfDayRunTransitions,
    private val registry: EndOfDayStepRegistry,
    @Qualifier("endOfDayLockingTaskExecutor") private val locking: LockingTaskExecutor,
    @Qualifier("endOfDayExecutor") private val executor: ExecutorService,
    @Value("\${zeny.endofday.lock-at-most-for}") private val lockAtMostFor: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Roda a execução [runId] na thread do motor e espera até [waitAtMost]: passou o prazo, devolve
     * [EndOfDayExecution.StillRunning] e o trabalho continua. Um erro inesperado (fora das etapas, como o banco fora
     * do ar) escapa daqui e deixa a execução como estava.
     */
    fun execute(
        runId: EndOfDayRunId,
        waitAtMost: Duration,
    ): EndOfDayExecution {
        val work = executor.submit<EndOfDayExecution> { loggingUnhandled(runId) { executeWithLock(runId) } }
        return try {
            work.get(waitAtMost.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            EndOfDayExecution.StillRunning
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Registra uma falha fora das etapas (banco fora do ar, trava recusada, erro da máquina virtual) antes de deixá-la
     * escapar: depois de um [EndOfDayExecution.StillRunning], ninguém mais lê o resultado. Pelo nome da classe, nunca
     * pela mensagem; o *stack trace* só em `DEBUG`. A falha de uma etapa já tratada não passa por aqui.
     */
    private fun loggingUnhandled(
        runId: EndOfDayRunId,
        work: () -> EndOfDayExecution,
    ): EndOfDayExecution =
        try {
            work()
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable, // registra e relança, sem engolir
        ) {
            log.error("Fechamento {}: falhou fora de uma etapa ({})", runId.value, error.javaClass.name)
            log.debug("Fechamento {}: falha fora de uma etapa", runId.value, error)
            throw error
        }

    private fun executeWithLock(runId: EndOfDayRunId): EndOfDayExecution {
        val lock = LockConfiguration(ClockProvider.now(), LOCK_NAME, lockAtMostFor, Duration.ZERO)
        val result = locking.executeWithLock(LockingTaskExecutor.TaskWithResult { runUnderLock(runId) }, lock)
        if (!result.wasExecuted()) {
            log.info("Fechamento {}: a trava {} está com outro executor", runId.value, LOCK_NAME)
            return EndOfDayExecution.Busy
        }
        return checkNotNull(result.result)
    }

    private fun runUnderLock(runId: EndOfDayRunId): EndOfDayExecution {
        val status = runs.findById(runId.value).orElse(null)?.status
        checkNotNull(status) { "Execução do fechamento inexistente: ${runId.value}." }
        var outcome: EndOfDayExecution? = EndOfDayExecution.Finished(status).takeIf { status != RunStatus.RUNNING }
        while (outcome == null) {
            val start = transitions.begin(runId)
            outcome = if (start == null) EndOfDayExecution.Finished(RunStatus.COMPLETED) else runStep(runId, start)
        }
        return outcome
    }

    /** Roda a etapa [start]; nulo se ela concluiu e o laço segue. */
    private fun runStep(
        runId: EndOfDayRunId,
        start: StepStart,
    ): EndOfDayExecution? {
        val handler =
            checkNotNull(registry.handlerOrNull(start.name)) { "Etapa do fechamento sem registro: ${start.name}." }
        val completed =
            try {
                transitions.complete(runId, start.name, start.attempts) { handler.execute(it) }
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable, // qualquer falha da etapa falha a etapa
            ) {
                if (error.isUnrecoverable()) throw error
                return failed(runId, start, error)
            }
        return if (completed) null else lostRace(runId, start)
    }

    private fun failed(
        runId: EndOfDayRunId,
        start: StepStart,
        error: Throwable,
    ): EndOfDayExecution {
        val reason = EndOfDayRunTransitions.reasonOf(error)
        val recorded =
            try {
                transitions.fail(runId, start.name, start.attempts, reason)
            } catch (_: IllegalStateException) {
                false // outro executor já concluiu ou falhou a etapa
            }
        if (!recorded) return lostRace(runId, start)
        log.warn("Fechamento {}: a etapa {} falhou: {}", runId.value, start.name, reason)
        log.debug("Fechamento {}: falha da etapa {}", runId.value, start.name, error)
        return EndOfDayExecution.Finished(RunStatus.FAILED)
    }

    /** Outro executor passou à frente nesta etapa (depois de uma trava vencida): sem pendência nem log de erro. */
    private fun lostRace(
        runId: EndOfDayRunId,
        start: StepStart,
    ): EndOfDayExecution {
        log.info("Fechamento {}: a etapa {} passou para outro executor", runId.value, start.name)
        return EndOfDayExecution.Busy
    }

    /**
     * Erro da máquina virtual que deixa o processo instável (`OutOfMemoryError`, `InternalError`): escapa sem tocar na
     * execução, que fica `RUNNING` e é retomada depois do reinício. `StackOverflowError` é da etapa (recursão) e a
     * falha como qualquer outro erro, inclusive `NotImplementedError` e `LinkageError` depois de um deploy.
     */
    private fun Throwable.isUnrecoverable(): Boolean = this is VirtualMachineError && this !is StackOverflowError

    companion object {
        /** A trava do ShedLock do fechamento: uma execução de cada vez, em todos os processos (RN-21). */
        const val LOCK_NAME = "end-of-day-run"
    }
}
