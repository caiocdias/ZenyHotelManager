package com.zeny.hotel.maintenance.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.maintenance.domain.WorkOrderEdit
import com.zeny.hotel.maintenance.domain.WorkOrderRules
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A chave da ordenação da lista: o instante de abertura (desc) e o id (desc). */
data class WorkOrderPageKey(
    val openedAt: Instant,
    val id: UUID,
)

/**
 * Os filtros da lista e o escopo `OWN` (na própria consulta): [ownerUserId] e [ownerEmployeeId] limitam às ordens que o
 * usuário abriu ou de que o colaborador dele é o responsável.
 */
data class WorkOrderFilter(
    val ownerUserId: UUID? = null,
    val ownerEmployeeId: UUID? = null,
    val status: WorkOrderStatus? = null,
    val roomId: RoomId? = null,
    val areaId: AreaId? = null,
    val priority: WorkOrderPriority? = null,
    val assigneeEmployeeId: EmployeeId? = null,
)

/** Persistência das ordens de serviço (D2-E07), na transação corrente. */
interface WorkOrderStore {
    fun insert(order: WorkOrder)

    fun find(id: WorkOrderId): WorkOrder?

    /** A ordem com a linha travada (`FOR UPDATE`), ou `null` se não existe. */
    fun findForUpdate(id: WorkOrderId): WorkOrder?

    /** Grava a ordem travada e sobe a versão; a nova já vale em [order]. */
    fun update(order: WorkOrder)

    /** Até [limit] ordens depois de [after], da mais recente para a mais antiga, com os [filter]s. */
    fun page(
        filter: WorkOrderFilter,
        after: WorkOrderPageKey?,
        limit: Int,
    ): List<WorkOrder>
}

/** A abertura de uma ordem: só o que quem pede informa (responsável, terceiro e custos são da edição). */
data class NewWorkOrder(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val category: WorkOrderCategory,
    val priority: WorkOrderPriority,
    val description: String?,
    val origin: WorkOrderOrigin,
    val dueAt: Instant?,
)

/** A conclusão: notas opcionais e os custos que o técnico informa agora (os nulos mantêm o gravado). */
data class WorkOrderCompletion(
    val notes: String?,
    val laborCost: Money?,
    val thirdPartyCost: Money?,
    val partsCost: Money?,
)

/** Uma ordem de serviço para a resposta. */
data class WorkOrderView(
    val id: WorkOrderId,
    val number: String,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val category: WorkOrderCategory,
    val priority: WorkOrderPriority,
    val description: String,
    val origin: WorkOrderOrigin,
    val assigneeEmployeeId: EmployeeId?,
    val thirdPartyName: String?,
    val status: WorkOrderStatus,
    val holdReason: String?,
    val laborCost: Money,
    val thirdPartyCost: Money,
    val partsCost: Money,
    val openedBy: UUID,
    val openedAt: Instant,
    val dueAt: Instant?,
    val startedAt: Instant?,
    val closedAt: Instant?,
    val completionNotes: String?,
    val cancellationReason: String?,
    val outOfOrderId: OutOfOrderId?,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * Ordens de serviço corretivas (RF-MAN-01 e RF-MAN-02, D2 a D4-E07): a abertura, a consulta, a edição e as ações
 * `start`, `hold`, `resume`, `complete` e `cancel`. A permissão vem antes de qualquer recusa que dependa da ordem:
 * - abrir, `maintenance.workorders.request` (só `ALL`);
 * - consultar, `maintenance.workorders.read`; editar e agir, `maintenance.workorders.manage`. Com `OWN`, só as ordens
 *   que o usuário abriu ou de que o colaborador dele é o responsável; a ordem de outro, ou inexistente, dá o mesmo
 *   `403 RESOURCE_OUT_OF_SCOPE` (com `ALL`, a inexistente é `404 WORK_ORDER_NOT_FOUND`);
 * - depois, a trava da ordem (`FOR UPDATE`), a versão (`412`) e o estado (`409`).
 *
 * A descrição, os motivos, as notas e o nome do terceiro são texto livre do operador (**sem dado de hóspede**, RN-13) e
 * não vão à trilha.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas das ordens; uma função por ação
class WorkOrdersService(
    private val store: WorkOrderStore,
    private val rooms: RoomsApi,
    private val staff: StaffApi,
    private val hotel: HotelApi,
    private val numbers: DocumentNumberGenerator,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * Abre uma ordem `OPEN` (custos 0, sem responsável nem terceiro). Origem `CHECKLIST`: `400` no campo `origin` (é da
     * E08). Local inexistente: `400 UNKNOWN_ROOM`/`UNKNOWN_AREA` no campo (H16); desativado: `422
     * WORK_ORDER_LOCATION_INACTIVE`.
     */
    @Audited(MaintenanceAudit.OPEN_WORK_ORDER)
    @Transactional
    fun open(command: NewWorkOrder): WorkOrderView {
        authorization.require(MaintenancePermissions.REQUEST)
        WorkOrderRules.requireOneLocation(command.roomId, command.areaId)
        WorkOrderRules.freeText("description", command.description, WorkOrder.MAX_DESCRIPTION)
        if (command.origin == WorkOrderOrigin.CHECKLIST) {
            throw WorkOrderRules.invalid("origin", "A origem CHECKLIST é reservada ao pedido por item de checklist.")
        }
        requireLocation(command.roomId, command.areaId)
        val now = now()
        val order =
            WorkOrder.open(
                WorkOrderId.new(),
                numbers.next(WORK_ORDER_NUMBER, hotel.currentBusinessDate().year),
                command.roomId,
                command.areaId,
                command.category,
                command.priority,
                command.description,
                command.origin,
                command.dueAt,
                actor.current().userId,
                now,
            )
        store.insert(order)
        audit.created(MaintenanceAudit.WORK_ORDER, order.id.value) { order.auditState() }
        return view(order)
    }

    @Transactional(readOnly = true)
    fun list(
        filter: WorkOrderFilter,
        page: PageParams,
    ): CursorPage<WorkOrderView> {
        val scope = authorization.scopeFilter(MaintenancePermissions.READ)
        val scoped =
            when (scope) {
                is ScopeFilter.All -> {
                    filter
                }

                is ScopeFilter.Own -> {
                    filter.copy(
                        ownerUserId = scope.userId.value,
                        ownerEmployeeId = staff.employeeOfUser(scope.userId)?.id?.value,
                    )
                }
            }
        val after = page.decodedCursor()?.let { WorkOrderPageKey(it.instant(0), it.uuid(1)) }
        return CursorPage
            .fromRows(store.page(scoped, after, page.limit + 1), page.limit) {
                PageCursor.of(it.openedAt.toString(), it.id.toString())
            }.map(::view)
    }

    @Transactional(readOnly = true)
    fun get(id: WorkOrderId): WorkOrderView = view(load(id, MaintenancePermissions.READ, lock = false))

    /**
     * Substitui os campos editáveis de uma ordem que não é final: local, categoria, prioridade, descrição, responsável
     * (inexistente `400 UNKNOWN_EMPLOYEE`; desativado `422 WORK_ORDER_ASSIGNEE_INACTIVE`), terceiro, prazo e os três
     * custos. O local e o responsável só são conferidos quando mudam.
     */
    @Audited(MaintenanceAudit.UPDATE_WORK_ORDER)
    @Transactional
    fun update(
        id: WorkOrderId,
        edit: WorkOrderEdit,
        expectedVersion: Long,
    ): WorkOrderView =
        act(id, expectedVersion) { order, now ->
            val room = order.roomId
            val area = order.areaId
            val assignee = order.assigneeEmployeeId
            order.update(edit, now)
            if (order.roomId != room || order.areaId != area) requireLocation(order.roomId, order.areaId)
            if (order.assigneeEmployeeId != assignee) requireAssignee(order.assigneeEmployeeId)
        }

    @Audited(MaintenanceAudit.START_WORK_ORDER)
    @Transactional
    fun start(
        id: WorkOrderId,
        expectedVersion: Long,
    ): WorkOrderView = act(id, expectedVersion) { order, now -> order.start(now) }

    /** Põe a ordem em espera (aguardando peça ou terceiro), com o motivo obrigatório (até 500). */
    @Audited(MaintenanceAudit.HOLD_WORK_ORDER)
    @Transactional
    fun hold(
        id: WorkOrderId,
        reason: String?,
        expectedVersion: Long,
    ): WorkOrderView = act(id, expectedVersion) { order, now -> order.hold(reason, now) }

    @Audited(MaintenanceAudit.RESUME_WORK_ORDER)
    @Transactional
    fun resume(
        id: WorkOrderId,
        expectedVersion: Long,
    ): WorkOrderView = act(id, expectedVersion) { order, now -> order.resume(now) }

    /** Conclui a ordem, com notas e custos opcionais. */
    @Audited(MaintenanceAudit.COMPLETE_WORK_ORDER)
    @Transactional
    fun complete(
        id: WorkOrderId,
        completion: WorkOrderCompletion,
        expectedVersion: Long,
    ): WorkOrderView =
        act(id, expectedVersion) { order, now ->
            order.complete(completion.notes, completion.laborCost, completion.thirdPartyCost, completion.partsCost, now)
        }

    /** Cancela a ordem, com o motivo obrigatório (até 500). */
    @Audited(MaintenanceAudit.CANCEL_WORK_ORDER)
    @Transactional
    fun cancel(
        id: WorkOrderId,
        reason: String?,
        expectedVersion: Long,
    ): WorkOrderView = act(id, expectedVersion) { order, now -> order.cancel(reason, now) }

    /** A ação [change] na ordem travada, na ordem: permissão e escopo, trava, `404`, versão (`412`) e a regra do domínio. */
    private fun act(
        id: WorkOrderId,
        expectedVersion: Long,
        change: (WorkOrder, Instant) -> Unit,
    ): WorkOrderView {
        val order = load(id, MaintenancePermissions.MANAGE, lock = true)
        requireCurrentVersion(expectedVersion, order.version)
        audit.track(MaintenanceAudit.WORK_ORDER, order.id.value) { order.auditState() }
        change(order, now())
        store.update(order)
        return view(order)
    }

    /**
     * A ordem [id] sob a [permission]: sem a permissão, `403`; inexistente, `404` (com `OWN`, o mesmo `403` da alheia);
     * com `OWN`, só a que o usuário abriu ou de que é o responsável.
     */
    private fun load(
        id: WorkOrderId,
        permission: PermissionCode,
        lock: Boolean,
    ): WorkOrder {
        authorization.require(permission)
        val order = if (lock) store.findForUpdate(id) else store.find(id)
        if (order == null) {
            authorization.require(permission, OwnedResource.of(emptyList()))
            throw NotFoundException(MaintenanceErrorCodes.WORK_ORDER_NOT_FOUND, "Ordem de serviço não encontrada.")
        }
        val assigneeUser = order.assigneeEmployeeId?.let { staff.employees(listOf(it))[it]?.userId }
        authorization.require(permission, OwnedResource.of(listOf(order.openedBy, assigneeUser)))
        return order
    }

    /** O instante de agora na precisão do banco (microssegundos), para a resposta valer o que a leitura devolve. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun requireLocation(
        roomId: RoomId?,
        areaId: AreaId?,
    ) {
        val (known, active, field, unknown) =
            if (roomId != null) {
                val room = rooms.room(roomId)
                Location(room != null, room?.active == true, "roomId", MaintenanceErrorCodes.UNKNOWN_ROOM)
            } else {
                val area = rooms.area(checkNotNull(areaId))
                Location(area != null, area?.active == true, "areaId", MaintenanceErrorCodes.UNKNOWN_AREA)
            }
        if (!known) throw WorkOrderRules.invalid(field, "O local da ordem de serviço não existe.", unknown)
        if (!active) {
            throw BusinessRuleException(
                MaintenanceErrorCodes.WORK_ORDER_LOCATION_INACTIVE,
                "A UH ou a área da ordem de serviço está desativada.",
            )
        }
    }

    private fun requireAssignee(employee: EmployeeId?) {
        if (employee == null) return
        val summary =
            staff.employees(listOf(employee))[employee]
                ?: throw WorkOrderRules.invalid(
                    "assigneeEmployeeId",
                    "O colaborador responsável não existe.",
                    MaintenanceErrorCodes.UNKNOWN_EMPLOYEE,
                )
        if (!summary.active) {
            throw BusinessRuleException(
                MaintenanceErrorCodes.WORK_ORDER_ASSIGNEE_INACTIVE,
                "O colaborador responsável está desativado.",
            )
        }
    }

    private data class Location(
        val known: Boolean,
        val active: Boolean,
        val field: String,
        val unknown: ErrorCode,
    )

    private fun view(order: WorkOrder) =
        WorkOrderView(
            order.id,
            order.number.toString(),
            order.roomId,
            order.areaId,
            order.category,
            order.priority,
            order.description,
            order.origin,
            order.assigneeEmployeeId,
            order.thirdPartyName,
            order.status,
            order.holdReason,
            order.laborCost,
            order.thirdPartyCost,
            order.partsCost,
            order.openedBy.value,
            order.openedAt,
            order.dueAt,
            order.startedAt,
            order.closedAt,
            order.completionNotes,
            order.cancellationReason,
            order.outOfOrderId,
            order.updatedAt,
            order.version,
        )

    private companion object {
        /** O número legível da ordem: `OS-<ano da data operacional>-NNNNNN`. */
        val WORK_ORDER_NUMBER = DocumentNumberType("OS", "maintenance.work_order_number_seq")
    }
}
