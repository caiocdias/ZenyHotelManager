package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Expurgo dos anexos órfãos (H12 do roadmap do M2): um anexo sem vínculo há mais de
 * `zeny.files.orphan-retention` (desde a criação ou o último desvínculo) é apagado como numa
 * exclusão (linha marcada com `deleted_at`, objeto removido depois do commit), um por transação,
 * cada um com a sua entrada na trilha (`files.file.purge-orphan`), com ator sistema: o anexo é dado
 * de usuário, não dado técnico. O job em `infrastructure` (`FileOrphanPurgeJob`) chama [candidates]
 * e depois [purge] para cada um.
 */
@Service
class FileOrphanPurge(
    private val files: StoredFiles,
    private val objectRemovals: StoredObjectRemovals,
    private val properties: FilesProperties,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Até [limit] órfãos já vencidos agora, dos mais antigos aos mais novos. */
    @Transactional(readOnly = true)
    fun candidates(limit: Int): List<FileId> = files.orphansBefore(cutoff(), limit)

    /**
     * Apaga o anexo [id] se ele ainda é um órfão vencido, conferido com a linha travada (um vínculo
     * feito depois da busca dos candidatos o preserva). Devolve `true` se apagou.
     */
    @Audited(FilesAudit.PURGE_ORPHAN)
    @Transactional
    fun purge(id: FileId): Boolean {
        val cutoff = cutoff()
        val file = files.findForUpdate(id)
        if (file == null || !file.isOrphanSince(cutoff)) {
            audit.noChanges()
            return false
        }
        audit.track(FilesAudit.FILE, id.value) { file.auditState() }
        file.purgeOrphan(cutoff, clock.instant())
        files.flush()
        objectRemovals.removeAfterCommit(file.objectKey)
        log.info("Anexo órfão {} expurgado ({})", id, file.purpose)
        return true
    }

    private fun cutoff(): Instant = clock.instant().minus(properties.orphanRetention)
}
