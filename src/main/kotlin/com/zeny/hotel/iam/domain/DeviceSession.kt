package com.zeny.hotel.iam.domain

import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant

/**
 * Sessão de um usuário num dispositivo (SPEC §8.2.1, RF-ACS-10, ARCHITECTURE §8.1). Guarda só
 * o hash do refresh token vigente; a cada uso ele é trocado por outro (rotação) e a validade
 * desliza. Revogada, a sessão não renova mais; o access token já emitido vence em até 15 min.
 */
@Entity
@Table(name = "device_session", schema = "iam")
@Suppress("LongParameterList") // estado imutável da sessão; a criação passa pela fábrica [open]
class DeviceSession private constructor(
    @Id
    val id: DeviceSessionId,
    @Column(name = "user_id", nullable = false)
    val userId: UserId,
    @Column(name = "device_id", nullable = false)
    val deviceId: String,
    @Column(name = "device_name", nullable = false)
    val deviceName: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    refreshTokenHash: String,
    expiresAt: Instant,
) {
    @Column(name = "refresh_token_hash", nullable = false, unique = true)
    var refreshTokenHash: String = refreshTokenHash
        protected set

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = createdAt
        protected set

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = expiresAt
        protected set

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason")
    var revocationReason: SessionRevocationReason? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val revoked: Boolean get() = revokedAt != null

    /** Se a sessão ainda renova em [now]: não revogada e dentro da validade do refresh token. */
    fun isActive(now: Instant): Boolean = !revoked && expiresAt.isAfter(now)

    /**
     * Troca o refresh token vigente pelo de hash [newTokenHash] e estende a validade por [ttl] a
     * partir de [now]. Devolve o hash do token substituído, que passa a valer como reuso.
     */
    fun rotate(
        newTokenHash: String,
        now: Instant,
        ttl: Duration,
    ): String {
        check(isActive(now)) { "Sessão inativa não renova" }
        val previous = refreshTokenHash
        refreshTokenHash = newTokenHash
        lastSeenAt = now
        expiresAt = now.plus(ttl)
        return previous
    }

    /** Revoga a sessão; revogar de novo não muda o motivo nem a data. */
    fun revoke(
        reason: SessionRevocationReason,
        now: Instant,
    ) {
        if (revoked) return
        revokedAt = now
        revocationReason = reason
    }

    companion object {
        @Suppress("LongParameterList")
        fun open(
            userId: UserId,
            deviceId: String,
            deviceName: String,
            refreshTokenHash: String,
            now: Instant,
            ttl: Duration,
        ): DeviceSession =
            DeviceSession(
                id = DeviceSessionId.new(),
                userId = userId,
                deviceId = deviceId,
                deviceName = deviceName,
                createdAt = now,
                refreshTokenHash = refreshTokenHash,
                expiresAt = now.plus(ttl),
            )
    }
}

/** Por que uma sessão foi revogada. */
enum class SessionRevocationReason {
    /** O usuário saiu (`POST /auth/logout`). */
    LOGOUT,

    /** O usuário revogou a sessão pela lista das suas sessões. */
    REVOKED_BY_USER,

    /** Novo login do mesmo usuário no mesmo dispositivo. */
    REPLACED_BY_LOGIN,

    /** Um refresh token já trocado foi reapresentado (possível roubo). */
    REFRESH_TOKEN_REUSED,

    /** O usuário trocou a senha: as outras sessões dele caem. */
    PASSWORD_CHANGED,

    /** O usuário foi desativado pelo administrador (ou o refresh chegou com ele desativado). */
    USER_DISABLED,

    /** Um administrador revogou a sessão pela lista de sessões do usuário (M1 E03). */
    REVOKED_BY_ADMINISTRATOR,

    /** O administrador redefiniu a senha do usuário: todas as sessões dele caem (M1 E03). */
    PASSWORD_RESET,
}
