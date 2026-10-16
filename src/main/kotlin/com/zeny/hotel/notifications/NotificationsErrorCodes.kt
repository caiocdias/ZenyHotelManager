package com.zeny.hotel.notifications

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do módulo `notifications` (ARCHITECTURE §7). */
object NotificationsErrorCodes {
    /** 404: notificação inexistente ou de outro usuário (não revela que existe). */
    val NOTIFICATION_NOT_FOUND = ErrorCode("NOTIFICATION_NOT_FOUND")

    /** 400: tipo de notificação fora do catálogo (campo `preferences[i].type`). */
    val UNKNOWN_NOTIFICATION_TYPE = ErrorCode("UNKNOWN_NOTIFICATION_TYPE")

    /** 400: preferência incoerente: o mesmo tipo duas vezes, ou push ligado com o painel desligado. */
    val NOTIFICATION_PREFERENCE_INVALID = ErrorCode("NOTIFICATION_PREFERENCE_INVALID")

    /** 422: painel desligado para um tipo obrigatório (o push continua desligável). */
    val NOTIFICATION_TYPE_MANDATORY = ErrorCode("NOTIFICATION_TYPE_MANDATORY")

    /** 404: dispositivo de push inexistente ou de outro usuário. */
    val PUSH_DEVICE_NOT_FOUND = ErrorCode("PUSH_DEVICE_NOT_FOUND")
}
