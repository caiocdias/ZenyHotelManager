package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingTaskCompleted
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.StaffApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * A execução da tarefa de governança (RF-GOV-04, SPEC §8.4, M6 E04): iniciar, pausar, retomar, recusar, tentar de
 * novo e concluir. Atende as ações online (o [Actor] da requisição e o relógio do servidor) e, na E19, os comandos offline (o
 * `occurredAt` corrigido em [at] e [expectedVersion] nulo, sem conferir a versão).
 *
 * Toda ação, na ordem: exige `housekeeping.tasks.execute` (`403 PERMISSION_DENIED`); trava a tarefa (D9-E04,
 * [HousekeepingTaskStore.findForUpdate]); confere o escopo por [requireActsOn] (`403 RESOURCE_OUT_OF_SCOPE`, antes de
 * qualquer recusa que revele estado); confere a versão (`412`); aplica a transição (`409
 * HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; no início, `422 HOUSEKEEPING_TASK_NOT_DUE`, D11-E04). A trilha leva o
 * retrato da execução, sem o texto das `notes` (RN-13).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas da execução; uma operação por ação da §8.4
class HousekeepingTaskExecution(
    private val store: HousekeepingTaskStore,
    private val cleaningTypes: CleaningTypeStore,
    private val scheduledCleanings: ScheduledCleaningStore,
    private val staff: StaffApi,
    private val hotel: HotelApi,
    private val rooms: RoomsApi,
    private val checklists: ChecklistsApi,
    private val events: ApplicationEventPublisher,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    /**
     * Inicia (ou refaz) a tarefa e abre o checklist dela (D5-E04): o modelo vinculado ao tipo de limpeza, se ativo e
     * publicado; senão o que vale para a tarefa ([ChecklistsApi.resolveTemplate]); senão nenhum. Havendo modelo, a
     * execução abre com a versão publicada vigente, com o ator como executor, e a tarefa guarda o id dela.
     */
    @Audited(HousekeepingAudit.START_TASK)
    @Transactional
    fun start(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView =
        act(taskId, actor, expectedVersion) {
            it.start(at, hotel.currentBusinessDate())
            it.openChecklist(openChecklist(it, actor))
        }

    @Audited(HousekeepingAudit.PAUSE_TASK)
    @Transactional
    fun pause(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.pause(at) }

    @Audited(HousekeepingAudit.RESUME_TASK)
    @Transactional
    fun resume(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.resume(at) }

    /**
     * Recusa a tarefa pelo [reason]. Por não perturbe numa tarefa de UH, liga o sinal na UH pela operação condicional
     * do `rooms` (D4-E04), que só age numa UH ocupada; numa UH vaga e numa área, a recusa vale do mesmo jeito.
     */
    @Audited(HousekeepingAudit.REFUSE_TASK)
    @Transactional
    fun refuse(
        taskId: HousekeepingTaskId,
        reason: RefusalReason,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView =
        act(taskId, actor, expectedVersion) { task ->
            task.refuse(reason, at)
            if (reason == RefusalReason.DO_NOT_DISTURB) {
                task.roomId?.let {
                    rooms.setServiceFlagIfOccupied(it, GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.TASK)
                }
            }
        }

    @Audited(HousekeepingAudit.RETRY_TASK)
    @Transactional
    fun retry(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.retry(at) }

    /**
     * Conclui a tarefa `IN_PROGRESS` (SPEC §8.4, D3, D5, D6, D7 e D10 da E04), numa transação só e na ordem de trava
     * D9 (tarefa, execução do checklist, UH, faxina programada):
     * - [answers] numa tarefa sem checklist, ou a observação ([foundOccupancy], [foundGuestCount]) numa tarefa de área
     *   ou sem a ocupação: `400 VALIDATION_FAILED` no campo;
     * - conclui o checklist pela operação condicional ([ChecklistsApi.completeExecutionIfOpen]): item obrigatório
     *   faltando, `422 CHECKLIST_REQUIRED_ITEMS_MISSING` e nada muda; já concluído por fora, segue sem erro e sem um
     *   segundo `ChecklistCompleted`, e a trilha registra isso;
     * - leva a UH Suja a Limpa ([RoomsApi.markCleanIfDirty]); a tarefa vai a `AWAITING_INSPECTION` só quando a UH ficou
     *   com inspeção pendente agora (D3), senão a `DONE`;
     * - grava a observação de ocupação, se informada ([RoomsApi.recordObservation]);
     * - na tarefa `SCHEDULED`, registra a faxina feita na data da tarefa; faxina desativada não muda, a tarefa conclui
     *   e a trilha registra que ela estava inativa (D10);
     * - grava quem concluiu e os tempos e publica [HousekeepingTaskCompleted] uma vez.
     */
    @Audited(HousekeepingAudit.COMPLETE_TASK)
    @Transactional
    @Suppress("LongParameterList") // a ação, a observação, o ator, o instante e a versão
    fun complete(
        taskId: HousekeepingTaskId,
        answers: List<ChecklistAnswerInput>?,
        foundOccupancy: OccupancyStatus?,
        foundGuestCount: Int?,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val task = requireActsOn(store.findForUpdate(taskId), actor)
        expectedVersion?.let { requireCurrentVersion(it, checkNotNull(task.version)) }
        val remarks = mutableMapOf<String, Any?>()
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.executionAuditState() + remarks }
        task.requireCompletable()
        requireCompletionInput(task, answers, foundOccupancy, foundGuestCount)

        task.checklistExecutionId?.let { execution ->
            if (checklists.completeExecutionIfOpen(execution, answers.orEmpty(), at) == null) {
                remarks["checklistCompletedElsewhere"] = true
            }
        }
        val requiresInspection = checkNotNull(cleaningTypes.cleaningType(task.cleaningTypeId)).requiresInspection
        val cleaned = task.roomId?.let { rooms.markCleanIfDirty(it, requiresInspection, RoomStatusSource.TASK) }
        if (foundOccupancy != null) {
            rooms.recordObservation(checkNotNull(task.roomId), foundOccupancy, foundGuestCount, actor.userId, at)
        }
        task.scheduledCleaningId?.let { recordScheduledCleaning(it, task, at, remarks) }
        task.complete(actor.userId, awaitingInspection = cleaned == true && requiresInspection, at)
        store.update(task)
        events.publishEvent(
            HousekeepingTaskCompleted(
                task.id.value,
                task.roomId?.value,
                task.areaId?.value,
                task.cleaningTypeId.value,
                task.businessDate,
                task.status,
                actor.userId.value,
                checkNotNull(task.finishedAt),
            ),
        )
        return task.view()
    }

    /**
     * Exige que [actor] possa executar [task] (D1-E04): com `ALL`, qualquer tarefa; com `OWN`, só a da camareira
     * atribuída, o colaborador de [HousekeepingTask.assignedEmployeeId] cujo usuário é o ator
     * ([StaffApi.employeeOfUser]). Uma tarefa inexistente ([task] nulo) dá, com `OWN`, o **mesmo** `403
     * RESOURCE_OUT_OF_SCOPE` que a tarefa alheia, sem revelar se ela existe; com `ALL`, `404
     * HOUSEKEEPING_TASK_NOT_FOUND`.
     */
    internal fun requireActsOn(
        task: HousekeepingTask?,
        actor: Actor,
    ): HousekeepingTask {
        if (task == null) {
            authorization.require(HousekeepingPermissions.TASKS_EXECUTE, OwnedResource.of(emptyList()))
            throw NotFoundException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_NOT_FOUND,
                "Tarefa de governança não encontrada.",
            )
        }
        val assignee = task.assignedEmployeeId
        val owns = assignee != null && staff.employeeOfUser(actor.userId)?.id == assignee
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE, OwnedResource.of(actor.userId.takeIf { owns }))
        return task
    }

    /** A execução do checklist da tarefa iniciada agora (D5-E04), ou `null` sem modelo que valha. */
    private fun openChecklist(
        task: HousekeepingTask,
        actor: Actor,
    ): ChecklistExecutionId? {
        val type = checkNotNull(cleaningTypes.cleaningType(task.cleaningTypeId))
        val version =
            type.checklistTemplateId?.let { checklists.currentVersion(ChecklistTemplateId(it)) }
                ?: checklists.resolveTemplate(
                    ChecklistContext.HOUSEKEEPING,
                    SubjectAttributes(
                        roomTypeId = task.roomId?.let { rooms.room(it)?.roomTypeId?.value },
                        cleaningTypeId = task.cleaningTypeId.value,
                        areaId = task.areaId?.value,
                    ),
                )
                ?: return null
        val subject = ChecklistSubject(CHECKLIST_SUBJECT_TYPE, task.id.value)
        return checklists.startExecution(version.id, subject, actor.userId, checkNotNull(task.lastTransitionAt)).id
    }

    /**
     * Registra a faxina feita na data da tarefa (D10-E04), com a faxina travada por último (D9); a desativada não muda,
     * e a trilha da conclusão registra que ela estava inativa.
     */
    private fun recordScheduledCleaning(
        id: ScheduledCleaningId,
        task: HousekeepingTask,
        at: Instant,
        remarks: MutableMap<String, Any?>,
    ) {
        val schedule = checkNotNull(scheduledCleanings.forUpdate(id)) { "A faxina $id da tarefa ${task.id} sumiu" }
        if (!schedule.active) {
            remarks["scheduledCleaningInactive"] = true
            return
        }
        audit.track(HousekeepingAudit.SCHEDULED_CLEANING, id.value) { schedule.auditState() }
        if (schedule.recordDone(task.businessDate, at)) scheduledCleanings.flush()
    }

    private fun act(
        taskId: HousekeepingTaskId,
        actor: Actor,
        expectedVersion: Long?,
        transition: (HousekeepingTask) -> Unit,
    ): HousekeepingTaskView {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val task = requireActsOn(store.findForUpdate(taskId), actor)
        expectedVersion?.let { requireCurrentVersion(it, checkNotNull(task.version)) }
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.executionAuditState() }
        transition(task)
        store.update(task)
        return task.view()
    }
}

/** Os dados de entrada da conclusão que dependem da tarefa (`400 VALIDATION_FAILED` no campo). */
private fun requireCompletionInput(
    task: HousekeepingTask,
    answers: List<ChecklistAnswerInput>?,
    foundOccupancy: OccupancyStatus?,
    foundGuestCount: Int?,
) {
    if (answers != null && task.checklistExecutionId == null) {
        throw invalidField("answers", "A tarefa não tem checklist: não envie respostas.")
    }
    if (foundOccupancy != null && task.roomId == null) {
        throw invalidField("foundOccupancy", "Uma tarefa de área não registra a ocupação de UH.")
    }
    if (foundGuestCount != null && foundOccupancy == null) {
        throw invalidField("foundOccupancy", "Informe a ocupação encontrada junto com o número de hóspedes.")
    }
}

private fun invalidField(
    field: String,
    message: String,
) = InvalidInputException(
    ErrorCodes.VALIDATION_FAILED,
    message,
    listOf(FieldViolation(field, FieldViolationCodes.INVALID_VALUE, message)),
)

/** O tipo do sujeito da execução do checklist de uma tarefa (o `checklists` não o confere). */
private const val CHECKLIST_SUBJECT_TYPE = "housekeeping.task"

/** O retrato da trilha da execução: o da tarefa ([auditState], sem o texto das `notes`) e os dados da execução. */
internal fun HousekeepingTask.executionAuditState(): Map<String, Any?> =
    auditState() +
        mapOf(
            "refusalReason" to refusalReason?.name,
            "startedAt" to startedAt?.toString(),
            "finishedAt" to finishedAt?.toString(),
            "workedSeconds" to workedSeconds,
            "cleanedBy" to cleanedBy?.toString(),
            "checklistExecutionId" to checklistExecutionId?.toString(),
        )
