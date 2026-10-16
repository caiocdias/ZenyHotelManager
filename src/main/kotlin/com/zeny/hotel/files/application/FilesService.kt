package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.files.domain.FileContentRules
import com.zeny.hotel.files.domain.ImageReencoder
import com.zeny.hotel.files.domain.StoredFile
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.isVersion7
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Um upload como chega da API: a finalidade pedida, o nome informado, o conteúdo e, opcionalmente,
 * o id escolhido pelo cliente (`clientFileId`, H10 do roadmap do M2), em texto, ainda não validado.
 */
class UploadCommand(
    val purpose: String,
    val originalName: String?,
    val content: ByteArray,
    val clientFileId: String? = null,
) {
    override fun toString(): String =
        "UploadCommand(purpose=$purpose, size=${content.size}, clientFileId=$clientFileId)"
}

/** O resultado de um upload: o anexo e se ele já existia (repetição com o mesmo `clientFileId`). */
data class UploadResult(
    val file: StoredFileView,
    val replayed: Boolean,
)

/** Os metadados de um anexo como a API os mostra. */
data class StoredFileView(
    val id: FileId,
    val purpose: FilePurposeCode,
    val contentType: String,
    val sizeBytes: Long,
    val sensitive: Boolean,
    val ownerEntityId: String?,
    val createdAt: Instant,
)

/**
 * Uma URL de download: direta ao armazenamento (anexo comum, assinada pelo S3) ou do próprio servidor
 * (anexo sensível, que ele decifra), válidas até [expiresAt].
 */
sealed interface DownloadUrl {
    val expiresAt: Instant

    data class Direct(
        val url: URI,
        override val expiresAt: Instant,
    ) : DownloadUrl

    /** O caminho é montado pela camada web: `/api/v1/files/{fileId}/content?token=…`. */
    data class Server(
        val fileId: FileId,
        val token: String,
        override val expiresAt: Instant,
    ) : DownloadUrl {
        override fun toString(): String = "Server(fileId=$fileId, expiresAt=$expiresAt)"
    }
}

/** O conteúdo original de um anexo, pronto para a resposta. */
class FileContent(
    val content: ByteArray,
    val contentType: String,
    val downloadName: String,
)

/**
 * Upload e download de anexos (ARCHITECTURE §8.3 e §14, RNF-09):
 *
 * - **upload:** exige a permissão de upload da finalidade, valida tamanho e tipo pelo conteúdo,
 *   reencoda a imagem se a finalidade pede ([ImageReencoder]), cifra o anexo sensível
 *   ([ContentCipher], com o id do anexo como dado associado) e grava o objeto depois dos
 *   metadados; se a transação desfizer, o objeto é apagado. Com `clientFileId` (H10), o id do
 *   anexo é o do cliente, e a repetição pelo mesmo usuário, com a mesma finalidade e o mesmo
 *   conteúdo devolve o anexo existente sem gravar nada; qualquer outra coisa com o mesmo id é
 *   `409 CLIENT_FILE_ID_CONFLICT`;
 * - **URL de download:** exige a permissão de download da finalidade (escopo `OWN`: só quem enviou).
 *   Anexo comum recebe uma URL assinada pelo S3; anexo sensível, uma URL do servidor, e a emissão
 *   vai para a trilha;
 * - **download do servidor:** só com o token da URL emitida, até vencer; decifra o conteúdo, confere
 *   o SHA-256 e grava o acesso na trilha, em nome de quem pediu a URL (o vencido também, como recusa).
 */
@Service
@Suppress("LongParameterList") // catálogo, metadados, armazenamento, cifragem, tokens e as portas do shared
class FilesService(
    private val catalog: FilePurposeCatalog,
    private val files: StoredFiles,
    private val storage: FileStorage,
    private val objectRemovals: StoredObjectRemovals,
    private val cipher: ContentCipher,
    private val tokens: DownloadTokens,
    private val properties: FilesProperties,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Audited(FilesAudit.UPLOAD)
    @Transactional
    fun upload(command: UploadCommand): UploadResult {
        val purpose = purposeOf(command.purpose)
        authorization.require(purpose.uploadPermission)
        val actor = currentActor.current()
        val clientFileId = command.clientFileId?.let(::clientFileIdOf)
        if (clientFileId != null) {
            files.lockClientFileId(clientFileId)
            val existing = files.findIncludingDeleted(clientFileId)
            if (existing != null) {
                requireSameUpload(existing, purpose, actor.userId, FileContentRules.sha256(command.content))
                audit.noChanges()
                return UploadResult(existing.view(), replayed = true)
            }
        }
        val received = FileContentRules.validate(purpose, command.content, command.originalName)
        val content =
            purpose.reencode?.let { ImageReencoder.reencode(command.content, received.type, it.maxSidePixels) }
                ?: command.content
        val upload = if (purpose.reencode != null) received.withContent(content) else received
        val id = clientFileId ?: FileId(UuidV7.generate())
        val stored = if (purpose.sensitive) cipher.encrypt(content, associatedDataOf(id)) else content
        val file =
            StoredFile.create(
                id = id,
                purpose = purpose.code,
                upload = upload,
                storedSizeBytes = stored.size.toLong(),
                encryptionKeyId = if (purpose.sensitive) cipher.keyIdOf(stored) else null,
                createdBy = actor.userId,
                // Na precisão do banco: a resposta do upload e a da repetição (lida do banco) são iguais.
                now = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        audit.created(FilesAudit.FILE, id.value) { file.auditState() }
        files.add(file)
        // O objeto depois dos metadados: falha de banco não deixa objeto; falha do armazenamento
        // desfaz os metadados; e o objeto de uma transação que desfaz depois daqui é apagado.
        objectRemovals.removeOnRollback(file.objectKey)
        storage.put(file.objectKey, stored, if (file.sensitive) ENCRYPTED_CONTENT_TYPE else file.contentType)
        log.info("Anexo {} gravado ({}, {} bytes, sensível: {})", id, purpose.code, file.sizeBytes, file.sensitive)
        return UploadResult(file.view(), replayed = false)
    }

    /**
     * A repetição de um upload com o mesmo `clientFileId` só devolve o anexo existente se é o mesmo
     * upload: do mesmo usuário, da mesma finalidade, com o mesmo conteúdo recebido e ainda não
     * apagado. Senão, `409 CLIENT_FILE_ID_CONFLICT`, sem dizer o quê (o anexo pode ser de outro
     * usuário).
     */
    private fun requireSameUpload(
        existing: StoredFile,
        purpose: FilePurpose,
        userId: UserId,
        receivedSha256: String,
    ) {
        val same =
            !existing.deleted &&
                existing.createdBy == userId &&
                existing.purpose == purpose.code &&
                existing.receivedSha256 == receivedSha256
        if (!same) {
            throw ConflictException(
                FilesErrorCodes.CLIENT_FILE_ID_CONFLICT,
                "O id informado já pertence a outro anexo. Envie com um id novo.",
            )
        }
    }

    @Transactional(readOnly = true)
    fun metadata(id: FileId): StoredFileView {
        val (file, purpose) = existing(id)
        authorization.require(purpose.downloadPermission, ownership(file))
        return file.view()
    }

    /**
     * A URL de download, válida por `zeny.files.download-url-ttl`. Transação de escrita só para a
     * entrada da trilha (anexo sensível); a de anexo comum não grava nada.
     */
    @Audited(FilesAudit.ISSUE_DOWNLOAD_URL)
    @Transactional
    fun downloadUrl(id: FileId): DownloadUrl {
        val (file, purpose) = existing(id)
        authorization.require(purpose.downloadPermission, ownership(file))
        val ttl = properties.downloadUrlTtl
        val expiresAt = clock.instant().plus(ttl)
        if (!file.sensitive) {
            audit.noChanges()
            val url = storage.presignedDownload(file.objectKey, ttl, file.contentType, downloadName(file))
            return DownloadUrl.Direct(url, expiresAt)
        }
        val actor = currentActor.current()
        val token = tokens.issue(DownloadGrant(file.id, expiresAt, actor.userId, actor.deviceSessionId))
        audit.record(
            FilesAudit.FILE,
            id.value,
            null,
            FileAccessAuditState("download-url", purpose.code.value, expiresAt),
        )
        return DownloadUrl.Server(file.id, token, expiresAt)
    }

    /**
     * O conteúdo original do anexo, com o token de uma URL emitida por [downloadUrl]. Sem token válido
     * para o anexo: `403 DOWNLOAD_URL_INVALID` (sem entrada na trilha: não há quem nem o quê). Token
     * vencido: `403 DOWNLOAD_URL_EXPIRED`, gravado na trilha como recusa.
     */
    @Audited(FilesAudit.DOWNLOAD, recordRejections = true)
    @Transactional(noRollbackFor = [DomainException::class])
    fun download(
        id: FileId,
        token: String,
    ): FileContent {
        val grant = tokens.read(token, id)
        val file = files.find(id) ?: throw PermissionDeniedException(FilesErrorCodes.DOWNLOAD_URL_INVALID, INVALID_URL)
        audit.attributeTo(grant.userId, grant.deviceSessionId)
        audit.record(
            FilesAudit.FILE,
            id.value,
            null,
            FileAccessAuditState("download", file.purpose.value, grant.expiresAt),
        )
        if (!clock.instant().isBefore(grant.expiresAt)) {
            throw PermissionDeniedException(
                FilesErrorCodes.DOWNLOAD_URL_EXPIRED,
                "O link de download venceu. Peça um novo.",
            )
        }
        val stored = storage.get(file.objectKey)
        val content = if (file.sensitive) cipher.decrypt(stored, associatedDataOf(id)) else stored
        check(
            FileContentRules.sha256(content) == file.sha256,
        ) { "Conteúdo do anexo $id não confere com o SHA-256 gravado" }
        return FileContent(content, file.contentType, downloadName(file))
    }

    private fun existing(id: FileId): Pair<StoredFile, FilePurpose> {
        val file = files.find(id) ?: notFound()
        val purpose = catalog.find(file.purpose) ?: notFound()
        return file to purpose
    }

    private fun purposeOf(code: String): FilePurpose {
        val purpose = runCatching { FilePurposeCode(code.trim()) }.getOrNull()?.let(catalog::find)
        if (purpose != null) return purpose
        val message = "Finalidade de anexo desconhecida."
        throw InvalidInputException(
            FilesErrorCodes.UNKNOWN_FILE_PURPOSE,
            message,
            listOf(FieldViolation("purpose", FilesErrorCodes.UNKNOWN_FILE_PURPOSE.value, message)),
        )
    }

    private fun ownership(file: StoredFile) = OwnedResource.of(file.createdBy)

    /** O `clientFileId` na forma canônica de um UUIDv7 (8-4-4-4-12, hexadecimal); senão, `400`. */
    private fun clientFileIdOf(text: String): FileId {
        val id =
            text
                .trim()
                .takeIf { CANONICAL_UUID.matches(it) }
                ?.let(UUID::fromString)
                ?.takeIf { it.isVersion7() }
        if (id != null) return FileId(id)
        val message = "O id do anexo precisa ser um UUIDv7."
        throw InvalidInputException(
            FilesErrorCodes.CLIENT_FILE_ID_INVALID,
            message,
            listOf(FieldViolation("clientFileId", FilesErrorCodes.CLIENT_FILE_ID_INVALID.value, message)),
        )
    }

    private fun notFound(): Nothing = throw NotFoundException(FilesErrorCodes.FILE_NOT_FOUND, "Anexo não encontrado.")

    private companion object {
        const val ENCRYPTED_CONTENT_TYPE = "application/octet-stream"
        const val INVALID_URL = "O link de download é inválido. Peça um novo."
        val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** O id do anexo como dado associado: o conteúdo cifrado de um anexo não decifra como outro. */
        fun associatedDataOf(id: FileId): ByteArray = "files.file:${id.value}".toByteArray(Charsets.US_ASCII)

        /** O nome original, se tem a extensão do tipo; senão, `<id>.<extensão>`. */
        fun downloadName(file: StoredFile): String =
            file.originalName
                ?.takeIf { name -> file.type.extensions.any { name.lowercase().endsWith(".$it") } }
                ?: "${file.id.value}.${file.type.defaultExtension}"
    }
}

internal fun StoredFile.view() =
    StoredFileView(id, purpose, contentType, sizeBytes, sensitive, ownerEntityId, createdAt)
