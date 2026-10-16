package com.zeny.hotel.notifications.infrastructure

import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.PushDeactivationReason
import com.zeny.hotel.notifications.PushPlatform
import com.zeny.hotel.notifications.application.NotificationStore
import com.zeny.hotel.notifications.application.PreferenceStore
import com.zeny.hotel.notifications.application.PushDeliveryLog
import com.zeny.hotel.notifications.application.PushDeliveryOutcome
import com.zeny.hotel.notifications.application.PushDeviceStore
import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PushDevice
import com.zeny.hotel.notifications.domain.PushDeviceId
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Notificações com `JdbcClient` (ARCHITECTURE §11). */
@Component
class JdbcNotificationStore(
    private val jdbc: JdbcClient,
) : NotificationStore {
    override fun add(notification: Notification) {
        jdbc
            .sql(
                """
                INSERT INTO notifications.notification (id, recipient_id, type, title, body, created_at, read_at, version)
                VALUES (:id, :recipient, :type, :title, :body, :createdAt, :readAt, :version)
                """.trimIndent(),
            ).param("id", notification.id.value)
            .param("recipient", notification.recipientId.value)
            .param("type", notification.type.value)
            .param("title", notification.title)
            .param("body", notification.body)
            .param("createdAt", utc(notification.createdAt))
            .param("readAt", notification.readAt?.let(::utc))
            .param("version", notification.version)
            .update()
    }

    override fun findOf(
        recipientId: UserId,
        id: NotificationId,
    ): Notification? = one("WHERE id = :id AND recipient_id = :recipient", id, recipientId, forUpdate = false)

    override fun findOfForUpdate(
        recipientId: UserId,
        id: NotificationId,
    ): Notification? = one("WHERE id = :id AND recipient_id = :recipient", id, recipientId, forUpdate = true)

    override fun find(id: NotificationId): Notification? = one("WHERE id = :id", id, null, forUpdate = false)

    override fun update(notification: Notification) {
        jdbc
            .sql("UPDATE notifications.notification SET read_at = :readAt, version = :version WHERE id = :id")
            .param("id", notification.id.value)
            .param("readAt", notification.readAt?.let(::utc))
            .param("version", notification.version)
            .update()
    }

    override fun page(
        recipientId: UserId,
        unreadOnly: Boolean,
        afterId: UUID?,
        limit: Int,
    ): List<Notification> =
        jdbc
            .sql(
                """
                SELECT $COLUMNS FROM notifications.notification
                WHERE recipient_id = :recipient
                  AND (NOT :unreadOnly OR read_at IS NULL)
                  AND (CAST(:after AS uuid) IS NULL OR id < CAST(:after AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("recipient", recipientId.value)
            .param("unreadOnly", unreadOnly)
            .param("after", afterId)
            .param("limit", limit)
            .query { rs, _ -> rs.notification() }
            .list()

    override fun unreadCount(recipientId: UserId): Int =
        jdbc
            .sql("SELECT count(*) FROM notifications.notification WHERE recipient_id = :recipient AND read_at IS NULL")
            .param("recipient", recipientId.value)
            .query(Int::class.java)
            .single()

    override fun unreadForUpdate(recipientId: UserId): List<Notification> =
        jdbc
            .sql(
                "SELECT $COLUMNS FROM notifications.notification WHERE recipient_id = :recipient AND read_at IS NULL " +
                    "ORDER BY id FOR UPDATE",
            ).param("recipient", recipientId.value)
            .query { rs, _ -> rs.notification() }
            .list()

    private fun one(
        where: String,
        id: NotificationId,
        recipientId: UserId?,
        forUpdate: Boolean,
    ): Notification? =
        jdbc
            .sql("SELECT $COLUMNS FROM notifications.notification $where${if (forUpdate) " FOR UPDATE" else ""}")
            .param("id", id.value)
            .apply { if (recipientId != null) param("recipient", recipientId.value) }
            .query { rs, _ -> rs.notification() }
            .optional()
            .orElse(null)

    private fun ResultSet.notification() =
        Notification.restore(
            id = NotificationId(getObject("id", UUID::class.java)),
            recipientId = UserId(getObject("recipient_id", UUID::class.java)),
            type = NotificationTypeCode(getString("type")),
            title = getString("title"),
            body = getString("body"),
            createdAt = checkNotNull(instant("created_at")),
            readAt = instant("read_at"),
            version = getLong("version"),
        )

    private companion object {
        const val COLUMNS = "id, recipient_id, type, title, body, created_at, read_at, version"
    }
}

/** Preferências com `JdbcClient`. */
@Component
class JdbcPreferenceStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : PreferenceStore {
    override fun of(userId: UserId): List<NotificationPreference> =
        jdbc
            .sql(
                "SELECT type, in_app, push FROM notifications.notification_preference " +
                    "WHERE user_id = :user ORDER BY type",
            ).param("user", userId.value)
            .query { rs, _ -> rs.preference() }
            .list()

    override fun of(
        userId: UserId,
        type: NotificationTypeCode,
    ): NotificationPreference? =
        jdbc
            .sql(
                "SELECT type, in_app, push FROM notifications.notification_preference " +
                    "WHERE user_id = :user AND type = :type",
            ).param("user", userId.value)
            .param("type", type.value)
            .query { rs, _ -> rs.preference() }
            .optional()
            .orElse(null)

    override fun save(
        userId: UserId,
        preferences: List<NotificationPreference>,
    ) {
        preferences.forEach { preference ->
            jdbc
                .sql(
                    """
                    INSERT INTO notifications.notification_preference (user_id, type, in_app, push, updated_at)
                    VALUES (:user, :type, :inApp, :push, :now)
                    ON CONFLICT (user_id, type) DO UPDATE
                    SET in_app = EXCLUDED.in_app, push = EXCLUDED.push, updated_at = EXCLUDED.updated_at
                    """.trimIndent(),
                ).param("user", userId.value)
                .param("type", preference.type.value)
                .param("inApp", preference.inApp)
                .param("push", preference.push)
                .param("now", utc(clock.instant()))
                .update()
        }
    }

    private fun ResultSet.preference() =
        NotificationPreference(NotificationTypeCode(getString("type")), getBoolean("in_app"), getBoolean("push"))
}

/** Dispositivos de push com `JdbcClient`; o token vai cifrado pelo [FieldCipher] e é decifrado só na leitura. */
@Component
class JdbcPushDeviceStore(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
) : PushDeviceStore {
    override fun lockRegistrations() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('notifications.push-device'))").query().singleRow()
    }

    override fun add(device: PushDevice) {
        jdbc
            .sql(
                """
                INSERT INTO notifications.push_device (id, user_id, session_id, platform, token, token_sha256, active,
                                                       created_at, deactivated_at, deactivation_reason, version)
                VALUES (:id, :user, :session, :platform, :token, :sha, :active, :createdAt, :deactivatedAt, :reason,
                        :version)
                """.trimIndent(),
            ).param("id", device.id.value)
            .param("user", device.userId.value)
            .param("session", device.sessionId.value)
            .param("platform", device.platform.name)
            .param("token", cipher.encrypt(device.token))
            .param("sha", device.tokenSha256)
            .param("active", device.active)
            .param("createdAt", utc(device.createdAt))
            .param("deactivatedAt", device.deactivatedAt?.let(::utc))
            .param("reason", device.deactivationReason?.name)
            .param("version", device.version)
            .update()
    }

    override fun update(device: PushDevice) {
        jdbc
            .sql(
                """
                UPDATE notifications.push_device
                SET active = :active, deactivated_at = :deactivatedAt, deactivation_reason = :reason, version = :version
                WHERE id = :id
                """.trimIndent(),
            ).param("id", device.id.value)
            .param("active", device.active)
            .param("deactivatedAt", device.deactivatedAt?.let(::utc))
            .param("reason", device.deactivationReason?.name)
            .param("version", device.version)
            .update()
    }

    override fun findOf(
        userId: UserId,
        id: PushDeviceId,
    ): PushDevice? =
        list("WHERE id = :id AND user_id = :user", mapOf("id" to id.value, "user" to userId.value)).firstOrNull()

    override fun findForUpdate(id: PushDeviceId): PushDevice? =
        list("WHERE id = :id FOR UPDATE", mapOf("id" to id.value)).firstOrNull()

    override fun activeByTokenOrSession(
        tokenSha256: String,
        sessionId: UUID,
    ): List<PushDevice> =
        list(
            "WHERE active AND (token_sha256 = :sha OR session_id = :session) ORDER BY id FOR UPDATE",
            mapOf("sha" to tokenSha256, "session" to sessionId),
        )

    override fun activeOf(userId: UserId): List<PushDevice> =
        list("WHERE active AND user_id = :user ORDER BY id", mapOf("user" to userId.value))

    override fun hasActive(userId: UserId): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM notifications.push_device WHERE active AND user_id = :user)")
            .param("user", userId.value)
            .query(Boolean::class.java)
            .single()

    override fun page(
        userId: UserId,
        active: Boolean?,
        afterId: UUID?,
        limit: Int,
    ): List<PushDevice> =
        list(
            """
            WHERE user_id = :user
              AND (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
              AND (CAST(:after AS uuid) IS NULL OR id < CAST(:after AS uuid))
            ORDER BY id DESC
            LIMIT :limit
            """.trimIndent(),
            mapOf("user" to userId.value, "active" to active, "after" to afterId, "limit" to limit),
        )

    private fun list(
        where: String,
        params: Map<String, Any?>,
    ): List<PushDevice> =
        jdbc
            .sql(
                "SELECT id, user_id, session_id, platform, token, active, created_at, deactivated_at, " +
                    "deactivation_reason, version FROM notifications.push_device $where",
            ).params(params)
            .query { rs, _ ->
                PushDevice.restore(
                    id = PushDeviceId(rs.getObject("id", UUID::class.java)),
                    userId = UserId(rs.getObject("user_id", UUID::class.java)),
                    sessionId = DeviceSessionId(rs.getObject("session_id", UUID::class.java)),
                    platform = PushPlatform.valueOf(rs.getString("platform")),
                    token = cipher.decrypt(rs.getString("token")),
                    createdAt = checkNotNull(rs.instant("created_at")),
                    active = rs.getBoolean("active"),
                    deactivatedAt = rs.instant("deactivated_at"),
                    deactivationReason = rs.getString("deactivation_reason")?.let(PushDeactivationReason::valueOf),
                    version = rs.getLong("version"),
                )
            }.list()
}

/**
 * O registro dos envios (`notifications.push_delivery`, *append-only*), gravado numa transação própria
 * (`REQUIRES_NEW`): o reprocessamento do outbox desfaz a transação do listener, mas não o que já foi entregue.
 * Dado técnico do envio (o "log de integração" da ARCHITECTURE §12), fora da trilha de auditoria.
 */
@Component
class JdbcPushDeliveryLog(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : PushDeliveryLog {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun contains(
        notificationId: NotificationId,
        deviceId: PushDeviceId,
    ): Boolean =
        jdbc
            .sql(
                "SELECT EXISTS (SELECT 1 FROM notifications.push_delivery " +
                    "WHERE notification_id = :notification AND device_id = :device)",
            ).param("notification", notificationId.value)
            .param("device", deviceId.value)
            .query(Boolean::class.java)
            .single()

    override fun record(
        notificationId: NotificationId,
        deviceId: PushDeviceId,
        outcome: PushDeliveryOutcome,
    ) {
        ownTransaction.executeWithoutResult {
            jdbc
                .sql(
                    """
                    INSERT INTO notifications.push_delivery (notification_id, device_id, outcome, attempted_at)
                    VALUES (:notification, :device, :outcome, :now)
                    ON CONFLICT (notification_id, device_id) DO NOTHING
                    """.trimIndent(),
                ).param("notification", notificationId.value)
                .param("device", deviceId.value)
                .param("outcome", outcome.name)
                .param("now", utc(clock.instant()))
                .update()
        }
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
