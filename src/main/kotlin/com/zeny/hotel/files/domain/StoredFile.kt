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
 * O conteúdo de um anexo não muda: só o vínculo com o registro dono, que pode ser desfeito (o anexo
 * volta a órfão, desde [detachedAt]), e a exclusão ([deletedAt]): o anexo apagado não volta, e a
 * linha fica porque a trilha de auditoria a referencia, **sem o nome original** (apagado junto: pode
 * citar o titular, como "rg-maria.jpg"; M3 E03).
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
    @Column(name = "received_sha256", updatable = false)
    private val receivedSha256Value: String?,
    @Column(nullable = false, updatable = false)
    val sensitive: Boolean,
    @Column(name = "encryption_key_id", updatable = false)
    val encryptionKeyId: String?,
    originalName: String?,
    @Column(name = "created_by", updatable = false)
    private val createdByValue: UUID?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    /** O nome original, cifrado; `null` sem nome ou depois da exclusão. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "original_name")
    var originalName: String? = originalName
        protected set

    @Column(name = "owner_entity_id")
    var ownerEntityId: String? = null
        protected set

    @Column(name = "attached_at")
    var attachedAt: Instant? = null
        protected set

    /** Quando o vínculo foi desfeito pela última vez (o anexo é órfão desde então); `null` se nunca. */
    @Column(name = "detached_at")
    var detachedAt: Instant? = null
        protected set

    /** Quando o anexo foi apagado (o objeto saiu do armazenamento depois do commit); `null` se não foi. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val deleted: Boolean get() = deletedAt != null

    /**
     * O SHA-256 do conteúdo **como chegou** no upload, antes do reencode (o mesmo de [sha256] numa
     * finalidade sem reencode e nos anexos anteriores à coluna). Confere a repetição de um upload com
     * o mesmo `clientFileId`.
     */
    val receivedSha256: String get() = receivedSha256Value ?: sha256

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
        requireNotDeleted()
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

    /**
     * Desfaz o vínculo com o registro [entityId]: o anexo volta a órfão desde [now]. Devolve `false`
     * se já era órfão; falha com `409 FILE_ALREADY_ATTACHED` se é de outro registro.
     */
    fun detachFrom(
        entityId: String,
        now: Instant,
    ): Boolean {
        requireNotDeleted()
        when (ownerEntityId) {
            null -> {
                return false
            }

            entityId -> {
                ownerEntityId = null
                attachedAt = null
                detachedAt = now
                return true
            }

            else -> {
                throw attachedToAnother()
            }
        }
    }

    /**
     * Apaga o anexo do registro [entityId] (ou um órfão) em [now]. Falha com `409
     * FILE_ALREADY_ATTACHED` se ele é de outro registro. O objeto sai do armazenamento depois do
     * commit, por quem chama.
     */
    fun deleteFor(
        entityId: String,
        now: Instant,
    ) {
        requireNotDeleted()
        if (ownerEntityId != null && ownerEntityId != entityId) throw attachedToAnother()
        deletedAt = now
        originalName = null
    }

    /** `true` se é órfão (sem vínculo e não apagado) desde antes de [cutoff]. */
    fun isOrphanSince(cutoff: Instant): Boolean =
        !deleted && ownerEntityId == null && (detachedAt ?: createdAt).isBefore(cutoff)

    /**
     * Apaga o órfão sem vínculo desde antes de [cutoff] (expurgo). Devolve `false`, sem mudar nada,
     * se ele foi vinculado, apagado ou desvinculado depois do corte.
     */
    fun purgeOrphan(
        cutoff: Instant,
        now: Instant,
    ): Boolean {
        if (!isOrphanSince(cutoff)) return false
        deletedAt = now
        originalName = null
        return true
    }

    private fun requireNotDeleted() {
        check(!deleted) { "Anexo $id já apagado" }
    }

    private fun attachedToAnother() =
        ConflictException(FilesErrorCodes.FILE_ALREADY_ATTACHED, "O anexo está vinculado a outro registro.")

    companion object {
        const val MAX_OWNER_ENTITY_ID_LENGTH = 100

        /**
         * Os metadados de um upload já validado ([FileContentRules]) e, se a finalidade pede,
         * reencodado ([ImageReencoder]). [storedSizeBytes] é o tamanho do objeto gravado (o conteúdo
         * cifrado, se sensível).
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
                receivedSha256Value = upload.receivedSha256,
                sensitive = upload.sensitive,
                encryptionKeyId = encryptionKeyId,
                originalName = upload.originalName,
                createdByValue = createdBy?.value,
                createdAt = now,
            )
        }
    }
}
