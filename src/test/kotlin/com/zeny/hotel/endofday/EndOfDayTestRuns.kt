package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import org.springframework.context.ApplicationContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Apoio dos testes do fechamento no banco isolado, nos contextos das etapas falsas ([EndOfDayTestConfiguration]) e das
 * reais ([EndOfDayRealStepsTestConfiguration]).
 */
object EndOfDayTestRuns {
    private const val MAX_ROUNDS = 10
    private val WAIT: Duration = Duration.ofSeconds(60)
    private const val ENGINE_EXECUTOR = "endOfDayExecutor"

    /**
     * Cumpre a regra do banco isolado: deixa a data operacional corrente sem execução ou com execução `COMPLETED`.
     * Zera os comandos do [FakeStepsProbe], da [BillingPostProbe], da [RoomsMarkProbe] e da [PrintingRenderProbe] (as
     * que o contexto tiver) e, enquanto a execução da data corrente não terminar, retoma a `FAILED` (`resume` +
     * `execute`) ou executa a `RUNNING`. Como a execução completa avança a data, o normal é terminar com a data
     * seguinte sem execução. Use no `finally` de todo teste que cria execução, depois de soltar qualquer trava que o
     * teste segure.
     */
    fun settle(context: ApplicationContext) {
        val hotel = context.getBean(HotelApi::class.java)
        val runs = context.getBean(EndOfDayRunRepository::class.java)
        val engine = context.getBean(EndOfDayEngine::class.java)
        val tx = context.getBean(TransactionTemplate::class.java)
        val clock = context.getBean(Clock::class.java)
        context.getBeanProvider(FakeStepsProbe::class.java).ifAvailable { it.reset() }
        context.getBeanProvider(BillingPostProbe::class.java).ifAvailable { it.reset() }
        context.getBeanProvider(RoomsMarkProbe::class.java).ifAvailable { it.reset() }
        context.getBeanProvider(PrintingRenderProbe::class.java).ifAvailable { it.reset() }
        repeat(MAX_ROUNDS) {
            val run = runs.findByBusinessDate(hotel.currentBusinessDate()) ?: return
            when (run.status) {
                RunStatus.COMPLETED -> {
                    return
                }

                RunStatus.FAILED -> {
                    tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run.id)).resume(clock.instant()) }
                    engine.execute(run.id, WAIT)
                }

                RunStatus.RUNNING -> {
                    engine.execute(run.id, WAIT)
                }
            }
        }
        error("A execução da data corrente não terminou depois de $MAX_ROUNDS rodadas.")
    }

    /**
     * Espera o motor terminar tudo o que já recebeu, no máximo 60 s: a thread única do motor (`endOfDayExecutor`) é uma
     * fila, então uma tarefa vazia posta nela só roda depois das anteriores. Use depois de um gatilho que voltou com a
     * execução ainda em curso (ele espera só `zeny.endofday.trigger.wait`, `PT1S` no banco isolado), antes das
     * asserções sobre o desfecho dela.
     */
    fun awaitEngine(context: ApplicationContext) {
        val engine = context.getBean(ENGINE_EXECUTOR, ExecutorService::class.java)
        engine.submit {}.get(WAIT.toNanos(), TimeUnit.NANOSECONDS)
    }

    /**
     * Fecha a data operacional corrente: cria a execução dela como o gatilho do horário
     * ([EndOfDayRunCommands.createAutomatic]) e a roda pelo motor, esperando até 60 s. Devolve a execução, que o teste
     * confere (`COMPLETED`, ou `FAILED` numa etapa); o `settle` no `finally` cuida do que sobrar.
     */
    fun closeCurrentDate(context: ApplicationContext): EndOfDayRunId {
        val hotel = context.getBean(HotelApi::class.java)
        val run = context.getBean(EndOfDayRunCommands::class.java).createAutomatic(hotel.currentBusinessDate())
        context.getBean(EndOfDayEngine::class.java).execute(run.id, WAIT)
        return run.id
    }
}
