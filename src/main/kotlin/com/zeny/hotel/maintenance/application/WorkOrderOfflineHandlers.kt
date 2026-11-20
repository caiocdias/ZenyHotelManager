package com.zeny.hotel.maintenance.application

import com.zeny.hotel.maintenance.MaintenanceOffline
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import jakarta.validation.constraints.NotNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * `maintenance.open-work-order` (D5-E08): o corpo do `POST /work-orders` (a UH **ou** a área, categoria, prioridade,
 * descrição, origem e prazo). O `commandId` é o id da ordem. Os limites e as referências são os do online, conferidos
 * pelo caso de uso (`REJECTED` com o código dele); o valor de enum desconhecido é `COMMAND_PAYLOAD_INVALID`.
 */
data class OpenWorkOrderPayload(
    val roomId: UUID? = null,
    val areaId: UUID? = null,
    @field:NotNull
    val category: WorkOrderCategory?,
    @field:NotNull
    val priority: WorkOrderPriority?,
    val description: String? = null,
    @field:NotNull
    val origin: WorkOrderOrigin?,
    val dueAt: Instant? = null,
)

/** `maintenance.start-work-order` e `maintenance.resume-work-order`: só a ordem. */
data class WorkOrderCommandPayload(
    @field:NotNull
    val workOrderId: UUID?,
)

/** `maintenance.hold-work-order`: a ordem e o motivo da espera (até 500). */
data class HoldWorkOrderPayload(
    @field:NotNull
    val workOrderId: UUID?,
    val reason: String? = null,
)

/**
 * `maintenance.complete-work-order`: a ordem, as notas e os custos opcionais (texto decimal como na API online, de 0.00
 * a 99999.99, com até 2 casas); os omitidos mantêm o gravado.
 */
data class CompleteWorkOrderPayload(
    @field:NotNull
    val workOrderId: UUID?,
    val notes: String? = null,
    val laborCost: Money? = null,
    val thirdPartyCost: Money? = null,
    val partsCost: Money? = null,
)

/** `maintenance.add-work-order-note`: a ordem e o texto da observação (de 1 a 1000). */
data class AddWorkOrderNotePayload(
    @field:NotNull
    val workOrderId: UUID?,
    val text: String? = null,
)

/**
 * O handler de `maintenance.open-work-order` ([OpenWorkOrderPayload], D5-E08): exige `maintenance.workorders.request`
 * e abre a ordem pelo caso de uso do online, com o `commandId` como id e o `occurredAt` corrigido como o instante de
 * abertura. Um id que já é de uma ordem: `REJECTED WORK_ORDER_ID_IN_USE`, sem sobrescrever.
 */
@Component
class OpenWorkOrderHandler(
    private val workOrders: WorkOrdersService,
) : OfflineCommandHandler<OpenWorkOrderPayload> {
    override val type = MaintenanceOffline.OPEN_WORK_ORDER
    override val payloadType = OpenWorkOrderPayload::class.java

    @Audited(MaintenanceAudit.OPEN_WORK_ORDER_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<OpenWorkOrderPayload>): OfflineCommandOutcome {
        val payload = command.payload
        workOrders.openAt(
            WorkOrderId(command.commandId),
            NewWorkOrder(
                payload.roomId?.let(::RoomId),
                payload.areaId?.let(::AreaId),
                checkNotNull(payload.category),
                checkNotNull(payload.priority),
                payload.description,
                checkNotNull(payload.origin),
                payload.dueAt,
            ),
            command.occurredAt,
        )
        return OfflineCommandOutcome.APPLIED
    }
}

/** O handler de `maintenance.start-work-order` ([WorkOrderCommandPayload]): `manage` no escopo do usuário. */
@Component
class StartWorkOrderHandler(
    private val workOrders: WorkOrdersService,
) : OfflineCommandHandler<WorkOrderCommandPayload> {
    override val type = MaintenanceOffline.START_WORK_ORDER
    override val payloadType = WorkOrderCommandPayload::class.java

    @Audited(MaintenanceAudit.START_WORK_ORDER_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<WorkOrderCommandPayload>): OfflineCommandOutcome {
        workOrders.startAt(WorkOrderId(checkNotNull(command.payload.workOrderId)), null, command.occurredAt)
        return OfflineCommandOutcome.APPLIED
    }
}

/** O handler de `maintenance.hold-work-order` ([HoldWorkOrderPayload]). */
@Component
class HoldWorkOrderHandler(
    private val workOrders: WorkOrdersService,
) : OfflineCommandHandler<HoldWorkOrderPayload> {
    override val type = MaintenanceOffline.HOLD_WORK_ORDER
    override val payloadType = HoldWorkOrderPayload::class.java

    @Audited(MaintenanceAudit.HOLD_WORK_ORDER_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<HoldWorkOrderPayload>): OfflineCommandOutcome {
        val payload = command.payload
        workOrders.holdAt(WorkOrderId(checkNotNull(payload.workOrderId)), payload.reason, null, command.occurredAt)
        return OfflineCommandOutcome.APPLIED
    }
}

/** O handler de `maintenance.resume-work-order` ([WorkOrderCommandPayload]). */
@Component
class ResumeWorkOrderHandler(
    private val workOrders: WorkOrdersService,
) : OfflineCommandHandler<WorkOrderCommandPayload> {
    override val type = MaintenanceOffline.RESUME_WORK_ORDER
    override val payloadType = WorkOrderCommandPayload::class.java

    @Audited(MaintenanceAudit.RESUME_WORK_ORDER_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<WorkOrderCommandPayload>): OfflineCommandOutcome {
        workOrders.resumeAt(WorkOrderId(checkNotNull(command.payload.workOrderId)), null, command.occurredAt)
        return OfflineCommandOutcome.APPLIED
    }
}

/**
 * O handler de `maintenance.complete-work-order` ([CompleteWorkOrderPayload]): conclui como o online, **libera o
 * bloqueio da UH** (a UH volta Suja) sem aprovação de superior (o bloqueio foi autorizado para esta ordem, U3-E07).
 */
@Component
class CompleteWorkOrderHandler(
    private val workOrders: WorkOrdersService,
) : OfflineCommandHandler<CompleteWorkOrderPayload> {
    override val type = MaintenanceOffline.COMPLETE_WORK_ORDER
    override val payloadType = CompleteWorkOrderPayload::class.java

    @Audited(MaintenanceAudit.COMPLETE_WORK_ORDER_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<CompleteWorkOrderPayload>): OfflineCommandOutcome {
        val payload = command.payload
        workOrders.completeAt(
            WorkOrderId(checkNotNull(payload.workOrderId)),
            WorkOrderCompletion(payload.notes, payload.laborCost, payload.thirdPartyCost, payload.partsCost),
            null,
            command.occurredAt,
        )
        return OfflineCommandOutcome.APPLIED
    }
}

/**
 * O handler de `maintenance.add-work-order-note` ([AddWorkOrderNotePayload]): `manage` no escopo do usuário, ou
 * `request` na ordem que ele abriu; o `commandId` é o id da observação e o `occurredAt` corrigido, o instante.
 */
@Component
class AddWorkOrderNoteHandler(
    private val notes: WorkOrderNotes,
    private val actor: CurrentActor,
) : OfflineCommandHandler<AddWorkOrderNotePayload> {
    override val type = MaintenanceOffline.ADD_WORK_ORDER_NOTE
    override val payloadType = AddWorkOrderNotePayload::class.java

    @Audited(MaintenanceAudit.ADD_NOTE_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<AddWorkOrderNotePayload>): OfflineCommandOutcome {
        val payload = command.payload
        notes.add(
            WorkOrderId(checkNotNull(payload.workOrderId)),
            command.commandId,
            payload.text,
            actor.current(),
            command.occurredAt,
        )
        return OfflineCommandOutcome.APPLIED
    }
}
