package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** O local de uma tarefa: uma UH **ou** uma área comum. */
sealed interface TaskLocation {
    data class Room(
        val id: RoomId,
    ) : TaskLocation

    data class Area(
        val id: AreaId,
    ) : TaskLocation

    companion object {
        /** O local de [roomId] ou de [areaId]; os dois ou nenhum, `400 HOUSEKEEPING_TASK_LOCATION_INVALID`. */
        fun of(
            roomId: RoomId?,
            areaId: AreaId?,
        ): TaskLocation =
            when {
                roomId != null && areaId == null -> {
                    Room(roomId)
                }

                areaId != null && roomId == null -> {
                    Area(areaId)
                }

                else -> {
                    val message = "Informe o local da tarefa: a UH (roomId) ou a área (areaId), um só."
                    val code = HousekeepingErrorCodes.HOUSEKEEPING_TASK_LOCATION_INVALID
                    throw InvalidInputException(
                        code,
                        message,
                        listOf("roomId", "areaId").map { FieldViolation(it, code.value, message) },
                    )
                }
            }
    }
}

/** As ações da execução da tarefa (SPEC §8.4, M6 E04). */
enum class TaskAction {
    START,
    PAUSE,
    RESUME,
    REFUSE,
    RETRY,
    COMPLETE,
}

/** `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`, com o status em `taskStatus` e a ação em `action`. */
class HousekeepingTaskTransitionNotAllowedException(
    val action: TaskAction,
    val status: TaskStatus,
) : ConflictException(
        HousekeepingErrorCodes.HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED,
        "A tarefa ${STATUS_LABELS.getValue(status)} não pode ser ${ACTION_LABELS.getValue(action)}.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("taskStatus" to status.name, "action" to action.name)

    private companion object {
        val STATUS_LABELS =
            mapOf(
                TaskStatus.PENDING to "pendente",
                TaskStatus.ASSIGNED to "atribuída",
                TaskStatus.IN_PROGRESS to "em andamento",
                TaskStatus.PAUSED to "pausada",
                TaskStatus.AWAITING_INSPECTION to "aguardando inspeção",
                TaskStatus.REJECTED to "reprovada",
                TaskStatus.DONE to "concluída",
                TaskStatus.REFUSED to "recusada",
                TaskStatus.CANCELLED to "cancelada",
            )
        val ACTION_LABELS =
            mapOf(
                TaskAction.START to "iniciada",
                TaskAction.PAUSE to "pausada",
                TaskAction.RESUME to "retomada",
                TaskAction.REFUSE to "recusada",
                TaskAction.RETRY to "tentada de novo",
                TaskAction.COMPLETE to "concluída",
            )
    }
}

/**
 * Tarefa de governança (RF-GOV-02, SPEC §8.2.10): uma limpeza de um tipo num local (UH ou área, exatamente um, que não
 * muda) numa data operacional, com a origem, a prioridade padrão do tipo e os créditos do tipo para o tipo da UH (D2;
 * área ou sem crédito, 0). Nasce `PENDING`; a distribuição é da E03 e a execução da E04. Há no máximo uma por (local,
 * data, tipo), em qualquer status (D5-E02).
 *
 * Execução (SPEC §8.4, D6-E04): o instante de cada transição é `max(instante informado, última transição)`, para que
 * um instante fora de ordem (o relógio do servidor, ou o `occurredAt` corrigido do offline) nunca faça o tempo voltar;
 * [workedSeconds] soma só os trechos em `IN_PROGRESS` (de [inProgressSince] até a pausa ou a conclusão).
 *
 * [arrivalExpected] é um **retrato do momento da geração** (D8-E02): alocação, cancelamento ou no-show posteriores não
 * o atualizam. [notes] é texto livre da recepção e nunca vai à trilha, a eventos nem a logs (RN-13). Nunca é apagada (o
 * banco recusa).
 */
@Entity
@Table(name = "housekeeping_task", schema = "housekeeping")
@Suppress("LongParameterList", "TooManyFunctions") // os campos da tarefa, só pelo create; as transições da §8.4
class HousekeepingTask private constructor(
    @Id
    val id: HousekeepingTaskId,
    @Column(name = "room_id", updatable = false)
    val roomId: RoomId?,
    @Column(name = "area_id", updatable = false)
    val areaId: AreaId?,
    @Column(name = "business_date", nullable = false, updatable = false)
    val businessDate: LocalDate,
    @Column(name = "cleaning_type_id", nullable = false, updatable = false)
    val cleaningTypeId: CleaningTypeId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val source: TaskSource,
    @Column(nullable = false, updatable = false)
    val priority: Int,
    @Column(nullable = false, updatable = false)
    val credits: Int,
    @Column(name = "arrival_expected", nullable = false, updatable = false)
    val arrivalExpected: Boolean,
    @Column(name = "scheduled_cleaning_id", updatable = false)
    val scheduledCleaningId: ScheduledCleaningId?,
    @Column(updatable = false)
    val notes: String?,
    @Column(name = "created_by", updatable = false)
    val createdBy: UserId?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TaskStatus = TaskStatus.PENDING
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    /** A camareira publicada (D12-E03); nula em `PENDING`. */
    @Column(name = "assigned_employee_id")
    var assignedEmployeeId: EmployeeId? = null
        protected set

    /** A posição publicada na ordem de execução da camareira, de 1 em diante; nula sem camareira. */
    @Column(name = "assignment_position")
    var assignmentPosition: Int? = null
        protected set

    /** O primeiro início (D6-E04); um refazer não o muda. */
    @Column(name = "started_at")
    var startedAt: Instant? = null
        protected set

    /** A conclusão; limpa no refazer. */
    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    /** A soma dos trechos em `IN_PROGRESS`, em segundos inteiros. */
    @Column(name = "worked_seconds", nullable = false)
    var workedSeconds: Long = 0
        protected set

    /** O início do trecho em `IN_PROGRESS` corrente; nulo em qualquer outro status. */
    @Column(name = "in_progress_since")
    var inProgressSince: Instant? = null
        protected set

    /** A última transição; nula numa tarefa gravada antes da E04 (vale então [updatedAt]). */
    @Column(name = "last_transition_at")
    var lastTransitionAt: Instant? = createdAt
        protected set

    /** O usuário que concluiu (a camareira, ou a governanta, se foi ela); limpo no refazer. */
    @Column(name = "cleaned_by")
    var cleanedBy: UserId? = null
        protected set

    /** O motivo da recusa; só em `REFUSED`. */
    @Enumerated(EnumType.STRING)
    @Column(name = "refusal_reason")
    var refusalReason: RefusalReason? = null
        protected set

    /** A execução do checklist aberta no último início (D5-E04); nula numa tarefa sem checklist. */
    @Column(name = "checklist_execution_id")
    var checklistExecutionId: ChecklistExecutionId? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val location: TaskLocation
        get() = roomId?.let { TaskLocation.Room(it) } ?: TaskLocation.Area(checkNotNull(areaId))

    /**
     * Dá a tarefa à camareira [employeeId] na [position] (de 1 em diante) e a deixa `ASSIGNED` (publicação do plano do
     * dia, M6 E03). Só a partir de `PENDING` ou `ASSIGNED`: a tarefa que já andou nunca é tocada (Review Focus 4).
     */
    fun assign(
        employeeId: EmployeeId,
        position: Int,
        now: Instant,
    ) {
        require(position >= 1) { "Posição fora da ordem da camareira: $position" }
        requireNotStarted()
        status = TaskStatus.ASSIGNED
        assignedEmployeeId = employeeId
        assignmentPosition = position
        transitioned(now)
    }

    /** Volta a tarefa a `PENDING`, sem camareira (D7-E03). Só a partir de `PENDING` ou `ASSIGNED`. */
    fun unassign(now: Instant) {
        requireNotStarted()
        status = TaskStatus.PENDING
        assignedEmployeeId = null
        assignmentPosition = null
        transitioned(now)
    }

    /**
     * Inicia a tarefa `ASSIGNED`, ou refaz a `REJECTED` (SPEC §8.4): guarda o primeiro início e limpa a conclusão
     * anterior. Só até a data operacional [today] (D11-E04): tarefa de data posterior, `422 HOUSEKEEPING_TASK_NOT_DUE`.
     */
    fun start(
        at: Instant,
        today: LocalDate,
    ) {
        requireFrom(TaskAction.START, TaskStatus.ASSIGNED, TaskStatus.REJECTED)
        if (businessDate.isAfter(today)) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_NOT_DUE,
                "A tarefa é de $businessDate: só pode ser iniciada a partir dessa data operacional.",
            )
        }
        val instant = transitionInstant(at)
        if (startedAt == null) startedAt = instant
        finishedAt = null
        cleanedBy = null
        inProgressSince = instant
        status = TaskStatus.IN_PROGRESS
        transitioned(instant)
    }

    /**
     * Guarda a execução do checklist aberta no início que acabou de acontecer (D5-E04), ou nenhuma; o refazer troca a
     * anterior. Só na tarefa em andamento.
     */
    fun openChecklist(executionId: ChecklistExecutionId?) {
        check(status == TaskStatus.IN_PROGRESS) { "A tarefa $id não está em andamento ($status)" }
        checklistExecutionId = executionId
    }

    /** Pausa a tarefa `IN_PROGRESS`, somando o trecho ao tempo trabalhado. */
    fun pause(at: Instant) {
        requireFrom(TaskAction.PAUSE, TaskStatus.IN_PROGRESS)
        val instant = transitionInstant(at)
        closeStretch(instant)
        status = TaskStatus.PAUSED
        transitioned(instant)
    }

    /** Retoma a tarefa `PAUSED`, abrindo um trecho novo. */
    fun resume(at: Instant) {
        requireFrom(TaskAction.RESUME, TaskStatus.PAUSED)
        val instant = transitionInstant(at)
        inProgressSince = instant
        status = TaskStatus.IN_PROGRESS
        transitioned(instant)
    }

    /** Recusa a tarefa `ASSIGNED` pelo [reason] (não perturbe ou hóspede); a camareira continua a mesma. */
    fun refuse(
        reason: RefusalReason,
        at: Instant,
    ) {
        requireFrom(TaskAction.REFUSE, TaskStatus.ASSIGNED)
        refusalReason = reason
        status = TaskStatus.REFUSED
        transitioned(transitionInstant(at))
    }

    /** Nova tentativa da tarefa `REFUSED`: volta a `ASSIGNED`, com a mesma camareira, sem o motivo. */
    fun retry(at: Instant) {
        requireFrom(TaskAction.RETRY, TaskStatus.REFUSED)
        refusalReason = null
        status = TaskStatus.ASSIGNED
        transitioned(transitionInstant(at))
    }

    /** Confere, antes dos efeitos da conclusão, que a tarefa pode ser concluída: `409` fora de `IN_PROGRESS`. */
    fun requireCompletable() = requireFrom(TaskAction.COMPLETE, TaskStatus.IN_PROGRESS)

    /**
     * Conclui a tarefa `IN_PROGRESS` por [by]: `AWAITING_INSPECTION` se [awaitingInspection] (a conclusão deixou a UH
     * com inspeção pendente, D3-E04), senão `DONE`; fecha o trecho e grava a conclusão.
     */
    fun complete(
        by: UserId,
        awaitingInspection: Boolean,
        at: Instant,
    ) {
        requireFrom(TaskAction.COMPLETE, TaskStatus.IN_PROGRESS)
        val instant = transitionInstant(at)
        closeStretch(instant)
        finishedAt = instant
        cleanedBy = by
        status = if (awaitingInspection) TaskStatus.AWAITING_INSPECTION else TaskStatus.DONE
        transitioned(instant)
    }

    private fun requireFrom(
        action: TaskAction,
        vararg allowed: TaskStatus,
    ) {
        if (status !in allowed) throw HousekeepingTaskTransitionNotAllowedException(action, status)
    }

    /** O instante da transição: o informado, ou a última transição se ele vem antes dela (Review Focus 1). */
    private fun transitionInstant(at: Instant): Instant = maxOf(at, lastTransitionAt ?: updatedAt)

    private fun transitioned(instant: Instant) {
        val effective = transitionInstant(instant)
        lastTransitionAt = effective
        updatedAt = effective
    }

    private fun closeStretch(instant: Instant) {
        val since = checkNotNull(inProgressSince) { "A tarefa $id está em andamento sem o início do trecho" }
        workedSeconds += Duration.between(since, instant).seconds.coerceAtLeast(0)
        inProgressSince = null
    }

    private fun requireNotStarted() =
        check(status == TaskStatus.PENDING || status == TaskStatus.ASSIGNED) {
            "A tarefa $id já andou ($status): a distribuição não a toca"
        }

    override fun toString(): String = "HousekeepingTask(id=$id, source=$source, status=$status)"

    companion object {
        const val MAX_NOTES_LENGTH = 500
        private const val NUL = '\u0000'

        /**
         * Uma tarefa nova, `PENDING`, do [type] (a prioridade é a padrão dele) no [location] e na [date], com os
         * [credits] já calculados ([creditsFor]). Só a origem `SCHEDULED` leva a [scheduledCleaningId], e sempre a
         * leva. As referências (local, tipo ativo) e a data são do caso de uso; as [notes] passam por [notes].
         */
        @Suppress("LongParameterList") // os dados de criação da tarefa
        fun create(
            type: CleaningType,
            location: TaskLocation,
            date: LocalDate,
            source: TaskSource,
            arrivalExpected: Boolean,
            credits: Int,
            now: Instant,
            scheduledCleaningId: ScheduledCleaningId? = null,
            notes: String? = null,
            createdBy: UserId? = null,
        ): HousekeepingTask {
            require((source == TaskSource.SCHEDULED) == (scheduledCleaningId != null)) {
                "Só a tarefa de faxina programada leva a faxina, e sempre a leva"
            }
            require(credits >= 0) { "Créditos negativos: $credits" }
            return HousekeepingTask(
                HousekeepingTaskId.new(),
                (location as? TaskLocation.Room)?.id,
                (location as? TaskLocation.Area)?.id,
                date,
                type.id,
                source,
                type.defaultPriority,
                credits,
                arrivalExpected,
                scheduledCleaningId,
                notes(notes),
                createdBy,
                now,
            )
        }

        /**
         * As observações aparadas, ou `null` se vazias; com mais de [MAX_NOTES_LENGTH] caracteres ou com NUL (que o
         * PostgreSQL não guarda), `400 HOUSEKEEPING_DATA_INVALID` no campo `notes`, sem ecoar o texto.
         */
        fun notes(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            HousekeepingRules.require(
                trimmed.codePointCount(0, trimmed.length) <= MAX_NOTES_LENGTH && NUL !in trimmed,
                "notes",
                "Informe as observações com até $MAX_NOTES_LENGTH caracteres.",
            )
            return trimmed
        }

        /** Os créditos do [type] para a UH do tipo [roomTypeId] (D2): sem crédito, ou numa área (`null`), 0. */
        fun creditsFor(
            type: CleaningType,
            roomTypeId: RoomTypeId?,
        ): Int = roomTypeId?.let { id -> type.credits.firstOrNull { it.roomTypeId == id }?.credits } ?: 0
    }
}
