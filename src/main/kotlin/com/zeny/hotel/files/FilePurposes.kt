package com.zeny.hotel.files

import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.TypedId
import java.util.UUID

/** Id de um anexo (UUIDv7, D15). */
@JvmInline
value class FileId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()
}

/**
 * Código de uma finalidade de anexo: o módulo dono e o nome, em minúsculas e kebab-case
 * (`hotel.logo`, `guests.identity-document`). Organiza as chaves no armazenamento
 * (`<módulo>/<nome>/<id>`, ARCHITECTURE §14). Um código publicado não muda: os anexos gravados o
 * referenciam.
 */
@JvmInline
value class FilePurposeCode(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) {
            "Código de finalidade fora do padrão <módulo>.<nome>: '$value'"
        }
    }

    /** O módulo dono (antes do ponto). */
    val module: String get() = value.substringBefore('.')

    /** O nome dentro do módulo (depois do ponto). */
    val name: String get() = value.substringAfter('.')

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}

/**
 * Tipos de arquivo aceitos por alguma finalidade. O tipo de um upload é **detectado pelo conteúdo**
 * (a assinatura dos primeiros bytes), nunca pelo `Content-Type` ou pela extensão que o cliente
 * informa; a extensão do nome, se houver, precisa ser uma das do tipo detectado. Formatos que podem
 * levar script (SVG, HTML) não entram.
 */
enum class FileType(
    val mediaType: String,
    val extensions: Set<String>,
) {
    PNG("image/png", setOf("png")),
    JPEG("image/jpeg", setOf("jpg", "jpeg")),
    WEBP("image/webp", setOf("webp")),
    PDF("application/pdf", setOf("pdf")),
    ;

    /** A extensão usada no nome do download quando o nome original não serve. */
    val defaultExtension: String get() = extensions.first()

    /** `true` se o conteúdo começa pela assinatura deste tipo. */
    fun matches(content: ByteArray): Boolean =
        when (this) {
            PNG -> content.startsWith(PNG_SIGNATURE)
            JPEG -> content.startsWith(JPEG_SIGNATURE)
            WEBP -> content.startsWith(RIFF) && content.startsWith(WEBP_TAG, offset = WEBP_TAG_OFFSET)
            PDF -> content.startsWith(PDF_SIGNATURE)
        }

    companion object {
        /** O tipo cuja assinatura abre o conteúdo, ou `null` se nenhum dos aceitos. */
        fun detect(content: ByteArray): FileType? = entries.firstOrNull { it.matches(content) }

        private val PNG_SIGNATURE = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIGNATURE = bytes(0xFF, 0xD8, 0xFF)
        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_TAG = "WEBPVP8".toByteArray(Charsets.US_ASCII)
        private const val WEBP_TAG_OFFSET = 8
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private fun ByteArray.startsWith(
            prefix: ByteArray,
            offset: Int = 0,
        ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }
    }
}

/**
 * Uma finalidade de anexo (ARCHITECTURE §14): o que pode ser enviado com ela e quem pode enviar e
 * baixar. Declarada em código pelo módulo dono, num [ModuleFilePurposes].
 *
 * - [maxSizeBytes]: tamanho máximo do conteúdo original; no máximo `zeny.files.max-upload-size`.
 * - [acceptedTypes]: tipos aceitos, detectados pelo conteúdo ([FileType]).
 * - [sensitive]: anexo com dado pessoal sensível (documento de hóspede, RNF-09): cifrado antes de
 *   ir ao armazenamento ([com.zeny.hotel.shared.ContentCipher]), baixado só pelo servidor, que
 *   decifra, e com cada geração de URL e cada download na trilha de auditoria.
 * - [uploadPermission] e [downloadPermission]: permissões do catálogo (de qualquer módulo,
 *   normalmente o dono). O download aceita o escopo `OWN`: com ele, só quem enviou o anexo baixa.
 */
data class FilePurpose(
    val code: FilePurposeCode,
    val description: String,
    val maxSizeBytes: Long,
    val acceptedTypes: Set<FileType>,
    val sensitive: Boolean,
    val uploadPermission: PermissionCode,
    val downloadPermission: PermissionCode,
) {
    init {
        require(description.isNotBlank()) { "Finalidade $code sem descrição" }
        require(maxSizeBytes > 0) { "Finalidade $code com tamanho máximo não positivo" }
        require(acceptedTypes.isNotEmpty()) { "Finalidade $code sem tipo aceito" }
    }
}

/**
 * As finalidades de anexo de um módulo. Cada módulo que guarda arquivos publica um bean deste tipo
 * (numa `@Configuration` de `application`, como o das permissões); o `files` junta todos no boot e
 * recusa a partida com código fora do módulo, repetido, tamanho acima do máximo do upload ou
 * permissão que não está no catálogo.
 */
interface ModuleFilePurposes {
    val module: String
    val purposes: List<FilePurpose>
}
