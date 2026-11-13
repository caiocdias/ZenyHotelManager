package com.zeny.hotel.endofday.infrastructure

import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O gatilho automático do fechamento do dia (M6 E10), a cada `zeny.endofday.trigger.interval` (padrão 1 min) e sob a
 * trava do ShedLock (`end-of-day-trigger`): uma rodada do [EndOfDayTrigger], que fecha a data operacional no horário
 * do hotel, no fuso dele. A execução em si corre sob a trava do motor (`end-of-day-run`), compartilhada com o
 * fechamento manual. Não devolve nada: o ShedLock só trava método sem retorno (ou com objeto).
 *
 * Uma falha da rodada fica no log só pelo nome da classe, nunca pela mensagem (pode trazer dado do banco): o motor já
 * registrou a dele, e a próxima rodada tenta de novo. Só um erro da máquina virtual que deixa o processo instável
 * (`OutOfMemoryError`, `InternalError`) escapa.
 */
@Component
class EndOfDayTriggerJob(
    private val trigger: EndOfDayTrigger,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.endofday.trigger.interval}",
        initialDelayString = "\${zeny.endofday.trigger.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun triggerEndOfDay() {
        val outcome =
            try {
                trigger.tick()
            } catch (error: StackOverflowError) {
                return failed(error)
            } catch (error: VirtualMachineError) {
                throw error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable, // a próxima rodada tenta de novo
            ) {
                return failed(error)
            }
        if (outcome == TickOutcome.STARTED || outcome == TickOutcome.RESUMED) {
            log.info("Gatilho do fechamento: {}", outcome)
        }
    }

    /** Só pelo nome da classe: nem a mensagem nem o *stack trace* (que a repete). */
    private fun failed(error: Throwable) {
        log.error("Gatilho do fechamento: a rodada falhou ({})", error.javaClass.name)
    }

    companion object {
        const val LOCK_NAME = "end-of-day-trigger"
    }
}
