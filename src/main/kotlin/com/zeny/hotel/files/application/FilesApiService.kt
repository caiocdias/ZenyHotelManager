package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileSummary
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.files.domain.StoredFile
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** A API pública do `files` ([FilesApi]): sem checar permissão, na transação de quem chama. */
@Service
class FilesApiService(
    private val files: StoredFiles,
    private val objectRemovals: StoredObjectRemovals,
    private val audit: AuditContext,
    private val clock: Clock,
) : FilesApi {
    @Transactional(readOnly = true)
    override fun find(id: FileId): FileSummary? = files.find(id)?.summary()

    @Audited(FilesAudit.ATTACH)
    @Transactional
    override fun attach(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): FileSummary {
        val file = lockedOf(id, purpose)
        audit.track(FilesAudit.FILE, id.value) { file.auditState() }
        if (file.attachTo(ownerEntityId, clock.instant())) files.flush()
        return file.summary()
    }

    @Audited(FilesAudit.DETACH)
    @Transactional
    override fun detach(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): FileSummary {
        val file = lockedOf(id, purpose)
        audit.track(FilesAudit.FILE, id.value) { file.auditState() }
        if (file.detachFrom(ownerEntityId, clock.instant())) files.flush()
        return file.summary()
    }

    @Audited(FilesAudit.DELETE)
    @Transactional
    override fun delete(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ) {
        val file = lockedOf(id, purpose)
        audit.track(FilesAudit.FILE, id.value) { file.auditState() }
        file.deleteFor(ownerEntityId, clock.instant())
        files.flush()
        // Só depois do commit: a transação de quem chama pode ainda desfazer a exclusão.
        objectRemovals.removeAfterCommit(file.objectKey)
    }

    /** O anexo não apagado, travado para a mudança, conferida a finalidade. */
    private fun lockedOf(
        id: FileId,
        purpose: FilePurposeCode,
    ): StoredFile {
        val file =
            files.findForUpdate(id) ?: throw NotFoundException(FilesErrorCodes.FILE_NOT_FOUND, "Anexo não encontrado.")
        if (file.purpose != purpose) {
            throw BusinessRuleException(FilesErrorCodes.FILE_PURPOSE_MISMATCH, "O anexo é de outra finalidade.")
        }
        return file
    }
}

private fun StoredFile.summary() =
    FileSummary(id, purpose, contentType, sizeBytes, sensitive, ownerEntityId, createdBy, createdAt)
