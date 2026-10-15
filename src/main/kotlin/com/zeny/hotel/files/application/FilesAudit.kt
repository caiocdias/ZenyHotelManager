package com.zeny.hotel.files.application

import com.zeny.hotel.files.domain.StoredFile
import java.time.Instant

/**
 * Tipos de entidade, ações e retratos do `files` na trilha de auditoria (RF-ACS-11, RNF-09). Os
 * retratos nunca levam o conteúdo, o nome original (pode ter dado pessoal) nem o token da URL.
 */
internal object FilesAudit {
    const val FILE = "files.file"

    const val UPLOAD = "files.file.upload"
    const val ATTACH = "files.file.attach"

    /** Geração de URL de download de anexo sensível (as de anexo comum não vão para a trilha). */
    const val ISSUE_DOWNLOAD_URL = "files.file.issue-download-url"

    /** Download de anexo sensível pelo servidor, com a URL emitida (inclusive o recusado por vencimento). */
    const val DOWNLOAD = "files.file.download"
}

internal data class StoredFileAuditState(
    val purpose: String,
    val ownerModule: String,
    val ownerEntityId: String?,
    val contentType: String,
    val sizeBytes: Long,
    val sensitive: Boolean,
    val encryptionKeyId: String?,
)

internal fun StoredFile.auditState() =
    StoredFileAuditState(
        purpose = purpose.value,
        ownerModule = ownerModule,
        ownerEntityId = ownerEntityId,
        contentType = contentType,
        sizeBytes = sizeBytes,
        sensitive = sensitive,
        encryptionKeyId = encryptionKeyId,
    )

/** O acesso a um anexo sensível: a URL emitida ou o download feito com ela, e até quando ela vale. */
internal data class FileAccessAuditState(
    val access: String,
    val purpose: String,
    val urlExpiresAt: Instant,
)
