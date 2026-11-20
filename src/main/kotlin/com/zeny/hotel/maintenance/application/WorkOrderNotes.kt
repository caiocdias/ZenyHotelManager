package com.zeny.hotel.maintenance.application

import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.domain.WorkOrderAction
import com.zeny.hotel.maintenance.domain.WorkOrderRules
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Uma observação de uma ordem de serviço: texto livre do operador (sem dado de hóspede, RN-13), de quem e quando. */
data class WorkOrderNoteRow(
    val id: UUID,
    val workOrderId: WorkOrderId,
    val text: String,
    val authorId: UUID,
    val createdAt: Instant,
) {
    /** O retrato da trilha: ids, tamanho e instante; **nunca** o texto. */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "workOrderId" to workOrderId.toString(),
            "authorId" to authorId.toString(),
            "length" to text.codePointCount(0, text.length),
            "createdAt" to createdAt.toString(),
        )
}

/** Uma observação para a resposta da ordem. */
data class WorkOrderNoteView(
    val id: UUID,
    val text: String,
    val authorId: UUID,
    val createdAt: Instant,
)

/** Persistência das observações (D3-E08), só de inserção, na transação corrente. */
interface WorkOrderNoteStore {
    /** As observações de cada ordem de [orderIds], em ordem de registro; ordem sem observação fica fora do mapa. */
    fun of(orderIds: Collection<WorkOrderId>): Map<WorkOrderId, List<WorkOrderNoteRow>>

    fun exists(id: UUID): Boolean

    fun add(row: WorkOrderNoteRow)
}

/**
 * As observações das ordens de serviço (D3-E08): texto livre de 1 a 1000 caracteres, com
 * `maintenance.workorders.manage` (no escopo do usuário) ou, sem ela, `maintenance.workorders.request` na ordem que o
 * próprio ator abriu; a ordem alheia e a inexistente dão o mesmo `403`. Ordem final: `409
 * WORK_ORDER_TRANSITION_NOT_ALLOWED`. Só se insere, e a observação não muda a versão nem o `ETag` da ordem. O texto
 * fica fora da trilha, que leva o fato e o tamanho.
 */
@Service
@Suppress("LongParameterList") // as portas da observação
class WorkOrderNotes(
    private val workOrders: WorkOrdersService,
    private val store: WorkOrderNoteStore,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Registra a observação [text] na ordem [id], com id novo, e devolve a ordem. */
    @Audited(MaintenanceAudit.ADD_NOTE)
    @Transactional
    fun add(
        id: WorkOrderId,
        text: String?,
    ): WorkOrderView = add(id, UUID.randomUUID(), text, actor.current(), at = null)

    /**
     * Registra a observação [noteId] na ordem [workOrderId] como [by], dentro do caso de uso auditado de quem chama (o
     * comando offline passa o `commandId` como id e o `occurredAt` corrigido como [at]). O instante nunca é anterior à
     * última mudança da ordem; sem [at], agora. Um id que já é de uma observação: `409 WORK_ORDER_ID_IN_USE`.
     */
    internal fun add(
        workOrderId: WorkOrderId,
        noteId: UUID,
        text: String?,
        by: Actor,
        at: Instant?,
    ): WorkOrderView {
        val manager = authorization.grantOrNull(MaintenancePermissions.MANAGE) != null
        val order = workOrders.loadLockedForNote(workOrderId, manager)
        order.requireActive(WorkOrderAction.NOTE)
        val clean = WorkOrderRules.freeText("text", text, MAX_TEXT)
        if (store.exists(noteId)) {
            throw ConflictException(
                MaintenanceErrorCodes.WORK_ORDER_ID_IN_USE,
                "O identificador da observação já é de uma observação existente.",
            )
        }
        val now = at?.truncatedTo(ChronoUnit.MICROS) ?: clock.instant().truncatedTo(ChronoUnit.MICROS)
        val row = WorkOrderNoteRow(noteId, workOrderId, clean, by.userId.value, maxOf(now, order.updatedAt))
        store.add(row)
        audit.created(MaintenanceAudit.WORK_ORDER_NOTE, noteId) { row.auditState() }
        return workOrders.viewOf(order)
    }

    companion object {
        const val MAX_TEXT = 1000
    }
}
