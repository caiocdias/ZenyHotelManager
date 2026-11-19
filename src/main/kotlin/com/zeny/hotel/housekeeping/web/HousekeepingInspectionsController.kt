package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionResult
import com.zeny.hotel.housekeeping.InspectionStatus
import com.zeny.hotel.housekeeping.application.HousekeepingInspections
import com.zeny.hotel.housekeeping.application.InspectionOutcome
import com.zeny.hotel.housekeeping.application.InspectionView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val INSPECTION_RULES =
    "Exige `housekeeping.inspections.perform` (só `ALL`; 403 `PERMISSION_DENIED` antes de qualquer outra recusa, " +
        "inclusive para quem limpou a tarefa). Tarefa inexistente: 404 `HOUSEKEEPING_TASK_NOT_FOUND`. Com o " +
        "`If-Match` da tarefa (412 com outra versão; 428 sem ele). Tarefa fora de `AWAITING_INSPECTION`: 409 " +
        "`HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` (com `taskStatus` e `action`). Responde a inspeção e a tarefa " +
        "**sem as observações**, com o `ETag` novo da tarefa."

/**
 * A inspeção da tarefa de governança (RF-GOV-06, SPEC §8.4, M6 E05): iniciar e concluir como subrecursos de ação da
 * tarefa, com o `If-Match` dela (ARCHITECTURE §7). O instante é o do relógio do servidor. A resposta é a
 * [HousekeepingInspectionResponse], sem `notes` (RN-13).
 */
@ApiV1Controller
@RequestMapping("/housekeeping-tasks")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class HousekeepingInspectionsController(
    private val inspections: HousekeepingInspections,
    private val actor: CurrentActor,
    private val clock: Clock,
) {
    @PostMapping("/{id}/inspection/start")
    @Operation(
        operationId = "startHousekeepingInspection",
        summary = "Iniciar a inspeção de uma tarefa de governança",
        description =
            "Abre a inspeção da tarefa `AWAITING_INSPECTION` e, se houver modelo de checklist de contexto " +
                "`INSPECTION` que valha para ela, a execução da versão vigente (`checklistExecutionId`), com quem " +
                "inicia como executor. Já há inspeção aberta: 409 `HOUSEKEEPING_INSPECTION_ALREADY_OPEN`. A tarefa " +
                "continua `AWAITING_INSPECTION`, mas a versão dela sobe. $INSPECTION_RULES",
    )
    fun start(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingInspectionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(inspections.start(HousekeepingTaskId(id), actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/inspection/complete")
    @Operation(
        operationId = "completeHousekeepingInspection",
        summary = "Concluir a inspeção de uma tarefa de governança",
        description =
            "Conclui a inspeção aberta da tarefa com o `result`. Sem inspeção aberta, só numa tarefa sem modelo de " +
                "checklist de inspeção (abre e conclui numa chamada); com modelo: 409 " +
                "`HOUSEKEEPING_INSPECTION_NOT_STARTED`. Conclui o checklist com as `answers` (todas; item " +
                "obrigatório faltando: 422 `CHECKLIST_REQUIRED_ITEMS_MISSING`, e nada muda; `answers` numa inspeção " +
                "sem checklist: 400 `VALIDATION_FAILED` no campo `answers`). `APPROVED`: a tarefa vai a `DONE` e a " +
                "UH Limpa com inspeção pendente fica Inspecionada; a UH que já perdeu a pendência (Suja pelo " +
                "check-out ou pelo fechamento, já Inspecionada) não muda, e `roomChanged` vem falso. A aprovação " +
                "não leva `rejectedItems`. $INSPECTION_RULES",
    )
    fun complete(
        @PathVariable id: UUID,
        @Valid @RequestBody request: HousekeepingInspectionCompleteRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingInspectionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(
            inspections.complete(
                HousekeepingTaskId(id),
                checkNotNull(request.result),
                request.answers?.map { it.toInput() },
                request.rejectedItems.orEmpty(),
                actor.current(),
                clock.instant(),
                expected,
            ),
        )
    }

    private fun respond(outcome: InspectionOutcome) =
        ResponseEntity
            .ok()
            .eTag(ETags.of(outcome.task.version))
            .body(HousekeepingInspectionResponse.of(outcome))
}

@Schema(description = "A conclusão da inspeção de uma tarefa de governança.")
data class HousekeepingInspectionCompleteRequest(
    @field:NotNull
    @field:Schema(description = "O resultado: `APPROVED` (aprovada) ou `REJECTED` (reprovada).")
    val result: InspectionResult?,
    @field:Size(max = MAX_ANSWERS)
    @field:Schema(
        description = "Todas as respostas do checklist de inspeção aberto no início. Só numa inspeção com checklist.",
        nullable = true,
    )
    val answers: List<@Valid HousekeepingChecklistAnswerRequest>?,
    @field:Size(max = MAX_ANSWERS)
    @field:Schema(
        description = "As chaves dos itens reprovados do checklist de inspeção; vazio ou ausente na aprovação.",
        nullable = true,
    )
    val rejectedItems: List<
        @Size(max = 40)
        String,
    >?,
)

@Schema(
    description =
        "Uma inspeção de UH: ids, estado e as chaves dos itens reprovados, sem texto livre nem dado de hóspede.",
)
data class HousekeepingInspectionDto(
    val id: UUID,
    @field:Schema(description = "A tarefa inspecionada; nula na inspeção voluntária da UH.", nullable = true)
    val taskId: UUID?,
    val roomId: UUID,
    @field:Schema(
        description = "A execução do checklist de inspeção (id opaco do `checklists`); nula sem modelo.",
        nullable = true,
    )
    val checklistExecutionId: UUID?,
    @field:ExtensibleEnum
    @field:Schema(description = "O status da inspeção (trate um valor desconhecido sem falhar).")
    val status: InspectionStatus,
    @field:ExtensibleEnum
    @field:Schema(
        description = "O resultado; nulo enquanto aberta (trate um valor desconhecido sem falhar).",
        nullable = true,
    )
    val result: InspectionResult?,
    @field:Schema(description = "As chaves dos itens reprovados do checklist; vazio na aprovação.")
    val rejectedItems: List<String>,
    @field:Schema(description = "O usuário que inspecionou: quem iniciou, trocado por quem concluiu.")
    val inspectorId: UUID,
    val startedAt: Instant,
    @field:Schema(description = "A conclusão; nula enquanto aberta.", nullable = true)
    val completedAt: Instant?,
) {
    companion object {
        fun of(view: InspectionView) =
            HousekeepingInspectionDto(
                view.id.value,
                view.taskId?.value,
                view.roomId.value,
                view.checklistExecutionId?.value,
                view.status,
                view.result,
                view.rejectedItems,
                view.inspectorId.value,
                view.startedAt,
                view.completedAt,
            )
    }
}

@Schema(description = "O resultado de uma ação da inspeção: a inspeção e a tarefa, sem as observações da recepção.")
data class HousekeepingInspectionResponse(
    val inspection: HousekeepingInspectionDto,
    val task: HousekeepingTaskActionResponse,
    @field:Schema(
        description =
            "Na conclusão, se a UH mudou: falso quando ela já tinha perdido a inspeção pendente (Suja ou já " +
                "Inspecionada) e ficou como estava. Nulo no início.",
        nullable = true,
    )
    val roomChanged: Boolean?,
) {
    companion object {
        fun of(outcome: InspectionOutcome) =
            HousekeepingInspectionResponse(
                HousekeepingInspectionDto.of(outcome.inspection),
                HousekeepingTaskActionResponse.of(outcome.task),
                outcome.roomChanged,
            )
    }
}

private const val MAX_ANSWERS = 300
