package com.zeny.hotel.notifications.application

import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PushDevice

/** Ações e entidades da trilha de auditoria do `notifications`. */
object NotificationsAudit {
    const val NOTIFICATION = "notifications.notification"
    const val PREFERENCE = "notifications.preference"
    const val PUSH_DEVICE = "notifications.push-device"

    const val CREATE = "notifications.notification.create"
    const val READ = "notifications.notification.read"
    const val READ_ALL = "notifications.notification.read-all"
    const val UPDATE_PREFERENCES = "notifications.preference.update"
    const val REGISTER_DEVICE = "notifications.push-device.register"
    const val DEACTIVATE_DEVICE = "notifications.push-device.deactivate"

    /** O sistema desativou o dispositivo (token inválido no FCM, sessão encerrada). */
    const val DISCARD_DEVICE = "notifications.push-device.discard"
}

/** Retrato de uma notificação na trilha: sem o título nem o texto. */
internal fun Notification.auditState() =
    mapOf(
        "type" to type.value,
        "recipientId" to recipientId.toString(),
        "createdAt" to createdAt.toString(),
        "readAt" to readAt?.toString(),
    )

/** Retrato de um dispositivo na trilha: nunca o token. */
internal fun PushDevice.auditState() =
    mapOf(
        "userId" to userId.toString(),
        "sessionId" to sessionId.toString(),
        "platform" to platform.name,
        "active" to active,
        "deactivationReason" to deactivationReason?.name,
    )
