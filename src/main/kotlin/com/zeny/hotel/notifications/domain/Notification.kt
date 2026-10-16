package com.zeny.hotel.notifications.domain

import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Uma notificação interna (SPEC §8.2.18, RF-NOT-01): do [type], para o [recipientId], com [title] e [body] que
 * vão ao painel e ao push (nunca com dado pessoal de hóspede, RN-13). Lida uma vez ([markRead]); a versão muda a
 * cada mudança, para o tópico de tempo real e o `ETag`.
 */
@Suppress("LongParameterList") // o estado da notificação
class Notification private constructor(
    val id: NotificationId,
    val recipientId: UserId,
    val type: NotificationTypeCode,
    val title: String,
    val body: String,
    val createdAt: Instant,
    readAt: Instant?,
    version: Long,
) {
    var readAt: Instant? = readAt
        private set

    var version: Long = version
        private set

    val read: Boolean get() = readAt != null

    /** Marca como lida em [now]; `false` se já estava (nada muda). */
    fun markRead(now: Instant): Boolean {
        if (read) return false
        readAt = maxOf(now.truncatedTo(ChronoUnit.MICROS), createdAt)
        version++
        return true
    }

    companion object {
        const val MAX_TITLE = 120
        const val MAX_BODY = 1000

        fun create(
            type: NotificationTypeCode,
            title: String,
            body: String,
            recipientId: UserId,
            now: Instant,
        ): Notification {
            val cleanTitle = title.trim()
            val cleanBody = body.trim()
            require(
                cleanTitle.length in 1..MAX_TITLE,
            ) { "Título da notificação vazio ou acima de $MAX_TITLE caracteres" }
            require(cleanBody.length in 1..MAX_BODY) { "Texto da notificação vazio ou acima de $MAX_BODY caracteres" }
            return Notification(
                NotificationId(UuidV7.generate()),
                recipientId,
                type,
                cleanTitle,
                cleanBody,
                now.truncatedTo(ChronoUnit.MICROS),
                null,
                0,
            )
        }

        /** Reconstrói do armazenamento. */
        @Suppress("LongParameterList") // as colunas da notificação
        fun restore(
            id: NotificationId,
            recipientId: UserId,
            type: NotificationTypeCode,
            title: String,
            body: String,
            createdAt: Instant,
            readAt: Instant?,
            version: Long,
        ) = Notification(id, recipientId, type, title, body, createdAt, readAt, version)
    }
}
