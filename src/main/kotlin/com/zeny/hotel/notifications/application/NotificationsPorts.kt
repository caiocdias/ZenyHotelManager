package com.zeny.hotel.notifications.application

import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PushDevice
import com.zeny.hotel.notifications.domain.PushDeviceId
import com.zeny.hotel.shared.UserId
import java.util.UUID

/** Persistência das notificações (`notifications.notification`). */
interface NotificationStore {
    fun add(notification: Notification)

    /** A notificação do [recipientId] com o [id], ou `null` (inexistente ou de outro usuário). */
    fun findOf(
        recipientId: UserId,
        id: NotificationId,
    ): Notification?

    /** O mesmo, travando a linha até o fim da transação. */
    fun findOfForUpdate(
        recipientId: UserId,
        id: NotificationId,
    ): Notification?

    fun find(id: NotificationId): Notification?

    fun update(notification: Notification)

    /** As do [recipientId], da mais nova para a mais antiga, depois de [afterId]; só as não lidas com [unreadOnly]. */
    fun page(
        recipientId: UserId,
        unreadOnly: Boolean,
        afterId: UUID?,
        limit: Int,
    ): List<Notification>

    fun unreadCount(recipientId: UserId): Int

    /** As não lidas do [recipientId], travadas até o fim da transação. */
    fun unreadForUpdate(recipientId: UserId): List<Notification>
}

/** Persistência das preferências (`notifications.notification_preference`). */
interface PreferenceStore {
    fun of(userId: UserId): List<NotificationPreference>

    fun of(
        userId: UserId,
        type: NotificationTypeCode,
    ): NotificationPreference?

    /** Grava (insere ou troca) as preferências do usuário. */
    fun save(
        userId: UserId,
        preferences: List<NotificationPreference>,
    )
}

/** Persistência dos dispositivos de push (`notifications.push_device`), com o token cifrado. */
interface PushDeviceStore {
    /** Trava o registro de dispositivos até o fim da transação (registros simultâneos se enfileiram). */
    fun lockRegistrations()

    fun add(device: PushDevice)

    fun update(device: PushDevice)

    fun findOf(
        userId: UserId,
        id: PushDeviceId,
    ): PushDevice?

    fun findForUpdate(id: PushDeviceId): PushDevice?

    /** O ativo com o token de impressão [tokenSha256], ou o ativo da sessão [sessionId]. */
    fun activeByTokenOrSession(
        tokenSha256: String,
        sessionId: UUID,
    ): List<PushDevice>

    fun activeOf(userId: UserId): List<PushDevice>

    fun hasActive(userId: UserId): Boolean

    /** Os do usuário, do mais novo para o mais antigo, depois de [afterId]; só os ativos (ou inativos) com [active]. */
    fun page(
        userId: UserId,
        active: Boolean?,
        afterId: UUID?,
        limit: Int,
    ): List<PushDevice>
}

/**
 * Registro técnico do que o push já fez com cada notificação em cada dispositivo (`notifications.push_delivery`,
 * *append-only*), gravado na sua própria transação: o reprocessamento pelo outbox (que desfaz a transação do
 * listener) não manda de novo a quem já recebeu.
 */
interface PushDeliveryLog {
    fun contains(
        notificationId: NotificationId,
        deviceId: PushDeviceId,
    ): Boolean

    fun record(
        notificationId: NotificationId,
        deviceId: PushDeviceId,
        outcome: PushDeliveryOutcome,
    )
}

/** O que ficou registrado de um envio. */
enum class PushDeliveryOutcome {
    DELIVERED,
    INVALID_TOKEN,
}

/** Uma mensagem para um aparelho (H7: título e texto da notificação, `type` e `notificationId` em `data`). */
data class PushMessage(
    val token: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
) {
    override fun toString(): String = "PushMessage(data=$data)"
}

/** O resultado de um envio. */
sealed interface PushOutcome {
    /** O FCM aceitou a mensagem. */
    data object Delivered : PushOutcome

    /** O token não vale mais: o dispositivo é desativado, sem nova tentativa. */
    data object InvalidToken : PushOutcome

    /** Falha que pode passar (rede, cota, indisponibilidade, credencial): o outbox reprocessa. */
    data class TemporaryFailure(
        val reason: String,
    ) : PushOutcome
}

/**
 * Porta do envio de push (ARCHITECTURE §12, H7), implementada pelo adaptador do FCM HTTP v1. Desligada
 * ([enabled] falso) sem a credencial `ZENY_FCM_CREDENTIALS`: aí só o painel é entregue.
 */
interface PushGateway {
    val enabled: Boolean

    fun send(message: PushMessage): PushOutcome
}
