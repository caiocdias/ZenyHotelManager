package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import org.springframework.context.ApplicationContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/** Apoio dos testes do motor do fechamento (contexto [EndOfDayTestConfiguration]). */
object EndOfDayTestRuns {
    private const val MAX_ROUNDS = 10
    private val WAIT: Duration = Duration.ofSeconds(60)

    /**
     * Cumpre a regra do banco isolado: deixa a data operacional corrente sem execução ou com execução `COMPLETED`.
     * Zera os comandos do [FakeStepsProbe] e, enquanto a execução da data corrente não terminar, retoma a `FAILED`
     * (`resume` + `execute`) ou executa a `RUNNING`. Como a execução completa avança a data, o normal é terminar com a
     * data seguinte sem execução. Use no `finally` de todo teste que cria execução, depois de soltar qualquer trava
     * que o teste segure.
     */
    fun settle(context: ApplicationContext) {
        val probe = context.getBean(FakeStepsProbe::class.java)
        val hotel = context.getBean(HotelApi::class.java)
        val runs = context.getBean(EndOfDayRunRepository::class.java)
        val engine = context.getBean(EndOfDayEngine::class.java)
        val tx = context.getBean(TransactionTemplate::class.java)
        val clock = context.getBean(Clock::class.java)
        probe.reset()
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
}
