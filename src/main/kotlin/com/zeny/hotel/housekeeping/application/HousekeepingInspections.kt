package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingRealtime
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionId
import com.zeny.hotel.housekeeping.InspectionResult
import com.zeny.hotel.housekeeping.InspectionStatus
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.Inspection
import com.zeny.hotel.housekeeping.domain.TaskAction
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Persistência das inspeções (D2-E05), na transação corrente. */
interface InspectionStore {
    fun insert(inspection: Inspection)

    /** A inspeção pelo id, ou `null` (o ouvinte das ocorrências do checklist, D5-E06). */
    fun find(id: InspectionId): Inspection?

    /** A inspeção aberta (`IN_PROGRESS`) da tarefa, com a linha travada, ou `null`. */
    fun openForUpdate(taskId: HousekeepingTaskId): Inspection?

    /** Grava a conclusão (status, resultado, itens reprovados, inspetor e instante). */
    fun update(inspection: Inspection)

    /** As chaves reprovadas da última inspeção reprovada de cada tarefa (D2-E05); sem reprovação, fora do mapa. */
    fun lastRejectedItems(taskIds: Collection<HousekeepingTaskId>): Map<HousekeepingTaskId, List<String>>
}

/** Uma inspeção, para a resposta (D2-E05): ids, estado e as chaves reprovadas, sem texto livre. */
data class InspectionView(
    val id: InspectionId,
    val taskId: HousekeepingTaskId?,
    val roomId: RoomId,
    val checklistExecutionId: ChecklistExecutionId?,
    val status: InspectionStatus,
    val result: InspectionResult?,
    val rejectedItems: List<String>,
    val inspectorId: UserId,
    val startedAt: Instant,
    val completedAt: Instant?,
)

/**
 * O resultado de uma ação da inspeção de tarefa: a inspeção, a tarefa e, na conclusão, se a UH mudou ([roomChanged]:
 * falso quando ela já tinha perdido a inspeção pendente, Review Focus 1 e 2); nulo no início.
 */
data class InspectionOutcome(
    val inspection: InspectionView,
    val task: HousekeepingTaskView,
    val roomChanged: Boolean?,
)

/** O resultado do início ([HousekeepingInspections.startInspection]): o da ação e os alertas da versão do aparelho. */
internal data class InspectionStart(
    val outcome: InspectionOutcome,
    val warnings: List<OfflineCommandWarning>,
)

/**
 * O resultado da conclusão ([HousekeepingInspections.completeInspection]): o da ação e as chaves dos itens cuja foto
 * ficou de fora da resposta pela política `TOLERATE` (a que ainda não chegou e a que não serve).
 */
internal data class InspectionCompletion(
    val outcome: InspectionOutcome,
    val missingPhotos: List<String>,
    val rejectedPhotos: List<String>,
)

/**
 * A inspeção da tarefa de governança (RF-GOV-06, SPEC §8.4, D3-E05): iniciar e concluir, pela tarefa
 * `AWAITING_INSPECTION`. Toda ação, na ordem: exige `housekeeping.inspections.perform` (`403 PERMISSION_DENIED`, antes
 * de qualquer recusa que revele estado, inclusive para quem limpou a tarefa); trava a tarefa (a ordem de trava é
 * tarefa → inspeção → execução do checklist → UH); `404 HOUSEKEEPING_TASK_NOT_FOUND`; confere a versão (`412`); exige
 * `AWAITING_INSPECTION` (`409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`). A UH muda só pela `RoomsApi` e o checklist só
 * pela `ChecklistsApi`; cada ação publica [HousekeepingRealtime.TASK] com a versão nova da tarefa.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas da inspeção; uma função por regra
class HousekeepingInspections(
    private val tasks: HousekeepingTaskStore,
    private val inspections: InspectionStore,
    private val rooms: RoomsApi,
    private val checklists: ChecklistsApi,
    private val staff: StaffApi,
    private val notifications: NotificationsApi,
    private val releases: RoomReleaseNotices,
    private val realtime: RealtimePublisher,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    /**
     * Abre a inspeção da tarefa e, se houver modelo de contexto `INSPECTION` que valha para ela
     * ([ChecklistsApi.resolveTemplate]), a execução da versão vigente, com o ator como executor. Uma inspeção já
     * aberta: `409 HOUSEKEEPING_INSPECTION_ALREADY_OPEN`. A versão da tarefa sobe (serializa duas inspeções pelo
     * `If-Match`).
     */
    @Audited(HousekeepingAudit.START_INSPECTION)
    @Transactional
    fun start(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): InspectionOutcome = startInspection(taskId, actor, at, expectedVersion, InspectionId.new(), null).outcome

    /**
     * Conclui a inspeção aberta da tarefa com o [result]; sem inspeção aberta, só numa tarefa sem modelo de inspeção,
     * que abre e conclui numa chamada (com modelo, `409 HOUSEKEEPING_INSPECTION_NOT_STARTED`). Confere os
     * [rejectedItems] (`400`) e conclui o checklist com as [answers] (item obrigatório faltando, `422` e nada muda;
     * [answers] sem checklist, `400`).
     * - Aprovação: a tarefa vai a `DONE` e a UH Limpa com inspeção pendente a Inspecionada
     *   ([RoomsApi.markInspectedIfPending]); se a UH passou a pronta, o aviso de UH liberada ([RoomReleaseNotices]).
     * - Reprovação (D6-E05): a tarefa vai a `REJECTED` com os itens na inspeção, a UH Limpa com inspeção pendente a
     *   Suja ([RoomsApi.rejectInspectionIfPending]) e a camareira atribuída recebe `notifications.inspection-rejected`
     *   (sem usuário vinculado, o aviso é omitido).
     * A UH que perdeu a pendência não muda nos dois casos (Review Focus 1 e 2).
     */
    @Audited(HousekeepingAudit.COMPLETE_INSPECTION)
    @Transactional
    @Suppress("LongParameterList") // a ação, as respostas, o ator, o instante e a versão
    fun complete(
        taskId: HousekeepingTaskId,
        result: InspectionResult,
        answers: List<ChecklistAnswerInput>?,
        rejectedItems: List<String>,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): InspectionOutcome =
        completeInspection(
            taskId,
            result,
            answers,
            rejectedItems,
            actor,
            at,
            expectedVersion,
            ChecklistPhotoPolicy.REFUSE,
            InspectionId.new(),
        ).outcome

    /**
     * A inspeção voluntária de uma UH (D4-E05), sem tarefa e sem checklist, só aprovação: exige
     * `housekeeping.inspections.perform` (`403`, antes de qualquer recusa); UH inexistente, `400
     * HOUSEKEEPING_INSPECTION_UNKNOWN_ROOM` no campo `roomId` (H16); pela [RoomsApi.markInspectedIfClean], a UH Limpa
     * sem pendência fica Inspecionada; Limpa com inspeção pendente, `409 HOUSEKEEPING_INSPECTION_USE_TASK` (inspecione
     * pela tarefa); Suja ou já Inspecionada, `409 HOUSEKEEPING_ROOM_NOT_CLEAN`. Grava uma inspeção sem tarefa
     * `COMPLETED`/`APPROVED`.
     */
    @Audited(HousekeepingAudit.INSPECT_ROOM)
    @Transactional
    fun inspectRoom(
        roomId: RoomId,
        actor: Actor,
        at: Instant,
    ): InspectionView = inspectRoomAs(InspectionId.new(), roomId, actor, at)

    // --- As ações sem trilha nem transação próprias ---------------------------------------------------------------
    // Os métodos públicos acima as envolvem para as ações online; os comandos offline (D7-E05) as chamam direto, na
    // transação e na trilha do próprio handler (a ação `…-offline`), com o `commandId` como id da inspeção criada.

    /**
     * O início ([start]) com o id da inspeção ([inspectionId]) e a versão do checklist de inspeção que o aparelho
     * tem ([deviceChecklistVersion], D7-E05, como a D1-E19): de um modelo `INSPECTION`, senão `422
     * HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`, sem abrir nada. Do modelo que vale para a tarefa, abre essa
     * versão, com o alerta `CHECKLIST_VERSION_OUTDATED` se não for a vigente; de outro modelo, ou numa tarefa sem
     * modelo, abre a do aparelho assim mesmo (RN-22), com o alerta `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o
     * modelo esperado e o recebido na trilha da inspeção. O instante não fica antes da última transição da tarefa.
     */
    @Suppress("LongParameterList") // a ação, o ator, o instante, a versão, o id e a versão do aparelho
    internal fun startInspection(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
        inspectionId: InspectionId,
        deviceChecklistVersion: ChecklistVersionId?,
    ): InspectionStart {
        val task = awaitingInspection(taskId, expectedVersion, TaskAction.START_INSPECTION)
        if (inspections.openForUpdate(task.id) != null) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_INSPECTION_ALREADY_OPEN,
                "A tarefa já tem uma inspeção aberta: conclua-a.",
            )
        }
        // A tarefa na trilha (a versão sobe pelo touch): o retrato "depois" leva a inspeção aberta agora.
        var opened: InspectionId? = null
        audit.track(HousekeepingAudit.TASK, task.id.value) {
            task.executionAuditState() + ("openInspectionId" to opened?.toString())
        }
        val warnings = mutableListOf<OfflineCommandWarning>()
        val remarks = mutableMapOf<String, Any?>()
        val current = inspectionChecklist(task)
        val device = deviceChecklistVersion?.let { checklists.deviceVersion(it, ChecklistContext.INSPECTION) }
        device?.let { compareDeviceVersion(it, current, warnings, remarks) }
        val inspection = open(task, actor, at, device ?: current, inspectionId, remarks)
        opened = inspection.id
        tasks.touch(task)
        publish(task)
        return InspectionStart(InspectionOutcome(inspection.view(), task.view(), roomChanged = null), warnings)
    }

    /**
     * A conclusão ([complete]) com as fotos pela [photoPolicy] (a ação online as recusa; o offline as tolera, D7-E05) e
     * o id da inspeção aberta e concluída numa chamada ([newInspectionId], só sem início numa tarefa sem modelo). O
     * instante não fica antes do início da inspeção.
     */
    @Suppress("LongParameterList") // a ação, as respostas, o ator, o instante, a versão, a política e o id
    internal fun completeInspection(
        taskId: HousekeepingTaskId,
        result: InspectionResult,
        answers: List<ChecklistAnswerInput>?,
        rejectedItems: List<String>,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
        photoPolicy: ChecklistPhotoPolicy,
        newInspectionId: InspectionId,
    ): InspectionCompletion {
        val task = awaitingInspection(taskId, expectedVersion, TaskAction.COMPLETE_INSPECTION)
        audit.track(HousekeepingAudit.TASK, task.id.value) { task.executionAuditState() }
        val inspection =
            inspections.openForUpdate(task.id)?.also { open ->
                audit.track(HousekeepingAudit.INSPECTION, open.id.value) { open.auditState() }
            } ?: openWithoutChecklist(task, actor, at, newInspectionId)
        val completedAt = maxOf(at, inspection.startedAt)
        val rejected = requireRejectedItems(result, rejectedItems, inspection)
        requireAnswersFit(inspection, answers)
        val checklist =
            inspection.checklistExecutionId?.let {
                checklists.completeExecutionIfOpen(it, answers.orEmpty(), completedAt, photoPolicy)
            }
        val room =
            if (result == InspectionResult.APPROVED) {
                rooms.markInspectedIfPending(inspection.roomId, RoomStatusSource.TASK).also {
                    task.approveInspection(completedAt)
                    releases.notifyIfReleased(it, actor.userId)
                }
            } else {
                rooms.rejectInspectionIfPending(inspection.roomId, RoomStatusSource.TASK).also {
                    task.rejectInspection(completedAt)
                }
            }
        inspection.complete(result, rejected, actor.userId, completedAt)
        inspections.update(inspection)
        tasks.update(task)
        if (result == InspectionResult.REJECTED) notifyRejection(task, rejected.size)
        publish(task)
        return InspectionCompletion(
            InspectionOutcome(inspection.view(), task.view(), room.changed),
            checklist?.missingPhotos.orEmpty(),
            checklist?.rejectedPhotos.orEmpty(),
        )
    }

    /** A inspeção voluntária ([inspectRoom]) com o id da inspeção ([inspectionId]; offline, o `commandId`). */
    internal fun inspectRoomAs(
        inspectionId: InspectionId,
        roomId: RoomId,
        actor: Actor,
        at: Instant,
    ): InspectionView {
        authorization.require(HousekeepingPermissions.INSPECTIONS_PERFORM)
        if (rooms.room(roomId) == null) {
            val code = HousekeepingErrorCodes.HOUSEKEEPING_INSPECTION_UNKNOWN_ROOM
            val violation = FieldViolation("roomId", code.value, "A UH não existe.")
            throw InvalidInputException(code, violation.message, listOf(violation))
        }
        val outcome = rooms.markInspectedIfClean(roomId, RoomStatusSource.TASK)
        if (!outcome.changed) throw notInspectable(outcome.before)
        val inspection = Inspection.start(inspectionId, null, roomId, null, actor.userId, at)
        inspection.complete(InspectionResult.APPROVED, emptyList(), actor.userId, at)
        inspections.insert(inspection)
        audit.created(HousekeepingAudit.INSPECTION, inspection.id.value) { inspection.auditState() }
        releases.notifyIfReleased(outcome, actor.userId)
        return inspection.view()
    }

    /** A tarefa travada, conferida e `AWAITING_INSPECTION`, na ordem da autorização antes do estado. */
    private fun awaitingInspection(
        taskId: HousekeepingTaskId,
        expectedVersion: Long?,
        action: TaskAction,
    ): HousekeepingTask {
        authorization.require(HousekeepingPermissions.INSPECTIONS_PERFORM)
        val task =
            tasks.findForUpdate(taskId)
                ?: throw NotFoundException(
                    HousekeepingErrorCodes.HOUSEKEEPING_TASK_NOT_FOUND,
                    "Tarefa de governança não encontrada.",
                )
        expectedVersion?.let { requireCurrentVersion(it, checkNotNull(task.version)) }
        task.requireAwaitingInspection(action)
        return task
    }

    /**
     * Sem inspeção aberta: só uma tarefa sem modelo de inspeção abre e conclui numa chamada; com modelo, o checklist
     * precisava abrir no início.
     */
    private fun openWithoutChecklist(
        task: HousekeepingTask,
        actor: Actor,
        at: Instant,
        id: InspectionId,
    ): Inspection {
        if (inspectionChecklist(task) != null) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_INSPECTION_NOT_STARTED,
                "A tarefa tem checklist de inspeção: inicie a inspeção antes de concluí-la.",
            )
        }
        return open(task, actor, at, version = null, id)
    }

    /**
     * Grava a inspeção nova [id] da tarefa, com a execução da [version] do checklist, se houver, e as notas da trilha
     * em [remarks] (D7-E05: o desvio de modelo do aparelho) no retrato da criação.
     */
    @Suppress("LongParameterList") // a tarefa, o ator, o instante, a versão, o id e as notas
    private fun open(
        task: HousekeepingTask,
        actor: Actor,
        requestedAt: Instant,
        version: ChecklistVersionView?,
        id: InspectionId,
        remarks: Map<String, Any?> = emptyMap(),
    ): Inspection {
        val at = task.lastTransitionAt?.let { maxOf(requestedAt, it) } ?: requestedAt
        val execution =
            version?.let {
                checklists.startExecution(
                    it.id,
                    ChecklistSubject(INSPECTION_CHECKLIST_SUBJECT, id.value),
                    actor.userId,
                    at,
                )
            }
        val roomId = checkNotNull(task.roomId) { "A tarefa ${task.id} aguarda inspeção sem UH" }
        val inspection = Inspection.start(id, task.id, roomId, execution?.id, actor.userId, at)
        inspections.insert(inspection)
        audit.created(HousekeepingAudit.INSPECTION, id.value) { inspection.auditState() + remarks }
        return inspection
    }

    /** A versão vigente do modelo de inspeção que vale para a tarefa (D3-E05), ou nenhuma. */
    private fun inspectionChecklist(task: HousekeepingTask): ChecklistVersionView? =
        checklists.resolveTemplate(
            ChecklistContext.INSPECTION,
            SubjectAttributes(
                roomTypeId = task.roomId?.let { rooms.room(it)?.roomTypeId?.value },
                cleaningTypeId = task.cleaningTypeId.value,
            ),
        )

    /**
     * O aviso da reprovação à camareira atribuída (D6-E05): o número da UH e a contagem de itens, nada de hóspede
     * (RN-13). Sem camareira ou sem usuário vinculado a ela, nenhum aviso.
     */
    private fun notifyRejection(
        task: HousekeepingTask,
        itemCount: Int,
    ) {
        val employee = task.assignedEmployeeId ?: return
        val user = staff.employees(listOf(employee))[employee]?.userId ?: return
        val number = task.roomId?.let { rooms.room(it)?.number } ?: "?"
        val body =
            when (itemCount) {
                0 -> "A inspeção da UH $number foi reprovada: refaça a limpeza."
                1 -> "A inspeção da UH $number reprovou 1 item: refaça a limpeza."
                else -> "A inspeção da UH $number reprovou $itemCount itens: refaça a limpeza."
            }
        notifications.notify(
            NotificationsTypes.INSPECTION_REJECTED,
            "Inspeção reprovada na UH $number",
            body,
            NotificationRecipients.user(user),
        )
    }

    /**
     * Os itens reprovados conferidos (D3-E05), sem repetição: a aprovação não os leva; a reprovação sem checklist
     * também não (nada de texto livre); com checklist, pelo menos um, cada um uma chave de item da versão do checklist
     * da inspeção. Fora disso, `400 VALIDATION_FAILED` no campo.
     */
    private fun requireRejectedItems(
        result: InspectionResult,
        rejectedItems: List<String>,
        inspection: Inspection,
    ): List<String> {
        val execution = inspection.checklistExecutionId
        val violation =
            when {
                result == InspectionResult.APPROVED && rejectedItems.isNotEmpty() -> {
                    invalidItems("A aprovação não tem itens reprovados.")
                }

                result == InspectionResult.REJECTED && execution == null && rejectedItems.isNotEmpty() -> {
                    invalidItems("A inspeção não tem checklist: não envie itens reprovados.")
                }

                result == InspectionResult.APPROVED || execution == null -> {
                    null
                }

                rejectedItems.isEmpty() -> {
                    FieldViolation(
                        "rejectedItems",
                        FieldViolationCodes.REQUIRED,
                        "Informe pelo menos um item reprovado do checklist de inspeção.",
                    )
                }

                else -> {
                    unknownItem(rejectedItems, checklistKeys(execution))
                }
            }
        if (violation != null) {
            throw InvalidInputException(ErrorCodes.VALIDATION_FAILED, violation.message, listOf(violation))
        }
        return rejectedItems.distinct()
    }

    /** As chaves dos itens da versão do checklist usada pela [execution]. */
    private fun checklistKeys(execution: ChecklistExecutionId): Set<String> {
        val version = checkNotNull(checklists.execution(execution)?.versionId) { "Execução $execution sem versão" }
        return checkNotNull(checklists.version(version)).content.items.mapTo(mutableSetOf()) { it.key }
    }

    private fun publish(task: HousekeepingTask) {
        realtime.publish(
            HousekeepingRealtime.TOPIC,
            RealtimeMessage(HousekeepingRealtime.TASK, task.id.value, checkNotNull(task.version)),
        )
    }
}

private fun invalidItems(message: String) = FieldViolation("rejectedItems", FieldViolationCodes.INVALID_VALUE, message)

/** A primeira chave reprovada que não é item do checklist, como violação no índice dela, ou `null`. */
private fun unknownItem(
    rejectedItems: List<String>,
    keys: Set<String>,
): FieldViolation? {
    val index = rejectedItems.indexOfFirst { it !in keys }
    if (index < 0) return null
    return FieldViolation(
        "rejectedItems[$index]",
        FieldViolationCodes.INVALID_VALUE,
        "O item reprovado não é do checklist de inspeção.",
    )
}

/** A recusa da inspeção voluntária pelo status que a UH tinha (D4-E05). */
private fun notInspectable(status: RoomStatus): ConflictException =
    if (status.housekeepingStatus == HousekeepingStatus.CLEAN && status.inspectionPending) {
        ConflictException(
            HousekeepingErrorCodes.HOUSEKEEPING_INSPECTION_USE_TASK,
            "A UH aguarda a inspeção de uma tarefa de governança: inspecione pela tarefa.",
        )
    } else {
        ConflictException(
            HousekeepingErrorCodes.HOUSEKEEPING_ROOM_NOT_CLEAN,
            "Só uma UH Limpa (sem inspeção pendente) é inspecionada voluntariamente.",
        )
    }

/** [answers] numa inspeção sem checklist: `400 VALIDATION_FAILED` no campo `answers` (padrão da E04). */
private fun requireAnswersFit(
    inspection: Inspection,
    answers: List<ChecklistAnswerInput>?,
) {
    if (answers == null || inspection.checklistExecutionId != null) return
    val violation =
        FieldViolation(
            "answers",
            FieldViolationCodes.INVALID_VALUE,
            "A inspeção não tem checklist: não envie respostas.",
        )
    throw InvalidInputException(ErrorCodes.VALIDATION_FAILED, violation.message, listOf(violation))
}

/**
 * O tipo do sujeito da execução do checklist de inspeção (o `checklists` não o confere); o ouvinte das ocorrências o
 * reconhece no `ChecklistCompleted` (D5-E06).
 */
internal const val INSPECTION_CHECKLIST_SUBJECT = "housekeeping.inspection"

internal fun Inspection.view() =
    InspectionView(
        id,
        taskId,
        roomId,
        checklistExecutionId,
        status,
        result,
        rejectedItems,
        inspectorId,
        startedAt,
        completedAt,
    )
