package com.zeny.hotel.checklists.web

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.application.ChecklistExecutionDetail
import com.zeny.hotel.checklists.application.ChecklistExecutionsService
import com.zeny.hotel.checklists.application.ExecutionFilter
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.LocalDate
import java.util.UUID

private const val SCOPE_RULE =
    "Com o escopo `OWN`, só as execuções de que o usuário é executor ou candidato (rotina, H14)."
private const val ANSWER_RULES =
    "Recusas das respostas, `400` com o campo em `errors[]`: `CHECKLIST_ANSWER_UNKNOWN_ITEM`, " +
        "`CHECKLIST_ANSWER_DUPLICATED` e `CHECKLIST_ANSWER_INVALID`. Foto inexistente: 422 " +
        "`CHECKLIST_PHOTO_NOT_FOUND`; " +
        "de outra finalidade, de outro usuário ou de outro registro: 422 `CHECKLIST_PHOTO_INVALID`."
private const val EXECUTOR_RULE =
    "Só o executor responde e conclui (403 `CHECKLIST_EXECUTOR_REQUIRED`); pendente: 409 " +
        "`CHECKLIST_EXECUTION_NOT_STARTED`; concluída: 409 `CHECKLIST_EXECUTION_ALREADY_COMPLETED`."

/**
 * Execuções de checklist online (RF-CHK-05 a 08): iniciar (avulsa ou a pendente de uma rotina), responder
 * (`If-Match`) e concluir, publicando `ChecklistCompleted`. Executar exige `checklists.executions.execute`;
 * consultar, `checklists.executions.read`. Offline, os mesmos passos vão pelos comandos
 * `checklists.start-execution` e `checklists.complete-execution` de `POST /api/v1/sync/commands`. Nada é apagado.
 */
@ApiV1Controller
@RequestMapping("/checklist-executions")
@Tag(name = "checklists", description = "Modelos de checklist, rascunhos e versões publicadas.")
class ChecklistExecutionsController(
    private val executions: ChecklistExecutionsService,
) {
    @GetMapping
    @Operation(
        operationId = "listChecklistExecutions",
        summary = "Listar execuções de checklist",
        description =
            "Da mais nova para a mais antiga, sem as respostas. Exige `checklists.executions.read`. $SCOPE_RULE",
    )
    @Suppress("LongParameterList") // os filtros da listagem
    fun list(
        @RequestParam(required = false) status: ChecklistExecutionStatus?,
        @RequestParam(required = false) context: ChecklistContext?,
        @Parameter(description = "Só as geradas por esta rotina.")
        @RequestParam(required = false) routineId: UUID?,
        @Parameter(description = "Só as de rotina desta data operacional.")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) businessDate: LocalDate?,
        @Parameter(description = "Só as deste tipo de sujeito (com `subjectId`, deste sujeito).")
        @RequestParam(required = false) subjectType: String?,
        @RequestParam(required = false) subjectId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<ChecklistExecutionSummaryResponse> {
        val filter =
            ExecutionFilter(
                status,
                context,
                routineId?.let(::ChecklistRoutineId),
                businessDate,
                subjectType,
                subjectId,
            )
        return executions.executions(filter, page).map(ChecklistExecutionSummaryResponse::of)
    }

    @PostMapping
    @Operation(
        operationId = "startChecklistExecution",
        summary = "Iniciar uma execução avulsa",
        description =
            "Com a última versão publicada do modelo (`templateId`, ativo) ou do modelo que vale para o sujeito no " +
                "contexto (`context` e `attributes`, RF-CHK-04); o executor é o usuário e o início, agora. Exige " +
                "`checklists.executions.execute`. Nem um nem outro, ou os dois: 400 `CHECKLIST_EXECUTION_INVALID`; " +
                "nenhum modelo que valha: 422 `CHECKLIST_NO_APPLICABLE_TEMPLATE`; modelo desativado ou nunca " +
                "publicado: 422 `CHECKLIST_TEMPLATE_INACTIVE` ou `CHECKLIST_TEMPLATE_NOT_PUBLISHED`.",
    )
    @ApiResponse(responseCode = "201", description = "Execução iniciada.")
    fun start(
        @Valid @RequestBody request: StartChecklistExecutionRequest,
    ): ResponseEntity<ChecklistExecutionResponse> {
        val detail = executions.start(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/checklist-executions/${detail.execution.id}"))
            .eTag(ETags.of(detail.execution.version))
            .body(ChecklistExecutionResponse.of(detail))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getChecklistExecution",
        summary = "Consultar uma execução",
        description =
            "Com as respostas e o conteúdo da versão usada. Exige `checklists.executions.read`. $SCOPE_RULE " +
                "Inexistente ou fora do escopo: 404 `CHECKLIST_EXECUTION_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistExecutionResponse> = ok(executions.execution(ChecklistExecutionId(id)))

    @PostMapping("/{id}/start")
    @Operation(
        operationId = "startScheduledChecklistExecution",
        summary = "Assumir a execução pendente de uma rotina",
        description =
            "O primeiro candidato que inicia assume a execução (H14), com a última versão publicada do modelo. Exige " +
                "`checklists.executions.execute` (com `OWN`, ser candidato: 403 `RESOURCE_OUT_OF_SCOPE`). Já " +
                "iniciada: 409 `CHECKLIST_EXECUTION_ALREADY_STARTED`.",
    )
    fun startScheduled(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistExecutionResponse> = ok(executions.startScheduled(ChecklistExecutionId(id)))

    @PutMapping("/{id}/answers")
    @Operation(
        operationId = "replaceChecklistAnswers",
        summary = "Responder (trocar todas as respostas)",
        description =
            "A lista substitui as respostas gravadas; uma resposta que não mudou mantém o horário. As fotos novas " +
                "são " +
                "conferidas e vinculadas à execução; as que saem, desvinculadas. Exige " +
                "`checklists.executions.execute` e o `If-Match` (ausente: 428; versão diferente: 412). " +
                "$EXECUTOR_RULE $ANSWER_RULES",
    )
    fun replaceAnswers(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ReplaceChecklistAnswersRequest,
    ): ResponseEntity<ChecklistExecutionResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val inputs = request.answers.map(ChecklistAnswerDto::toInput)
        return ok(executions.replaceAnswers(ChecklistExecutionId(id), expected, inputs))
    }

    @PostMapping("/{id}/complete")
    @Operation(
        operationId = "completeChecklistExecution",
        summary = "Concluir uma execução",
        description =
            "Confere os obrigatórios visíveis (o item oculto pela condição não é exigido; a resposta dele sai), " +
                "conclui e publica `ChecklistCompleted` com as respostas que disparam ações (RF-CHK-05), uma vez. " +
                "Exige `checklists.executions.execute`. Obrigatório visível sem resposta: 422 " +
                "`CHECKLIST_REQUIRED_ITEMS_MISSING`, com as chaves em `missingItems`. $EXECUTOR_RULE",
    )
    fun complete(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistExecutionResponse> = ok(executions.complete(ChecklistExecutionId(id)))

    private fun ok(detail: ChecklistExecutionDetail) =
        ResponseEntity
            .ok()
            .eTag(ETags.of(detail.execution.version))
            .body(ChecklistExecutionResponse.of(detail))
}
