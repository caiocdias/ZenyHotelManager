package com.zeny.hotel.maintenance.application

import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Component
import java.time.LocalDate

/** O pedido de bloqueio da UH: o tipo e as datas de negócio, inclusive. */
data class WorkOrderRoomBlockRequest(
    val kind: OutOfOrderKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

/**
 * O bloqueio da UH pela ordem de serviço (D7-E07, RF-MAN-03): um período OOO/OOS do `rooms`, aberto pela
 * `RoomsApi.openOutOfOrder` (que não confere permissão; o step-up é daqui, como no `OutOfOrderService`) e guardado em
 * `outOfOrderId`. Chamado dentro dos casos de uso auditados e transacionais do [WorkOrdersService].
 *
 * - Abrir: OOO passa por `requireOrApproval(maintenance.work-order.block-room, rooms.outoforder.manage)` (sem a
 *   permissão, `403 APPROVAL_REQUIRED`); OOS exige `rooms.outofservice.manage`. Os erros do `rooms` passam como
 *   estão.
 * - **Bloqueio vigente** = a ordem tem `outOfOrderId` e o período não foi liberado (`releasedOn == null`). Um período
 *   que terminou sozinho, sem liberação, continua vigente (trava o local e um novo bloqueio) até a ordem concluir ou
 *   cancelar, quando a liberação só o encerra, sem mexer no status da UH.
 * - Liberar (concluir ou cancelar): `releaseOutOfOrder(id, MANUAL)` (o enum publicado não muda; a proveniência fica
 *   na trilha `maintenance.*`); a UH volta ao status de retorno, Suja. Período já liberado à mão: nada muda.
 * - Cancelar com bloqueio vigente pede o que o `rooms` pede para liberar: OOO, aprovação
 *   (`maintenance.work-order.cancel`); OOS, `rooms.outofservice.manage`. Concluir não pede nada: o bloqueio foi
 *   autorizado para esta ordem.
 * - Prorrogar ou encurtar o período é pelo `PUT` do período no `rooms`.
 */
@Component
class WorkOrderRoomBlocks(
    private val rooms: RoomsApi,
    private val staff: StaffApi,
    private val authorization: Authorization,
) {
    /** O período vigente da ordem, ou `null` sem bloqueio ou com o período já liberado. */
    fun inEffect(order: WorkOrder): OutOfOrderPeriod? =
        order.outOfOrderId?.let { rooms.outOfOrder(it) }?.takeIf { it.releasedOn == null }

    /** `409 WORK_ORDER_ROOM_ALREADY_BLOCKED` se a ordem já tem bloqueio vigente. */
    fun requireNotBlocked(order: WorkOrder) {
        if (inEffect(order) != null) {
            throw ConflictException(
                MaintenanceErrorCodes.WORK_ORDER_ROOM_ALREADY_BLOCKED,
                "A ordem de serviço já tem um bloqueio da UH vigente.",
            )
        }
    }

    /** Abre o período da UH da ordem, com o step-up do tipo, e devolve o período. */
    fun open(
        order: WorkOrder,
        request: WorkOrderRoomBlockRequest,
        approvalToken: String?,
    ): OutOfOrderPeriod {
        val ref = ref(order.id)
        when (request.kind) {
            OutOfOrderKind.OUT_OF_ORDER -> {
                authorization.requireOrApproval(
                    SensitiveAction(BLOCK_ROOM, RoomsPermissions.OUT_OF_ORDER_MANAGE, ref),
                    approvalToken,
                )
            }

            OutOfOrderKind.OUT_OF_SERVICE -> {
                authorization.require(RoomsPermissions.OUT_OF_SERVICE_MANAGE)
            }
        }
        return rooms.openOutOfOrder(
            checkNotNull(order.roomId),
            request.kind,
            OutOfOrderRequest(
                request.startsOn,
                request.endsOn,
                "OS ${order.number}",
                responsible(order),
            ),
        )
    }

    /** O que o `rooms` exige para liberar o bloqueio vigente da ordem, antes de cancelá-la; sem bloqueio, nada. */
    fun authorizeCancel(
        order: WorkOrder,
        approvalToken: String?,
    ) {
        val period = inEffect(order) ?: return
        when (period.kind) {
            OutOfOrderKind.OUT_OF_ORDER -> {
                authorization.requireOrApproval(
                    SensitiveAction(CANCEL, RoomsPermissions.OUT_OF_ORDER_MANAGE, ref(order.id)),
                    approvalToken,
                )
            }

            OutOfOrderKind.OUT_OF_SERVICE -> {
                authorization.require(RoomsPermissions.OUT_OF_SERVICE_MANAGE)
            }
        }
    }

    /** Libera o período da ordem (concluir ou cancelar); sem bloqueio ou já liberado, não muda nada. */
    fun release(order: WorkOrder) {
        order.outOfOrderId?.let { rooms.releaseOutOfOrder(it, RoomStatusSource.MANUAL) }
    }

    private fun ref(id: WorkOrderId) = "maintenance.work-order:$id"

    private fun responsible(order: WorkOrder): String {
        val name =
            order.assigneeEmployeeId?.let { staff.employees(listOf(it))[it]?.name }
                ?: order.thirdPartyName
                ?: DEFAULT_RESPONSIBLE
        // O `rooms` limita o responsável a 120 caracteres; o terceiro pode ter até 200.
        return name.take(MAX_RESPONSIBLE)
    }

    private companion object {
        const val BLOCK_ROOM = MaintenanceAudit.BLOCK_ROOM
        const val CANCEL = MaintenanceAudit.CANCEL_WORK_ORDER
        const val DEFAULT_RESPONSIBLE = "Manutenção"
        const val MAX_RESPONSIBLE = 120
    }
}
