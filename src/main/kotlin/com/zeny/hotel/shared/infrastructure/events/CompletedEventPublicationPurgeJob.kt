package com.zeny.hotel.shared.infrastructure.events

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Apaga de `shared.event_publication` as publicações concluídas há mais de
 * `zeny.events.completed-retention` (padrão 7 dias, H13 do roadmap do M2), a cada hora e sob trava do
 * ShedLock.
 *
 * Usa a API do Modulith (`CompletedEventPublications.deletePublicationsOlderThan`), que apaga só as linhas
 * com `completion_date` anterior ao corte e `status` `COMPLETED` (ou nulo, da estrutura antiga) e conta o
 * corte pelo `Clock` da aplicação, o mesmo que gravou a conclusão. Uma incompleta (`completion_date`
 * nulo: pendente, com falha ou abandonada) nunca é apagada. O modo de conclusão continua o padrão
 * (`update`): o `delete` apagaria na hora, sem retenção, e o `archive` moveria para outra tabela, que
 * cresceria sem expurgo.
 *
 * Expurgo de dado técnico em `infrastructure`: fica fora da trilha de auditoria, como o das chaves de
 * idempotência.
 */
@Component
class CompletedEventPublicationPurgeJob(
    private val completedPublications: CompletedEventPublications,
    properties: OutboxProperties,
) {
    private val retention = properties.completedRetention

    @Scheduled(
        fixedDelayString = "\${zeny.events.purge.interval}",
        initialDelayString = "\${zeny.events.purge.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun purgeCompletedPublications() {
        completedPublications.deletePublicationsOlderThan(retention)
    }

    companion object {
        const val LOCK_NAME = "events-purge-completed"
    }
}
