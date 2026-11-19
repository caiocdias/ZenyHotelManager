package com.zeny.hotel.housekeeping.application

/** Ações e entidades da trilha do `housekeeping` (retrato só da configuração e dos ids, local e estado das tarefas). */
internal object HousekeepingAudit {
    const val CLEANING_TYPE = "housekeeping.cleaning-type"

    const val CREATE_CLEANING_TYPE = "housekeeping.cleaning-type.create"
    const val UPDATE_CLEANING_TYPE = "housekeeping.cleaning-type.update"
    const val DEACTIVATE_CLEANING_TYPE = "housekeeping.cleaning-type.deactivate"
    const val REACTIVATE_CLEANING_TYPE = "housekeeping.cleaning-type.reactivate"

    const val SCHEDULED_CLEANING = "housekeeping.scheduled-cleaning"

    const val CREATE_SCHEDULED_CLEANING = "housekeeping.scheduled-cleaning.create"
    const val UPDATE_SCHEDULED_CLEANING = "housekeeping.scheduled-cleaning.update"
    const val DEACTIVATE_SCHEDULED_CLEANING = "housekeeping.scheduled-cleaning.deactivate"
    const val REACTIVATE_SCHEDULED_CLEANING = "housekeeping.scheduled-cleaning.reactivate"

    const val TASK = "housekeeping.task"

    /** Um conflito offline registrado (D4-E19): entrada própria, sem texto livre (o resumo já não o tem). */
    const val TASK_CONFLICT = "housekeeping.task-conflict"

    const val REQUEST_TASK = "housekeeping.task.request"
    const val GENERATE_TASK = "housekeeping.task.generate"
    const val START_TASK = "housekeeping.task.start"
    const val PAUSE_TASK = "housekeeping.task.pause"
    const val RESUME_TASK = "housekeeping.task.resume"
    const val REFUSE_TASK = "housekeeping.task.refuse"
    const val RETRY_TASK = "housekeeping.task.retry"
    const val COMPLETE_TASK = "housekeeping.task.complete"

    // Os comandos offline da execução (M6 E19): uma entrada por comando, com o retrato da tarefa.
    const val START_TASK_OFFLINE = "housekeeping.task.start-offline"
    const val PAUSE_TASK_OFFLINE = "housekeeping.task.pause-offline"
    const val RESUME_TASK_OFFLINE = "housekeeping.task.resume-offline"
    const val REFUSE_TASK_OFFLINE = "housekeeping.task.refuse-offline"
    const val RETRY_TASK_OFFLINE = "housekeeping.task.retry-offline"
    const val COMPLETE_TASK_OFFLINE = "housekeeping.task.complete-offline"

    /** A inspeção de uma UH (M6 E05): retrato só com ids, estado e as chaves reprovadas. */
    const val INSPECTION = "housekeeping.inspection"

    const val START_INSPECTION = "housekeeping.inspection.start"
    const val COMPLETE_INSPECTION = "housekeeping.inspection.complete"

    /** A inspeção voluntária de uma UH, sem tarefa (D4-E05). */
    const val INSPECT_ROOM = "housekeeping.inspection.inspect-room"

    // Os comandos offline da inspeção (D7-E05): uma entrada por entidade do comando, com a ação `…-offline`.
    const val START_INSPECTION_OFFLINE = "housekeeping.inspection.start-offline"
    const val COMPLETE_INSPECTION_OFFLINE = "housekeeping.inspection.complete-offline"
    const val INSPECT_ROOM_OFFLINE = "housekeeping.inspection.inspect-room-offline"

    /**
     * Um item de achados e perdidos (M6 E06): retrato com os textos livres só pelo tamanho e sem a quem foi devolvido.
     */
    const val LOST_FOUND_ITEM = "housekeeping.lost-found-item"

    const val REGISTER_LOST_FOUND_ITEM = "housekeeping.lost-found-item.register"
    const val RETURN_LOST_FOUND_ITEM = "housekeeping.lost-found-item.return"
    const val DISCARD_LOST_FOUND_ITEM = "housekeeping.lost-found-item.discard"
    const val DONATE_LOST_FOUND_ITEM = "housekeeping.lost-found-item.donate"
    const val CHANGE_LOST_FOUND_STORAGE_PLACE = "housekeeping.lost-found-item.change-storage-place"

    /**
     * Um dano (M6 E06): retrato com a descrição e o motivo da dispensa só pelo tamanho; ids, valores e estado.
     */
    const val DAMAGE_REPORT = "housekeeping.damage-report"

    const val REGISTER_DAMAGE_REPORT = "housekeeping.damage-report.register"
    const val REVIEW_DAMAGE_REPORT = "housekeeping.damage-report.review"
    const val CHARGE_DAMAGE_REPORT = "housekeeping.damage-report.charge"
    const val WAIVE_DAMAGE_REPORT = "housekeeping.damage-report.waive"

    const val ASSIGNMENT_PLAN = "housekeeping.assignment-plan"

    const val SUGGEST_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.suggest"
    const val ADJUST_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.adjust"
    const val PUBLISH_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.publish"
}
