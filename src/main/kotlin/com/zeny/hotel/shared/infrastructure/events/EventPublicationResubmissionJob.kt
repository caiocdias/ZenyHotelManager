package com.zeny.hotel.shared.infrastructure.events

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.function.Predicate

/**
 * Reprocessa as publicações do outbox cujo listener falhou (ARCHITECTURE §5 e §13, H13 do roadmap do M2).
 *
 * O ciclo completo de uma publicação:
 * - gravada como `PUBLISHED` na transação que publica o evento;
 * - o listener roda depois do commit; se lançar exceção, a publicação vira `FAILED`;
 * - este job, a cada minuto e sob trava do ShedLock, reenvia ao listener as `FAILED` cuja próxima
 *   tentativa venceu, com o backoff exponencial do [OutboxRetryPolicy] (1, 2, 4… min até 1 h);
 * - a que ainda falha numa tentativa feita 24 h ou mais depois da publicação fica **abandonada**: continua
 *   `FAILED`, este job não a reenvia mais e o [OutboxMetrics] a conta como `abandoned` (com alerta). O
 *   reenvio é manual, por SQL (CLAUDE.md, "Eventos e jobs");
 * - publicação parada em `PUBLISHED`/`PROCESSING`/`RESUBMITTED` além do prazo de
 *   `spring.modulith.events.staleness.*` vira `FAILED` e volta para este job (o staleness só muda o
 *   estado, nunca reenvia);
 * - no boot, nada é reenviado de imediato: `republish-outstanding-events-on-restart` está desligado,
 *   porque reenviaria todas as incompletas, sem filtro, inclusive as abandonadas;
 * - concluída há mais de `zeny.events.completed-retention`, é apagada ([CompletedEventPublicationPurgeJob]).
 *
 * Por isso os listeners precisam ser idempotentes: um evento pode ser entregue mais de uma vez.
 *
 * O Modulith lê as `FAILED` em ordem de publicação com `LIMIT` e só depois aplica o filtro; com o lote
 * dele, as abandonadas e as que esperam o backoff ocupariam o lote e esconderiam as vencidas mais novas.
 * Por isso a leitura não tem limite (só linhas `FAILED`; o evento só é desserializado se for reenviado)
 * e o limite por execução, `batch-size`, é aplicado no filtro. A hora é a do `Clock` da aplicação, o
 * mesmo com que o Modulith grava `publication_date` e `last_resubmission_date`.
 */
@Component
class EventPublicationResubmissionJob(
    private val failedPublications: FailedEventPublications,
    private val clock: Clock,
    properties: OutboxProperties,
) {
    private val policy = properties.resubmission.policy()
    private val batchSize = properties.resubmission.batchSize

    @Scheduled(
        fixedDelayString = "\${zeny.events.resubmission.interval}",
        initialDelayString = "\${zeny.events.resubmission.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
    fun resubmitFailedPublications() {
        failedPublications.resubmit(
            ResubmissionOptions
                .defaults()
                .withBatchSize(Int.MAX_VALUE)
                .withFilter(DueFilter(policy, clock.instant(), batchSize)),
        )
    }

    /**
     * Aceita as vencidas até o limite da execução. O Modulith aplica o filtro em sequência, uma
     * publicação por vez (`stream().filter(…).forEach(…)`), então a contagem é exata.
     */
    private class DueFilter(
        private val policy: OutboxRetryPolicy,
        private val now: Instant,
        private val limit: Int,
    ) : Predicate<EventPublication> {
        private var accepted = 0

        override fun test(publication: EventPublication): Boolean {
            if (accepted >= limit || !policy.isDue(publication, now)) return false
            accepted++
            return true
        }
    }

    companion object {
        const val LOCK_NAME = "events-resubmit-failed"
    }
}
