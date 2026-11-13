package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayErrorCodes
import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.shared.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/** O que uma rodada do gatilho ([EndOfDayTrigger.tick]) fez. */
enum class TickOutcome {
    /** A data corrente ainda não venceu. */
    NOT_DUE,

    /** A data venceu há mais que a janela: nada roda sozinho, só o fechamento manual. */
    OUTSIDE_WINDOW,

    /** Nenhuma etapa registrada: nada a rodar. */
    NO_STEPS,

    /** Criou a execução da data e a rodou. */
    STARTED,

    /** Rodou a execução interrompida (`RUNNING`) da data. */
    RESUMED,

    /** A execução da data falhou e espera a retomada manual. */
    WAITING_MANUAL_RESUME,

    /** A data já está fechada. */
    ALREADY_CLOSED,

    /** Outro executor tem a trava do fechamento. */
    BUSY,
}

/**
 * O gatilho automático do fechamento do dia (M6 E10, RF-FEC): a cada rodada, decide pela data operacional corrente, o
 * horário do fechamento e o fuso do hotel ([HotelApi]) se roda o fechamento, sempre com o ator sistema.
 *
 * - Antes do vencimento ([EndOfDaySchedule.dueAt]): [TickOutcome.NOT_DUE].
 * - A partir do vencimento mais a janela (`zeny.endofday.trigger.catch-up-window`, 24 h): [TickOutcome.OUTSIDE_WINDOW]
 *   e nada roda, nem a execução interrompida. Fechar sozinho uma data atrasada (servidor fora do ar) lançaria no-show
 *   com multa (RN-15, imutável pela RN-03) e enviaria à FNRH hóspedes que podem ter chegado nesse meio-tempo: o
 *   fechamento fica para o operador. Uma vez por data e processo, `WARN` e o aviso `END_OF_DAY_PENDING` a quem
 *   acompanha o fechamento (`endofday.runs.read`), só com a data e o código.
 * - Sem etapa registrada: [TickOutcome.NO_STEPS], com no máximo um `WARN` por data.
 * - Sem execução da data: cria a execução ([EndOfDayRunCommands.createAutomatic]) e a roda ([TickOutcome.STARTED]).
 *   Perdeu a corrida da criação (`END_OF_DAY_RUN_EXISTS`, `END_OF_DAY_ALREADY_CLOSED` ou `END_OF_DAY_DATE_MISMATCH`):
 *   relê a execução e segue pelo estado dela.
 * - Execução `RUNNING` (interrompida por uma queda): roda-a de novo; o motor não reexecuta etapa concluída
 *   ([TickOutcome.RESUMED]). `FAILED`: espera a retomada manual ([TickOutcome.WAITING_MANUAL_RESUME]), porque a falha
 *   já avisou (pendência e alerta) e quem corrige retoma. `COMPLETED`: [TickOutcome.ALREADY_CLOSED].
 * - O motor com a trava em outro executor: [TickOutcome.BUSY].
 *
 * Espera a execução até o fim (no máximo `zeny.endofday.lock-at-most-for`; passou disso, o motor continua sozinho). Sem
 * transação própria: a criação confirma a dela antes de o motor, em outra thread, ler a execução.
 */
@Component
@Suppress("LongParameterList") // as portas do gatilho e os dois prazos
class EndOfDayTrigger(
    private val hotel: HotelApi,
    private val runs: EndOfDayRunRepository,
    private val registry: EndOfDayStepRegistry,
    private val commands: EndOfDayRunCommands,
    private val engine: EndOfDayEngine,
    private val notifications: NotificationsApi,
    private val clock: Clock,
    @Value("\${zeny.endofday.trigger.catch-up-window}") private val catchUpWindow: Duration,
    @Value("\${zeny.endofday.lock-at-most-for}") private val waitAtMost: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A última data avisada fora da janela e a última avisada sem etapas: um aviso por data. */
    private val warnedOutsideWindow = AtomicReference<LocalDate?>()
    private val warnedNoSteps = AtomicReference<LocalDate?>()

    fun tick(): TickOutcome {
        val date = hotel.currentBusinessDate()
        val dueAt = EndOfDaySchedule.dueAt(date, hotel.operationalSettings().endOfDayTime, hotel.timezone())
        val now = clock.instant()
        return when {
            now < dueAt -> TickOutcome.NOT_DUE
            now >= dueAt.plus(catchUpWindow) -> outsideWindow(date)
            registry.slots.isEmpty() -> noSteps(date)
            else -> runs.findByBusinessDate(date)?.let { follow(it) } ?: start(date)
        }
    }

    private fun start(date: LocalDate): TickOutcome {
        val run =
            try {
                commands.createAutomatic(date)
            } catch (e: ConflictException) {
                if (e.code !in LOST_RACE) throw e
                log.info("Fechamento de {}: outro executor criou a execução ({})", date, e.code.value)
                return runs.findByBusinessDate(date)?.let { follow(it) } ?: TickOutcome.BUSY
            }
        return executed(run, TickOutcome.STARTED)
    }

    private fun follow(run: EndOfDayRun): TickOutcome =
        when (run.status) {
            RunStatus.COMPLETED -> TickOutcome.ALREADY_CLOSED
            RunStatus.FAILED -> TickOutcome.WAITING_MANUAL_RESUME
            RunStatus.RUNNING -> executed(run, TickOutcome.RESUMED)
        }

    /** Roda a [run] pelo motor, esperando até o fim: [done], ou [TickOutcome.BUSY] se outro executor tem a trava. */
    private fun executed(
        run: EndOfDayRun,
        done: TickOutcome,
    ): TickOutcome = if (engine.execute(run.id, waitAtMost) == EndOfDayExecution.Busy) TickOutcome.BUSY else done

    private fun outsideWindow(date: LocalDate): TickOutcome {
        if (warnedOutsideWindow.getAndSet(date) != date) {
            log.warn("Fechamento de {}: fora da janela do automático ({}); execute manualmente", date, catchUpWindow)
            try {
                notifications.notify(
                    NotificationsTypes.END_OF_DAY_PENDING,
                    "Pendência no fechamento de ${date.format(DATE_FORMAT)}",
                    OUTSIDE_WINDOW_TEXT,
                    NotificationRecipients.withPermission(EndOfDayPermissions.RUNS_READ),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") error: RuntimeException, // avisa de novo na próxima rodada
            ) {
                warnedOutsideWindow.compareAndSet(date, null)
                throw error
            }
        }
        return TickOutcome.OUTSIDE_WINDOW
    }

    private fun noSteps(date: LocalDate): TickOutcome {
        if (warnedNoSteps.getAndSet(date) != date) {
            log.warn("Fechamento de {}: nenhuma etapa registrada; o automático não roda", date)
        }
        return TickOutcome.NO_STEPS
    }

    private companion object {
        val LOST_RACE =
            setOf(
                EndOfDayErrorCodes.END_OF_DAY_RUN_EXISTS,
                EndOfDayErrorCodes.END_OF_DAY_ALREADY_CLOSED,
                EndOfDayErrorCodes.END_OF_DAY_DATE_MISMATCH,
            )
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        const val OUTSIDE_WINDOW_TEXT = "OUTSIDE_WINDOW: fechamento automático fora da janela; execute manualmente"
    }
}
