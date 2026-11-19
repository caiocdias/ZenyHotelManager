package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.application.HousekeepingTaskExecution
import com.zeny.hotel.housekeeping.application.HousekeepingTaskView
import com.zeny.hotel.rooms.OccupancyStatus
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val ACTION_RULES =
    "Exige `housekeeping.tasks.execute`: com o escopo `OWN` (a Camareira), só na tarefa atribuída a ela; outra " +
        "tarefa, ou uma inexistente, dá o mesmo 403 `RESOURCE_OUT_OF_SCOPE`. Com `ALL`, tarefa inexistente: 404 " +
        "`HOUSEKEEPING_TASK_NOT_FOUND`. Com `If-Match` (412 com outra versão; 428 sem ele). Fora do status de " +
        "partida: 409 `HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` (com `taskStatus` e `action`). Responde a tarefa " +
        "**sem as observações** e o `ETag` novo."

/**
 * As ações da execução da tarefa de governança (RF-GOV-04, SPEC §8.4, M6 E04): iniciar, pausar, retomar, recusar,
 * tentar de novo e concluir, como subrecursos de ação com `If-Match` (ARCHITECTURE §7). O instante é o do relógio
 * do servidor. A resposta é a [HousekeepingTaskActionResponse], sem `notes` (D2-E04, RN-13).
 */
@ApiV1Controller
@RequestMapping("/housekeeping-tasks")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class HousekeepingTaskActionsController(
    private val execution: HousekeepingTaskExecution,
    private val actor: CurrentActor,
    private val clock: Clock,
) {
    @PostMapping("/{id}/start")
    @Operation(
        operationId = "startHousekeepingTask",
        summary = "Iniciar uma tarefa de governança",
        description =
            "Leva a tarefa `ASSIGNED` (ou a `REJECTED`, para refazer) a `IN_PROGRESS`; o primeiro início fica " +
                "gravado. Só até a data operacional: tarefa de data posterior, 422 `HOUSEKEEPING_TASK_NOT_DUE` " +
                "(as de datas anteriores seguem válidas). $ACTION_RULES",
    )
    fun start(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(execution.start(HousekeepingTaskId(id), actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/pause")
    @Operation(
        operationId = "pauseHousekeepingTask",
        summary = "Pausar uma tarefa de governança",
        description =
            "Leva a tarefa `IN_PROGRESS` a `PAUSED`; o trecho em andamento entra no tempo trabalhado. $ACTION_RULES",
    )
    fun pause(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(execution.pause(HousekeepingTaskId(id), actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/resume")
    @Operation(
        operationId = "resumeHousekeepingTask",
        summary = "Retomar uma tarefa de governança",
        description = "Leva a tarefa `PAUSED` de volta a `IN_PROGRESS`. $ACTION_RULES",
    )
    fun resume(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(execution.resume(HousekeepingTaskId(id), actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/refuse")
    @Operation(
        operationId = "refuseHousekeepingTask",
        summary = "Recusar uma tarefa de governança",
        description =
            "Leva a tarefa `ASSIGNED` a `REFUSED`, com o motivo: `DO_NOT_DISTURB` (a UH com não perturbe) ou " +
                "`GUEST_REFUSED` (o hóspede recusou o serviço). Com `DO_NOT_DISTURB` numa UH ocupada, liga o " +
                "sinal de não perturbe na UH; numa UH vaga e numa tarefa de área, a UH não muda. A camareira " +
                "continua a mesma; a nova tentativa é o `retry`. Responde 200 com a tarefa `REFUSED` em todos os " +
                "casos. $ACTION_RULES",
    )
    fun refuse(
        @PathVariable id: UUID,
        @Valid @RequestBody request: HousekeepingTaskRefuseRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val reason = checkNotNull(request.reason)
        return respond(execution.refuse(HousekeepingTaskId(id), reason, actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/retry")
    @Operation(
        operationId = "retryHousekeepingTask",
        summary = "Tentar de novo uma tarefa de governança recusada",
        description =
            "Leva a tarefa `REFUSED` de volta a `ASSIGNED`, com a mesma camareira e sem o motivo da recusa. " +
                ACTION_RULES,
    )
    fun retry(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(execution.retry(HousekeepingTaskId(id), actor.current(), clock.instant(), expected))
    }

    @PostMapping("/{id}/complete")
    @Operation(
        operationId = "completeHousekeepingTask",
        summary = "Concluir uma tarefa de governança",
        description =
            "Conclui a tarefa `IN_PROGRESS`, numa transação só: conclui o checklist aberto no início com as " +
                "`answers` (todas; item obrigatório faltando: 422 `CHECKLIST_REQUIRED_ITEMS_MISSING`, e nada muda; " +
                "foto que não existe ou não serve, porque só vale um anexo da finalidade `checklists.photo` enviado " +
                "por quem iniciou o checklist: 422 `CHECKLIST_PHOTO_NOT_FOUND` ou `CHECKLIST_PHOTO_INVALID`, e " +
                "nada muda; checklist **já concluído por fora**: a tarefa conclui e as `answers` são ignoradas; " +
                "`answers` numa " +
                "tarefa sem checklist: 400 `VALIDATION_FAILED` no campo `answers`), leva a UH Suja a Limpa (com " +
                "inspeção pendente se o tipo de limpeza a exige), grava a ocupação encontrada (`foundOccupancy`, " +
                "`foundGuestCount`; só numa tarefa de UH; o número sem a ocupação: 400 `VALIDATION_FAILED` com " +
                "`REQUIRED` em `foundOccupancy`), registra a faxina programada feita e grava quem concluiu " +
                "e os tempos. A tarefa vai a `AWAITING_INSPECTION` só quando a UH ficou com inspeção pendente agora; " +
                "numa UH já Limpa ou Inspecionada e numa área, vai a `DONE`. O corpo é opcional. $ACTION_RULES",
    )
    fun complete(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) request: HousekeepingTaskCompleteRequest?,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<HousekeepingTaskActionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(
            execution.complete(
                HousekeepingTaskId(id),
                request?.answers?.map { it.toInput() },
                request?.foundOccupancy,
                request?.foundGuestCount,
                actor.current(),
                clock.instant(),
                expected,
                // A ação online recusa a foto que não serve, como o endpoint do `checklists` (I1 da revisão).
                ChecklistPhotoPolicy.REFUSE,
            ),
        )
    }

    private fun respond(view: HousekeepingTaskView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(HousekeepingTaskActionResponse.of(view))
}

@Schema(description = "A recusa de uma tarefa de governança.")
data class HousekeepingTaskRefuseRequest(
    @field:NotNull
    @field:Schema(description = "O motivo: `DO_NOT_DISTURB` (não perturbe) ou `GUEST_REFUSED` (o hóspede recusou).")
    val reason: RefusalReason?,
)

@Schema(description = "A conclusão de uma tarefa de governança; todos os campos são opcionais.")
data class HousekeepingTaskCompleteRequest(
    @field:Size(max = MAX_ANSWERS)
    @field:Schema(
        description =
            "Todas as respostas do checklist aberto no início (as que valem na conclusão). Só numa tarefa com " +
                "checklist; ignoradas se ele já foi concluído por fora.",
        nullable = true,
    )
    val answers: List<@Valid HousekeepingChecklistAnswerRequest>?,
    @field:Schema(
        description = "A ocupação que a camareira encontrou na UH (só numa tarefa de UH).",
        nullable = true,
    )
    val foundOccupancy: OccupancyStatus?,
    @field:Schema(
        description = "O número de pessoas encontradas, de 1 a 40, só com `foundOccupancy` = `OCCUPIED`.",
        nullable = true,
    )
    val foundGuestCount: Int?,
)

@Schema(
    description =
        "Uma resposta do checklist: a chave do item e só o campo do tipo dele — `check` (CHECK), `quantity` " +
            "(QUANTITY), `number` (NUMBER), `text` (TEXT), `choice` (SINGLE_CHOICE), `choices` (MULTI_CHOICE) ou " +
            "`photoFileId` (PHOTO: um anexo da finalidade `checklists.photo` enviado por quem iniciou o checklist) — " +
            "e a observação opcional.",
)
data class HousekeepingChecklistAnswerRequest(
    @field:Size(max = 40)
    @field:Schema(example = "towels")
    val itemKey: String,
    @field:Schema(nullable = true)
    val check: CheckAnswer?,
    @field:Schema(nullable = true, example = "2")
    val quantity: BigDecimal?,
    @field:Schema(nullable = true, example = "4.5")
    val number: BigDecimal?,
    @field:Size(max = 2000)
    @field:Schema(nullable = true)
    val text: String?,
    @field:Size(max = 40)
    @field:Schema(nullable = true)
    val choice: String?,
    @field:Size(max = 50)
    @field:Schema(nullable = true)
    val choices: List<String>?,
    @field:Schema(nullable = true)
    val photoFileId: UUID?,
    @field:Size(max = 500)
    @field:Schema(nullable = true)
    val note: String?,
) {
    fun toInput() = ChecklistAnswerInput.of(itemKey, check, quantity, number, text, choice, choices, photoFileId, note)
}

private const val MAX_ANSWERS = 300

@Schema(
    description =
        "Uma tarefa de governança na resposta das ações da execução: **sem as observações** da recepção, que só a " +
            "consulta com `housekeeping.tasks.read` mostra.",
)
data class HousekeepingTaskActionResponse(
    val id: UUID,
    @field:Schema(description = "A UH; nula numa tarefa de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum; nula numa tarefa de UH.", nullable = true)
    val areaId: UUID?,
    @field:Schema(description = "A data operacional da tarefa.")
    val date: LocalDate,
    val cleaningTypeId: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "A origem da tarefa (trate um valor desconhecido sem falhar).")
    val source: TaskSource,
    @field:ExtensibleEnum
    @field:Schema(description = "O status da tarefa (trate um valor desconhecido sem falhar).")
    val status: TaskStatus,
    @field:Schema(description = "A prioridade, de 1 (mais baixa) a 5 (mais alta).")
    val priority: Int,
    @field:Schema(description = "Os créditos do tipo de limpeza para o tipo da UH; 0 numa área ou sem crédito.")
    val credits: Int,
    @field:Schema(description = "Se a UH tinha chegada prevista na data (retrato do momento da geração).")
    val arrivalExpected: Boolean,
    @field:Schema(description = "A faxina programada que gerou a tarefa; nula nas outras origens.", nullable = true)
    val scheduledCleaningId: UUID?,
    @field:Schema(description = "A camareira publicada pelo plano do dia; nula sem camareira.", nullable = true)
    val assignedEmployeeId: UUID?,
    @field:Schema(
        description = "A posição publicada na ordem de execução da camareira, de 1 em diante; nula sem camareira.",
        nullable = true,
    )
    val assignmentPosition: Int?,
    @field:ExtensibleEnum
    @field:Schema(
        description = "O motivo da recusa, só em `REFUSED` (trate um valor desconhecido sem falhar).",
        nullable = true,
    )
    val refusalReason: RefusalReason?,
    @field:Schema(description = "O primeiro início; um refazer não o muda. Nulo antes do início.", nullable = true)
    val startedAt: Instant?,
    @field:Schema(description = "A conclusão; nula antes dela e depois de um refazer.", nullable = true)
    val finishedAt: Instant?,
    @field:Schema(description = "O tempo trabalhado, em segundos: só os trechos em andamento, sem as pausas.")
    val workedSeconds: Long,
    @field:Schema(description = "O usuário que concluiu a tarefa; nulo antes da conclusão.", nullable = true)
    val cleanedBy: UUID?,
    @field:Schema(
        description =
            "A execução do checklist aberta no último início (id opaco do `checklists`); nula numa tarefa sem " +
                "checklist, que não aceita `answers` na conclusão.",
        nullable = true,
    )
    val checklistExecutionId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: HousekeepingTaskView) =
            HousekeepingTaskActionResponse(
                view.id.value,
                view.roomId?.value,
                view.areaId?.value,
                view.date,
                view.cleaningTypeId.value,
                view.source,
                view.status,
                view.priority,
                view.credits,
                view.arrivalExpected,
                view.scheduledCleaningId?.value,
                view.assignedEmployeeId?.value,
                view.assignmentPosition,
                view.refusalReason,
                view.startedAt,
                view.finishedAt,
                view.workedSeconds,
                view.cleanedBy?.value,
                view.checklistExecutionId?.value,
                view.createdAt,
                view.updatedAt,
            )
    }
}
