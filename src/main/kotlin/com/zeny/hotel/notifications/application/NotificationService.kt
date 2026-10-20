package com.zeny.hotel.notifications.application

import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsErrorCodes
import com.zeny.hotel.notifications.NotificationsRealtime
import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PreferenceRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UserId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Uma notificação como o próprio destinatário a vê. */
data class NotificationView(
    val id: NotificationId,
    val type: NotificationTypeCode,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val readAt: Instant?,
    val version: Long,
)

/**
 * Evento interno do `notifications` (só ele o escuta): a notificação deve ir por push aos dispositivos do
 * destinatário. Publicado na transação de [NotificationsApi.notify] e gravado no outbox no mesmo commit; o
 * listener ([PushDispatchListener]) envia depois, assíncrono, e uma falha fica para o reenvio do outbox (backoff
 * e abandono da E12), sem afetar quem notificou. Só o id (o resto é lido na hora do envio).
 */
data class NotificationPushRequested(
    val notificationId: UUID,
)

/**
 * A API pública ([NotificationsApi.notify]) e o painel do próprio usuário (RF-NOT-01): listar, contar as não
 * lidas, consultar e marcar como lida. O painel não exige permissão (decisão do usuário em 2026-10-16): cada um
 * vê só as próprias, e a de outro usuário responde `404`.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do caso de uso: armazenamento, catálogos, iam, push e canais
class NotificationService(
    private val notifications: NotificationStore,
    private val preferences: PreferenceStore,
    private val devices: PushDeviceStore,
    private val catalog: NotificationTypeCatalog,
    private val permissions: List<ModulePermissions>,
    private val iam: IamApi,
    private val push: PushGateway,
    private val realtime: RealtimePublisher,
    private val events: ApplicationEventPublisher,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) : NotificationsApi {
    @Audited(NotificationsAudit.CREATE)
    @Transactional
    override fun notify(
        type: NotificationTypeCode,
        title: String,
        body: String,
        recipients: NotificationRecipients,
    ): List<NotificationId> {
        val definition = catalog.require(type)
        val declared = permissions.flatMap { module -> module.permissions.map { it.code } }.toSet()
        recipients.permissions.forEach { require(it in declared) { "Permissão fora do catálogo: $it" } }
        val now = clock.instant()
        // Confere os textos antes de qualquer destinatário (erro de programação de quem notifica).
        Notification.create(type, title, body, UserId.new(), now)

        val users =
            recipients.users.filter { iam.findUser(it)?.active == true }.toSet() +
                recipients.permissions.flatMap(iam::usersWithPermission) - recipients.except
        val created =
            users.sortedBy { it.value }.mapNotNull { user ->
                val preference = PreferenceRules.effective(definition, preferences.of(user, type))
                if (!preference.inApp) return@mapNotNull null
                val notification = Notification.create(type, title, body, user, now)
                notifications.add(notification)
                audit.created(NotificationsAudit.NOTIFICATION, notification.id.value) { notification.auditState() }
                publish(notification)
                if (push.enabled && preference.push && devices.hasActive(user)) {
                    events.publishEvent(NotificationPushRequested(notification.id.value))
                }
                notification.id
            }
        if (created.isEmpty()) audit.noChanges()
        return created
    }

    @Transactional(readOnly = true)
    fun list(
        unreadOnly: Boolean,
        page: PageParams,
    ): CursorPage<NotificationView> {
        val user = currentActor.current().userId
        val after = page.decodedCursor()?.uuid(0)
        val rows = notifications.page(user, unreadOnly, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }.map { it.view() }
    }

    @Transactional(readOnly = true)
    fun unreadCount(): Int = notifications.unreadCount(currentActor.current().userId)

    @Transactional(readOnly = true)
    fun get(id: NotificationId): NotificationView =
        (notifications.findOf(currentActor.current().userId, id) ?: throw notFound()).view()

    @Audited(NotificationsAudit.READ)
    @Transactional
    fun markRead(id: NotificationId): NotificationView {
        val notification = notifications.findOfForUpdate(currentActor.current().userId, id) ?: throw notFound()
        audit.track(NotificationsAudit.NOTIFICATION, id.value) { notification.auditState() }
        if (notification.markRead(clock.instant())) {
            notifications.update(notification)
            publish(notification)
        }
        return notification.view()
    }

    /** Marca como lidas todas as não lidas do usuário; devolve quantas. */
    @Audited(NotificationsAudit.READ_ALL)
    @Transactional
    fun markAllRead(): Int {
        val now = clock.instant()
        val unread = notifications.unreadForUpdate(currentActor.current().userId)
        unread.forEach { notification ->
            audit.track(NotificationsAudit.NOTIFICATION, notification.id.value) { notification.auditState() }
            notification.markRead(now)
            notifications.update(notification)
            publish(notification)
        }
        if (unread.isEmpty()) audit.noChanges()
        return unread.size
    }

    private fun publish(notification: Notification) =
        realtime.publish(
            NotificationsRealtime.TOPIC,
            RealtimeMessage(
                NotificationsRealtime.NOTIFICATION,
                notification.id.value,
                notification.version,
                setOf(notification.recipientId),
            ),
        )

    private fun notFound() =
        NotFoundException(NotificationsErrorCodes.NOTIFICATION_NOT_FOUND, "Notificação não encontrada.")
}

internal fun Notification.view() = NotificationView(id, type, title, body, createdAt, readAt, version)
