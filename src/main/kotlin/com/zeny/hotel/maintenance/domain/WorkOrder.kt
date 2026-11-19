package com.zeny.hotel.maintenance.domain

import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import java.time.Instant

/** As ações sobre uma ordem de serviço (D3-E07). */
enum class WorkOrderAction {
    UPDATE,
    START,
    HOLD,
    RESUME,
    COMPLETE,
    CANCEL,
}

/** `409 WORK_ORDER_TRANSITION_NOT_ALLOWED`, com o estado em `workOrderStatus` e a ação em `action`. */
class WorkOrderTransitionNotAllowedException(
    val action: WorkOrderAction,
    val status: WorkOrderStatus,
) : ConflictException(
        MaintenanceErrorCodes.WORK_ORDER_TRANSITION_NOT_ALLOWED,
        "A ação não vale no estado atual da ordem de serviço.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("workOrderStatus" to status.name, "action" to action.name)
}

/** O que a edição (`PUT`) substitui: todos os campos editáveis, os três custos inclusive. */
data class WorkOrderEdit(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val category: WorkOrderCategory,
    val priority: WorkOrderPriority,
    val description: String?,
    val assigneeEmployeeId: EmployeeId?,
    val thirdPartyName: String?,
    val dueAt: Instant?,
    val laborCost: Money,
    val thirdPartyCost: Money,
    val partsCost: Money,
)

/**
 * Uma ordem de serviço corretiva (RF-MAN-01 e RF-MAN-02, SPEC §5.15, D2-E07): numa UH **ou** numa área, com a
 * [description] (texto livre do operador: **não escreva dado de hóspede**, RN-13), a categoria, a prioridade, a
 * origem, o responsável, o terceiro e os três custos (de 0 a [MAX_COST]). Máquina de estados (D3-E07):
 * `OPEN → IN_PROGRESS` ([start]), `IN_PROGRESS → ON_HOLD` ([hold], com motivo), `ON_HOLD → IN_PROGRESS` ([resume]),
 * `OPEN | IN_PROGRESS | ON_HOLD → DONE` ([complete]) e `→ CANCELLED` ([cancel], com motivo). `DONE` e `CANCELLED` são
 * finais: editar ou agir numa ordem final é [WorkOrderTransitionNotAllowedException]. Uma ação recusada não muda
 * nada. Nunca é apagada.
 */
@Suppress("LongParameterList", "TooManyFunctions") // os campos da ordem, só pelo open e pelo restore
class WorkOrder private constructor(
    val id: WorkOrderId,
    val number: DocumentNumber,
    roomId: RoomId?,
    areaId: AreaId?,
    category: WorkOrderCategory,
    priority: WorkOrderPriority,
    description: String,
    val origin: WorkOrderOrigin,
    assigneeEmployeeId: EmployeeId?,
    thirdPartyName: String?,
    status: WorkOrderStatus,
    holdReason: String?,
    laborCost: Money,
    thirdPartyCost: Money,
    partsCost: Money,
    val openedBy: UserId,
    val openedAt: Instant,
    dueAt: Instant?,
    startedAt: Instant?,
    closedAt: Instant?,
    completionNotes: String?,
    cancellationReason: String?,
    outOfOrderId: OutOfOrderId?,
    updatedAt: Instant,
    version: Long,
) {
    var roomId: RoomId? = roomId
        private set

    var areaId: AreaId? = areaId
        private set

    var category: WorkOrderCategory = category
        private set

    var priority: WorkOrderPriority = priority
        private set

    var description: String = description
        private set

    var assigneeEmployeeId: EmployeeId? = assigneeEmployeeId
        private set

    var thirdPartyName: String? = thirdPartyName
        private set

    var status: WorkOrderStatus = status
        private set

    var holdReason: String? = holdReason
        private set

    var laborCost: Money = laborCost
        private set

    var thirdPartyCost: Money = thirdPartyCost
        private set

    var partsCost: Money = partsCost
        private set

    var dueAt: Instant? = dueAt
        private set

    var startedAt: Instant? = startedAt
        private set

    var closedAt: Instant? = closedAt
        private set

    var completionNotes: String? = completionNotes
        private set

    var cancellationReason: String? = cancellationReason
        private set

    /** O período OOO/OOS aberto para esta ordem (Task 3 da E07); nulo sem bloqueio. */
    var outOfOrderId: OutOfOrderId? = outOfOrderId
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** A versão gravada (o `ETag`); a persistência a sobe a cada alteração. */
    var version: Long = version
        internal set

    /** `OPEN → IN_PROGRESS`; grava o início no primeiro `start`. */
    fun start(now: Instant) {
        requireStatus(WorkOrderAction.START, WorkOrderStatus.OPEN)
        startedAt = startedAt ?: now
        move(WorkOrderStatus.IN_PROGRESS, now)
    }

    /** `IN_PROGRESS → ON_HOLD` com o [reason] (obrigatório, até [MAX_REASON]). */
    fun hold(
        reason: String?,
        now: Instant,
    ) {
        requireStatus(WorkOrderAction.HOLD, WorkOrderStatus.IN_PROGRESS)
        val text = WorkOrderRules.freeText("holdReason", reason, MAX_REASON)
        holdReason = text
        move(WorkOrderStatus.ON_HOLD, now)
    }

    /** `ON_HOLD → IN_PROGRESS`; o motivo da espera sai. */
    fun resume(now: Instant) {
        requireStatus(WorkOrderAction.RESUME, WorkOrderStatus.ON_HOLD)
        holdReason = null
        move(WorkOrderStatus.IN_PROGRESS, now)
    }

    /**
     * `OPEN | IN_PROGRESS | ON_HOLD → DONE`, com as [notes] opcionais (até [MAX_NOTES]) e os custos que vierem (os
     * nulos mantêm o valor gravado).
     */
    fun complete(
        notes: String?,
        laborCost: Money?,
        thirdPartyCost: Money?,
        partsCost: Money?,
        now: Instant,
    ) {
        requireStatus(
            WorkOrderAction.COMPLETE,
            WorkOrderStatus.OPEN,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD,
        )
        val text = WorkOrderRules.optionalFreeText("completionNotes", notes, MAX_NOTES)
        laborCost?.let { WorkOrderRules.requireCost("laborCost", it) }
        thirdPartyCost?.let { WorkOrderRules.requireCost("thirdPartyCost", it) }
        partsCost?.let { WorkOrderRules.requireCost("partsCost", it) }
        completionNotes = text
        laborCost?.let { this.laborCost = it }
        thirdPartyCost?.let { this.thirdPartyCost = it }
        partsCost?.let { this.partsCost = it }
        holdReason = null
        closedAt = now
        move(WorkOrderStatus.DONE, now)
    }

    /** `OPEN | IN_PROGRESS | ON_HOLD → CANCELLED` com o [reason] (obrigatório, até [MAX_REASON]). */
    fun cancel(
        reason: String?,
        now: Instant,
    ) {
        requireStatus(
            WorkOrderAction.CANCEL,
            WorkOrderStatus.OPEN,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD,
        )
        val text = WorkOrderRules.freeText("cancellationReason", reason, MAX_REASON)
        cancellationReason = text
        holdReason = null
        closedAt = now
        move(WorkOrderStatus.CANCELLED, now)
    }

    /**
     * Confere a edição [edit] como o [update] a confere (ordem não final, um só local, textos e custos), sem mudar
     * nada: o caso de uso a chama antes de validar as referências (local e responsável) com os valores novos.
     */
    fun checkUpdate(edit: WorkOrderEdit) {
        requireStatus(
            WorkOrderAction.UPDATE,
            WorkOrderStatus.OPEN,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD,
        )
        WorkOrderRules.requireOneLocation(edit.roomId, edit.areaId)
        WorkOrderRules.freeText("description", edit.description, MAX_DESCRIPTION)
        WorkOrderRules.optionalFreeText("thirdPartyName", edit.thirdPartyName, MAX_THIRD_PARTY)
        WorkOrderRules.requireCost("laborCost", edit.laborCost)
        WorkOrderRules.requireCost("thirdPartyCost", edit.thirdPartyCost)
        WorkOrderRules.requireCost("partsCost", edit.partsCost)
    }

    /** Substitui os campos editáveis (`PUT`) de uma ordem que não é final. */
    fun update(
        edit: WorkOrderEdit,
        now: Instant,
    ) {
        checkUpdate(edit)
        val text = WorkOrderRules.freeText("description", edit.description, MAX_DESCRIPTION)
        val third = WorkOrderRules.optionalFreeText("thirdPartyName", edit.thirdPartyName, MAX_THIRD_PARTY)
        roomId = edit.roomId
        areaId = edit.areaId
        category = edit.category
        priority = edit.priority
        description = text
        assigneeEmployeeId = edit.assigneeEmployeeId
        thirdPartyName = third
        dueAt = edit.dueAt
        laborCost = edit.laborCost
        thirdPartyCost = edit.thirdPartyCost
        partsCost = edit.partsCost
        updatedAt = now
    }

    /**
     * O retrato da trilha: ids, local, estado, custos e datas; os textos livres (descrição, motivos, notas e nome do
     * terceiro) só pelo tamanho (RN-13).
     */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "number" to number.toString(),
            "roomId" to roomId?.toString(),
            "areaId" to areaId?.toString(),
            "category" to category.name,
            "priority" to priority.name,
            "origin" to origin.name,
            "status" to status.name,
            "assigneeEmployeeId" to assigneeEmployeeId?.toString(),
            "descriptionLength" to length(description),
            "thirdPartyNameLength" to thirdPartyName?.let(::length),
            "holdReasonLength" to holdReason?.let(::length),
            "completionNotesLength" to completionNotes?.let(::length),
            "cancellationReasonLength" to cancellationReason?.let(::length),
            "laborCost" to laborCost.toString(),
            "thirdPartyCost" to thirdPartyCost.toString(),
            "partsCost" to partsCost.toString(),
            "openedBy" to openedBy.toString(),
            "openedAt" to openedAt.toString(),
            "dueAt" to dueAt?.toString(),
            "startedAt" to startedAt?.toString(),
            "closedAt" to closedAt?.toString(),
            "outOfOrderId" to outOfOrderId?.toString(),
        )

    private fun length(text: String) = text.codePointCount(0, text.length)

    private fun move(
        to: WorkOrderStatus,
        now: Instant,
    ) {
        status = to
        updatedAt = now
    }

    private fun requireStatus(
        action: WorkOrderAction,
        vararg allowed: WorkOrderStatus,
    ) {
        if (status !in allowed) throw WorkOrderTransitionNotAllowedException(action, status)
    }

    companion object {
        const val MAX_DESCRIPTION = 2000
        const val MAX_NOTES = 2000
        const val MAX_REASON = 500
        const val MAX_THIRD_PARTY = 200

        /** O teto de cada custo. */
        val MAX_COST: Money = Money.of("99999.99")

        /** Abre uma ordem `OPEN`, com os três custos em zero, na UH **ou** na área. */
        @Suppress("LongParameterList")
        fun open(
            id: WorkOrderId,
            number: DocumentNumber,
            roomId: RoomId?,
            areaId: AreaId?,
            category: WorkOrderCategory,
            priority: WorkOrderPriority,
            description: String?,
            origin: WorkOrderOrigin,
            dueAt: Instant?,
            openedBy: UserId,
            now: Instant,
        ): WorkOrder {
            WorkOrderRules.requireOneLocation(roomId, areaId)
            return WorkOrder(
                id,
                number,
                roomId,
                areaId,
                category,
                priority,
                WorkOrderRules.freeText("description", description, MAX_DESCRIPTION),
                origin,
                null,
                null,
                WorkOrderStatus.OPEN,
                null,
                Money.ZERO,
                Money.ZERO,
                Money.ZERO,
                openedBy,
                now,
                dueAt,
                null,
                null,
                null,
                null,
                null,
                now,
                0,
            )
        }

        /** Reconstrói a ordem gravada (a persistência). */
        @Suppress("LongParameterList")
        fun restore(
            id: WorkOrderId,
            number: DocumentNumber,
            roomId: RoomId?,
            areaId: AreaId?,
            category: WorkOrderCategory,
            priority: WorkOrderPriority,
            description: String,
            origin: WorkOrderOrigin,
            assigneeEmployeeId: EmployeeId?,
            thirdPartyName: String?,
            status: WorkOrderStatus,
            holdReason: String?,
            laborCost: Money,
            thirdPartyCost: Money,
            partsCost: Money,
            openedBy: UserId,
            openedAt: Instant,
            dueAt: Instant?,
            startedAt: Instant?,
            closedAt: Instant?,
            completionNotes: String?,
            cancellationReason: String?,
            outOfOrderId: OutOfOrderId?,
            updatedAt: Instant,
            version: Long,
        ) = WorkOrder(
            id,
            number,
            roomId,
            areaId,
            category,
            priority,
            description,
            origin,
            assigneeEmployeeId,
            thirdPartyName,
            status,
            holdReason,
            laborCost,
            thirdPartyCost,
            partsCost,
            openedBy,
            openedAt,
            dueAt,
            startedAt,
            closedAt,
            completionNotes,
            cancellationReason,
            outOfOrderId,
            updatedAt,
            version,
        )
    }
}

/** As regras dos campos da ordem: texto livre, custo e local. */
internal object WorkOrderRules {
    private const val NUL = '\u0000'

    /** Texto livre obrigatório, aparado, de 1 a [max] code points e sem NUL; a recusa não ecoa o texto. */
    fun freeText(
        field: String,
        raw: String?,
        max: Int,
    ): String =
        optionalFreeText(field, raw, max)
            ?: throw invalid(field, "Informe $field com até $max caracteres.")

    /** O texto livre opcional aparado, ou `null` se vazio; acima de [max] ou com NUL, `400`. */
    fun optionalFreeText(
        field: String,
        raw: String?,
        max: Int,
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.codePointCount(0, trimmed.length) > max || NUL in trimmed) {
            throw invalid(field, "Informe $field com até $max caracteres.")
        }
        return trimmed
    }

    /** O custo, de 0,00 a [WorkOrder.MAX_COST]. */
    fun requireCost(
        field: String,
        cost: Money,
    ) {
        if (cost.isNegative() || cost > WorkOrder.MAX_COST) {
            throw invalid(field, "Informe $field de 0.00 a ${WorkOrder.MAX_COST}.")
        }
    }

    /** O local: a UH **ou** a área, um só; os dois ou nenhum, `400 WORK_ORDER_LOCATION_INVALID`. */
    fun requireOneLocation(
        roomId: RoomId?,
        areaId: AreaId?,
    ) {
        if ((roomId == null) != (areaId == null)) return
        val code = MaintenanceErrorCodes.WORK_ORDER_LOCATION_INVALID
        val message = "Informe o local: a UH (roomId) ou a área (areaId), um só."
        throw InvalidInputException(
            code,
            message,
            listOf("roomId", "areaId").map { FieldViolation(it, code.value, message) },
        )
    }

    fun invalid(
        field: String,
        message: String,
        code: ErrorCode = ErrorCodes.VALIDATION_FAILED,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
