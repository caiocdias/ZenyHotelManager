package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.domain.StoredFile
import com.zeny.hotel.files.domain.ValidatedUpload
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * A gravação de um anexo novo, comum ao upload ([FilesService.upload]) e à gravação pelo servidor
 * ([FilesApiService.store]): cifra o anexo sensível ([ContentCipher], com o id do anexo como dado
 * associado), grava os metadados, registra o retrato de criação na trilha e grava o objeto depois dos
 * metadados; se a transação desfizer, o objeto é apagado. Roda na transação de quem chama.
 */
@Component
class StoredFileWriter(
    private val files: StoredFiles,
    private val storage: FileStorage,
    private val objectRemovals: StoredObjectRemovals,
    private val cipher: ContentCipher,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Grava [content] como o anexo [id], vinculado a [ownerEntityId] se informado. Só chamar depois de
     * [com.zeny.hotel.files.domain.FileContentRules.validate] sobre o mesmo conteúdo (e, se a finalidade reencoda, do
     * reencode); o escritor não confere tipo nem tamanho.
     */
    @Suppress("LongParameterList") // o anexo e como ele nasce
    fun write(
        id: FileId,
        purpose: FilePurpose,
        upload: ValidatedUpload,
        content: ByteArray,
        createdBy: UserId?,
        ownerEntityId: String? = null,
    ): StoredFile {
        val stored = if (purpose.sensitive) cipher.encrypt(content, associatedDataOf(id)) else content
        // Na precisão do banco: a resposta do upload e a da repetição (lida do banco) são iguais.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val file =
            StoredFile.create(
                id = id,
                purpose = purpose.code,
                upload = upload,
                storedSizeBytes = stored.size.toLong(),
                encryptionKeyId = if (purpose.sensitive) cipher.keyIdOf(stored) else null,
                createdBy = createdBy,
                now = now,
            )
        if (ownerEntityId != null) file.attachTo(ownerEntityId, now)
        audit.created(FilesAudit.FILE, id.value) { file.auditState() }
        files.add(file)
        // O objeto depois dos metadados: falha de banco não deixa objeto; falha do armazenamento
        // desfaz os metadados; e o objeto de uma transação que desfaz depois daqui é apagado.
        objectRemovals.removeOnRollback(file.objectKey)
        storage.put(file.objectKey, stored, if (file.sensitive) ENCRYPTED_CONTENT_TYPE else file.contentType)
        log.info("Anexo {} gravado ({}, {} bytes, sensível: {})", id, purpose.code, file.sizeBytes, file.sensitive)
        return file
    }

    private companion object {
        const val ENCRYPTED_CONTENT_TYPE = "application/octet-stream"
    }
}

/** O id do anexo como dado associado: o conteúdo cifrado de um anexo não decifra como outro. */
internal fun associatedDataOf(id: FileId): ByteArray = "files.file:${id.value}".toByteArray(Charsets.US_ASCII)
