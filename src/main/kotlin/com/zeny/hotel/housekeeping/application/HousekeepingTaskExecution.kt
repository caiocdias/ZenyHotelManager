package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingRealtime
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
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.StaffApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * A execução da tarefa de governança (RF-GOV-04, SPEC §8.4, M6 E04): iniciar, pausar, retomar, recusar, tentar de
 * novo e concluir. Atende as ações online (o [Actor] da requisição e o relógio do servidor) e, na E19, os comandos
 * offline, pelas variantes `…Task` sem trilha própria (o `occurredAt` corrigido em `at` e `expectedVersion` nulo, sem
 * conferir a versão).
 *
 * Toda ação, na ordem: exige `housekeeping.tasks.execute` (`403 PERMISSION_DENIED`); trava a tarefa (D9-E04,
 * [HousekeepingTaskStore.findForUpdate]); confere o escopo por [requireActsOn] (`403 RESOURCE_OUT_OF_SCOPE`, antes de
 * qualquer recusa que revele estado); confere a versão (`412`); aplica a transição (`409
 * HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; no início, `422 HOUSEKEEPING_TASK_NOT_DUE`, D11-E04). A trilha leva o
 * retrato da execução, sem o texto das `notes` (RN-13). Toda transição gravada publica a mensagem
 * [HousekeepingRealtime.TASK] no tópico `housekeeping` (D8-E04), enviada depois do commit.
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
    private val releases: RoomReleaseNotices,
    private val events: ApplicationEventPublisher,
    private val realtime: RealtimePublisher,
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
    ): HousekeepingTaskView = startTask(taskId, actor, at, expectedVersion, deviceChecklistVersion = null).view

    @Audited(HousekeepingAudit.PAUSE_TASK)
    @Transactional
    fun pause(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = pauseTask(taskId, actor, at, expectedVersion)

    @Audited(HousekeepingAudit.RESUME_TASK)
    @Transactional
    fun resume(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = resumeTask(taskId, actor, at, expectedVersion)

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
    ): HousekeepingTaskView = refuseTask(taskId, reason, actor, at, expectedVersion)

    @Audited(HousekeepingAudit.RETRY_TASK)
    @Transactional
    fun retry(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = retryTask(taskId, actor, at, expectedVersion)

    /**
     * Conclui a tarefa `IN_PROGRESS` (SPEC §8.4, D3, D5, D6, D7 e D10 da E04), numa transação só e na ordem de trava
     * D9 (tarefa, execução do checklist, UH, faxina programada):
     * - [answers] numa tarefa sem checklist, ou a observação ([foundOccupancy], [foundGuestCount]) numa tarefa de área
     *   ou sem a ocupação: `400 VALIDATION_FAILED` no campo;
     * - conclui o checklist pela operação condicional ([ChecklistsApi.completeExecutionIfOpen]): item obrigatório
     *   faltando, `422 CHECKLIST_REQUIRED_ITEMS_MISSING` e nada muda; as fotos pela [photoPolicy] (a ação online recusa
     *   a que não serve, `422 CHECKLIST_PHOTO_NOT_FOUND`/`CHECKLIST_PHOTO_INVALID`; o offline da E19 a tolera); já
     *   concluído por fora, segue sem erro e sem um segundo `ChecklistCompleted`, e a trilha registra isso;
     * - leva a UH Suja a Limpa ([RoomsApi.markCleanIfDirtyWithStatus]); a tarefa vai a `AWAITING_INSPECTION` só quando
     *   a UH ficou com inspeção pendente agora (D3), senão a `DONE`; a UH vaga que passou a pronta (tipo sem inspeção)
     *   gera o aviso de UH liberada ([RoomReleaseNotices], D5-E05);
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
        photoPolicy: ChecklistPhotoPolicy,
    ): HousekeepingTaskView =
        completeTask(taskId, answers, foundOccupancy, foundGuestCount, actor, at, expectedVersion, photoPolicy).view

    // --- As ações sem trilha nem transação próprias ---------------------------------------------------------------
    // Os métodos públicos acima as envolvem para as ações online; os comandos offline (E19) as chamam direto, na
    // transação e na trilha do próprio handler (uma entrada por comando, com a ação `…-offline`).

    /**
     * O início ([start]), com a versão do checklist que o aparelho tem ([deviceChecklistVersion], D1-E19), que
     * precisa ser de um modelo `HOUSEKEEPING` (`422 HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`, sem abrir execução).
     * Do modelo que a tarefa usa agora, abre essa versão, com o alerta `CHECKLIST_VERSION_OUTDATED` se não for a
     * vigente; de outro modelo, ou numa tarefa sem modelo, abre a do aparelho assim mesmo (RN-22), com o alerta
     * `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o modelo esperado e o recebido na trilha.
     */
    internal fun startTask(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
        deviceChecklistVersion: ChecklistVersionId?,
    ): HousekeepingTaskStart {
        val warnings = mutableListOf<OfflineCommandWarning>()
        val remarks = mutableMapOf<String, Any?>()
        val view =
            act(taskId, actor, expectedVersion, remarks) {
                val device = deviceChecklistVersion?.let(::deviceVersion)
                it.start(at, hotel.currentBusinessDate())
                it.openChecklist(openChecklist(it, actor, device, warnings, remarks))
            }
        return HousekeepingTaskStart(view, warnings)
    }

    internal fun pauseTask(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.pause(at) }

    internal fun resumeTask(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.resume(at) }

    internal fun refuseTask(
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

    internal fun retryTask(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.retry(at) }

    /**
     * A conclusão ([complete]). O instante vale a partir da última transição da tarefa (D2-E19): um [at] anterior, o do
     * aparelho corrigido, não grava nada antes dela (checklist, observação, faxina e tarefa).
     */
    @Suppress("LongParameterList") // a ação, a observação, o ator, o instante e a versão
    internal fun completeTask(
        taskId: HousekeepingTaskId,
        answers: List<ChecklistAnswerInput>?,
        foundOccupancy: OccupancyStatus?,
        foundGuestCount: Int?,
        actor: Actor,
        requestedAt: Instant,
        expectedVersion: Long?,
        photoPolicy: ChecklistPhotoPolicy,
    ): HousekeepingTaskCompletion {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val task = requireActsOn(store.findForUpdate(taskId), actor)
        expectedVersion?.let { requireCurrentVersion(it, checkNotNull(task.version)) }
        val at = task.lastTransitionAt?.let { maxOf(requestedAt, it) } ?: requestedAt
        val remarks = mutableMapOf<String, Any?>()
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.executionAuditState() + remarks }
        task.requireCompletable()
        requireCompletionInput(task, answers, foundOccupancy, foundGuestCount)

        val completion =
            task.checklistExecutionId?.let { execution ->
                checklists.completeExecutionIfOpen(execution, answers.orEmpty(), at, photoPolicy).also {
                    if (it == null) remarks["checklistCompletedElsewhere"] = true
                }
            }
        val requiresInspection = checkNotNull(cleaningTypes.cleaningType(task.cleaningTypeId)).requiresInspection
        val cleaned =
            task.roomId?.let { rooms.markCleanIfDirtyWithStatus(it, requiresInspection, RoomStatusSource.TASK) }
        if (foundOccupancy != null) {
            rooms.recordObservation(checkNotNull(task.roomId), foundOccupancy, foundGuestCount, actor.userId, at)
        }
        task.scheduledCleaningId?.let { recordScheduledCleaning(it, task, at, remarks) }
        task.complete(actor.userId, awaitingInspection = cleaned?.changed == true && requiresInspection, at)
        save(task)
        cleaned?.let { releases.notifyIfReleased(it, actor.userId) }
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
        return HousekeepingTaskCompletion(
            task.view(),
            completion?.missingPhotos.orEmpty(),
            completion?.rejectedPhotos.orEmpty(),
        )
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

    /**
     * A versão do checklist informada pelo aparelho (D1-E19): publicada e de um modelo `HOUSEKEEPING`, senão `422
     * HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID` (o payload vem do aparelho, entrada não confiável).
     */
    private fun deviceVersion(id: ChecklistVersionId): ChecklistVersionView =
        checklists.version(id)?.takeIf { it.context == ChecklistContext.HOUSEKEEPING }
            ?: throw BusinessRuleException(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID,
                "A versão do checklist informada não existe ou não é de um modelo de governança.",
            )

    /**
     * A execução do checklist da tarefa iniciada agora (D5-E04), ou `null` sem modelo que valha: a versão do aparelho
     * ([device], D1-E19), se informada, senão a vigente do modelo da tarefa. Os alertas vão a [warnings] e o desvio de
     * modelo, a [remarks] (a trilha).
     */
    private fun openChecklist(
        task: HousekeepingTask,
        actor: Actor,
        device: ChecklistVersionView?,
        warnings: MutableList<OfflineCommandWarning>,
        remarks: MutableMap<String, Any?>,
    ): ChecklistExecutionId? {
        val current = currentChecklistVersion(task)
        val version = device ?: current ?: return null
        device?.let { compareWithCurrent(it, current, warnings, remarks) }
        val subject = ChecklistSubject(CHECKLIST_SUBJECT_TYPE, task.id.value)
        return checklists.startExecution(version.id, subject, actor.userId, checkNotNull(task.lastTransitionAt)).id
    }

    /**
     * Compara a versão do aparelho ([device]) com a vigente do modelo da tarefa ([current], D1-E19): do mesmo modelo e
     * já substituída, `CHECKLIST_VERSION_OUTDATED`; de outro modelo (ou sem modelo na tarefa),
     * `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o modelo esperado e o recebido na trilha.
     */
    private fun compareWithCurrent(
        device: ChecklistVersionView,
        current: ChecklistVersionView?,
        warnings: MutableList<OfflineCommandWarning>,
        remarks: MutableMap<String, Any?>,
    ) {
        if (current != null && device.templateId == current.templateId) {
            if (device.id != current.id) {
                warnings +=
                    OfflineCommandWarning(
                        ChecklistsErrorCodes.CHECKLIST_VERSION_OUTDATED,
                        "O checklist usou a versão ${device.number} do modelo, já substituída; ela ficou guardada.",
                    )
            }
            return
        }
        remarks["expectedChecklistTemplateId"] = current?.templateId?.toString()
        remarks["receivedChecklistTemplateId"] = device.templateId.toString()
        warnings +=
            OfflineCommandWarning(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH,
                "O aparelho abriu o checklist de outro modelo; a execução ficou com ele e a troca foi registrada.",
            )
    }

    /** A versão vigente do modelo da tarefa (D5-E04): a do vínculo do tipo, senão a que vale para ela, ou nenhuma. */
    private fun currentChecklistVersion(task: HousekeepingTask): ChecklistVersionView? {
        val type = checkNotNull(cleaningTypes.cleaningType(task.cleaningTypeId))
        return type.checklistTemplateId?.let { checklists.currentVersion(ChecklistTemplateId(it)) }
            ?: checklists.resolveTemplate(
                ChecklistContext.HOUSEKEEPING,
                SubjectAttributes(
                    roomTypeId = task.roomId?.let { rooms.room(it)?.roomTypeId?.value },
                    cleaningTypeId = task.cleaningTypeId.value,
                    areaId = task.areaId?.value,
                ),
            )
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

    /** Uma ação com as notas da trilha em [remarks], que entram no retrato "depois" (D1-E19: o desvio de modelo). */
    private fun act(
        taskId: HousekeepingTaskId,
        actor: Actor,
        expectedVersion: Long?,
        remarks: Map<String, Any?> = emptyMap(),
        transition: (HousekeepingTask) -> Unit,
    ): HousekeepingTaskView {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val task = requireActsOn(store.findForUpdate(taskId), actor)
        expectedVersion?.let { requireCurrentVersion(it, checkNotNull(task.version)) }
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.executionAuditState() + remarks }
        transition(task)
        save(task)
        return task.view()
    }

    /** Grava a transição e publica a tarefa no tópico `housekeeping` (D8-E04): só `id` e a versão nova. */
    private fun save(task: HousekeepingTask) {
        store.update(task)
        realtime.publish(
            HousekeepingRealtime.TOPIC,
            RealtimeMessage(HousekeepingRealtime.TASK, task.id.value, checkNotNull(task.version)),
        )
    }
}

/** O resultado do início ([HousekeepingTaskExecution.startTask]): a tarefa e os alertas do checklist do aparelho. */
internal data class HousekeepingTaskStart(
    val view: HousekeepingTaskView,
    val warnings: List<OfflineCommandWarning>,
)

/**
 * O resultado da conclusão ([HousekeepingTaskExecution.completeTask]): a tarefa e as chaves dos itens cuja foto ficou
 * de fora da resposta pela política `TOLERATE` (a que ainda não chegou e a que não serve).
 */
internal data class HousekeepingTaskCompletion(
    val view: HousekeepingTaskView,
    val missingPhotos: List<String>,
    val rejectedPhotos: List<String>,
)

/** Os dados de entrada da conclusão que dependem da tarefa (`400 VALIDATION_FAILED` no campo). */
private fun requireCompletionInput(
    task: HousekeepingTask,
    answers: List<ChecklistAnswerInput>?,
    foundOccupancy: OccupancyStatus?,
    foundGuestCount: Int?,
) {
    val violation =
        when {
            answers != null && task.checklistExecutionId == null -> {
                FieldViolation(
                    "answers",
                    FieldViolationCodes.INVALID_VALUE,
                    "A tarefa não tem checklist: não envie respostas.",
                )
            }

            foundOccupancy != null && task.roomId == null -> {
                FieldViolation(
                    "foundOccupancy",
                    FieldViolationCodes.INVALID_VALUE,
                    "Uma tarefa de área não registra a ocupação de UH.",
                )
            }

            // O número de hóspedes sem a ocupação: a ocupação passa a ser obrigatória (padrão da E20).
            foundGuestCount != null && foundOccupancy == null -> {
                FieldViolation(
                    "foundOccupancy",
                    FieldViolationCodes.REQUIRED,
                    "Informe a ocupação encontrada junto com o número de hóspedes.",
                )
            }

            else -> {
                return
            }
        }
    throw InvalidInputException(ErrorCodes.VALIDATION_FAILED, violation.message, listOf(violation))
}

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
