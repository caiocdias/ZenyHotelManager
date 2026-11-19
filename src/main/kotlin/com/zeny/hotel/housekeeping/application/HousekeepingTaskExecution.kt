package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * A execução da tarefa de governança (RF-GOV-04, SPEC §8.4, M6 E04): iniciar, pausar, retomar, recusar e tentar de
 * novo. Atende as ações online (o [Actor] da requisição e o relógio do servidor) e, na E19, os comandos offline (o
 * `occurredAt` corrigido em [at] e [expectedVersion] nulo, sem conferir a versão).
 *
 * Toda ação, na ordem: exige `housekeeping.tasks.execute` (`403 PERMISSION_DENIED`); trava a tarefa (D9-E04,
 * [HousekeepingTaskStore.findForUpdate]); confere o escopo por [requireActsOn] (`403 RESOURCE_OUT_OF_SCOPE`, antes de
 * qualquer recusa que revele estado); confere a versão (`412`); aplica a transição (`409
 * HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; no início, `422 HOUSEKEEPING_TASK_NOT_DUE`, D11-E04). A trilha leva o
 * retrato da execução, sem o texto das `notes` (RN-13).
 */
@Service
class HousekeepingTaskExecution(
    private val store: HousekeepingTaskStore,
    private val staff: StaffApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    @Audited(HousekeepingAudit.START_TASK)
    @Transactional
    fun start(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.start(at, hotel.currentBusinessDate()) }

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

    /** Recusa a tarefa pelo [reason]. O sinal de não perturbe na UH entra na Task 2 da E04. */
    @Audited(HousekeepingAudit.REFUSE_TASK)
    @Transactional
    fun refuse(
        taskId: HousekeepingTaskId,
        reason: RefusalReason,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.refuse(reason, at) }

    @Audited(HousekeepingAudit.RETRY_TASK)
    @Transactional
    fun retry(
        taskId: HousekeepingTaskId,
        actor: Actor,
        at: Instant,
        expectedVersion: Long?,
    ): HousekeepingTaskView = act(taskId, actor, expectedVersion) { it.retry(at) }

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

/** O retrato da trilha da execução: o da tarefa ([auditState], sem o texto das `notes`) e os dados da execução. */
internal fun HousekeepingTask.executionAuditState(): Map<String, Any?> =
    auditState() +
        mapOf(
            "refusalReason" to refusalReason?.name,
            "startedAt" to startedAt?.toString(),
            "finishedAt" to finishedAt?.toString(),
            "workedSeconds" to workedSeconds,
            "cleanedBy" to cleanedBy?.toString(),
        )
