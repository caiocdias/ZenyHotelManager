package com.zeny.hotel.maintenance.application

/** Ações e entidades da trilha do `maintenance` (retrato sem texto livre: só ids, estado, custos e tamanhos). */
internal object MaintenanceAudit {
    const val WORK_ORDER = "maintenance.work-order"
    const val WORK_ORDER_ATTACHMENT = "maintenance.work-order-attachment"

    const val OPEN_WORK_ORDER = "maintenance.work-order.open"
    const val UPDATE_WORK_ORDER = "maintenance.work-order.update"
    const val START_WORK_ORDER = "maintenance.work-order.start"
    const val HOLD_WORK_ORDER = "maintenance.work-order.hold"
    const val RESUME_WORK_ORDER = "maintenance.work-order.resume"
    const val COMPLETE_WORK_ORDER = "maintenance.work-order.complete"
    const val CANCEL_WORK_ORDER = "maintenance.work-order.cancel"
    const val ATTACH_FILE = "maintenance.work-order-attachment.attach"
    const val DETACH_FILE = "maintenance.work-order-attachment.detach"
}
