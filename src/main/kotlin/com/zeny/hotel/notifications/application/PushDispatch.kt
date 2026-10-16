package com.zeny.hotel.notifications.application

import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.PushDeactivationReason
import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PreferenceRules
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Escuta o próprio evento do módulo ([NotificationPushRequested]) e envia o push depois do commit de quem
 * notificou, assíncrono e em transação própria (ARCHITECTURE §5 e §12). Uma falha temporária lança
 * [PushDeliveryPostponed]: a publicação fica `FAILED` e o outbox a reenvia com o backoff e o abandono da E12.
 * Idempotente: o reenvio não manda de novo a quem já recebeu ([PushDeliveryLog]).
 */
@Component
class PushDispatchListener(
    private val dispatcher: PushDispatcher,
) {
    @ApplicationModuleListener
    fun on(event: NotificationPushRequested) = dispatcher.dispatch(NotificationId(event.notificationId))
}

/** Falha temporária do push num ou mais dispositivos: o outbox reprocessa a notificação inteira. */
class PushDeliveryPostponed(
    message: String,
) : RuntimeException(message)

/**
 * O envio de uma notificação por push (H7): só com o push configurado, a preferência de push do tipo ainda ligada
 * e aos dispositivos ativos do destinatário cuja sessão do `iam` continua ativa (H9). O dispositivo de sessão
 * revogada ou vencida é desativado (`SESSION_ENDED`) sem envio; o token recusado pelo FCM, desativado
 * (`INVALID_TOKEN`) sem nova tentativa. O log leva só ids e o resultado, nunca o token, o título nem o texto.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do envio
class PushDispatcher(
    private val notifications: NotificationStore,
    private val preferences: PreferenceStore,
    private val devices: PushDeviceStore,
    private val deliveries: PushDeliveryLog,
    private val discards: PushDeviceDiscards,
    private val catalog: NotificationTypeCatalog,
    private val gateway: PushGateway,
    private val iam: IamApi,
) {
    fun dispatch(id: NotificationId) {
        val notification = notifications.find(id)?.takeIf { gateway.enabled && wantsPush(it) } ?: return
        val activeSessions = iam.activeSessionsOf(notification.recipientId)
        var postponed = 0
        devices.activeOf(notification.recipientId).forEach { device ->
            if (device.sessionId !in activeSessions) {
                discards.discard(device.id, PushDeactivationReason.SESSION_ENDED)
                log.info("Push {}: dispositivo {} desativado, sessão encerrada", id, device.id)
                return@forEach
            }
            if (deliveries.contains(id, device.id)) return@forEach
            val message =
                PushMessage(
                    token = device.token,
                    title = notification.title,
                    body = notification.body,
                    data = mapOf("type" to notification.type.value, "notificationId" to id.toString()),
                )
            when (val outcome = gateway.send(message)) {
                PushOutcome.Delivered -> {
                    deliveries.record(id, device.id, PushDeliveryOutcome.DELIVERED)
                }

                PushOutcome.InvalidToken -> {
                    discards.discard(device.id, PushDeactivationReason.INVALID_TOKEN)
                    deliveries.record(id, device.id, PushDeliveryOutcome.INVALID_TOKEN)
                    log.info("Push {}: dispositivo {} desativado, token recusado pelo FCM", id, device.id)
                }

                is PushOutcome.TemporaryFailure -> {
                    postponed++
                    log.warn("Push {}: falha temporária no dispositivo {} ({})", id, device.id, outcome.reason)
                }
            }
        }
        if (postponed > 0) throw PushDeliveryPostponed("Push $id adiado em $postponed dispositivo(s)")
    }

    /** A preferência de push do tipo, conferida de novo na hora do envio (pode ter mudado desde o `notify`). */
    private fun wantsPush(notification: Notification): Boolean =
        catalog.find(notification.type)?.let { type ->
            PreferenceRules.effective(type, preferences.of(notification.recipientId, notification.type)).push
        } ?: false

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PushDispatcher::class.java)
    }
}
