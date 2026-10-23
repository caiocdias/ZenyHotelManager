package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.TraceId
import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.frontdesk.domain.Trace
import com.zeny.hotel.frontdesk.domain.TraceLink
import com.zeny.hotel.frontdesk.domain.TraceRoomInactiveException
import com.zeny.hotel.frontdesk.domain.TraceRules
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Um recado novo: o setor, o vencimento (data operacional), o texto, o vínculo (a reserva **ou** a UH, D3) e o
 * responsável opcional (um usuário vinculado a um colaborador, D1). O [toString] não mostra o texto (RN-13).
 */
data class NewTraceCommand(
    val departmentId: DepartmentId,
    val dueOn: LocalDate,
    val text: String,
    val reservationId: ReservationId?,
    val roomId: RoomId?,
    val assigneeUserId: UserId?,
) {
    override fun toString(): String =
        "NewTraceCommand(departmentId=$departmentId, dueOn=$dueOn, reservationId=$reservationId, roomId=$roomId)"
}

/**
 * A alteração de um recado aberto: setor, vencimento, texto e responsável (o vínculo é fixo, D3). O [toString] não
 * mostra o texto.
 */
data class TraceChangeCommand(
    val departmentId: DepartmentId,
    val dueOn: LocalDate,
    val text: String,
    val assigneeUserId: UserId?,
) {
    override fun toString(): String = "TraceChangeCommand(departmentId=$departmentId, dueOn=$dueOn)"
}

/**
 * O recado como a camada web o vê, com os nomes lidos na hora da consulta (nada disso é gravado no recado): o do setor,
 * o número da UH do vínculo e o nome do colaborador do responsável (nulo se o usuário não tem mais colaborador). O
 * [toString] não mostra o texto (RN-13).
 */
data class TraceView(
    val id: TraceId,
    val departmentId: DepartmentId,
    val departmentName: String,
    val dueOn: LocalDate,
    val text: String,
    val reservationId: ReservationId?,
    val roomId: RoomId?,
    val roomNumber: String?,
    val assigneeUserId: UserId?,
    val assigneeName: String?,
    val status: TraceStatus,
    val createdAt: Instant,
    val createdBy: UserId,
    val resolvedAt: Instant?,
    val resolvedBy: UserId?,
    val version: Long,
) {
    override fun toString(): String = "TraceView(id=$id, departmentId=$departmentId, dueOn=$dueOn, status=$status)"
}

/**
 * Os recados da recepção para os setores (M5 E06; RF-REC-08, RN-01, RN-13, D1 a D6): criar e alterar exigem
 * `frontdesk.traces.manage`; consultar, `frontdesk.traces.read`; resolver, `frontdesk.traces.resolve` (o setor que
 * cumpre o recado o resolve sem poder editá-lo, D2).
 *
 * Na criação, na ordem: as regras de [TraceRules] (texto, vínculo, vencimento não anterior à data operacional,
 * `400 TRACE_INVALID` com o campo e nunca o texto); o setor (`400 UNKNOWN_DEPARTMENT`) pela `StaffApi`; a reserva
 * (`400 UNKNOWN_RESERVATION`, em qualquer situação) pela `ReservationsApi` ou a UH (`400 UNKNOWN_ROOM`; desativada,
 * `422 ROOM_INACTIVE`) pela `RoomsApi`; e o responsável, um usuário vinculado a um colaborador
 * (`400 UNKNOWN_ASSIGNEE`). A alteração e a resolução **travam a linha** do recado: a alteração confere a versão do
 * `If-Match` e um recado resolvido não muda mais (`409 TRACE_ALREADY_RESOLVED`); de duas resoluções simultâneas, a
 * segunda espera a trava e recebe o `409`. A trilha (`frontdesk.trace`) leva o retrato de [Trace.auditState], sem o
 * texto.
 */
@Service
@Suppress("LongParameterList") // a persistência, as referências e as portas do kernel que os recados usam
class FrontdeskTraceService(
    private val store: TraceStore,
    private val references: TraceReferences,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(FrontdeskAudit.CREATE_TRACE)
    @Transactional
    fun create(command: NewTraceCommand): TraceView {
        authorization.require(FrontdeskPermissions.TRACES_MANAGE)
        val text = TraceRules.text(command.text)
        val link = TraceRules.link(command.reservationId, command.roomId)
        val dueOn = TraceRules.dueOn(command.dueOn, hotel.currentBusinessDate())
        references.requireDepartment(command.departmentId)
        references.requireLink(link)
        command.assigneeUserId?.let(references::requireAssignee)
        val now = clock.instant()
        val trace =
            Trace(
                id = TraceId.new(),
                departmentId = command.departmentId,
                dueOn = dueOn,
                text = text,
                link = link,
                assigneeUserId = command.assigneeUserId,
                createdAt = now,
                createdBy = currentActor.current().userId,
                updatedAt = now,
                resolvedAt = null,
                resolvedBy = null,
                version = 0,
            )
        store.add(trace)
        audit.record(FrontdeskAudit.TRACE, trace.id.value, null, trace.auditState())
        return view(stored(trace.id))
    }

    /**
     * Altera setor, vencimento, texto e responsável com a versão do `If-Match` ([expectedVersion]). O vencimento, o
     * setor e o responsável só são conferidos quando mudam (D4). Sem mudança, nada é gravado.
     */
    @Audited(FrontdeskAudit.UPDATE_TRACE)
    @Transactional
    fun change(
        id: TraceId,
        expectedVersion: Long,
        command: TraceChangeCommand,
    ): TraceView {
        authorization.require(FrontdeskPermissions.TRACES_MANAGE)
        val text = TraceRules.text(command.text)
        val trace = store.forUpdate(id) ?: throw notFound()
        requireCurrentVersion(expectedVersion, trace.version)
        val changed = trace.change(command.departmentId, command.dueOn, text, command.assigneeUserId, clock.instant())
        if (changed.dueOn != trace.dueOn) TraceRules.dueOn(changed.dueOn, hotel.currentBusinessDate())
        if (changed.departmentId != trace.departmentId) references.requireDepartment(changed.departmentId)
        changed.assigneeUserId?.takeIf { it != trace.assigneeUserId }?.let(references::requireAssignee)
        if (changed.copy(updatedAt = trace.updatedAt) == trace) {
            audit.noChanges()
            return view(trace)
        }
        val saved = store.update(changed)
        audit.record(FrontdeskAudit.TRACE, id.value, trace.auditState(), saved.auditState())
        return view(stored(id))
    }

    /** Resolve o recado com quem e quando, com a linha travada; resolvido, `409 TRACE_ALREADY_RESOLVED`. */
    @Audited(FrontdeskAudit.RESOLVE_TRACE)
    @Transactional
    fun resolve(id: TraceId): TraceView {
        authorization.require(FrontdeskPermissions.TRACES_RESOLVE)
        val trace = store.forUpdate(id) ?: throw notFound()
        val saved = store.update(trace.resolve(currentActor.current().userId, clock.instant()))
        audit.record(FrontdeskAudit.TRACE, id.value, trace.auditState(), saved.auditState())
        return view(stored(id))
    }

    @Transactional(readOnly = true)
    fun trace(id: TraceId): TraceView {
        authorization.require(FrontdeskPermissions.TRACES_READ)
        return view(store.find(id) ?: throw notFound())
    }

    /** Uma página em ordem `(dueOn, id)`, com o cursor dessas duas chaves (a ordem é estável no mesmo vencimento). */
    @Transactional(readOnly = true)
    fun list(
        filter: TraceFilter,
        page: PageParams,
    ): CursorPage<TraceView> {
        authorization.require(FrontdeskPermissions.TRACES_READ)
        val after = page.decodedCursor()?.let { it.localDate(0) to it.uuid(1) }
        val rows = store.page(filter, after, page.limit + 1)
        val traces =
            CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.dueOn.toString(), it.id.value.toString()) }
        return CursorPage(references.views(traces.items), traces.nextCursor)
    }

    private fun stored(id: TraceId): Trace = checkNotNull(store.find(id)) { "Recado $id recém-gravado não encontrado" }

    private fun view(trace: Trace): TraceView = references.views(listOf(trace)).single()

    private fun notFound() = NotFoundException(FrontdeskErrorCodes.TRACE_NOT_FOUND, "Recado não encontrado.")
}

/**
 * As referências do recado em outros módulos (M5 E06): confere o setor e o responsável pela `StaffApi`, a reserva pela
 * `ReservationsApi` e a UH pela `RoomsApi` (`400 UNKNOWN_…` com o campo; UH desativada `422 ROOM_INACTIVE`), e lê os
 * nomes das respostas na hora da consulta. Não confere permissão: quem chama é o [FrontdeskTraceService].
 */
@Component
class TraceReferences(
    private val staff: StaffApi,
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
) {
    fun requireDepartment(id: DepartmentId) {
        if (staff.departments().none { it.id == id }) {
            throw unknown(FrontdeskErrorCodes.UNKNOWN_DEPARTMENT, "departmentId", "O setor informado não existe.")
        }
    }

    fun requireLink(link: TraceLink) {
        when (link) {
            is TraceLink.Reservation -> requireReservation(link.id)
            is TraceLink.Room -> requireRoom(link.id)
        }
    }

    /** D1: o responsável é um usuário vinculado a um colaborador (ativo ou não). */
    fun requireAssignee(userId: UserId) {
        staff.employeeOfUser(userId)
            ?: throw unknown(
                FrontdeskErrorCodes.UNKNOWN_ASSIGNEE,
                "assigneeUserId",
                "O responsável precisa ser um usuário vinculado a um colaborador.",
            )
    }

    /** Os nomes de uma vez para a página: os setores numa leitura só, cada UH e cada responsável uma vez. */
    fun views(traces: List<Trace>): List<TraceView> {
        if (traces.isEmpty()) return emptyList()
        val departments = staff.departments().associateBy { it.id }
        val roomNumbers =
            traces
                .mapNotNull { (it.link as? TraceLink.Room)?.id }
                .distinct()
                .associateWith { rooms.room(it)?.number }
        val assigneeNames =
            traces
                .mapNotNull { it.assigneeUserId }
                .distinct()
                .associateWith { staff.employeeOfUser(it)?.name }
        return traces.map { trace ->
            val roomId = (trace.link as? TraceLink.Room)?.id
            TraceView(
                id = trace.id,
                departmentId = trace.departmentId,
                departmentName =
                    checkNotNull(departments[trace.departmentId]) { "Recado com um setor inexistente" }.name,
                dueOn = trace.dueOn,
                text = trace.text,
                reservationId = (trace.link as? TraceLink.Reservation)?.id,
                roomId = roomId,
                roomNumber = roomId?.let { roomNumbers[it] },
                assigneeUserId = trace.assigneeUserId,
                assigneeName = trace.assigneeUserId?.let { assigneeNames[it] },
                status = trace.status,
                createdAt = trace.createdAt,
                createdBy = trace.createdBy,
                resolvedAt = trace.resolvedAt,
                resolvedBy = trace.resolvedBy,
                version = trace.version,
            )
        }
    }

    private fun requireReservation(id: ReservationId) {
        reservations.reservation(id)
            ?: throw unknown(
                FrontdeskErrorCodes.UNKNOWN_RESERVATION,
                "reservationId",
                "A reserva informada não existe.",
            )
    }

    private fun requireRoom(id: RoomId) {
        val room =
            rooms.room(id) ?: throw unknown(FrontdeskErrorCodes.UNKNOWN_ROOM, "roomId", "A UH informada não existe.")
        if (!room.active) throw TraceRoomInactiveException(room.number)
    }

    private fun unknown(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
