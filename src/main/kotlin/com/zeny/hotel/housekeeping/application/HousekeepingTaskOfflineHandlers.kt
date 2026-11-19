package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
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
)

/**
 * `housekeeping.start-task`: a tarefa e, opcional, a versão do checklist que o aparelho tem ([checklistVersionId],
 * D1-E19); sem ela, a vigente, como na ação online.
 */
data class StartHousekeepingTaskPayload(
    @field:NotNull
    val taskId: UUID?,
    val checklistVersionId: UUID? = null,
)

/** `housekeeping.refuse-task`: a tarefa e o motivo da recusa. */
data class RefuseHousekeepingTaskPayload(
    @field:NotNull
    val taskId: UUID?,
    @field:NotNull
    val reason: RefusalReason?,
)

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
}

/**
 * A porta comum dos comandos offline da execução (D4-E19): exige `housekeeping.tasks.execute`, trava a tarefa e decide
 * a posse num ponto só ([resolveOwnership]) antes de entregar a ação ao handler. O ator é o usuário da sincronização.
 */
@Component
class HousekeepingTaskOfflineAccess(
    private val store: HousekeepingTaskStore,
    private val execution: HousekeepingTaskExecution,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) {
    /** Aplica [action] na tarefa [taskId] como o ator da sincronização, depois de travada e decidida a posse. */
    fun <T> applyTo(
        taskId: UUID,
        action: (HousekeepingTaskId, Actor) -> T,
    ): T {
        authorization.require(HousekeepingPermissions.TASKS_EXECUTE)
        val current = actor.current()
        val id = HousekeepingTaskId(taskId)
        resolveOwnership(store.findForUpdate(id), current)
        return action(id, current)
    }

    /**
     * A decisão de posse, com a tarefa travada: com `ALL`, qualquer tarefa; com `OWN`, só a dona atual (a camareira
     * atribuída) aplica, e qualquer outra pessoa recebe `RESOURCE_OUT_OF_SCOPE` (inexistente, idem; com `ALL`, `404`).
     */
    private fun resolveOwnership(
        task: HousekeepingTask?,
        actor: Actor,
    ) {
        execution.requireActsOn(task, actor)
    }
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
        val start =
            access.applyTo(checkNotNull(payload.taskId)) { id, actor ->
                val device = payload.checklistVersionId?.let(::ChecklistVersionId)
                execution.startTask(id, actor, command.occurredAt, expectedVersion = null, device)
            }
        return OfflineCommandOutcome(start.warnings)
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
        access.applyTo(checkNotNull(command.payload.taskId)) { id, actor ->
            execution.pauseTask(id, actor, command.occurredAt, expectedVersion = null)
        }
        return OfflineCommandOutcome.APPLIED
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
        access.applyTo(checkNotNull(command.payload.taskId)) { id, actor ->
            execution.resumeTask(id, actor, command.occurredAt, expectedVersion = null)
        }
        return OfflineCommandOutcome.APPLIED
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
        access.applyTo(checkNotNull(payload.taskId)) { id, actor ->
            execution.refuseTask(id, checkNotNull(payload.reason), actor, command.occurredAt, expectedVersion = null)
        }
        return OfflineCommandOutcome.APPLIED
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
        access.applyTo(checkNotNull(command.payload.taskId)) { id, actor ->
            execution.retryTask(id, actor, command.occurredAt, expectedVersion = null)
        }
        return OfflineCommandOutcome.APPLIED
    }
}

/**
 * O handler de `housekeeping.complete-task` ([CompleteHousekeepingTaskPayload]): o horário de cada resposta é o
 * `answeredAt` do aparelho corrigido pela mesma defasagem do `occurredAt` (RN-22), nunca depois dele; as fotos pela
 * política `TOLERATE` (a que não serve fica de fora da resposta, sem recusar o comando, H10).
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
        // A defasagem do relógio do aparelho, a mesma que o protocolo aplicou ao occurredAt (RN-22).
        val skew = Duration.between(command.deviceOccurredAt, command.occurredAt)
        val answers =
            payload.answers?.map { answer ->
                val corrected = answer.answeredAt?.plus(skew)?.coerceAtMost(command.occurredAt)
                answer.toInput(corrected ?: command.occurredAt)
            }
        access.applyTo(checkNotNull(payload.taskId)) { id, actor ->
            execution.completeTask(
                id,
                answers,
                payload.foundOccupancy,
                payload.foundGuestCount,
                actor,
                command.occurredAt,
                expectedVersion = null,
                ChecklistPhotoPolicy.TOLERATE,
            )
        }
        return OfflineCommandOutcome.APPLIED
    }
}
