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

    const val REQUEST_TASK = "housekeeping.task.request"
    const val GENERATE_TASK = "housekeeping.task.generate"
    const val START_TASK = "housekeeping.task.start"
    const val PAUSE_TASK = "housekeeping.task.pause"
    const val RESUME_TASK = "housekeeping.task.resume"
    const val REFUSE_TASK = "housekeeping.task.refuse"
    const val RETRY_TASK = "housekeeping.task.retry"
    const val COMPLETE_TASK = "housekeeping.task.complete"

    const val ASSIGNMENT_PLAN = "housekeeping.assignment-plan"

    const val SUGGEST_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.suggest"
    const val ADJUST_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.adjust"
    const val PUBLISH_ASSIGNMENT_PLAN = "housekeeping.assignment-plan.publish"
}
