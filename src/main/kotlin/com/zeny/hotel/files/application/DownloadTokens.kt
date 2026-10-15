package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.FieldDecryptionException
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.TokenSealer
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O que uma URL de download do servidor autoriza: um anexo, até quando, e para quem ela foi emitida. */
data class DownloadGrant(
    val fileId: FileId,
    val expiresAt: Instant,
    val userId: UserId,
    val deviceSessionId: DeviceSessionId?,
)

/**
 * A assinatura das URLs de download servidas pelo próprio servidor (anexos sensíveis, que ele
 * decifra): o token é o [DownloadGrant] selado pelo [TokenSealer] (AES-256-GCM com uma chave
 * **derivada** por HKDF das chaves de `ZENY_ENCRYPTION_KEYS` só para esta finalidade, com o id da
 * chave para a rotação). Um valor cifrado de uma coluna pelo `FieldCipher` não vale como token. O
 * token não guarda estado; na leitura, validade acima de agora + `zeny.files.download-url-ttl` é
 * recusada, para nenhum token valer mais que uma URL emitida agora.
 */
@Component
class DownloadTokens(
    private val sealer: TokenSealer,
    private val properties: FilesProperties,
    private val clock: Clock,
) {
    fun issue(grant: DownloadGrant): String =
        sealer.seal(
            PURPOSE,
            listOf(
                PREFIX,
                grant.fileId.value.toString(),
                grant.expiresAt.toEpochMilli().toString(),
                grant.userId.value.toString(),
                grant.deviceSessionId
                    ?.value
                    ?.toString()
                    .orEmpty(),
            ).joinToString(SEPARATOR),
        )

    /**
     * O que o token autoriza, se ele foi emitido por este servidor para o anexo [fileId] e não vale
     * além de agora + a validade configurada; senão, `403 DOWNLOAD_URL_INVALID`. Se já venceu, quem
     * chama confere (`DOWNLOAD_URL_EXPIRED`).
     */
    fun read(
        token: String,
        fileId: FileId,
    ): DownloadGrant {
        val parts =
            try {
                sealer.open(PURPOSE, token).split(SEPARATOR)
            } catch (_: FieldDecryptionException) {
                invalid()
            }
        if (parts.size != PARTS || parts[0] != PREFIX) invalid()
        return runCatching {
            DownloadGrant(
                fileId = FileId(UUID.fromString(parts[FILE_PART])),
                expiresAt = Instant.ofEpochMilli(parts[EXPIRES_PART].toLong()),
                userId = UserId(UUID.fromString(parts[USER_PART])),
                deviceSessionId =
                    parts[SESSION_PART].takeIf { it.isNotEmpty() }?.let { DeviceSessionId(UUID.fromString(it)) },
            )
        }.getOrElse { invalid() }
            .takeIf { it.fileId == fileId && !it.expiresAt.isAfter(clock.instant().plus(properties.downloadUrlTtl)) }
            ?: invalid()
    }

    private fun invalid(): Nothing =
        throw PermissionDeniedException(
            FilesErrorCodes.DOWNLOAD_URL_INVALID,
            "O link de download é inválido. Peça um novo.",
        )

    private companion object {
        const val PURPOSE = "files.download-url"
        const val PREFIX = "files.download.v1"
        const val SEPARATOR = "|"
        const val PARTS = 5
        const val FILE_PART = 1
        const val EXPIRES_PART = 2
        const val USER_PART = 3
        const val SESSION_PART = 4
    }
}
