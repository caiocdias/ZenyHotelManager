package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanId
import com.zeny.hotel.housekeeping.AssignmentPlanStatus
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingRealtime
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.NotNotifiedReason
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.domain.AssignmentChange
import com.zeny.hotel.housekeeping.domain.AssignmentPlan
import com.zeny.hotel.housekeeping.domain.AssignmentSuggester
import com.zeny.hotel.housekeeping.domain.Attendant
import com.zeny.hotel.housekeeping.domain.AttendantId
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.SuggestionTask
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PreconditionRequiredException
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.StorableRange
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.EmployeeSummary
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Persistência dos planos do dia, na transação corrente. */
interface AssignmentPlanStore {
    /**
     * Grava o plano se ainda não há outro na data; devolve `false` quando já há, sem lançar e sem abortar a transação
     * (`INSERT … ON CONFLICT DO NOTHING`). Com outro `INSERT` da mesma data em curso, espera ele confirmar.
     */
    fun createIfAbsent(plan: AssignmentPlan): Boolean

    /** O plano da data com a linha travada (`SELECT … FOR UPDATE`), ou `null` se não há. */
    fun lockByDate(date: LocalDate): AssignmentPlan?

    /** O plano da data, sem trava, ou `null` se não há. */
    fun byDate(date: LocalDate): AssignmentPlan?

    /** Grava o plano já travado na versão anterior à dele, com os itens inteiros. */
    fun update(plan: AssignmentPlan)
}

/** O ajuste do plano (D6-E03): as tarefas que vão para uma camareira (mover ou acrescentar) e as que saem dele. */
data class AssignmentAdjustment(
    val items: List<AssignmentItemCommand>,
    val remove: List<HousekeepingTaskId>,
)

/** Uma tarefa que vai para a camareira [employeeId] (`null`: fica no plano sem camareira) na [position] opcional. */
data class AssignmentItemCommand(
    val taskId: HousekeepingTaskId,
    val employeeId: EmployeeId?,
    val position: Int?,
)

/** Um item do plano com camareira. */
data class AssignmentPlanItemView(
    val taskId: HousekeepingTaskId,
    val employeeId: EmployeeId,
    val position: Int,
)

/** O plano do dia: os itens com camareira (por camareira e posição) e as tarefas sem camareira, na ordem delas. */
data class AssignmentPlanView(
    val id: AssignmentPlanId,
    val date: LocalDate,
    val status: AssignmentPlanStatus,
    val version: Long,
    val items: List<AssignmentPlanItemView>,
    val unassigned: List<HousekeepingTaskId>,
)

/** Uma camareira com tarefa incluída ou removida que não foi avisada, e o motivo. */
data class NotNotifiedView(
    val employeeId: EmployeeId,
    val reason: NotNotifiedReason,
)

/** O resultado da publicação: o plano publicado, quantas camareiras foram avisadas e as que não foram. */
data class PublishResult(
    val id: AssignmentPlanId,
    val date: LocalDate,
    val status: AssignmentPlanStatus,
    val version: Long,
    val notified: Int,
    val notNotified: List<NotNotifiedView>,
)

/**
 * 422 `ASSIGNMENT_TASK_NOT_ASSIGNABLE` da publicação: o rascunho passaria a outra camareira tarefas que já andaram
 * (Review Focus 4); os ids delas vão em `errors[]` (no `field`).
 */
class TaskAlreadyStartedException(
    taskIds: List<HousekeepingTaskId>,
) : DomainException(
        ErrorKind.RULE_VIOLATION,
        HousekeepingErrorCodes.ASSIGNMENT_TASK_NOT_ASSIGNABLE,
        MESSAGE,
        taskIds.map {
            FieldViolation(
                it.toString(),
                HousekeepingErrorCodes.ASSIGNMENT_TASK_NOT_ASSIGNABLE.value,
                MESSAGE,
            )
        },
    ) {
    private companion object {
        const val MESSAGE =
            "A tarefa já andou e o plano a passaria a outra camareira: ajuste o plano e publique de novo."
    }
}

/**
 * O plano do dia da distribuição das tarefas de governança (RF-GOV-03, M6 E03): a sugestão, a leitura, o ajuste e a
 * publicação.
 *
 * - **Permissões (D10-E03):** sugerir e ajustar exigem `housekeeping.assignments.manage`, conferida antes de qualquer
 *   recusa que revele estado; ler exige `housekeeping.tasks.read`.
 * - **Janela (D2-E03):** da data operacional até [MAX_DAYS_AHEAD] dias depois (`422 ASSIGNMENT_PLAN_DATE_INVALID`); o
 *   plano de uma data que ficou para trás está congelado. O plano da data operacional leva as tarefas `PENDING` e
 *   `ASSIGNED` da data e das anteriores; o de uma data futura, só as da data.
 * - **Camareiras (D1-E03):** os colaboradores ativos escalados na data no setor `DepartmentKey.HOUSEKEEPING`; a
 *   sugestão aceita um subconjunto (`employeeIds`). Fora da escala, ou inexistente (D11-E03), `422
 *   ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`.
 * - **Concorrência (D6-E03):** o plano é criado por `INSERT … ON CONFLICT DO NOTHING` e relido com a linha travada; a
 *   versão do `If-Match` é comparada sob a trava. Num plano existente, toda escrita exige o `If-Match` (`428`/`412`);
 *   na criação, ele é opcional.
 * - **Sugestão:** refaz o rascunho inteiro com o [AssignmentSuggester], com a chegada prevista recalculada (D3-E03) e o
 *   "Não perturbe" do momento (D4-E03), e volta o plano a `DRAFT`.
 * - **Publicação** ([publish]): grava o rascunho nas tarefas, avisa as camareiras com mudança (D8-E03) e publica no
 *   tópico `housekeeping` (D9-E03); a sugestão e o ajuste não publicam no tópico.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas que o caso de uso usa; um apoio por regra
class AssignmentPlansService(
    private val plans: AssignmentPlanStore,
    private val tasks: HousekeepingTaskStore,
    private val rooms: RoomsApi,
    private val staff: StaffApi,
    private val arrivals: ArrivalsByRoom,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
    private val actor: CurrentActor,
    private val notifications: NotificationsApi,
    private val realtime: RealtimePublisher,
) {
    @Audited(HousekeepingAudit.SUGGEST_ASSIGNMENT_PLAN)
    @Transactional
    fun suggest(
        date: LocalDate,
        expectedVersion: Long?,
        employeeIds: List<EmployeeId>?,
    ): AssignmentPlanView {
        authorization.require(HousekeepingPermissions.ASSIGNMENTS_MANAGE)
        val today = requireInWindow(date)
        val created = plans.createIfAbsent(AssignmentPlan.create(date, clock.instant()))
        val plan = checkNotNull(plans.lockByDate(date))
        if (created) {
            expectedVersion?.let { requireCurrentVersion(it, plan.version) }
            audit.created(HousekeepingAudit.ASSIGNMENT_PLAN, plan.id.value) { plan.auditState() }
        } else {
            requireCurrentVersion(expectedVersion ?: throw preconditionRequired(), plan.version)
            audit.track(HousekeepingAudit.ASSIGNMENT_PLAN, plan.id.value) { plan.auditState() }
        }
        val attendants = chosen(scheduledAttendants(date), employeeIds)
        val eligible = tasks.assignableUpTo(date, includePrevious = date == today)
        plan.replace(AssignmentSuggester.suggest(suggestionTasks(eligible, date), attendants), clock.instant())
        plans.update(plan)
        return plan.view()
    }

    @Transactional(readOnly = true)
    fun get(date: LocalDate): AssignmentPlanView {
        authorization.require(HousekeepingPermissions.TASKS_READ)
        if (!StorableRange.contains(date)) throw notFound()
        return (plans.byDate(date) ?: throw notFound()).view()
    }

    /**
     * Na ordem: `If-Match` (`428`), o corpo (`400`), a janela (`422`), o plano (`404`), a versão (`412`), as tarefas
     * inexistentes (`400 ASSIGNMENT_UNKNOWN_TASK`), as camareiras fora da escala e as tarefas que não podem entrar no
     * plano (`422`). Tirar uma tarefa que não está no plano não muda nada.
     */
    @Audited(HousekeepingAudit.ADJUST_ASSIGNMENT_PLAN)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun adjust(
        date: LocalDate,
        expectedVersion: Long?,
        adjustment: AssignmentAdjustment,
    ): AssignmentPlanView {
        authorization.require(HousekeepingPermissions.ASSIGNMENTS_MANAGE)
        val version = expectedVersion ?: throw preconditionRequired()
        validate(adjustment)
        val today = requireInWindow(date)
        val plan = plans.lockByDate(date) ?: throw notFound()
        requireCurrentVersion(version, plan.version)
        audit.track(HousekeepingAudit.ASSIGNMENT_PLAN, plan.id.value) { plan.auditState() }
        val referenced = adjustment.items.map { it.taskId } + adjustment.remove
        val found = tasks.tasks(referenced.map { it.value }).associateBy { it.id }
        referenced.firstOrNull { it !in found }?.let { throw unknownTask(adjustment, it) }
        chosen(scheduledAttendants(date), adjustment.items.mapNotNull { it.employeeId })
        val refused = adjustment.items.filterNot { found.getValue(it.taskId).assignableTo(date, today) }
        if (refused.isNotEmpty()) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.ASSIGNMENT_TASK_NOT_ASSIGNABLE,
                "A tarefa não pode entrar no plano: já andou ou não é da data do plano.",
            )
        }
        plan.adjust(
            adjustment.items.map {
                AssignmentChange(
                    it.taskId,
                    it.employeeId?.let { id ->
                        AttendantId(id.value)
                    },
                    it.position,
                )
            },
            adjustment.remove.toSet(),
            clock.instant(),
        )
        plans.update(plan)
        return plan.view()
    }

    /**
     * Publica o plano da [date], sob a trava dele. Na ordem: `If-Match` (`428`), a janela (`422`), o plano (`404`), a
     * versão (`412`); depois, sem gravar nada se alguma falhar:
     *
     * - **tarefa que já andou** (fora de `PENDING` e `ASSIGNED`, Review Focus 4): nunca é tocada. O item dela com a
     *   mesma camareira (inclusive com outra posição) é ignorado; o item que a passaria a outra camareira (ou a
     *   deixaria sem camareira) recusa a publicação inteira, `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE` com os ids em
     *   `errors[]`;
     * - **escala** (Review Focus 1): toda camareira que o rascunho faria receber uma tarefa precisa estar na escala da
     *   data (`422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`). Os `employeeIds` da sugestão não são reconferidos.
     *
     * Grava em cada tarefa do rascunho a camareira e a posição (`ASSIGNED`), ou a volta a `PENDING` sem camareira; a
     * tarefa já igual ao item não muda. A tarefa **retirada** (D7-E03: `ASSIGNED`, dentro do conteúdo do plano pela
     * D2-E03 e fora do rascunho) volta a `PENDING`. Uma falha otimista numa tarefa (outra escrita chegou antes) dá
     * `412`. O plano fica `PUBLISHED`. Depois de tudo gravado, avisa cada camareira com tarefa incluída ou removida
     * (não a que só mudou de posição) e publica a mensagem no tópico `housekeeping`.
     */
    @Audited(HousekeepingAudit.PUBLISH_ASSIGNMENT_PLAN)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun publish(
        date: LocalDate,
        expectedVersion: Long?,
    ): PublishResult {
        authorization.require(HousekeepingPermissions.ASSIGNMENTS_MANAGE)
        val version = expectedVersion ?: throw preconditionRequired()
        val today = requireInWindow(date)
        val plan = plans.lockByDate(date) ?: throw notFound()
        requireCurrentVersion(version, plan.version)
        audit.track(HousekeepingAudit.ASSIGNMENT_PLAN, plan.id.value) { plan.auditState() }
        val items = plan.items.associateBy { it.taskId }
        val (open, started) =
            tasks.tasks(items.keys.map { it.value }).partition { it.status in ASSIGNABLE }
        val moved = started.filter { it.assignedEmployeeId?.value != items.getValue(it.id).attendant?.value }
        if (moved.isNotEmpty()) throw TaskAlreadyStartedException(moved.map { it.id })
        val schedule = scheduledEmployees(date)
        val scheduled = schedule.filter { it.active }.map { it.id.value }.toSet()
        if (open.any { task -> items.getValue(task.id).attendant?.let { it.value !in scheduled } == true }) {
            throw notScheduled()
        }
        val now = clock.instant()
        val withdrawn =
            tasks
                .assignableUpTo(date, includePrevious = date == today)
                .filter { it.status == TaskStatus.ASSIGNED && it.id !in items }
        val changes =
            open.mapNotNull { task ->
                val item = items.getValue(task.id)
                write(task, item.attendant?.let { EmployeeId(it.value) }, item.position, now)
            } + withdrawn.mapNotNull { write(it, null, 0, now) }
        plan.publish(now, actor.currentOrNull()?.userId)
        plans.update(plan)
        val (notified, notNotified) = notify(date, changes, schedule.associateBy { it.id })
        realtime.publish(
            HousekeepingRealtime.TOPIC,
            RealtimeMessage(HousekeepingRealtime.ASSIGNMENT_PLAN, plan.id.value, plan.version),
        )
        return PublishResult(plan.id, plan.businessDate, plan.status, plan.version, notified, notNotified)
    }

    /**
     * As camareiras da [date] (D1-E03): os colaboradores ativos escalados no setor de governança da carga
     * (`DepartmentKey.HOUSEKEEPING`), sem repetição, na ordem da escala. Sem o setor (a carga do `staff` não deixa
     * apagá-lo), a invariante quebrou: `IllegalStateException`.
     */
    internal fun scheduledAttendants(date: LocalDate): List<Attendant> =
        scheduledEmployees(date)
            .filter { it.active }
            .map { Attendant(AttendantId(it.id.value), it.name) }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Os colaboradores escalados na [date] no setor de governança, ativos ou não, sem repetição. */
    private fun scheduledEmployees(date: LocalDate): List<EmployeeSummary> {
        val department =
            checkNotNull(staff.departments().firstOrNull { it.key == DepartmentKey.HOUSEKEEPING }) {
                "O setor de governança (DepartmentKey.HOUSEKEEPING) da carga do staff não existe"
            }
        return staff
            .daySchedule(date, department.id)
            .assignments
            .map { it.employee }
            .distinctBy { it.id }
    }

    /** A mudança da camareira de uma tarefa na publicação, para o aviso. */
    private data class AssigneeChange(
        val task: HousekeepingTask,
        val before: EmployeeId?,
        val after: EmployeeId?,
        val position: Int,
    )

    /**
     * Grava na tarefa a camareira [employee] na [position] (`null`: volta a `PENDING`), acompanhada na trilha; a tarefa
     * que já está assim não muda. Devolve a mudança, ou `null` se nada mudou.
     */
    private fun write(
        task: HousekeepingTask,
        employee: EmployeeId?,
        position: Int,
        now: Instant,
    ): AssigneeChange? {
        val before = task.assignedEmployeeId
        val unchanged =
            if (employee == null) {
                task.status == TaskStatus.PENDING
            } else {
                task.status == TaskStatus.ASSIGNED && before == employee && task.assignmentPosition == position
            }
        if (unchanged) return null
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.auditState() }
        if (employee == null) task.unassign(now) else task.assign(employee, position, now)
        tasks.update(task)
        return AssigneeChange(task, before, employee, position)
    }

    /**
     * Avisa cada camareira com tarefa incluída ou removida (D8-E03), uma notificação para cada, e devolve quantas foram
     * avisadas e as que não foram: sem usuário vinculado (ou fora da escala da data, onde o usuário é achado),
     * `NO_USER`; com o `notify` sem notificação criada, `NOT_DELIVERED`.
     */
    private fun notify(
        date: LocalDate,
        changes: List<AssigneeChange>,
        employees: Map<EmployeeId, EmployeeSummary>,
    ): Pair<Int, List<NotNotifiedView>> {
        val included = changes.filter { it.after != null && it.after != it.before }.groupBy { checkNotNull(it.after) }
        val removed = changes.filter { it.before != null && it.after != it.before }.groupBy { checkNotNull(it.before) }
        val title = AssignmentNotice.title(date)
        val changed = (included.keys + removed.keys).sortedBy { it.value }
        val notNotified =
            changed.mapNotNull { employee ->
                val user =
                    employees[employee]?.userId
                        ?: return@mapNotNull NotNotifiedView(employee, NotNotifiedReason.NO_USER)
                val body =
                    AssignmentNotice.body(
                        included[employee].orEmpty().sortedBy { it.position }.map { label(it.task) },
                        removed[employee].orEmpty().map { label(it.task) }.sorted(),
                    )
                val created =
                    notifications.notify(
                        NotificationsTypes.TASK_ASSIGNED,
                        title,
                        body,
                        NotificationRecipients.user(user),
                    )
                if (created.isEmpty()) NotNotifiedView(employee, NotNotifiedReason.NOT_DELIVERED) else null
            }
        return (changed.size - notNotified.size) to notNotified
    }

    /** O local da tarefa no aviso: o número da UH ou o nome da área, nada de hóspede (RN-13). */
    private fun label(task: HousekeepingTask): String =
        when (val location = task.location) {
            is TaskLocation.Room -> "UH ${rooms.room(location.id)?.number ?: "?"}"
            is TaskLocation.Area -> rooms.area(location.id)?.name ?: "Área"
        }

    private fun notScheduled() =
        BusinessRuleException(
            HousekeepingErrorCodes.ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED,
            "O colaborador não está na escala do setor de governança na data do plano.",
        )

    /** As camareiras de [employeeIds] entre as escaladas (todas, sem a lista); alguma fora delas, `422`. */
    private fun chosen(
        scheduled: List<Attendant>,
        employeeIds: List<EmployeeId>?,
    ): List<Attendant> {
        if (employeeIds == null) return scheduled
        val wanted = employeeIds.map { AttendantId(it.value) }.toSet()
        if (!scheduled.map { it.id }.containsAll(wanted)) throw notScheduled()
        return scheduled.filter { it.id in wanted }
    }

    /** A data operacional, se [date] está na janela do plano; senão, `422`. */
    private fun requireInWindow(date: LocalDate): LocalDate {
        val today = hotel.currentBusinessDate()
        if (!StorableRange.contains(date) || date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.ASSIGNMENT_PLAN_DATE_INVALID,
                "Informe a data do plano entre a data operacional ($today) e $MAX_DAYS_AHEAD dias depois dela.",
            )
        }
        return today
    }

    /** Se a tarefa pode entrar no plano de [date] (D2-E03 e Review Focus 4). */
    private fun HousekeepingTask.assignableTo(
        date: LocalDate,
        today: LocalDate,
    ): Boolean =
        status in ASSIGNABLE &&
            (businessDate == date || (date == today && businessDate.isBefore(date)))

    /** O que a sugestão usa de cada tarefa: o local, o andar, a chegada recalculada (D3-E03) e o DND (D4-E03). */
    private fun suggestionTasks(
        eligible: List<HousekeepingTask>,
        date: LocalDate,
    ): List<SuggestionTask> {
        if (eligible.isEmpty()) return emptyList()
        val arriving = arrivals.arrivalsByRoom(date)
        val activeRooms = rooms.activeRooms().associateBy { it.id }
        return eligible.map { task ->
            val room = task.roomId?.let { activeRooms[it] ?: rooms.room(it) }
            SuggestionTask(
                task.id,
                room?.number,
                task.areaId?.let { rooms.area(it)?.name },
                room?.floor,
                task.credits,
                task.priority,
                arrivalExpected = task.roomId != null && task.roomId in arriving,
                source = task.source,
                doNotDisturb =
                    task.source == TaskSource.STAYOVER &&
                        task.roomId?.let { rooms.status(it)?.serviceFlag } == GuestServiceFlag.DO_NOT_DISTURB,
            )
        }
    }

    /** Sem lista vazia e sem a mesma tarefa duas vezes (entre os itens ou entre os itens e o `remove`): `400`. */
    private fun validate(adjustment: AssignmentAdjustment) {
        val ids = adjustment.items.map { it.taskId } + adjustment.remove
        val message =
            when {
                ids.isEmpty() -> "Informe ao menos uma mudança: em items ou em remove."
                ids.toSet().size != ids.size -> "Cada tarefa pode aparecer uma só vez no ajuste."
                else -> return
            }
        throw InvalidInputException(
            HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID,
            message,
            listOf(FieldViolation("items", HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID.value, message)),
        )
    }

    private fun unknownTask(
        adjustment: AssignmentAdjustment,
        taskId: HousekeepingTaskId,
    ): InvalidInputException {
        val field = if (adjustment.items.any { it.taskId == taskId }) "items" else "remove"
        val message = "A tarefa não existe."
        val code = HousekeepingErrorCodes.ASSIGNMENT_UNKNOWN_TASK
        return InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
    }

    private fun preconditionRequired() =
        PreconditionRequiredException(
            ErrorCodes.PRECONDITION_REQUIRED,
            "Envie o cabeçalho If-Match com a ETag da última leitura do plano.",
        )

    private fun notFound() =
        NotFoundException(HousekeepingErrorCodes.ASSIGNMENT_PLAN_NOT_FOUND, "Ainda não há plano do dia nesta data.")

    companion object {
        /** Até quantos dias depois da data operacional o plano pode ser feito (D2-E03). */
        const val MAX_DAYS_AHEAD = 7L
        private val ASSIGNABLE = setOf(TaskStatus.PENDING, TaskStatus.ASSIGNED)
    }
}

/**
 * O texto do aviso da publicação (D8-E03): o título com a data e o texto com os números das UHs e os nomes das áreas
 * incluídas e removidas, nada de hóspede (RN-13). Se passar do limite do `notify` ([MAX_BODY]), cada lista é cortada
 * pelo fim (a maior primeiro) com "e mais N".
 */
internal object AssignmentNotice {
    const val MAX_BODY = 1000
    private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")

    fun title(date: LocalDate): String = "Suas tarefas de ${date.format(DAY_MONTH)} mudaram"

    fun body(
        included: List<String>,
        removed: List<String>,
    ): String {
        var shownIncluded = included.size
        var shownRemoved = removed.size
        var text = render(included, shownIncluded, removed, shownRemoved)
        while (text.length > MAX_BODY && shownIncluded + shownRemoved > 0) {
            if (shownIncluded >= shownRemoved) shownIncluded-- else shownRemoved--
            text = render(included, shownIncluded, removed, shownRemoved)
        }
        return text
    }

    private fun render(
        included: List<String>,
        shownIncluded: Int,
        removed: List<String>,
        shownRemoved: Int,
    ): String =
        listOfNotNull(section("Incluídas", included, shownIncluded), section("Removidas", removed, shownRemoved))
            .joinToString(" ")

    private fun section(
        name: String,
        labels: List<String>,
        shown: Int,
    ): String? {
        val hidden = labels.size - shown
        return when {
            labels.isEmpty() -> null
            shown == 0 -> "$name: ${labels.size} ${if (labels.size == 1) "tarefa" else "tarefas"}."
            hidden == 0 -> "$name: ${labels.joinToString(", ")}."
            else -> "$name: ${labels.take(shown).joinToString(", ")} e mais $hidden."
        }
    }
}

/** O retrato da trilha: data, status e os itens só por ids e posição (sem nome, RN-13). */
internal fun AssignmentPlan.auditState(): Map<String, Any?> =
    mapOf(
        "date" to businessDate.toString(),
        "status" to status.name,
        "items" to
            items.map {
                mapOf(
                    "taskId" to it.taskId.toString(),
                    "employeeId" to it.attendant?.value?.toString(),
                    "position" to it.position,
                )
            },
    )

internal fun AssignmentPlan.view(): AssignmentPlanView =
    AssignmentPlanView(
        id,
        businessDate,
        status,
        version,
        items
            .mapNotNull { item ->
                item.attendant?.let { AssignmentPlanItemView(item.taskId, EmployeeId(it.value), item.position) }
            }.sortedWith(compareBy({ it.employeeId.value }, { it.position })),
        items.filter { it.attendant == null }.sortedBy { it.position }.map { it.taskId },
    )
