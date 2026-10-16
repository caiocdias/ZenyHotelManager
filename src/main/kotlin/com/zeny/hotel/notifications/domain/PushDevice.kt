package com.zeny.hotel.notifications.domain

import com.zeny.hotel.notifications.PushDeactivationReason
import com.zeny.hotel.notifications.PushPlatform
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Id de um dispositivo de push (UUIDv7). */
@JvmInline
value class PushDeviceId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()
}

/**
 * Um dispositivo de push (SPEC §8.2.18): o token do FCM de um aparelho, vinculado à sessão de dispositivo do `iam`
 * em que foi registrado. Só recebe push enquanto ativo e com a sessão ativa; nunca é apagado. O [token] é um
 * segredo do aparelho: fica cifrado no banco, nunca volta pela API nem vai à trilha ou ao log.
 */
@Suppress("LongParameterList") // o estado do dispositivo
class PushDevice private constructor(
    val id: PushDeviceId,
    val userId: UserId,
    val sessionId: DeviceSessionId,
    val platform: PushPlatform,
    val token: String,
    val createdAt: Instant,
    active: Boolean,
    deactivatedAt: Instant?,
    deactivationReason: PushDeactivationReason?,
    version: Long,
) {
    var active: Boolean = active
        private set

    var deactivatedAt: Instant? = deactivatedAt
        private set

    var deactivationReason: PushDeactivationReason? = deactivationReason
        private set

    var version: Long = version
        private set

    /** A impressão do token (SHA-256 em hexadecimal), para a unicidade entre os ativos. */
    val tokenSha256: String get() = fingerprint(token)

    /** Desativa pelo [reason]; `false` se já estava desativado (nada muda). */
    fun deactivate(
        reason: PushDeactivationReason,
        now: Instant,
    ): Boolean {
        if (!active) return false
        active = false
        deactivatedAt = now.truncatedTo(ChronoUnit.MICROS)
        deactivationReason = reason
        version++
        return true
    }

    override fun toString(): String = "PushDevice(id=$id, platform=$platform, active=$active)"

    companion object {
        const val MAX_TOKEN = 4096
        private val TOKEN = Regex("[\\x21-\\x7E]+")

        fun register(
            userId: UserId,
            sessionId: DeviceSessionId,
            platform: PushPlatform,
            token: String,
            now: Instant,
        ): PushDevice {
            require(token.length <= MAX_TOKEN && TOKEN.matches(token)) { "Token de push fora do formato" }
            return PushDevice(
                PushDeviceId(UuidV7.generate()),
                userId,
                sessionId,
                platform,
                token,
                now.truncatedTo(ChronoUnit.MICROS),
                true,
                null,
                null,
                0,
            )
        }

        @Suppress("LongParameterList") // as colunas do dispositivo
        fun restore(
            id: PushDeviceId,
            userId: UserId,
            sessionId: DeviceSessionId,
            platform: PushPlatform,
            token: String,
            createdAt: Instant,
            active: Boolean,
            deactivatedAt: Instant?,
            deactivationReason: PushDeactivationReason?,
            version: Long,
        ) = PushDevice(
            id,
            userId,
            sessionId,
            platform,
            token,
            createdAt,
            active,
            deactivatedAt,
            deactivationReason,
            version,
        )

        fun fingerprint(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
