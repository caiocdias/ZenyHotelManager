package com.zeny.hotel.files.domain

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Um upload que passou pelas regras da finalidade: o tipo detectado e o resumo do conteúdo que vai
 * ao armazenamento ([sizeBytes] e [sha256]: o recebido ou, numa finalidade com reencode, o
 * regravado, ver [withContent]) e o SHA-256 do conteúdo como chegou ([receivedSha256]).
 */
data class ValidatedUpload(
    val type: FileType,
    val sizeBytes: Long,
    val sha256: String,
    val sensitive: Boolean,
    /** O nome informado pelo cliente, sem caminho e sem caracteres de controle, ou `null`. */
    val originalName: String?,
    val receivedSha256: String = sha256,
) {
    /** O mesmo upload com o conteúdo trocado pelo reencodado (do mesmo tipo). */
    fun withContent(content: ByteArray): ValidatedUpload =
        copy(sizeBytes = content.size.toLong(), sha256 = FileContentRules.sha256(content))
}

/**
 * Regras de um upload contra a finalidade (ARCHITECTURE §14): não vazio, até o tamanho máximo, de um
 * tipo aceito **detectado pelo conteúdo** ([FileType.detect]) e, se o nome tem extensão, com a
 * extensão do tipo detectado. As recusas saem como `400` com o campo `file` em `errors[]`.
 */
object FileContentRules {
    const val MAX_NAME_LENGTH = 255
    private val CONTROL = Regex("\\p{Cntrl}")

    fun validate(
        purpose: FilePurpose,
        content: ByteArray,
        originalName: String?,
    ): ValidatedUpload {
        if (content.isEmpty()) reject(FilesErrorCodes.FILE_EMPTY, "O arquivo está vazio.")
        if (content.size > purpose.maxSizeBytes) {
            reject(
                FilesErrorCodes.FILE_TOO_LARGE,
                "O arquivo passa do tamanho máximo de ${describeSize(purpose.maxSizeBytes)} para esta finalidade.",
            )
        }
        val type = FileType.detect(content)
        if (type == null || type !in purpose.acceptedTypes) {
            reject(
                FilesErrorCodes.FILE_TYPE_NOT_ACCEPTED,
                "O conteúdo do arquivo não é de um tipo aceito para esta finalidade " +
                    "(${purpose.acceptedTypes.joinToString { it.defaultExtension.uppercase() }}).",
            )
        }
        val name = sanitizeName(originalName)
        val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
        if (extension != null && extension !in type.extensions) {
            reject(
                FilesErrorCodes.FILE_EXTENSION_MISMATCH,
                "A extensão do nome do arquivo não corresponde ao conteúdo (${type.defaultExtension.uppercase()}).",
            )
        }
        return ValidatedUpload(type, content.size.toLong(), sha256(content), purpose.sensitive, name)
    }

    /** SHA-256 em hexadecimal minúsculo. */
    fun sha256(content: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    /** O nome sem diretórios nem caracteres de controle, cortado em [MAX_NAME_LENGTH]; vazio vira `null`. */
    fun sanitizeName(name: String?): String? =
        name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(CONTROL, "")
            ?.trim()
            ?.take(MAX_NAME_LENGTH)
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

    @Suppress("MagicNumber")
    private fun describeSize(bytes: Long): String =
        when {
            bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0L -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 && bytes % 1024 == 0L -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }

    private fun reject(
        code: ErrorCode,
        message: String,
    ): Nothing = throw InvalidInputException(code, message, listOf(FieldViolation(FIELD, code.value, message)))

    private const val FIELD = "file"
}
