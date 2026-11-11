package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O envio das submissões à FNRH (M5 E08; D-E08-4 e 5), a cada `zeny.fnrh.submissions.interval` e sob a trava do
 * ShedLock (`fnrh-submissions-send`). Não é `@Transactional`: o [FnrhSubmissionSender] recusa rodar dentro de uma
 * transação. Sem configuração da FNRH, não faz nada. Não devolve nada: o ShedLock só trava método sem retorno.
 *
 * A trava dura até `PT1H` porque uma execução pode levar muito mais que os 10 min dos outros jobs: até 50 submissões
 * (`batch-size`), cada uma com várias chamadas ao Serpro de até 25 s (connect 5 s + read 20 s). Uma trava curta
 * expiraria no meio da execução e outra instância começaria a enviar as mesmas submissões. O custo é uma instância que
 * morre com a trava presa atrasar o envio por até 1 h, o mesmo teto do backoff.
 */
@Component
class FnrhSubmissionJob(
    private val sender: FnrhSubmissionSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.fnrh.submissions.interval}",
        initialDelayString = "\${zeny.fnrh.submissions.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1H")
    fun send() {
        val run = sender.sendDue()
        if (run.sent + run.postponed + run.failed + run.invalid > 0) {
            log.info(
                "Envio à FNRH: {} enviadas, {} adiadas, {} com falha, {} inválidas",
                run.sent,
                run.postponed,
                run.failed,
                run.invalid,
            )
        }
    }

    companion object {
        const val LOCK_NAME = "fnrh-submissions-send"
    }
}
