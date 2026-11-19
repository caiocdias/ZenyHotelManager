package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionId
import com.zeny.hotel.housekeeping.InspectionResult
import com.zeny.hotel.housekeeping.InspectionStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import java.time.Instant

/**
 * A inspeção de uma UH (RF-GOV-06, SPEC §8.2.10, D2-E05): ligada a uma tarefa de governança (nula só na inspeção
 * voluntária) e, havendo modelo de checklist de contexto `INSPECTION`, à execução dele. Ciclo `IN_PROGRESS →
 * COMPLETED`; a conclusão grava o [result] e, na reprovação, as chaves dos itens reprovados do checklist
 * ([rejectedItems], vazio na aprovação). [inspectorId] é o usuário que inspecionou: quem iniciou, trocado por quem
 * concluiu (não há dono da inspeção). Sem campo de texto livre (RN-13); nunca é apagada.
 */
@Suppress("LongParameterList") // os campos da inspeção, só pelo start e pelo restore
class Inspection private constructor(
    val id: InspectionId,
    val taskId: HousekeepingTaskId?,
    val roomId: RoomId,
    val checklistExecutionId: ChecklistExecutionId?,
    inspectorId: UserId,
    val startedAt: Instant,
    status: InspectionStatus,
    result: InspectionResult?,
    rejectedItems: List<String>,
    completedAt: Instant?,
) {
    var inspectorId: UserId = inspectorId
        private set

    var status: InspectionStatus = status
        private set

    var result: InspectionResult? = result
        private set

    var rejectedItems: List<String> = rejectedItems
        private set

    var completedAt: Instant? = completedAt
        private set

    /**
     * Conclui a inspeção aberta com o [result], por [inspector] (que passa a ser o inspetor), em [at] (nunca antes do
     * início). Na aprovação, sem itens reprovados.
     */
    fun complete(
        result: InspectionResult,
        rejectedItems: List<String>,
        inspector: UserId,
        at: Instant,
    ) {
        check(status == InspectionStatus.IN_PROGRESS) { "A inspeção $id já foi concluída" }
        require(result != InspectionResult.APPROVED || rejectedItems.isEmpty()) {
            "A aprovação não tem itens reprovados"
        }
        this.result = result
        this.rejectedItems = rejectedItems
        inspectorId = inspector
        completedAt = maxOf(at, startedAt)
        status = InspectionStatus.COMPLETED
    }

    /** O retrato da trilha: ids, estado e as chaves reprovadas, sem dado de hóspede (RN-13). */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "taskId" to taskId?.toString(),
            "roomId" to roomId.toString(),
            "checklistExecutionId" to checklistExecutionId?.toString(),
            "inspectorId" to inspectorId.toString(),
            "status" to status.name,
            "result" to result?.name,
            "rejectedItems" to rejectedItems,
            "startedAt" to startedAt.toString(),
            "completedAt" to completedAt?.toString(),
        )

    override fun toString(): String = "Inspection(id=$id, taskId=$taskId, status=$status, result=$result)"

    companion object {
        /** Uma inspeção aberta agora ([at]) por [inspector], com a execução do checklist, se houver modelo. */
        fun start(
            id: InspectionId,
            taskId: HousekeepingTaskId?,
            roomId: RoomId,
            checklistExecutionId: ChecklistExecutionId?,
            inspector: UserId,
            at: Instant,
        ): Inspection =
            Inspection(
                id,
                taskId,
                roomId,
                checklistExecutionId,
                inspector,
                at,
                InspectionStatus.IN_PROGRESS,
                null,
                emptyList(),
                null,
            )

        /** A inspeção gravada, como está no banco (persistência). */
        fun restore(
            id: InspectionId,
            taskId: HousekeepingTaskId?,
            roomId: RoomId,
            checklistExecutionId: ChecklistExecutionId?,
            inspectorId: UserId,
            startedAt: Instant,
            status: InspectionStatus,
            result: InspectionResult?,
            rejectedItems: List<String>,
            completedAt: Instant?,
        ): Inspection =
            Inspection(
                id,
                taskId,
                roomId,
                checklistExecutionId,
                inspectorId,
                startedAt,
                status,
                result,
                rejectedItems,
                completedAt,
            )
    }
}
