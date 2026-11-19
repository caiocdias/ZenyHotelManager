package com.zeny.hotel.housekeeping

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode

/**
 * Os tipos de notificação do `housekeeping` (D5-E19), publicados como bean para o catálogo do `notifications`.
 * [TASK_CONFLICT]: o primeiro comando offline de uma ex-dona numa tarefa (RN-22) virou conflito; vai a quem tem
 * `housekeeping.assignments.manage`, com o número da UH (ou o nome da área) e o verbo do comando, sem dado de hóspede
 * (RN-13). Não obrigatório no painel: o sinal durável é o `hasConflict` da tarefa.
 */
object HousekeepingNotificationTypes : ModuleNotificationTypes {
    override val module = "housekeeping"

    /** Um comando offline de uma ex-dona numa tarefa virou conflito. */
    val TASK_CONFLICT = NotificationTypeCode("housekeeping.task-conflict")

    override val types =
        listOf(NotificationType(TASK_CONFLICT, "Comando offline de uma ex-dona numa tarefa de governança (conflito)"))
}
