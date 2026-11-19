package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionId
import com.zeny.hotel.housekeeping.InspectionResult
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `housekeeping.start-inspection`: a tarefa e, opcional, a versão do checklist de inspeção que o aparelho tem
 * ([checklistVersionId], D7-E05); sem ela, a vigente, como na ação online.
 */
data class StartInspectionPayload(
    @field:NotNull
    val taskId: UUID?,
    val checklistVersionId: UUID? = null,
)

/**
 * `housekeeping.complete-inspection`: a tarefa, o resultado, as respostas do checklist de inspeção aberto no início
 * (todas; só numa inspeção com checklist) e as chaves dos itens reprovados (na reprovação com checklist).
 */
data class CompleteInspectionPayload(
    @field:NotNull
    val taskId: UUID?,
    @field:NotNull
    val result: InspectionResult?,
    @field:Size(max = MAX_ITEMS)
    val answers: List<@Valid HousekeepingOfflineAnswerPayload>? = null,
    @field:Size(max = MAX_ITEMS)
    val rejectedItems: List<
        @NotBlank
        @Pattern(regexp = ITEM_KEY)
        String?,
    >? = null,
) {
    private companion object {
        const val MAX_ITEMS = 300

        /** O formato das chaves de item do `checklists` (`ChecklistContentRules.KEY`). */
        const val ITEM_KEY = "[a-z0-9][a-z0-9_-]{0,39}"
    }
}

/** `housekeeping.inspect-room`: a UH da inspeção voluntária (D4-E05). */
data class InspectRoomPayload(
    @field:NotNull
    val roomId: UUID?,
)

/**
 * O handler de `housekeeping.start-inspection` ([StartInspectionPayload], D7-E05): o `commandId` é o id da inspeção;
 * o `occurredAt` corrigido, o instante (nunca antes da última transição da tarefa). Sem a permissão, tarefa fora de
 * `AWAITING_INSPECTION` (inclusive já inspecionada online, Review Focus 5) ou inspeção já aberta: `REJECTED` com o
 * código da ação online.
 */
@Component
class StartInspectionHandler(
    private val inspections: HousekeepingInspections,
    private val actor: CurrentActor,
) : OfflineCommandHandler<StartInspectionPayload> {
    override val type = HousekeepingOffline.START_INSPECTION
    override val payloadType = StartInspectionPayload::class.java

    @Audited(HousekeepingAudit.START_INSPECTION_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<StartInspectionPayload>): OfflineCommandOutcome {
        val payload = command.payload
        val start =
            inspections.startInspection(
                HousekeepingTaskId(checkNotNull(payload.taskId)),
                actor.current(),
                command.occurredAt,
                expectedVersion = null,
                InspectionId(command.commandId),
                payload.checklistVersionId?.let(::ChecklistVersionId),
            )
        return OfflineCommandOutcome(start.warnings)
    }
}

/**
 * O handler de `housekeeping.complete-inspection` ([CompleteInspectionPayload], D7-E05): o horário de cada resposta é
 * o `answeredAt` do aparelho corrigido pela mesma defasagem do `occurredAt`, nunca depois dele; as fotos pela política
 * `TOLERATE`, com os alertas `CHECKLIST_PHOTO_MISSING` e `CHECKLIST_PHOTO_INVALID` (como a E19). Sem início, numa
 * tarefa sem modelo de inspeção, o `commandId` é o id da inspeção aberta e concluída.
 */
@Component
class CompleteInspectionHandler(
    private val inspections: HousekeepingInspections,
    private val actor: CurrentActor,
) : OfflineCommandHandler<CompleteInspectionPayload> {
    override val type = HousekeepingOffline.COMPLETE_INSPECTION
    override val payloadType = CompleteInspectionPayload::class.java

    @Audited(HousekeepingAudit.COMPLETE_INSPECTION_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<CompleteInspectionPayload>): OfflineCommandOutcome {
        val payload = command.payload
        val completion =
            inspections.completeInspection(
                HousekeepingTaskId(checkNotNull(payload.taskId)),
                checkNotNull(payload.result),
                command.correctedAnswers(payload.answers),
                payload.rejectedItems.orEmpty().filterNotNull(),
                actor.current(),
                command.occurredAt,
                expectedVersion = null,
                ChecklistPhotoPolicy.TOLERATE,
                InspectionId(command.commandId),
            )
        return OfflineCommandOutcome(offlinePhotoWarnings(completion.missingPhotos, completion.rejectedPhotos))
    }
}

/**
 * O handler de `housekeeping.inspect-room` ([InspectRoomPayload], D7-E05): a inspeção voluntária da ação online, com o
 * `commandId` como id da inspeção e o `occurredAt` corrigido como o instante; as recusas, com os códigos dela.
 */
@Component
class InspectRoomHandler(
    private val inspections: HousekeepingInspections,
    private val actor: CurrentActor,
) : OfflineCommandHandler<InspectRoomPayload> {
    override val type = HousekeepingOffline.INSPECT_ROOM
    override val payloadType = InspectRoomPayload::class.java

    @Audited(HousekeepingAudit.INSPECT_ROOM_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<InspectRoomPayload>): OfflineCommandOutcome {
        inspections.inspectRoomAs(
            InspectionId(command.commandId),
            RoomId(checkNotNull(command.payload.roomId)),
            actor.current(),
            command.occurredAt,
        )
        return OfflineCommandOutcome.APPLIED
    }
}
