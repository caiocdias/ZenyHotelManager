package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.DeviceSessions
import com.zeny.hotel.iam.application.RotatedRefreshTokens
import com.zeny.hotel.iam.application.SessionDirectory
import com.zeny.hotel.iam.application.SessionSummary
import com.zeny.hotel.iam.application.Users
import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.UserId
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface UserJpaRepository : JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.login = :login")
    fun findForUpdateByLogin(login: String): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findForUpdateById(id: UUID): User?
}

interface DeviceSessionJpaRepository : JpaRepository<DeviceSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeviceSession s where s.refreshTokenHash = :tokenHash")
    fun findForUpdateByRefreshTokenHash(tokenHash: String): DeviceSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeviceSession s where s.id = :id")
    fun findForUpdateById(id: UUID): DeviceSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeviceSession s where s.userId = :userId and s.deviceId = :deviceId and s.revokedAt is null")
    fun findForUpdateNotRevokedOnDevice(
        userId: UUID,
        deviceId: String,
    ): DeviceSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeviceSession s where s.userId = :userId and s.revokedAt is null order by s.id")
    fun findForUpdateNotRevokedOf(userId: UUID): List<DeviceSession>
}

@Component
class JpaUsers(
    private val repository: UserJpaRepository,
) : Users {
    override fun findByLoginForUpdate(login: String): User? = repository.findForUpdateByLogin(login)

    override fun findById(id: UserId): User? = repository.findById(id.value).orElse(null)

    override fun findByIdForUpdate(id: UserId): User? = repository.findForUpdateById(id.value)

    override fun anyExists(): Boolean = repository.count() > 0

    override fun add(user: User) {
        repository.save(user)
    }
}

@Component
class JpaDeviceSessions(
    private val repository: DeviceSessionJpaRepository,
) : DeviceSessions {
    override fun findByRefreshTokenHashForUpdate(tokenHash: String): DeviceSession? =
        repository.findForUpdateByRefreshTokenHash(tokenHash)

    override fun findByIdForUpdate(id: DeviceSessionId): DeviceSession? = repository.findForUpdateById(id.value)

    override fun findNotRevokedOf(
        userId: UserId,
        except: DeviceSessionId?,
    ): List<DeviceSession> = repository.findForUpdateNotRevokedOf(userId.value).filterNot { it.id == except }

    override fun findNotRevokedOnDeviceForUpdate(
        userId: UserId,
        deviceId: String,
    ): DeviceSession? = repository.findForUpdateNotRevokedOnDevice(userId.value, deviceId)

    override fun add(session: DeviceSession) {
        repository.save(session)
    }

    override fun flush() = repository.flush()
}

/** `iam.rotated_refresh_token`, só inserção, consulta e expurgo, pelo `JdbcClient` na transação corrente. */
@Component
class JdbcRotatedRefreshTokens(
    private val jdbc: JdbcClient,
) : RotatedRefreshTokens {
    override fun record(
        tokenHash: String,
        sessionId: DeviceSessionId,
        rotatedAt: Instant,
    ) {
        jdbc
            .sql(
                "INSERT INTO iam.rotated_refresh_token (token_hash, session_id, rotated_at) " +
                    "VALUES (:hash, :sessionId, :rotatedAt)",
            ).param("hash", tokenHash)
            .param("sessionId", sessionId.value)
            .param("rotatedAt", utc(rotatedAt))
            .update()
    }

    override fun sessionOf(tokenHash: String): DeviceSessionId? =
        jdbc
            .sql("SELECT session_id FROM iam.rotated_refresh_token WHERE token_hash = :hash")
            .param("hash", tokenHash)
            .query(UUID::class.java)
            .optional()
            .map(::DeviceSessionId)
            .orElse(null)

    override fun deleteRotatedBefore(instant: Instant): Int =
        jdbc
            .sql("DELETE FROM iam.rotated_refresh_token WHERE rotated_at < :before")
            .param("before", utc(instant))
            .update()
}

/** Lista de sessões ativas (leitura com `JdbcClient`, ARCHITECTURE §11). */
@Component
class JdbcSessionDirectory(
    private val jdbc: JdbcClient,
) : SessionDirectory {
    override fun activeSessionsOf(
        userId: UserId,
        now: Instant,
        afterId: UUID?,
        limit: Int,
    ): List<SessionSummary> =
        jdbc
            .sql(
                """
                SELECT id, device_id, device_name, created_at, last_seen_at, expires_at
                FROM iam.device_session
                WHERE user_id = :userId AND revoked_at IS NULL AND expires_at > :now
                  AND (CAST(:afterId AS uuid) IS NULL OR id < CAST(:afterId AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("userId", userId.value)
            .param("now", utc(now))
            .param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ ->
                SessionSummary(
                    id = rs.getObject("id", UUID::class.java),
                    deviceId = rs.getString("device_id"),
                    deviceName = rs.getString("device_name"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
