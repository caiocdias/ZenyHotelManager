package com.zeny.hotel.shared.infrastructure.idempotency

import com.zeny.hotel.shared.application.idempotency.IdempotencyService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Expurga as chaves de idempotência vencidas (mais de 24h, `zeny.idempotency.retention`) da
 * tabela `shared.idempotency_key`, sob trava do ShedLock (ARCHITECTURE §13). Chave vencida e
 * ainda não expurgada já não é repetida: a requisição seguinte com ela executa de novo.
 */
@Component
class IdempotencyKeyPurgeJob(
    private val idempotency: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.idempotency.purge.interval}",
        initialDelayString = "\${zeny.idempotency.purge.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun purgeExpiredKeys() {
        val purged = idempotency.purgeExpired()
        if (purged > 0) log.info("Expurgadas {} chaves de idempotência vencidas", purged)
    }

    companion object {
        const val LOCK_NAME = "idempotency-keys-purge"
    }
}
