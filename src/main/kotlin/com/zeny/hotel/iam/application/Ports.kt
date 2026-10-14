package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.util.UUID

/** Hash de senha e PIN em Argon2id (ARCHITECTURE §8.1). Os valores em claro nunca saem daqui. */
interface PasswordHasher {
    fun hash(raw: CharSequence): String

    fun matches(
        raw: CharSequence,
        hash: String,
    ): Boolean

    /** Se o hash foi feito com parâmetros mais fracos que os atuais e deve ser refeito. */
    fun needsRehash(hash: String): Boolean
}

/** Emite o access token JWT de um usuário numa sessão (RS256, com `kid`). */
interface AccessTokenIssuer {
    fun issue(
        user: User,
        session: DeviceSession,
        now: Instant,
    ): IssuedAccessToken
}

/** Um access token emitido. */
class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant,
) {
    /** Nunca mostra o token. */
    override fun toString(): String = "IssuedAccessToken(expiresAt=$expiresAt)"
}

/** Usuários persistidos. As buscas "ForUpdate" travam a linha até o fim da transação. */
interface Users {
    fun findByLoginForUpdate(login: String): User?

    fun findById(id: UserId): User?

    fun findByIdForUpdate(id: UserId): User?

    fun anyExists(): Boolean

    fun add(user: User)
}

/** Sessões de dispositivo persistidas. */
interface DeviceSessions {
    /** A sessão cujo refresh token vigente tem este hash, travada para a rotação. */
    fun findByRefreshTokenHashForUpdate(tokenHash: String): DeviceSession?

    fun findByIdForUpdate(id: DeviceSessionId): DeviceSession?

    /** As sessões não revogadas do usuário, menos a [except]. */
    fun findNotRevokedOf(
        userId: UserId,
        except: DeviceSessionId? = null,
    ): List<DeviceSession>

    /** A sessão não revogada do usuário neste dispositivo, travada. */
    fun findNotRevokedOnDeviceForUpdate(
        userId: UserId,
        deviceId: String,
    ): DeviceSession?

    fun add(session: DeviceSession)

    /** Grava já as mudanças pendentes (a revogação precisa chegar ao banco antes da sessão nova). */
    fun flush()
}

/** Hashes dos refresh tokens já trocados, para detectar reuso. */
interface RotatedRefreshTokens {
    fun record(
        tokenHash: String,
        sessionId: DeviceSessionId,
        rotatedAt: Instant,
    )

    /** A sessão a que pertencia o refresh token já trocado com este hash, se houver. */
    fun sessionOf(tokenHash: String): DeviceSessionId?

    /** Apaga os trocados antes de [instant]; devolve quantos. */
    fun deleteRotatedBefore(instant: Instant): Int
}

/** Leitura das sessões ativas de um usuário, para a lista "minhas sessões". */
interface SessionDirectory {
    /** Sessões não revogadas e não vencidas em [now], da mais nova para a mais antiga, depois de [afterId]. */
    fun activeSessionsOf(
        userId: UserId,
        now: Instant,
        afterId: UUID?,
        limit: Int,
    ): List<SessionSummary>
}

/** Uma sessão ativa, como o próprio usuário a vê. */
data class SessionSummary(
    val id: UUID,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
)
