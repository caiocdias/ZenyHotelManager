package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileSummary
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.files.domain.FileContentRules
import com.zeny.hotel.files.domain.StoredFile
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private typealias PublicFileContent = com.zeny.hotel.files.FileContent

/** A API pública do `files` ([FilesApi]): sem checar permissão, na transação de quem chama. */
@Service
class FilesApiService(
    private val files: StoredFiles,
    private val objectRemovals: StoredObjectRemovals,
    private val storage: FileStorage,
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

    @Transactional(readOnly = true)
    override fun readContent(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): PublicFileContent {
        val file = requireOwned(files.find(id), purpose, ownerEntityId)
        require(!file.sensitive) { "A leitura de anexo sensível pelo servidor não é suportada: $id" }
        val content = storage.get(file.objectKey)
        check(FileContentRules.sha256(content) == file.sha256) {
            "Conteúdo do anexo $id não confere com o SHA-256 gravado"
        }
        return PublicFileContent(file.summary(), content)
    }

    /** O anexo existente, da finalidade e do dono informados; senão 404, 422 ou 409. */
    private fun requireOwned(
        file: StoredFile?,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): StoredFile {
        val found = checkedPurpose(file, purpose)
        if (found.ownerEntityId != ownerEntityId) {
            throw ConflictException(FilesErrorCodes.FILE_ALREADY_ATTACHED, "O anexo é de outro registro.")
        }
        return found
    }

    private fun checkedPurpose(
        file: StoredFile?,
        purpose: FilePurposeCode,
    ): StoredFile {
        val found = file ?: throw NotFoundException(FilesErrorCodes.FILE_NOT_FOUND, "Anexo não encontrado.")
        if (found.purpose != purpose) {
            throw BusinessRuleException(FilesErrorCodes.FILE_PURPOSE_MISMATCH, "O anexo é de outra finalidade.")
        }
        return found
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
