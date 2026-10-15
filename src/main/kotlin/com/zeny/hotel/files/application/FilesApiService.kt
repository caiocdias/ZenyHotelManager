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
        val file =
            files.findForUpdate(id) ?: throw NotFoundException(FilesErrorCodes.FILE_NOT_FOUND, "Anexo não encontrado.")
        if (file.purpose != purpose) {
            throw BusinessRuleException(FilesErrorCodes.FILE_PURPOSE_MISMATCH, "O anexo é de outra finalidade.")
        }
        audit.track(FilesAudit.FILE, id.value) { file.auditState() }
        if (file.attachTo(ownerEntityId, clock.instant())) files.flush()
        return file.summary()
    }
}

private fun StoredFile.summary() =
    FileSummary(id, purpose, contentType, sizeBytes, sensitive, ownerEntityId, createdBy, createdAt)
