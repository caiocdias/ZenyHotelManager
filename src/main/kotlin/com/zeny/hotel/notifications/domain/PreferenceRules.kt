package com.zeny.hotel.notifications.domain

import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.NotificationsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException

/**
 * Regras das preferências (RF-NOT-02, decisão do usuário em 2026-10-16): sem preferência gravada, painel e push
 * ligados; o painel de um tipo obrigatório não desliga (o push sim); push sem painel não existe (sem painel, nada
 * é criado, então não haveria o que enviar).
 */
object PreferenceRules {
    /** A preferência que vale para o [type]: a gravada (ou a padrão), com o painel sempre ligado no obrigatório. */
    fun effective(
        type: NotificationType,
        stored: NotificationPreference?,
    ): NotificationPreference {
        val preference = stored ?: NotificationPreference(type.code, inApp = true, push = true)
        return if (type.mandatory && !preference.inApp) preference.copy(inApp = true) else preference
    }

    /**
     * Confere as mudanças pedidas: tipo do catálogo (`400 UNKNOWN_NOTIFICATION_TYPE`), sem tipo repetido nem push
     * sem painel (`400 NOTIFICATION_PREFERENCE_INVALID`), sem painel desligado num obrigatório
     * (`422 NOTIFICATION_TYPE_MANDATORY`).
     */
    fun validate(
        changes: List<NotificationPreference>,
        typeOf: (NotificationTypeCode) -> NotificationType?,
    ) {
        val violations = mutableListOf<FieldViolation>()
        var code = NotificationsErrorCodes.NOTIFICATION_PREFERENCE_INVALID
        val seen = mutableSetOf<NotificationTypeCode>()
        changes.forEachIndexed { index, change ->
            val field = "preferences[$index]"
            when {
                typeOf(change.type) == null -> {
                    code = NotificationsErrorCodes.UNKNOWN_NOTIFICATION_TYPE
                    violations += violation("$field.type", code, "Tipo de notificação desconhecido.")
                }

                !seen.add(change.type) -> {
                    violations += violation("$field.type", code, "O tipo aparece mais de uma vez.")
                }

                change.push && !change.inApp -> {
                    violations += violation("$field.push", code, "Sem o painel, não há push: desligue os dois.")
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(code, violations.first().message, violations)
        }
        changes.firstOrNull { !it.inApp && typeOf(it.type)?.mandatory == true }?.let {
            throw BusinessRuleException(
                NotificationsErrorCodes.NOTIFICATION_TYPE_MANDATORY,
                "As notificações deste tipo sempre aparecem no painel; só o push pode ser desligado.",
            )
        }
    }

    private fun violation(
        field: String,
        code: ErrorCode,
        message: String,
    ) = FieldViolation(field, code.value, message)
}
