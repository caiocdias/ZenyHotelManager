package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.rooms.RoomStatusOutcome
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Component

/**
 * O aviso de UH liberada (RF-GOV-07, D5-E05), num ponto só: `notifications.room-released` quando a operação
 * condicional do `rooms` levou a UH de **não pronta** a **pronta** para check-in (a prontidão de antes e de depois
 * decidida com a linha travada). UH que já estava pronta, ou que não ficou (ocupada, em OOO, com inspeção pendente),
 * não avisa. Vai aos usuários com `housekeeping.tasks.read` (Recepção, Governanta e Gerente), menos quem causou a
 * liberação (o inspetor ou quem concluiu a tarefa), na transação de quem chama. Só o número da UH, nada de hóspede
 * (RN-13). O painel da recepção rebusca a prontidão pela mensagem `rooms.room-status` que o `rooms` já publica.
 */
@Component
class RoomReleaseNotices(
    private val rooms: RoomsApi,
    private val notifications: NotificationsApi,
) {
    fun notifyIfReleased(
        outcome: RoomStatusOutcome,
        releasedBy: UserId,
    ) {
        if (outcome.before.readyForCheckIn || !outcome.after.readyForCheckIn) return
        val number = rooms.room(outcome.after.roomId)?.number ?: "?"
        notifications.notify(
            NotificationsTypes.ROOM_RELEASED,
            "UH $number liberada",
            "A UH $number está pronta para check-in.",
            NotificationRecipients(
                permissions = setOf(HousekeepingPermissions.TASKS_READ),
                except = setOf(releasedBy),
            ),
        )
    }
}
