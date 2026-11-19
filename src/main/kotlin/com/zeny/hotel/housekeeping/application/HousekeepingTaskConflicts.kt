package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingNotificationTypes
import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingRealtime
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.EmployeeId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Um conflito offline (D4-E19, RN-22): o comando [commandId] de uma ex-dona ([employeeId], pelo usuário [userId]) na
 * tarefa [taskId], que estava em [taskStatus], com o resumo estruturado do que ela fez ([summary]), **sem texto livre**
 * nem o id das fotos.
 */
data class TaskConflict(
    val id: UUID,
    val taskId: HousekeepingTaskId,
    val commandId: UUID,
    val commandType: OfflineCommandType,
    val userId: UserId,
    val employeeId: EmployeeId,
    val occurredAt: Instant,
    val taskStatus: TaskStatus,
    val summary: Map<String, Any?>,
    val recordedAt: Instant,
)

/** O retrato da trilha do conflito: os ids, o comando, o status da tarefa e o resumo, que não tem texto livre. */
private fun TaskConflict.auditState(): Map<String, Any?> =
    mapOf(
        "taskId" to taskId.toString(),
        "commandId" to commandId.toString(),
        "commandType" to commandType.value,
        "employeeId" to employeeId.toString(),
        "taskStatus" to taskStatus.name,
        "summary" to summary,
    )

/** O registro dos conflitos offline (D4-E19), só de inserção, na transação corrente. */
interface TaskConflictStore {
    fun record(conflict: TaskConflict)

    /** Se a ex-dona [employeeId] já tem conflito registrado na tarefa [taskId] (o aviso é só no primeiro, D5-E19). */
    fun hasConflict(
        taskId: HousekeepingTaskId,
        employeeId: EmployeeId,
    ): Boolean
}

/**
 * O conflito da RN-22 (D4 e D5 da E19), com a tarefa já travada pelo handler: grava o registro (com entrada própria
 * na trilha, [HousekeepingAudit.TASK_CONFLICT]), marca `hasConflict`
 * na tarefa (a versão sobe e a mensagem [HousekeepingRealtime.TASK] vai ao tópico, só na primeira marca), avisa quem
 * distribui as tarefas só no primeiro conflito do par (tarefa, ex-dona) e devolve o comando aplicado com o alerta
 * `HOUSEKEEPING_TASK_CONFLICT`. Status, tempos, checklist, camareira e UH não mudam; as fotos não são vinculadas.
 */
@Component
@Suppress("LongParameterList") // as portas do registro, da marca, do aviso e da trilha
class HousekeepingTaskConflicts(
    private val conflicts: TaskConflictStore,
    private val tasks: HousekeepingTaskStore,
    private val rooms: RoomsApi,
    private val notifications: NotificationsApi,
    private val realtime: RealtimePublisher,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    fun record(
        task: HousekeepingTask,
        formerOwner: EmployeeId,
        actor: Actor,
        command: OfflineCommand<*>,
        summary: Map<String, Any?>,
    ): OfflineCommandOutcome {
        val first = !conflicts.hasConflict(task.id, formerOwner)
        audit.track(HousekeepingAudit.TASK, task.id.value) {
            task.executionAuditState() + ("hasConflict" to task.hasConflict)
        }
        val conflict =
            TaskConflict(
                UuidV7.generate(),
                task.id,
                command.commandId,
                command.type,
                actor.userId,
                formerOwner,
                command.occurredAt,
                task.status,
                summary,
                clock.instant(),
            )
        conflicts.record(conflict)
        // Uma entrada por conflito, também quando a tarefa já estava marcada (o track acima não muda então).
        audit.created(HousekeepingAudit.TASK_CONFLICT, conflict.id) { conflict.auditState() }
        if (task.markConflict()) {
            tasks.update(task)
            realtime.publish(
                HousekeepingRealtime.TOPIC,
                RealtimeMessage(HousekeepingRealtime.TASK, task.id.value, checkNotNull(task.version)),
            )
        }
        if (first) notify(task, command.type)
        return OfflineCommandOutcome(listOf(WARNING))
    }

    /** O aviso do conflito (D5-E19): o local e o verbo do comando, nada de hóspede nem de texto da ex-dona (RN-13). */
    private fun notify(
        task: HousekeepingTask,
        type: OfflineCommandType,
    ) {
        val location = label(task)
        notifications.notify(
            HousekeepingNotificationTypes.TASK_CONFLICT,
            "Conflito offline na tarefa da $location",
            "Uma camareira que não é mais dona da tarefa da $location (${DATE.format(task.businessDate)}) enviou " +
                "do aparelho o comando de ${VERBS[type] ?: "executar"} a tarefa. O comando ficou registrado sem " +
                "mudar a tarefa, que está marcada com conflito.",
            NotificationRecipients.withPermission(HousekeepingPermissions.ASSIGNMENTS_MANAGE),
        )
    }

    /** O local da tarefa no aviso: o número da UH ou o nome da área. */
    private fun label(task: HousekeepingTask): String =
        when (val location = task.location) {
            is TaskLocation.Room -> "UH ${rooms.room(location.id)?.number ?: "?"}"
            is TaskLocation.Area -> "área ${rooms.area(location.id)?.name ?: "?"}"
        }

    private companion object {
        val WARNING =
            OfflineCommandWarning(
                HousekeepingErrorCodes.HOUSEKEEPING_TASK_CONFLICT,
                "A tarefa não é mais sua (reatribuída, retirada ou cancelada): o comando ficou registrado como " +
                    "conflito, sem mudar a tarefa.",
            )
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val VERBS =
            mapOf(
                HousekeepingOffline.START_TASK to "iniciar",
                HousekeepingOffline.PAUSE_TASK to "pausar",
                HousekeepingOffline.RESUME_TASK to "retomar",
                HousekeepingOffline.COMPLETE_TASK to "concluir",
                HousekeepingOffline.REFUSE_TASK to "recusar",
                HousekeepingOffline.RETRY_TASK to "tentar de novo",
            )
    }
}
