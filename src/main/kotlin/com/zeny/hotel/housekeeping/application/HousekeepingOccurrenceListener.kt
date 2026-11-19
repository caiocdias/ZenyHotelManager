package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionId
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.domain.LostAndFoundItem
import com.zeny.hotel.housekeeping.domain.TaskLocation
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
 * As ocorrências do checklist (D5-E06, RF-CHK-05): para cada ação `OPEN_OCCURRENCE` de tipo `LOST_AND_FOUND` ou
 * `DAMAGE` de um `ChecklistCompleted`, registra o achado ou o dano com origem `CHECKLIST`, o instante do fato
 * `completedAt`, o executor como quem registrou e a descrição do rótulo do item (da versão do checklist), da categoria
 * e da observação da ação, até 500 caracteres. O local sai do sujeito da execução, opaco no `checklists`:
 * - a tarefa (`housekeeping.task`) ou a inspeção (`housekeeping.inspection`) da governança: a UH ou a área dela, e a
 *   origem da tarefa, para o dano não vincular reserva a partir de uma tarefa de saída ou de troca de UH (D4-E06);
 * - a UH (`rooms.room`) ou a área (`rooms.area`) de uma rotina, conferida pela [RoomsApi].
 *
 * Outros sujeitos, a rotina sem local e o local, a tarefa ou a inspeção inexistente são ignorados com log. A execução
 * registrada à parte de uma rotina (`duplicateOf`) tem outro `executionId` e cria o próprio registro (achado e dano não
 * são consumo em dobro; a revisão filtra, Review Focus 3). Assíncrono, em transação própria e reentregue pelo outbox se
 * falhar: idempotente por execução, item e tipo (o índice único da origem em cada tabela; a reentrega devolve o
 * registro gravado).
 */
@Component
@Suppress("LongParameterList") // as portas do local (tarefa, inspeção, rooms), do checklist e dos dois registros
class HousekeepingOccurrenceListener(
    private val tasks: HousekeepingTaskStore,
    private val inspections: InspectionStore,
    private val rooms: RoomsApi,
    private val checklists: ChecklistsApi,
    private val lostAndFound: HousekeepingLostAndFound,
    private val damages: HousekeepingDamages,
) {
    @ApplicationModuleListener
    fun on(event: ChecklistCompleted) {
        val occurrences =
            event.actions.filter {
                it.type == ItemActionType.OPEN_OCCURRENCE && it.occurrenceKind in RECORDED_KINDS
            }
        if (occurrences.isEmpty()) return
        val place = placeOf(event) ?: return
        val labels =
            checklists
                .version(ChecklistVersionId(event.versionId))
                ?.content
                ?.items
                ?.associate { it.key to it.label }
                .orEmpty()
        occurrences.forEach { record(event, place, it, description(labels[it.itemKey] ?: it.itemKey, it)) }
    }

    private fun record(
        event: ChecklistCompleted,
        place: Place,
        action: ChecklistTriggeredAction,
        description: String,
    ) {
        val execution = ChecklistExecutionId(event.executionId)
        when (action.occurrenceKind) {
            OccurrenceKind.LOST_AND_FOUND -> {
                lostAndFound.record(
                    LostAndFoundDraft(
                        LostAndFoundItemId.new(),
                        place.roomId,
                        place.areaId,
                        description,
                        storagePlace = null,
                        foundAt = event.completedAt,
                        reportedBy = UserId(event.executorId),
                        source = OccurrenceSource.CHECKLIST,
                        checklistExecutionId = execution,
                        checklistItemKey = action.itemKey,
                    ),
                )
            }

            OccurrenceKind.DAMAGE -> {
                damages.record(
                    DamageReportDraft(
                        DamageReportId.new(),
                        place.roomId,
                        place.areaId,
                        description,
                        estimatedCost = null,
                        occurredAt = event.completedAt,
                        reportedBy = UserId(event.executorId),
                        source = OccurrenceSource.CHECKLIST,
                        taskSource = place.taskSource,
                        checklistExecutionId = execution,
                        checklistItemKey = action.itemKey,
                    ),
                )
            }

            else -> {
                return
            }
        }
    }

    /** O local do sujeito da execução, ou `null` (com log) quando não é da governança nem uma UH ou área existente. */
    private fun placeOf(event: ChecklistCompleted): Place? {
        val id = event.subjectId
        val place =
            when (event.subjectType) {
                TASK_CHECKLIST_SUBJECT -> id?.let { taskPlace(HousekeepingTaskId(it)) }
                INSPECTION_CHECKLIST_SUBJECT -> id?.let(::inspectionPlace)
                ROOM_SUBJECT -> id?.let { RoomId(it) }?.takeIf { rooms.room(it) != null }?.let { Place(it, null, null) }
                AREA_SUBJECT -> id?.let { AreaId(it) }?.takeIf { rooms.area(it) != null }?.let { Place(null, it, null) }
                else -> null
            }
        if (place == null) {
            log.info(
                "Ocorrências do checklist {} ignoradas pela governança: sujeito {} sem local conhecido (rotina {})",
                event.executionId,
                event.subjectType,
                event.routineId,
            )
        }
        return place
    }

    private fun taskPlace(id: HousekeepingTaskId): Place? =
        tasks.task(id)?.let { task ->
            when (val location = task.location) {
                is TaskLocation.Room -> Place(location.id, null, task.source)
                is TaskLocation.Area -> Place(null, location.id, task.source)
            }
        }

    private fun inspectionPlace(id: UUID): Place? =
        inspections.find(InspectionId(id))?.let { inspection ->
            Place(inspection.roomId, null, inspection.taskId?.let { tasks.task(it)?.source })
        }

    /** O local do registro: a UH **ou** a área e, quando vem de uma tarefa, a origem dela. */
    private data class Place(
        val roomId: RoomId?,
        val areaId: AreaId?,
        val taskSource: TaskSource?,
    )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HousekeepingOccurrenceListener::class.java)

        /** Os tipos de sujeito de rotina que o ouvinte reconhece (os do `rooms`, D5-E06). */
        const val ROOM_SUBJECT = "rooms.room"
        const val AREA_SUBJECT = "rooms.area"

        val RECORDED_KINDS = setOf(OccurrenceKind.LOST_AND_FOUND, OccurrenceKind.DAMAGE)

        /**
         * A descrição do registro: o rótulo do item, a categoria e a observação da ação (os presentes, nesta ordem,
         * separados por " - "), cortada em [LostAndFoundItem.MAX_DESCRIPTION] (o mesmo limite do dano) code points.
         */
        fun description(
            label: String,
            action: ChecklistTriggeredAction,
        ): String {
            val text =
                listOf(label, action.category, action.note)
                    .mapNotNull { part -> part?.replace(NUL, "")?.trim()?.takeIf { it.isNotEmpty() } }
                    .joinToString(" - ")
            val limit = LostAndFoundItem.MAX_DESCRIPTION
            if (text.codePointCount(0, text.length) <= limit) return text
            return text.substring(0, text.offsetByCodePoints(0, limit))
        }

        const val NUL = "\u0000"
    }
}
