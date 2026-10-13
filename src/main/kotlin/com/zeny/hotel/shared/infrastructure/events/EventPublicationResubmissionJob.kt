package com.zeny.hotel.shared.infrastructure.events

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Reprocessa as publicações do outbox cujo listener falhou (ARCHITECTURE §5 e §13).
 *
 * O ciclo completo de uma publicação:
 * - gravada como `PUBLISHED` na transação que publica o evento;
 * - o listener roda depois do commit; se lançar exceção, a publicação vira `FAILED`;
 * - este job, a cada minuto e sob trava do ShedLock, reenvia as `FAILED` ao listener;
 * - no boot, `republish-outstanding-events-on-restart` reenvia todas as incompletas;
 * - publicação parada em `PUBLISHED`/`PROCESSING`/`RESUBMITTED` além do prazo de
 *   `spring.modulith.events.staleness.*` vira `FAILED` e volta para este job.
 *
 * Por isso os listeners precisam ser idempotentes: um evento pode ser entregue mais de uma vez.
 */
@Component
class EventPublicationResubmissionJob(
    private val failedPublications: FailedEventPublications,
) {
    @Scheduled(
        fixedDelayString = "\${zeny.events.resubmission.interval}",
        initialDelayString = "\${zeny.events.resubmission.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
    fun resubmitFailedPublications() {
        failedPublications.resubmit(OPTIONS)
    }

    companion object {
        const val LOCK_NAME = "events-resubmit-failed"
        private const val BATCH_SIZE = 100

        private val OPTIONS: ResubmissionOptions = ResubmissionOptions.defaults().withBatchSize(BATCH_SIZE)
    }
}
