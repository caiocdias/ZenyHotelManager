package com.zeny.hotel.housekeeping

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode

/**
 * Os tipos de notificação do `housekeeping` (D5-E19), publicados como bean para o catálogo do `notifications`.
 * [TASK_CONFLICT]: o primeiro comando offline de uma ex-dona numa tarefa (RN-22) virou conflito; vai a quem tem
 * `housekeeping.assignments.manage`, com o número da UH (ou o nome da área) e o verbo do comando, sem dado de hóspede
 * (RN-13). Não obrigatório no painel: o sinal durável é o `hasConflict` da tarefa.
 * [LOST_FOUND_EXPIRED] (D3-E06): um item de achados e perdidos guardado passou do prazo de guarda; vai uma vez por
 * item, pelo job diário, a quem tem `housekeeping.lostfound.manage`, só com o id do item, a UH (ou a área) e a data em
 * que foi achado, sem texto livre (descrição e local de guarda). Não obrigatório: o sinal durável é o filtro
 * `expired=true`.
 */
object HousekeepingNotificationTypes : ModuleNotificationTypes {
    override val module = "housekeeping"

    /** Um comando offline de uma ex-dona numa tarefa virou conflito. */
    val TASK_CONFLICT = NotificationTypeCode("housekeeping.task-conflict")

    /** Um item de achados e perdidos guardado passou do prazo de guarda. */
    val LOST_FOUND_EXPIRED = NotificationTypeCode("housekeeping.lost-found-expired")

    override val types =
        listOf(
            NotificationType(TASK_CONFLICT, "Comando offline de uma ex-dona numa tarefa de governança (conflito)"),
            NotificationType(LOST_FOUND_EXPIRED, "Item de achados e perdidos que passou do prazo de guarda"),
        )
}
