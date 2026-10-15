package com.zeny.hotel.files.domain

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.EncryptedStringConverter
import com.zeny.hotel.shared.UserId
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Os metadados de um anexo (ARCHITECTURE §14): a finalidade, o módulo e o registro donos, o tipo
 * detectado, o tamanho e o SHA-256 do conteúdo original, se é sensível e, nesse caso, a chave que o
 * cifrou, e quem enviou. O conteúdo fica no armazenamento, em [objectKey]
 * (`<módulo>/<finalidade>/<id>`). O nome original pode ter dado pessoal e fica cifrado no banco.
 * O conteúdo de um anexo não muda: só o vínculo com o registro dono.
 */
@Entity
@Table(name = "stored_file", schema = "files")
@Suppress("LongParameterList") // os metadados do anexo
class StoredFile private constructor(
    @Id
    val id: FileId,
    @Column(name = "purpose", nullable = false, updatable = false)
    private val purposeValue: String,
    @Column(name = "owner_module", nullable = false, updatable = false)
    val ownerModule: String,
    @Column(name = "object_key", nullable = false, updatable = false)
    val objectKey: String,
    @Column(name = "content_type", nullable = false, updatable = false)
    val contentType: String,
    @Column(name = "size_bytes", nullable = false, updatable = false)
    val sizeBytes: Long,
    @Column(name = "stored_size_bytes", nullable = false, updatable = false)
    val storedSizeBytes: Long,
    @Column(name = "sha256", nullable = false, updatable = false)
    val sha256: String,
    @Column(nullable = false, updatable = false)
    val sensitive: Boolean,
    @Column(name = "encryption_key_id", updatable = false)
    val encryptionKeyId: String?,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "original_name", updatable = false)
    val originalName: String?,
    @Column(name = "created_by", updatable = false)
    private val createdByValue: UUID?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "owner_entity_id")
    var ownerEntityId: String? = null
        protected set

    @Column(name = "attached_at")
    var attachedAt: Instant? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val purpose: FilePurposeCode get() = FilePurposeCode(purposeValue)

    val createdBy: UserId? get() = createdByValue?.let(::UserId)

    /** O tipo detectado no upload. */
    val type: FileType get() = FileType.entries.first { it.mediaType == contentType }

    /**
     * Vincula ao registro dono. Devolve `false` se já era dele; falha com `409 FILE_ALREADY_ATTACHED`
     * se é de outro.
     */
    fun attachTo(
        entityId: String,
        now: Instant,
    ): Boolean {
        require(entityId.isNotBlank() && entityId.length <= MAX_OWNER_ENTITY_ID_LENGTH) {
            "Id do registro dono vazio ou longo demais"
        }
        when (ownerEntityId) {
            entityId -> {
                return false
            }

            null -> {
                ownerEntityId = entityId
                attachedAt = now
                return true
            }

            else -> {
                throw ConflictException(
                    FilesErrorCodes.FILE_ALREADY_ATTACHED,
                    "O anexo já está vinculado a outro registro.",
                )
            }
        }
    }

    companion object {
        const val MAX_OWNER_ENTITY_ID_LENGTH = 100

        /**
         * Os metadados de um upload já validado ([FileContentRules]). [storedSizeBytes] é o tamanho
         * do objeto gravado (o conteúdo cifrado, se sensível).
         */
        @Suppress("LongParameterList")
        fun create(
            id: FileId,
            purpose: FilePurposeCode,
            upload: ValidatedUpload,
            storedSizeBytes: Long,
            encryptionKeyId: String?,
            createdBy: UserId?,
            now: Instant,
        ): StoredFile {
            require(upload.sensitive == (encryptionKeyId != null)) { "Anexo sensível sem chave, ou comum com chave" }
            return StoredFile(
                id = id,
                purposeValue = purpose.value,
                ownerModule = purpose.module,
                objectKey = "${purpose.module}/${purpose.name}/${id.value}",
                contentType = upload.type.mediaType,
                sizeBytes = upload.sizeBytes,
                storedSizeBytes = storedSizeBytes,
                sha256 = upload.sha256,
                sensitive = upload.sensitive,
                encryptionKeyId = encryptionKeyId,
                originalName = upload.originalName,
                createdByValue = createdBy?.value,
                createdAt = now,
            )
        }
    }
}
