package com.zeny.hotel.files.infrastructure

import com.zeny.hotel.files.application.FileOrphanPurge
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Expurgo dos anexos órfãos (H12 do roadmap do M2), a cada `zeny.files.orphan-purge.interval`
 * (padrão 1 h) e sob trava do ShedLock (`files-orphans-purge`): apaga, um por transação e cada um
 * na trilha com ator sistema, os anexos sem vínculo há mais de `zeny.files.orphan-retention`. Por
 * execução, no máximo [MAX_BATCHES] lotes de [BATCH_SIZE]; o que sobrar fica para a próxima. Um
 * anexo que falha vai para o log e não interrompe os outros. Não devolve nada: o ShedLock só trava
 * método sem retorno (ou com retorno de objeto).
 */
@Component
class FileOrphanPurgeJob(
    private val purge: FileOrphanPurge,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.files.orphan-purge.interval}",
        initialDelayString = "\${zeny.files.orphan-purge.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    fun purgeOrphans() {
        var purged = 0
        var batches = 0
        do {
            val candidates = purge.candidates(BATCH_SIZE)
            candidates.forEach { id ->
                runCatching { purge.purge(id) }
                    .onSuccess { if (it) purged++ }
                    .onFailure { log.warn("Expurgo do anexo órfão {} falhou", id, it) }
            }
            batches++
        } while (candidates.size == BATCH_SIZE && batches < MAX_BATCHES)
        if (purged > 0) log.info("Expurgo de anexos órfãos: {} apagados", purged)
    }

    companion object {
        const val LOCK_NAME = "files-orphans-purge"
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 10
    }
}
