package com.zeny.hotel.maintenance.application

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.checklists.describe
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.maintenance.domain.WorkOrderChecklistLink
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UserId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A ordem de serviço do checklist (D2-E08, RF-CHK-05): para cada ação `OPEN_OCCURRENCE` de tipo `MAINTENANCE` de um
 * `ChecklistCompleted`, abre uma ordem `origin = CHECKLIST` pelo [WorkOrdersService.openFromChecklist], com o local da
 * execução (`event.location`, gravado pelo dono do sujeito ou derivado do sujeito de uma rotina: nunca escolhido pelo
 * cliente numa execução avulsa), a categoria da ação (quando é um nome de [WorkOrderCategory], senão `OTHER`), o
 * executor como quem abriu e a conclusão como a abertura. A descrição junta o rótulo do item (da versão do checklist),
 * a categoria e a observação, até [WorkOrder.MAX_DESCRIPTION].
 *
 * Sem local (execução avulsa, evento antigo) ou com a UH ou a área inexistente ou desativada, o evento é ignorado com
 * log só dos ids, sem falhar o ouvinte (Review Focus 2). A reentrega do outbox e a execução registrada à parte de uma
 * rotina (`duplicateOf`) não abrem outra ordem do mesmo item: a chave é a execução de referência (`duplicateOf` ou a
 * própria) com o item, e a ordem guarda o `executionId` real de quem a abriu (Review Focus 1). Assíncrono, em transação
 * própria, reentregue pelo outbox se falhar.
 */
@Component
class MaintenanceChecklistListener(
    private val orders: WorkOrdersService,
    private val checklists: ChecklistsApi,
    private val rooms: RoomsApi,
) {
    @ApplicationModuleListener
    fun on(event: ChecklistCompleted) {
        val maintenance =
            event.actions.filter {
                it.type == ItemActionType.OPEN_OCCURRENCE && it.occurrenceKind == OccurrenceKind.MAINTENANCE
            }
        if (maintenance.isEmpty()) return
        val location = event.location
        if (location == null || !exists(location.roomId, location.areaId)) {
            log.info(
                "Ordens do checklist {} ignoradas pela manutenção: local ausente, inexistente ou desativado " +
                    "(UH {}, área {})",
                event.executionId,
                location?.roomId,
                location?.areaId,
            )
            return
        }
        val labels =
            checklists
                .version(ChecklistVersionId(event.versionId))
                ?.content
                ?.items
                ?.associate { it.key to it.label }
                .orEmpty()
        maintenance.forEach { action ->
            orders.openFromChecklist(
                ChecklistWorkOrderDraft(
                    roomId = location.roomId?.let(::RoomId),
                    areaId = location.areaId?.let(::AreaId),
                    category = categoryOf(action),
                    description = action.describe(labels[action.itemKey] ?: action.itemKey, WorkOrder.MAX_DESCRIPTION),
                    openedBy = UserId(event.executorId),
                    openedAt = event.completedAt,
                    link =
                        WorkOrderChecklistLink(
                            ChecklistExecutionId(event.executionId),
                            action.itemKey,
                            ChecklistExecutionId(event.duplicateOf ?: event.executionId),
                        ),
                ),
            )
        }
    }

    /** A UH ou a área existe e está ativa (a conferência que o `checklists` não faz). */
    private fun exists(
        roomId: UUID?,
        areaId: UUID?,
    ): Boolean =
        if (roomId != null) {
            rooms.room(RoomId(roomId))?.active == true
        } else {
            areaId != null && rooms.area(AreaId(areaId))?.active == true
        }

    private fun categoryOf(action: ChecklistTriggeredAction): WorkOrderCategory =
        WorkOrderCategory.entries.firstOrNull { it.name == action.category } ?: WorkOrderCategory.OTHER

    private companion object {
        val log: Logger = LoggerFactory.getLogger(MaintenanceChecklistListener::class.java)
    }
}
