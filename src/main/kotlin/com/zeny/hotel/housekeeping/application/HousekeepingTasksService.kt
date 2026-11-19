package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.StorableRange
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** As chaves da ordenação da lista de tarefas: chegada prevista (desc), prioridade (desc) e id. */
data class TaskPageKey(
    val arrivalExpected: Boolean,
    val priority: Int,
    val id: UUID,
)

/** Os filtros da lista de tarefas: a data (sem ela, a data operacional) e os opcionais. */
data class TaskFilter(
    val date: LocalDate? = null,
    val roomId: RoomId? = null,
    val areaId: AreaId? = null,
    val status: TaskStatus? = null,
    val cleaningTypeId: CleaningTypeId? = null,
    val source: TaskSource? = null,
)

/** Persistência das tarefas de governança e da posse delas (D3-E19), na transação corrente. */
@Suppress("TooManyFunctions") // uma operação por método; a posse fica junto da tarefa
interface HousekeepingTaskStore {
    fun task(id: HousekeepingTaskId): HousekeepingTask?

    /**
     * A tarefa com a linha travada (`SELECT … FOR NO KEY UPDATE`) e relida, ou `null` se não existe: a primeira trava
     * de toda ação da execução (D9-E04).
     */
    fun findForUpdate(id: HousekeepingTaskId): HousekeepingTask?

    /** As tarefas de [ids], na ordem de [ids] (as inexistentes ficam de fora). */
    fun tasks(ids: List<UUID>): List<HousekeepingTask>

    /** As chaves da ordenação da página das tarefas de [date] depois de [after], com os outros filtros de [filter]. */
    fun page(
        date: LocalDate,
        filter: TaskFilter,
        after: TaskPageKey?,
        limit: Int,
    ): List<TaskPageKey>

    /**
     * Grava a tarefa se ainda não há outra no mesmo (local, data, tipo), em qualquer status (D5-E02); devolve `false`
     * quando já há, sem lançar e sem abortar a transação (`INSERT … ON CONFLICT DO NOTHING`).
     */
    fun insertIfAbsent(task: HousekeepingTask): Boolean

    /**
     * Se a faxina programada tem tarefa **aberta**: em qualquer status fora `DONE` e `CANCELLED` (inclusive `REFUSED`
     * e `REJECTED`, que ainda pedem uma decisão da governança).
     */
    fun hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean

    /**
     * As tarefas que podem entrar no plano de [date] (D2-E03), em ordem de id: as `PENDING` e `ASSIGNED` da data e, se
     * [includePrevious] (o plano da data operacional), também as de datas anteriores (D4 da E02).
     */
    fun assignableUpTo(
        date: LocalDate,
        includePrevious: Boolean,
    ): List<HousekeepingTask>

    /**
     * Grava a mudança da tarefa já carregada nesta transação e a manda ao banco agora, conferindo a versão (o lock
     * otimista): outra escrita que chegou antes faz falhar com `OptimisticLockingFailureException` (`412`).
     */
    fun update(task: HousekeepingTask)

    /**
     * Grava a posse (D3-E19): a tarefa [taskId] passou à camareira [employeeId] em [at], no histórico só de inserção
     * `housekeeping.task_assignment`. A camareira atual e a retirada vêm da própria tarefa.
     */
    fun recordAssignment(
        taskId: HousekeepingTaskId,
        employeeId: EmployeeId,
        at: Instant,
    )

    /** Se a camareira [employeeId] já foi dona da tarefa [taskId], atual ou antiga (o histórico de posse, D3-E19). */
    fun wasAssignedTo(
        taskId: HousekeepingTaskId,
        employeeId: EmployeeId,
    ): Boolean

    /**
     * A tarefa [taskId] saiu do escopo `OWN` dos [users] na fonte offline `housekeeping-tasks` (D6-E19): o *tombstone*
     * vai só a eles; quem tem `ALL` continua com a tarefa.
     */
    fun recordLeftScope(
        taskId: HousekeepingTaskId,
        users: Set<UserId>,
    )

    /** A data da última tarefa da UH até [onOrBefore] (inclusive), sem contar as `CANCELLED`; `null` se não há. */
    fun lastTaskDate(
        roomId: RoomId,
        onOrBefore: LocalDate,
    ): LocalDate?
}

/** A solicitação avulsa: o local (a UH **ou** a área), o tipo, a data (padrão: a operacional) e as observações. */
data class TaskRequestCommand(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val cleaningTypeId: CleaningTypeId,
    val date: LocalDate?,
    val notes: String?,
)

/**
 * Uma tarefa de governança, com a execução (M6 E04): o motivo da recusa, o primeiro início, a conclusão, o tempo
 * trabalhado e quem concluiu; e a marca de conflito offline (D4-E19).
 */
data class HousekeepingTaskView(
    val id: HousekeepingTaskId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val date: LocalDate,
    val cleaningTypeId: CleaningTypeId,
    val source: TaskSource,
    val status: TaskStatus,
    val priority: Int,
    val credits: Int,
    val arrivalExpected: Boolean,
    val scheduledCleaningId: ScheduledCleaningId?,
    val notes: String?,
    val assignedEmployeeId: EmployeeId?,
    val assignmentPosition: Int?,
    val refusalReason: RefusalReason?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val workedSeconds: Long,
    val cleanedBy: UserId?,
    val checklistExecutionId: ChecklistExecutionId?,
    val hasConflict: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * Tarefas de governança (RF-GOV-02, M6 E02): a lista do dia, a leitura e a solicitação avulsa da recepção. Consultar
 * exige `housekeeping.tasks.read`; solicitar, `housekeeping.tasks.request`, conferida **antes** de qualquer recusa que
 * revele estado (`400 …UNKNOWN_…`, `404`, `409`, `422`).
 *
 * A avulsa, na ordem: o local exatamente um e as `notes` (`400`); o local (pela `RoomsApi`) e o tipo inexistentes, `400
 * …UNKNOWN_…` (H16); o local ou o tipo desativados, `422`; a data antes da operacional ou mais de [MAX_DAYS_AHEAD] dias
 * depois dela, `422`; uma tarefa já existente no mesmo (local, data, tipo), em qualquer status, `409` (D5-E02). Nasce
 * `PENDING`, `AD_HOC`, com a prioridade do tipo, os créditos do tipo para o tipo da UH (D2) e a marca de chegada
 * prevista na data da tarefa (D8-E02, [ArrivalsByRoom]; numa área, sem marca).
 */
@Service
@Suppress("LongParameterList") // as portas que o caso de uso usa
class HousekeepingTasksService(
    private val store: HousekeepingTaskStore,
    private val cleaningTypes: CleaningTypeStore,
    private val rooms: RoomsApi,
    private val arrivals: ArrivalsByRoom,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * A lista do dia ([TaskFilter.date] ou, sem ela, a data operacional), na ordem de [TaskPageKey]. Uma data fora do
     * [StorableRange] não tem tarefa e não é consultada (a consulta falharia com um 500 ou, abaixo do piso do driver,
     * buscaria `-infinity`): a página vem vazia.
     */
    @Transactional(readOnly = true)
    fun list(
        filter: TaskFilter,
        page: PageParams,
    ): CursorPage<HousekeepingTaskView> {
        authorization.require(HousekeepingPermissions.TASKS_READ)
        val after = page.decodedCursor()?.let(::pageKey)
        val date = filter.date ?: hotel.currentBusinessDate()
        if (!StorableRange.contains(date)) return CursorPage(emptyList(), null)
        val rows = store.page(date, filter, after, page.limit + 1)
        val keys =
            CursorPage.fromRows(rows, page.limit) {
                PageCursor.of(it.arrivalExpected.toString(), it.priority.toString(), it.id.toString())
            }
        return CursorPage(store.tasks(keys.items.map { it.id }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: HousekeepingTaskId): HousekeepingTaskView {
        authorization.require(HousekeepingPermissions.TASKS_READ)
        return (store.task(id) ?: throw notFound()).view()
    }

    @Audited(HousekeepingAudit.REQUEST_TASK)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo, na ordem 400 → 422 → 409
    fun request(command: TaskRequestCommand): HousekeepingTaskView {
        authorization.require(HousekeepingPermissions.TASKS_REQUEST)
        val location = TaskLocation.of(command.roomId, command.areaId)
        val notes = HousekeepingTask.notes(command.notes)
        val place = place(location) ?: throw unknownLocation(location)
        val type =
            cleaningTypes.cleaningType(command.cleaningTypeId)
                ?: throw invalid(
                    HousekeepingErrorCodes.HOUSEKEEPING_TASK_UNKNOWN_CLEANING_TYPE,
                    "cleaningTypeId",
                    "O tipo de limpeza não existe.",
                )
        if (!place.active) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_LOCATION_INACTIVE,
                "O local da tarefa está desativado.",
            )
        }
        requireActive(type)
        val today = hotel.currentBusinessDate()
        val date = command.date ?: today
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_DATE_INVALID,
                "Informe a data da tarefa entre a data operacional ($today) e $MAX_DAYS_AHEAD dias depois dela.",
            )
        }
        val task =
            HousekeepingTask.create(
                type,
                location,
                date,
                TaskSource.AD_HOC,
                arrivalExpected = location is TaskLocation.Room && location.id in arrivals.arrivalsByRoom(date),
                credits = HousekeepingTask.creditsFor(type, place.roomTypeId),
                now = clock.instant(),
                notes = notes,
                createdBy = actor.current().userId,
            )
        if (!store.insertIfAbsent(task)) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_ALREADY_EXISTS,
                "Já há uma tarefa com este tipo de limpeza neste local nesta data; para outra limpeza, use outro tipo.",
            )
        }
        val stored = checkNotNull(store.task(task.id))
        audit.created(HousekeepingAudit.TASK, task.id.value) { stored.auditState() }
        return stored.view()
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Se o local está ativo e, numa UH, o tipo dela; `null` se o local não existe. */
    private data class Place(
        val active: Boolean,
        val roomTypeId: RoomTypeId?,
    )

    private fun place(location: TaskLocation): Place? =
        when (location) {
            is TaskLocation.Room -> rooms.room(location.id)?.let { Place(it.active, it.roomTypeId) }
            is TaskLocation.Area -> rooms.area(location.id)?.let { Place(it.active, null) }
        }

    private fun requireActive(type: CleaningType) {
        if (!type.active) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.CLEANING_TYPE_INACTIVE,
                "O tipo de limpeza está desativado.",
            )
        }
    }

    /**
     * As chaves do cursor: a chegada (`true`/`false`), a prioridade (de 1 a 5) e o id; qualquer outra coisa é `400
     * INVALID_CURSOR`, antes de chegar ao banco.
     */
    private fun pageKey(cursor: PageCursor): TaskPageKey {
        val arrival =
            when (cursor.string(0)) {
                "true" -> true
                "false" -> false
                else -> throw invalidCursor()
            }
        val priority = cursor.long(1).takeIf { it in PRIORITIES }?.toInt() ?: throw invalidCursor()
        return TaskPageKey(arrival, priority, cursor.uuid(2))
    }

    private fun unknownLocation(location: TaskLocation) =
        invalid(
            HousekeepingErrorCodes.HOUSEKEEPING_TASK_UNKNOWN_LOCATION,
            if (location is TaskLocation.Room) "roomId" else "areaId",
            "O local da tarefa não existe.",
        )

    private fun notFound() =
        NotFoundException(HousekeepingErrorCodes.HOUSEKEEPING_TASK_NOT_FOUND, "Tarefa de governança não encontrada.")

    private fun invalidCursor() =
        InvalidInputException(ErrorCodes.INVALID_CURSOR, "Cursor de paginação inválido. Recomece da primeira página.")

    private fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))

    companion object {
        /** Até quantos dias depois da data operacional a avulsa pode ser marcada. */
        const val MAX_DAYS_AHEAD = 30L
        private val PRIORITIES = CleaningType.PRIORITIES.first.toLong()..CleaningType.PRIORITIES.last.toLong()
    }
}

/**
 * O retrato da trilha: ids, local, data, tipo, origem e estado, o tamanho das `notes`, **sem** o texto (RN-13), e a
 * camareira e a posição publicadas, só pelo id dela.
 */
internal fun HousekeepingTask.auditState(): Map<String, Any?> =
    mapOf(
        "roomId" to roomId?.toString(),
        "areaId" to areaId?.toString(),
        "date" to businessDate.toString(),
        "cleaningTypeId" to cleaningTypeId.toString(),
        "source" to source.name,
        "status" to status.name,
        "priority" to priority,
        "credits" to credits,
        "arrivalExpected" to arrivalExpected,
        "scheduledCleaningId" to scheduledCleaningId?.toString(),
        "notesLength" to (notes?.let { it.codePointCount(0, it.length) } ?: 0),
        "assignedEmployeeId" to assignedEmployeeId?.toString(),
        "assignmentPosition" to assignmentPosition,
    )

internal fun HousekeepingTask.view() =
    HousekeepingTaskView(
        id,
        roomId,
        areaId,
        businessDate,
        cleaningTypeId,
        source,
        status,
        priority,
        credits,
        arrivalExpected,
        scheduledCleaningId,
        notes,
        assignedEmployeeId,
        assignmentPosition,
        refusalReason,
        startedAt,
        finishedAt,
        workedSeconds,
        cleanedBy,
        checklistExecutionId,
        hasConflict,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )
