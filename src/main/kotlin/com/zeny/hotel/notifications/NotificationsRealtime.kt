package com.zeny.hotel.notifications

import com.zeny.hotel.shared.RealtimeTopic

/**
 * O tópico de tempo real do `notifications` (ARCHITECTURE §10, M2 E09): **pessoal**, sem permissão (decisão do
 * usuário em 2026-10-16): qualquer usuário autenticado se inscreve em `GET /api/v1/stream?topics=notifications`
 * e recebe só as mensagens das próprias notificações.
 */
object NotificationsRealtime {
    val TOPIC = RealtimeTopic("notifications")

    /** Uma notificação criada ou lida: `id` = a notificação, `version` = a versão dela. */
    const val NOTIFICATION = "notifications.notification"
}
