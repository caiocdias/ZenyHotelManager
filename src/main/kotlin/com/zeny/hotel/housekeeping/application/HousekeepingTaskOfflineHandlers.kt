package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.StaffApi
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `housekeeping.pause-task`, `housekeeping.resume-task` e `housekeeping.retry-task`: só a tarefa. */
data class HousekeepingTaskCommandPayload(
    @field:NotNull
    val taskId: UUID?,
) {
    /** O resumo do conflito (D4-E19): só a tarefa, que já está no registro. */
    fun conflictSummary(): Map<String, Any?> = emptyMap()
}

/**
 * `housekeeping.start-task`: a tarefa e, opcional, a versão do checklist que o aparelho tem ([checklistVersionId],
 * D1-E19); sem ela, a vigente, como na ação online.
 */
data class StartHousekeepingTaskPayload(
    @field:NotNull
    val taskId: UUID?,
    val checklistVersionId: UUID? = null,
) {
    /** O resumo do conflito (D4-E19): a versão do checklist que o aparelho abriu. */
    fun conflictSummary(): Map<String, Any?> = mapOf("checklistVersionId" to checklistVersionId?.toString())
}

/** `housekeeping.refuse-task`: a tarefa e o motivo da recusa. */
data class RefuseHousekeepingTaskPayload(
    @field:NotNull
    val taskId: UUID?,
    @field:NotNull
    val reason: RefusalReason?,
) {
    /** O resumo do conflito (D4-E19): o motivo da recusa. */
    fun conflictSummary(): Map<String, Any?> = mapOf("reason" to reason?.name)
}

/**
 * `housekeeping.complete-task`: a tarefa, as respostas do checklist aberto no início (todas; só numa tarefa com
 * checklist, como na ação online) e a ocupação encontrada na UH.
 */
data class CompleteHousekeepingTaskPayload(
    @field:NotNull
    val taskId: UUID?,
    @field:Size(max = MAX_ANSWERS)
    val answers: List<@Valid HousekeepingOfflineAnswerPayload>? = null,
    val foundOccupancy: OccupancyStatus? = null,
    val foundGuestCount: Int? = null,
) {
    /**
     * O resumo do conflito (D4-E19), sem texto livre: cada resposta
     * ([HousekeepingOfflineAnswerPayload.conflictSummary]), a contagem das fotos e a ocupação encontrada.
     */
    fun conflictSummary(): Map<String, Any?> =
        mapOf(
            "answers" to answers?.map { it.conflictSummary() },
            "photoCount" to answers.orEmpty().count { it.photoFileId != null },
            "foundOccupancy" to foundOccupancy?.name,
            "foundGuestCount" to foundGuestCount,
        )

    companion object {
        const val MAX_ANSWERS = 300
    }
}

/**
 * Uma resposta do checklist num comando offline: a chave do item, só o campo do tipo dele, a foto pelo `fileId` enviado
 * antes (`POST /api/v1/files`), a observação e o horário do aparelho ([answeredAt]), corrigido pela defasagem.
 */
data class HousekeepingOfflineAnswerPayload(
    @field:NotNull
    @field:Size(max = 40)
    val itemKey: String?,
    val check: CheckAnswer? = null,
    val quantity: BigDecimal? = null,
    val number: BigDecimal? = null,
    @field:Size(max = 2000)
    val text: String? = null,
    @field:Size(max = 40)
    val choice: String? = null,
    @field:Size(max = 50)
    val choices: List<String>? = null,
    val photoFileId: UUID? = null,
    @field:Size(max = 500)
    val note: String? = null,
    val answeredAt: Instant? = null,
) {
    fun toInput(answeredAt: Instant): ChecklistAnswerInput =
        ChecklistAnswerInput
            .of(checkNotNull(itemKey), check, quantity, number, text, choice, choices, photoFileId, note)
            .copy(answeredAt = answeredAt)

    /**
     * A resposta no resumo do conflito (D4-E19), que é só de inserção e não admite expurgo: o tipo pelo campo
     * preenchido e os valores não textuais (marcação, quantidade, número, escolhas); o texto e a observação só como
     * "tinha" e a foto só como "tinha", sem o id. A quantidade e o número só entram dentro dos limites do `checklists`
     * (fora deles, só "tinha"): um número de 11 dígitos (CPF, telefone) ou com expoente extremo fica de fora. A chave
     * do item e as das opções vêm do aparelho sem conferência com o modelo: fora do formato de chave do `checklists`,
     * ficam de fora (nada de texto livre por elas).
     */
    fun conflictSummary(): Map<String, Any?> =
        mapOf(
            "itemKey" to itemKey?.takeIf(KEY::matches),
            "type" to answeredType()?.name,
            "check" to check?.name,
            "quantity" to quantity?.takeIf(::isSummaryQuantity),
            "hadQuantity" to (quantity != null),
            "number" to number?.takeIf(::isSummaryNumber),
            "hadNumber" to (number != null),
            "choice" to choice?.takeIf(KEY::matches),
            "choices" to choices?.filter(KEY::matches),
            "hadText" to !text.isNullOrBlank(),
            "hadNote" to !note.isNullOrBlank(),
            "hasPhoto" to (photoFileId != null),
        )

    private fun answeredType(): ChecklistItemType? =
        when {
            check != null -> ChecklistItemType.CHECK
            quantity != null -> ChecklistItemType.QUANTITY
            number != null -> ChecklistItemType.NUMBER
            text != null -> ChecklistItemType.TEXT
            choice != null -> ChecklistItemType.SINGLE_CHOICE
            choices != null -> ChecklistItemType.MULTI_CHOICE
            photoFileId != null -> ChecklistItemType.PHOTO
            else -> null
        }

    private companion object {
        val KEY = Regex(CHECKLIST_ITEM_KEY)

        /** Os limites de resposta do `checklists` (`ChecklistAnswers`), que não os expõe a outros módulos. */
        val MAX_QUANTITY = BigDecimal("999999")
        const val MAX_QUANTITY_SCALE = 3
        val MAX_NUMBER = BigDecimal("9999999999")
        const val MAX_NUMBER_SCALE = 4

        // A comparação vem antes da escala: um expoente extremo nunca é expandido.
        fun isSummaryQuantity(value: BigDecimal) =
            value.signum() >= 0 && value <= MAX_QUANTITY && value.stripTrailingZeros().scale() <= MAX_QUANTITY_SCALE

        fun isSummaryNumber(value: BigDecimal) =
            value.abs() <= MAX_NUMBER && value.stripTrailingZeros().scale() <= MAX_NUMBER_SCALE
    }
}

/**
 * A porta comum dos comandos offline da execução (D4-E19): exige `housekeeping.tasks.execute`, trava a tarefa e decide
 * a posse num ponto só ([resolveOwnership]) antes de entregar a ação ao handler: a ex-dona vira conflito
 * ([HousekeepingTaskConflicts]), sem a ação; os demais seguem a regra de escopo da execução. O ator é o usuário da
 * sincronização.
 */
@Component
class HousekeepingTaskOfflineAccess(
    private val store: HousekeepingTaskStore,
    private val execution: HousekeepingTaskExecution,
    private val conflicts: HousekeepingTaskConflicts,
    private val staff: StaffApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) {
    /**
     * Aplica [action] na tarefa [taskId] do [command] como o ator da sincronização, depois de travada e decidida a
     * posse, com os alertas que ela devolve; a ex-dona registra o conflito com o resumo [summary] (só calculado então).
     */
    fun applyTo(
        command: OfflineCommand<*>,
        taskId: UUID,
        summary: () -> Map<String, Any?>,
        action: (HousekeepingTaskId, Actor) -> List<OfflineCommandWarning>,
    ): OfflineCommandOutcome {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val current = actor.current()
        val id = HousekeepingTaskId(taskId)
        val task = store.findForUpdate(id)
        resolveOwnership(task, current)?.let { formerOwner ->
            return conflicts.record(checkNotNull(task), formerOwner, current, command, summary())
        }
        return OfflineCommandOutcome(action(id, current))
    }

    /**
     * A decisão de posse (D4-E19), com a tarefa travada; devolve a ex-dona quando o comando é conflito, senão `null`
     * depois de conferir o escopo. Com `ALL`, qualquer tarefa aplica. Com `OWN`, na ordem: (1) tarefa cancelada, de
     * outra dona ou sem camareira, e o ator **foi** dona (a camareira atual ou alguém da posse em
     * `housekeeping.task_assignment`) ⇒ conflito; (2) dona atual de tarefa não cancelada ⇒ aplica (uma transição
     * inválida dá a recusa da E04); (3) quem nunca foi dona, sem colaborador ou numa tarefa inexistente ⇒
     * `RESOURCE_OUT_OF_SCOPE`, sem registro.
     */
    private fun resolveOwnership(
        task: HousekeepingTask?,
        actor: Actor,
    ): EmployeeId? {
        if (task != null && authorization.scopeFilter(HousekeepingPermissions.TASKS_EXECUTE) is ScopeFilter.Own) {
            val employee = staff.employeeOfUser(actor.userId)?.id
            if (employee != null && !isCurrentOwner(task, employee) && wasOwner(task, employee)) return employee
        }
        execution.requireActsOn(task, actor)
        return null
    }

    private fun isCurrentOwner(
        task: HousekeepingTask,
        employee: EmployeeId,
    ) = task.status != TaskStatus.CANCELLED && task.assignedEmployeeId == employee

    private fun wasOwner(
        task: HousekeepingTask,
        employee: EmployeeId,
    ) = task.assignedEmployeeId == employee || store.wasAssignedTo(task.id, employee)
}

/** O handler de `housekeeping.start-task` ([StartHousekeepingTaskPayload]). */
@Component
class StartHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<StartHousekeepingTaskPayload> {
    override val type = HousekeepingOffline.START_TASK
    override val payloadType = StartHousekeepingTaskPayload::class.java

    @Audited(HousekeepingAudit.START_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<StartHousekeepingTaskPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            val device = payload.checklistVersionId?.let(::ChecklistVersionId)
            execution.startTask(id, actor, command.occurredAt, expectedVersion = null, device).warnings
        }
    }
}

/** O handler de `housekeeping.pause-task` ([HousekeepingTaskCommandPayload]). */
@Component
class PauseHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<HousekeepingTaskCommandPayload> {
    override val type = HousekeepingOffline.PAUSE_TASK
    override val payloadType = HousekeepingTaskCommandPayload::class.java

    @Audited(HousekeepingAudit.PAUSE_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<HousekeepingTaskCommandPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            execution.pauseTask(id, actor, command.occurredAt, expectedVersion = null)
            emptyList()
        }
    }
}

/** O handler de `housekeeping.resume-task` ([HousekeepingTaskCommandPayload]). */
@Component
class ResumeHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<HousekeepingTaskCommandPayload> {
    override val type = HousekeepingOffline.RESUME_TASK
    override val payloadType = HousekeepingTaskCommandPayload::class.java

    @Audited(HousekeepingAudit.RESUME_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<HousekeepingTaskCommandPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            execution.resumeTask(id, actor, command.occurredAt, expectedVersion = null)
            emptyList()
        }
    }
}

/** O handler de `housekeeping.refuse-task` ([RefuseHousekeepingTaskPayload]). */
@Component
class RefuseHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<RefuseHousekeepingTaskPayload> {
    override val type = HousekeepingOffline.REFUSE_TASK
    override val payloadType = RefuseHousekeepingTaskPayload::class.java

    @Audited(HousekeepingAudit.REFUSE_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<RefuseHousekeepingTaskPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            execution.refuseTask(id, checkNotNull(payload.reason), actor, command.occurredAt, expectedVersion = null)
            emptyList()
        }
    }
}

/** O handler de `housekeeping.retry-task` ([HousekeepingTaskCommandPayload]). */
@Component
class RetryHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<HousekeepingTaskCommandPayload> {
    override val type = HousekeepingOffline.RETRY_TASK
    override val payloadType = HousekeepingTaskCommandPayload::class.java

    @Audited(HousekeepingAudit.RETRY_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<HousekeepingTaskCommandPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            execution.retryTask(id, actor, command.occurredAt, expectedVersion = null)
            emptyList()
        }
    }
}

/**
 * O handler de `housekeeping.complete-task` ([CompleteHousekeepingTaskPayload]): o horário de cada resposta é o
 * `answeredAt` do aparelho corrigido pela mesma defasagem do `occurredAt` (RN-22), nunca depois dele; as fotos pela
 * política `TOLERATE`: a que ainda não chegou ou não serve fica de fora da resposta, sem recusar o comando, e o comando
 * é aplicado com o alerta `CHECKLIST_PHOTO_MISSING` ou `CHECKLIST_PHOTO_INVALID` (H10).
 */
@Component
class CompleteHousekeepingTaskHandler(
    private val access: HousekeepingTaskOfflineAccess,
    private val execution: HousekeepingTaskExecution,
) : OfflineCommandHandler<CompleteHousekeepingTaskPayload> {
    override val type = HousekeepingOffline.COMPLETE_TASK
    override val payloadType = CompleteHousekeepingTaskPayload::class.java

    @Audited(HousekeepingAudit.COMPLETE_TASK_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<CompleteHousekeepingTaskPayload>): OfflineCommandOutcome {
        val payload = command.payload
        return access.applyTo(command, checkNotNull(payload.taskId), payload::conflictSummary) { id, actor ->
            val completion =
                execution.completeTask(
                    id,
                    command.correctedAnswers(payload.answers),
                    payload.foundOccupancy,
                    payload.foundGuestCount,
                    actor,
                    command.occurredAt,
                    expectedVersion = null,
                    ChecklistPhotoPolicy.TOLERATE,
                )
            offlinePhotoWarnings(completion.missingPhotos, completion.rejectedPhotos)
        }
    }
}

/**
 * As respostas de um comando offline como entrada do `checklists` (RN-22): o horário de cada uma é o `answeredAt` do
 * aparelho corrigido pela mesma defasagem que o protocolo aplicou ao `occurredAt`, nunca depois dele; sem `answeredAt`,
 * o próprio `occurredAt` corrigido.
 */
internal fun OfflineCommand<*>.correctedAnswers(
    answers: List<HousekeepingOfflineAnswerPayload>?,
): List<ChecklistAnswerInput>? {
    val skew = Duration.between(deviceOccurredAt, occurredAt)
    return answers?.map { answer ->
        val corrected = answer.answeredAt?.plus(skew)?.coerceAtMost(occurredAt)
        answer.toInput(corrected ?: occurredAt)
    }
}

/**
 * Os alertas das fotos que ficaram de fora de uma conclusão offline pela política `TOLERATE` (H10, RN-22: aplicado com
 * alerta), os mesmos do `checklists`: a que ainda não chegou ([missing]) e a que não serve ([rejected]).
 */
internal fun offlinePhotoWarnings(
    missing: List<String>,
    rejected: List<String>,
): List<OfflineCommandWarning> =
    missing.map {
        OfflineCommandWarning(
            ChecklistsErrorCodes.CHECKLIST_PHOTO_MISSING,
            "A foto do item \"$it\" ainda não chegou ao servidor; a resposta ficou sem ela.",
        )
    } +
        rejected.map {
            OfflineCommandWarning(
                ChecklistsErrorCodes.CHECKLIST_PHOTO_INVALID,
                "A foto do item \"$it\" não serve (outra finalidade, outro autor ou outro registro); a resposta " +
                    "ficou sem ela.",
            )
        }
