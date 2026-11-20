package com.zeny.hotel.maintenance.application

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.MaintenanceOffline
import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.maintenance.domain.WorkOrderAction
import com.zeny.hotel.maintenance.domain.WorkOrderChecklistLink
import com.zeny.hotel.maintenance.domain.WorkOrderEdit
import com.zeny.hotel.maintenance.domain.WorkOrderRules
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OfflineTombstones
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
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

    /**
     * Trava consultiva da transação (`pg_advisory_xact_lock`) sobre a execução de referência e o item: serializa as
     * entregas simultâneas do mesmo evento, para a segunda achar a ordem da primeira em vez de violar o índice único.
     */
    fun lockChecklistItem(
        referenceExecution: ChecklistExecutionId,
        itemKey: String,
    )

    /** A ordem aberta pelo item [itemKey] da execução de referência [referenceExecution], ou `null` (D2-E08). */
    fun findByChecklistItem(
        referenceExecution: ChecklistExecutionId,
        itemKey: String,
    ): WorkOrder?

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

/**
 * A ordem a abrir por um item de checklist "não ok" (D2-E08), pelo ouvinte de `ChecklistCompleted` (sem requisição):
 * o local já conferido, a categoria, a descrição (rótulo do item, categoria e observação), quem executou
 * ([openedBy]), o instante da conclusão ([openedAt]) e o vínculo à execução e ao item.
 */
data class ChecklistWorkOrderDraft(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val category: WorkOrderCategory,
    val description: String,
    val openedBy: UserId,
    val openedAt: Instant,
    val link: WorkOrderChecklistLink,
)

/** A edição (`PUT`): todos os campos editáveis, os três custos inclusive; o caso de uso a passa ao domínio. */
data class WorkOrderUpdate(
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
) {
    internal fun toEdit() =
        WorkOrderEdit(
            roomId,
            areaId,
            category,
            priority,
            description,
            assigneeEmployeeId,
            thirdPartyName,
            dueAt,
            laborCost,
            thirdPartyCost,
            partsCost,
        )
}

/** A conclusão: notas opcionais e os custos que o técnico informa agora (os nulos mantêm o gravado). */
data class WorkOrderCompletion(
    val notes: String?,
    val laborCost: Money?,
    val thirdPartyCost: Money?,
    val partsCost: Money?,
)

/** Um anexo vigente da ordem: o id do arquivo no `files` e a finalidade (foto ou PDF). */
data class WorkOrderAttachmentView(
    val fileId: FileId,
    val purpose: FilePurposeCode,
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
    /** Os anexos vigentes, em ordem de vínculo; a versão da ordem não os cobre. */
    val attachments: List<WorkOrderAttachmentView>,
    /** As observações, em ordem de registro; a versão da ordem não as cobre. */
    val notes: List<WorkOrderNoteView>,
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
 * Uma ordem criada `URGENT`, ou editada de outra prioridade para `URGENT`, avisa quem tem `manage` (D6), menos o ator,
 * com o número, o local e a categoria; editar uma ordem que já era urgente não avisa de novo. Os anexos são do
 * [WorkOrderAttachments].
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
    private val attachments: WorkOrderAttachmentStore,
    private val notes: WorkOrderNoteStore,
    private val blocks: WorkOrderRoomBlocks,
    private val notifications: NotificationsApi,
    private val tombstones: OfflineTombstones,
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
    fun open(command: NewWorkOrder): WorkOrderView = openAt(WorkOrderId.new(), command, at = null)

    /**
     * A abertura de [command] com o id [id] e o instante [at] (sem [at], agora), dentro do caso de uso auditado de quem
     * chama (o comando offline passa o `commandId` como id e o `occurredAt` corrigido). As recusas do [open]; um id que
     * já é de uma ordem: `409 WORK_ORDER_ID_IN_USE`, sem sobrescrever.
     */
    internal fun openAt(
        id: WorkOrderId,
        command: NewWorkOrder,
        at: Instant?,
    ): WorkOrderView {
        authorization.require(MaintenancePermissions.REQUEST)
        WorkOrderRules.requireOneLocation(command.roomId, command.areaId)
        WorkOrderRules.freeText("description", command.description, WorkOrder.MAX_DESCRIPTION)
        if (command.origin == WorkOrderOrigin.CHECKLIST) {
            throw WorkOrderRules.invalid("origin", "A origem CHECKLIST é reservada ao pedido por item de checklist.")
        }
        requireLocation(command.roomId, command.areaId)
        if (store.find(id) != null) {
            throw ConflictException(
                MaintenanceErrorCodes.WORK_ORDER_ID_IN_USE,
                "O identificador já é de uma ordem de serviço existente.",
            )
        }
        val order =
            WorkOrder.open(
                id,
                numbers.next(WORK_ORDER_NUMBER, hotel.currentBusinessDate().year),
                command.roomId,
                command.areaId,
                command.category,
                command.priority,
                command.description,
                command.origin,
                command.dueAt,
                actor.current().userId,
                at?.truncatedTo(ChronoUnit.MICROS) ?: now(),
            )
        store.insert(order)
        audit.created(MaintenanceAudit.WORK_ORDER, order.id.value) { order.auditState() }
        if (order.priority == WorkOrderPriority.URGENT) notifyUrgent(order)
        return viewOf(order)
    }

    /**
     * Abre a ordem de um item de checklist "não ok" (D2-E08): origem `CHECKLIST`, prioridade `NORMAL`, aberta por quem
     * executou, em `openedAt`, sem permissão nem aviso (o sistema abre, sem requisição). Idempotente pela execução de
     * referência e o item: a reentrega do evento e a execução registrada à parte do mesmo item devolvem a ordem já
     * gravada, sem outra entrada na trilha. `internal`, só do ouvinte do módulo: não confere permissão
     * nem local (o ouvinte confere a UH ou a área pela `RoomsApi`).
     */
    @Audited(MaintenanceAudit.OPEN_WORK_ORDER_FROM_CHECKLIST)
    @Transactional
    internal fun openFromChecklist(draft: ChecklistWorkOrderDraft): WorkOrderView {
        store.lockChecklistItem(draft.link.referenceExecutionId, draft.link.itemKey)
        store.findByChecklistItem(draft.link.referenceExecutionId, draft.link.itemKey)?.let {
            audit.noChanges()
            return viewOf(it)
        }
        val order =
            WorkOrder.open(
                WorkOrderId.new(),
                numbers.next(WORK_ORDER_NUMBER, hotel.currentBusinessDate().year),
                draft.roomId,
                draft.areaId,
                draft.category,
                WorkOrderPriority.NORMAL,
                draft.description,
                WorkOrderOrigin.CHECKLIST,
                dueAt = null,
                draft.openedBy,
                draft.openedAt.truncatedTo(ChronoUnit.MICROS),
                draft.link,
            )
        store.insert(order)
        audit.created(MaintenanceAudit.WORK_ORDER, order.id.value) { order.auditState() }
        return viewOf(order)
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
        val rows =
            CursorPage.fromRows(store.page(scoped, after, page.limit + 1), page.limit) {
                PageCursor.of(it.openedAt.toString(), it.id.toString())
            }
        val ids = rows.items.map { it.id }
        val attached = attachments.active(ids)
        val noted = notes.of(ids)
        return rows.map { view(it, attached[it.id].orEmpty(), noted[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun get(id: WorkOrderId): WorkOrderView = viewOf(load(id, MaintenancePermissions.READ, lock = false))

    /**
     * Substitui os campos editáveis de uma ordem que não é final: local, categoria, prioridade, descrição, responsável
     * (inexistente `400 UNKNOWN_EMPLOYEE`; desativado `422 WORK_ORDER_ASSIGNEE_INACTIVE`), terceiro, prazo e os três
     * custos. O local e o responsável só são conferidos quando mudam.
     */
    @Audited(MaintenanceAudit.UPDATE_WORK_ORDER)
    @Transactional
    fun update(
        id: WorkOrderId,
        update: WorkOrderUpdate,
        expectedVersion: Long,
    ): WorkOrderView =
        act(id, expectedVersion) { order, now ->
            val edit = update.toEdit()
            // Confere a edição e as referências com os valores novos antes de mudar o agregado.
            order.checkUpdate(edit)
            if (edit.roomId != order.roomId || edit.areaId != order.areaId) {
                if (blocks.inEffect(order) != null) {
                    throw ConflictException(
                        MaintenanceErrorCodes.WORK_ORDER_LOCATION_LOCKED,
                        "O local da ordem não muda enquanto há um bloqueio da UH vigente.",
                    )
                }
                requireLocation(edit.roomId, edit.areaId)
            }
            if (edit.assigneeEmployeeId != order.assigneeEmployeeId) requireAssignee(edit.assigneeEmployeeId)
            val becomesUrgent = edit.priority == WorkOrderPriority.URGENT && order.priority != WorkOrderPriority.URGENT
            val previousAssignee = order.assigneeEmployeeId
            order.update(edit, now)
            if (edit.assigneeEmployeeId != previousAssignee) leftOfflineScope(order, previousAssignee)
            if (becomesUrgent) notifyUrgent(order)
        }

    @Audited(MaintenanceAudit.START_WORK_ORDER)
    @Transactional
    fun start(
        id: WorkOrderId,
        expectedVersion: Long,
    ): WorkOrderView = startAt(id, expectedVersion, at = null)

    /**
     * As ações com o instante [at] (o `occurredAt` corrigido do comando offline, nunca antes da última mudança da
     * ordem, D5-E08) e sem versão quando [expectedVersion] é `null`, dentro do caso de uso auditado de quem chama.
     */
    internal fun startAt(
        id: WorkOrderId,
        expectedVersion: Long?,
        at: Instant?,
    ): WorkOrderView = act(id, expectedVersion, at) { order, now -> order.start(now) }

    /** Põe a ordem em espera (aguardando peça ou terceiro), com o motivo obrigatório (até 500). */
    @Audited(MaintenanceAudit.HOLD_WORK_ORDER)
    @Transactional
    fun hold(
        id: WorkOrderId,
        reason: String?,
        expectedVersion: Long,
    ): WorkOrderView = holdAt(id, reason, expectedVersion, at = null)

    internal fun holdAt(
        id: WorkOrderId,
        reason: String?,
        expectedVersion: Long?,
        at: Instant?,
    ): WorkOrderView = act(id, expectedVersion, at) { order, now -> order.hold(reason, now) }

    @Audited(MaintenanceAudit.RESUME_WORK_ORDER)
    @Transactional
    fun resume(
        id: WorkOrderId,
        expectedVersion: Long,
    ): WorkOrderView = resumeAt(id, expectedVersion, at = null)

    internal fun resumeAt(
        id: WorkOrderId,
        expectedVersion: Long?,
        at: Instant?,
    ): WorkOrderView = act(id, expectedVersion, at) { order, now -> order.resume(now) }

    /** Conclui a ordem, com notas e custos opcionais. */
    @Audited(MaintenanceAudit.COMPLETE_WORK_ORDER)
    @Transactional
    fun complete(
        id: WorkOrderId,
        completion: WorkOrderCompletion,
        expectedVersion: Long,
    ): WorkOrderView = completeAt(id, completion, expectedVersion, at = null)

    internal fun completeAt(
        id: WorkOrderId,
        completion: WorkOrderCompletion,
        expectedVersion: Long?,
        at: Instant?,
    ): WorkOrderView =
        act(id, expectedVersion, at) { order, now ->
            order.complete(completion.notes, completion.laborCost, completion.thirdPartyCost, completion.partsCost, now)
            blocks.release(order)
        }

    /**
     * Cancela a ordem, com o motivo obrigatório (até 500). Com bloqueio da UH vigente, pede o que o `rooms` pede para
     * liberá-lo (OOO: aprovação de superior pelo [approvalToken]; OOS: `rooms.outofservice.manage`) e o libera.
     */
    @Audited(MaintenanceAudit.CANCEL_WORK_ORDER)
    @Transactional
    fun cancel(
        id: WorkOrderId,
        reason: String?,
        expectedVersion: Long,
        approvalToken: String?,
    ): WorkOrderView =
        act(id, expectedVersion) { order, now ->
            order.requireActive(WorkOrderAction.CANCEL)
            WorkOrderRules.freeText("cancellationReason", reason, WorkOrder.MAX_REASON)
            blocks.authorizeCancel(order, approvalToken)
            order.cancel(reason, now)
            blocks.release(order)
        }

    /**
     * Bloqueia a UH da ordem com um período OOO/OOS do `rooms` (D7-E07). Recusas, nesta ordem: `403` sem `manage`,
     * `404`, `412`, ordem final (`409 WORK_ORDER_TRANSITION_NOT_ALLOWED`), ordem de área (`422 WORK_ORDER_NOT_ROOM`),
     * bloqueio vigente (`409 WORK_ORDER_ROOM_ALREADY_BLOCKED`), o step-up e, por fim, as regras do `rooms`.
     */
    @Audited(MaintenanceAudit.BLOCK_ROOM)
    @Transactional
    fun blockRoom(
        id: WorkOrderId,
        request: WorkOrderRoomBlockRequest,
        expectedVersion: Long,
        approvalToken: String?,
    ): WorkOrderView =
        act(id, expectedVersion) { order, now ->
            order.requireBlockable()
            blocks.requireNotBlocked(order)
            order.blockRoom(blocks.open(order, request, approvalToken).id, now)
        }

    /**
     * A ação [change] na ordem travada: permissão e escopo, trava, `404`, versão (`412`, só com [expectedVersion]) e a
     * regra do domínio. O instante é [at] (o do comando offline) ou agora, e nunca anterior à última mudança da ordem
     * (`updatedAt`, que anda em toda ação, edição e bloqueio): os instantes da ordem não andam para trás (D5-E08).
     */
    private fun act(
        id: WorkOrderId,
        expectedVersion: Long?,
        at: Instant? = null,
        change: (WorkOrder, Instant) -> Unit,
    ): WorkOrderView {
        val order = load(id, MaintenancePermissions.MANAGE, lock = true)
        expectedVersion?.let { requireCurrentVersion(it, order.version) }
        audit.track(MaintenanceAudit.WORK_ORDER, order.id.value) { order.auditState() }
        change(order, at?.truncatedTo(ChronoUnit.MICROS)?.let { maxOf(it, order.updatedAt) } ?: now())
        store.update(order)
        return viewOf(order)
    }

    /** A ordem [id] travada (`FOR UPDATE`) sob a [permission], com o escopo e os `403`/`404` do [load]. */
    internal fun loadLocked(
        id: WorkOrderId,
        permission: PermissionCode,
    ): WorkOrder = load(id, permission, lock = true)

    /** A ordem [id] travada, ou `null` se não existe; a permissão é de quem chama (sem escopo). */
    internal fun lockedOrNull(id: WorkOrderId): WorkOrder? = store.findForUpdate(id)

    /**
     * A ordem [id] travada para vincular um anexo ou registrar uma observação: quem tem `manage` ([manager]) a carrega
     * no escopo dele; quem não tem exige `request` e só a ordem que abriu. A ordem alheia e a inexistente dão o mesmo
     * `403` de quem não tem `manage` (sem revelar se a ordem existe).
     */
    internal fun loadLockedForNote(
        id: WorkOrderId,
        manager: Boolean,
    ): WorkOrder {
        if (manager) return loadLocked(id, MaintenancePermissions.MANAGE)
        authorization.require(MaintenancePermissions.REQUEST)
        val order = lockedOrNull(id)
        if (order == null || order.openedBy != actor.current().userId) {
            authorization.require(MaintenancePermissions.MANAGE)
        }
        return checkNotNull(order) { "A ordem ausente passou pela exigência de manage" }
    }

    /** A ordem para a resposta, com os anexos vigentes e as observações dela. */
    internal fun viewOf(order: WorkOrder): WorkOrderView =
        view(
            order,
            attachments.active(listOf(order.id))[order.id].orEmpty(),
            notes.of(listOf(order.id))[order.id].orEmpty(),
        )

    /**
     * Avisa quem tem `manage`, menos o ator (D6): o número, o local (número da UH ou nome da área) e a categoria. Sem
     * a descrição (texto livre) nem dado de hóspede (RN-13): o aviso vai ao painel e ao push.
     */
    private fun notifyUrgent(order: WorkOrder) {
        val place =
            order.roomId?.let { "UH ${room(it).number}" }
                ?: order.areaId?.let { "Área ${area(it).name}" }
                ?: error("A ordem sem local")
        notifications.notify(
            NotificationsTypes.URGENT_WORK_ORDER,
            "OS urgente ${order.number}",
            "${order.number}: $place, ${CATEGORY_LABELS.getValue(order.category)}.",
            NotificationRecipients(
                permissions = setOf(MaintenancePermissions.MANAGE),
                except = setOf(actor.current().userId),
            ),
        )
    }

    /**
     * O responsável anterior sai do escopo `OWN` da fonte offline (D4-E08): o *tombstone* vai só ao usuário dele, e
     * só se ele não abriu a ordem (quem abriu continua com ela).
     */
    private fun leftOfflineScope(
        order: WorkOrder,
        previous: EmployeeId?,
    ) {
        val user = previous?.let { staff.employees(listOf(it))[it]?.userId } ?: return
        if (user != order.openedBy) {
            tombstones.record(MaintenanceOffline.WORK_ORDERS, order.id.value, setOf(user))
        }
    }

    private fun room(id: RoomId) = requireNotNull(rooms.room(id)) { "A UH $id da ordem de serviço não existe" }

    private fun area(id: AreaId) = requireNotNull(rooms.area(id)) { "A área $id da ordem de serviço não existe" }

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
        val location =
            if (roomId != null) {
                val room = rooms.room(roomId)
                Location(room != null, room?.active == true, "roomId", MaintenanceErrorCodes.UNKNOWN_ROOM)
            } else {
                val area = rooms.area(checkNotNull(areaId))
                Location(area != null, area?.active == true, "areaId", MaintenanceErrorCodes.UNKNOWN_AREA)
            }
        if (!location.known) {
            throw WorkOrderRules.invalid(location.field, "O local da ordem de serviço não existe.", location.unknown)
        }
        if (!location.active) {
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

    private fun view(
        order: WorkOrder,
        attached: List<WorkOrderAttachmentRow>,
        noted: List<WorkOrderNoteRow>,
    ) = WorkOrderView(
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
        attached.map { WorkOrderAttachmentView(it.fileId, it.purpose) },
        noted.map { WorkOrderNoteView(it.id, it.text, it.authorId, it.createdAt) },
    )

    private companion object {
        /** Os nomes das categorias no aviso. */
        val CATEGORY_LABELS =
            mapOf(
                WorkOrderCategory.ELECTRICAL to "Elétrica",
                WorkOrderCategory.PLUMBING to "Hidráulica",
                WorkOrderCategory.AIR_CONDITIONING to "Ar-condicionado",
                WorkOrderCategory.FURNITURE to "Mobiliário",
                WorkOrderCategory.OTHER to "Outra categoria",
            )

        /** O número legível da ordem: `OS-<ano da data operacional>-NNNNNN`. */
        val WORK_ORDER_NUMBER = DocumentNumberType("OS", "maintenance.work_order_number_seq")
    }
}
