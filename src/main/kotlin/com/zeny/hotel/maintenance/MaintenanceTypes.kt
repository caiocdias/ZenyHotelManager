package com.zeny.hotel.maintenance

import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/** Id de uma ordem de serviço. */
@JvmInline
value class WorkOrderId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): WorkOrderId = WorkOrderId(UuidV7.generate())
    }
}

/**
 * Estado de uma ordem de serviço (D3-E07): `OPEN`, `IN_PROGRESS`, `ON_HOLD` (aguardando peça ou terceiro, com motivo),
 * `DONE` e `CANCELLED` (finais). O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class WorkOrderStatus {
    OPEN,
    IN_PROGRESS,
    ON_HOLD,
    DONE,
    CANCELLED,
    ;

    /** Se é um estado final: a ordem não muda mais. */
    val final: Boolean get() = this == DONE || this == CANCELLED
}

/** Prioridade de uma ordem de serviço, da menor para a maior. */
enum class WorkOrderPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

/**
 * Origem de uma ordem de serviço: quem a pediu. `CHECKLIST` é a do pedido por item de checklist (E08), aberta só pelo
 * sistema; o `POST` a recusa. O conjunto pode crescer (a preventiva, no M10): o cliente trata um valor desconhecido
 * sem falhar.
 */
enum class WorkOrderOrigin {
    RECEPTION,
    HOUSEKEEPING,
    MAINTENANCE,
    CHECKLIST,
}

/** Categoria do problema. O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar. */
enum class WorkOrderCategory {
    ELECTRICAL,
    PLUMBING,
    AIR_CONDITIONING,
    FURNITURE,
    OTHER,
}

/** Códigos de erro estáveis do `maintenance` (ARCHITECTURE §7). Referência inexistente no corpo é `400` (H16). */
object MaintenanceErrorCodes {
    /** 404: ordem de serviço inexistente. */
    val WORK_ORDER_NOT_FOUND = ErrorCode("WORK_ORDER_NOT_FOUND")

    /** 400: a UH e a área juntas, ou nenhuma (campos `roomId` e `areaId`). */
    val WORK_ORDER_LOCATION_INVALID = ErrorCode("WORK_ORDER_LOCATION_INVALID")

    /** 400: a UH do corpo não existe (campo `roomId`). */
    val UNKNOWN_ROOM = ErrorCode("UNKNOWN_ROOM")

    /** 400: a área do corpo não existe (campo `areaId`). */
    val UNKNOWN_AREA = ErrorCode("UNKNOWN_AREA")

    /** 400: o colaborador responsável do corpo não existe (campo `assigneeEmployeeId`). */
    val UNKNOWN_EMPLOYEE = ErrorCode("UNKNOWN_EMPLOYEE")

    /** 422: a UH ou a área da ordem está desativada. */
    val WORK_ORDER_LOCATION_INACTIVE = ErrorCode("WORK_ORDER_LOCATION_INACTIVE")

    /** 422: o colaborador responsável está desativado. */
    val WORK_ORDER_ASSIGNEE_INACTIVE = ErrorCode("WORK_ORDER_ASSIGNEE_INACTIVE")

    /** 409: a ação não vale no estado da ordem (inclusive editar uma ordem final). */
    val WORK_ORDER_TRANSITION_NOT_ALLOWED = ErrorCode("WORK_ORDER_TRANSITION_NOT_ALLOWED")

    /** 400: o anexo do corpo não existe ou foi apagado (campo `fileId`). */
    val UNKNOWN_FILE = ErrorCode("UNKNOWN_FILE")

    /** 422: o anexo é de uma finalidade que não é de ordem de serviço (foto ou PDF da ordem). */
    val WORK_ORDER_ATTACHMENT_INVALID = ErrorCode("WORK_ORDER_ATTACHMENT_INVALID")

    /** 404: o anexo não está vinculado a esta ordem (nem vigente). */
    val WORK_ORDER_ATTACHMENT_NOT_FOUND = ErrorCode("WORK_ORDER_ATTACHMENT_NOT_FOUND")

    /** 422: só a ordem de uma UH bloqueia a UH (a ordem de uma área comum não). */
    val WORK_ORDER_NOT_ROOM = ErrorCode("WORK_ORDER_NOT_ROOM")

    /** 409: a ordem já tem um bloqueio da UH vigente. */
    val WORK_ORDER_ROOM_ALREADY_BLOCKED = ErrorCode("WORK_ORDER_ROOM_ALREADY_BLOCKED")

    /** 409: o local da ordem não muda enquanto há um bloqueio da UH vigente. */
    val WORK_ORDER_LOCATION_LOCKED = ErrorCode("WORK_ORDER_LOCATION_LOCKED")

    /** 409: o id da ordem ou da observação (o `commandId` de um comando offline) já é de um registro existente. */
    val WORK_ORDER_ID_IN_USE = ErrorCode("WORK_ORDER_ID_IN_USE")
}
