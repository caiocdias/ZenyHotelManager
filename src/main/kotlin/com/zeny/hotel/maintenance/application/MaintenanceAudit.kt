package com.zeny.hotel.maintenance.application

/** Ações e entidades da trilha do `maintenance` (retrato sem texto livre: só ids, estado, custos e tamanhos). */
internal object MaintenanceAudit {
    const val WORK_ORDER = "maintenance.work-order"
    const val WORK_ORDER_ATTACHMENT = "maintenance.work-order-attachment"
    const val WORK_ORDER_NOTE = "maintenance.work-order-note"

    const val OPEN_WORK_ORDER = "maintenance.work-order.open"
    const val OPEN_WORK_ORDER_FROM_CHECKLIST = "maintenance.work-order.open-from-checklist"
    const val UPDATE_WORK_ORDER = "maintenance.work-order.update"
    const val START_WORK_ORDER = "maintenance.work-order.start"
    const val HOLD_WORK_ORDER = "maintenance.work-order.hold"
    const val RESUME_WORK_ORDER = "maintenance.work-order.resume"
    const val COMPLETE_WORK_ORDER = "maintenance.work-order.complete"
    const val CANCEL_WORK_ORDER = "maintenance.work-order.cancel"
    const val BLOCK_ROOM = "maintenance.work-order.block-room"
    const val ATTACH_FILE = "maintenance.work-order-attachment.attach"
    const val DETACH_FILE = "maintenance.work-order-attachment.detach"
    const val ADD_NOTE = "maintenance.work-order-note.add"

    // Os comandos offline (D5-E08): os mesmos casos de uso, com a trilha própria `<verbo>-offline`.
    const val OPEN_WORK_ORDER_OFFLINE = "maintenance.work-order.open-offline"
    const val START_WORK_ORDER_OFFLINE = "maintenance.work-order.start-offline"
    const val HOLD_WORK_ORDER_OFFLINE = "maintenance.work-order.hold-offline"
    const val RESUME_WORK_ORDER_OFFLINE = "maintenance.work-order.resume-offline"
    const val COMPLETE_WORK_ORDER_OFFLINE = "maintenance.work-order.complete-offline"
    const val ADD_NOTE_OFFLINE = "maintenance.work-order-note.add-offline"
}
