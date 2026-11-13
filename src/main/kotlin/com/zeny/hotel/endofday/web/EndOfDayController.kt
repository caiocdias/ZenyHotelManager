package com.zeny.hotel.endofday.web

import com.zeny.hotel.endofday.application.EndOfDayIssueView
import com.zeny.hotel.endofday.application.EndOfDayQueries
import com.zeny.hotel.endofday.application.EndOfDayRunStatusView
import com.zeny.hotel.endofday.application.EndOfDayRunStepView
import com.zeny.hotel.endofday.application.EndOfDayRunTriggerView
import com.zeny.hotel.endofday.application.EndOfDayRunView
import com.zeny.hotel.endofday.application.EndOfDayService
import com.zeny.hotel.endofday.application.EndOfDayStepStatusView
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN = "O cliente trata um valor desconhecido sem falhar."

/**
 * O fechamento do dia (M6 E10, RF-FEC, RN-21): as consultas (`endofday.runs.read`) e o disparo manual, ação
 * sensível (RF-ACS-08) com aprovação de superior para quem não tem `endofday.runs.execute`.
 */
@ApiV1Controller
@RequestMapping("/end-of-day/runs")
@Tag(name = "end-of-day", description = "Fechamento do dia: execuções e etapas.")
class EndOfDayController(
    private val endOfDay: EndOfDayService,
    private val queries: EndOfDayQueries,
) {
    @PostMapping
    @Idempotent
    @Operation(
        operationId = "startEndOfDayRun",
        summary = "Executar o fechamento do dia manualmente",
        description =
            "Cria a execução do fechamento da data operacional corrente e a roda, esperando até " +
                "`zeny.endofday.manual-wait` (30 s por padrão). A resposta traz a execução **no estado em que " +
                "estiver**: `COMPLETED`, `FAILED` (a etapa que falhou e o motivo nas etapas; a retomada é manual) " +
                "ou `RUNNING`, que quer dizer que o fechamento continua (ou que outro disparo o executa); o cliente " +
                "acompanha pelo tópico `end-of-day` do tempo real ou por `GET /api/v1/end-of-day/runs/{id}`. Exige " +
                "`Idempotency-Key` e `endofday.runs.execute` ou a aprovação de um superior (ação " +
                "`endofday.run.start`, recurso `endofday.business-date:<data>`); a autorização vem antes de qualquer " +
                "recusa que revele o estado do fechamento. Na ordem: data já fechada (execução `COMPLETED`) 409 " +
                "`END_OF_DAY_ALREADY_CLOSED`; execução da data `RUNNING` ou `FAILED` (uma por data) 409 " +
                "`END_OF_DAY_RUN_EXISTS`, com `runId`, que **pode vir sem `runId`** quando outra criação da mesma " +
                "data passou à frente ao mesmo tempo (o cliente relê a execução da data); data diferente da " +
                "operacional corrente 409 `END_OF_DAY_DATE_MISMATCH`, com `currentBusinessDate`; nenhuma etapa " +
                "registrada 409 `END_OF_DAY_NOT_CONFIGURED`. A repetição com a mesma `Idempotency-Key` devolve a " +
                "resposta " +
                "guardada (que pode ser `RUNNING`), não o estado atual; acompanhe pelo `GET …/runs/{id}` ou pelo " +
                "tópico `end-of-day`. Publica `endofday.run` no tópico `end-of-day`.",
    )
    @ApiResponse(responseCode = "201", description = "Execução criada, no estado em que estiver.")
    fun start(
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: StartEndOfDayRunRequest,
    ): ResponseEntity<EndOfDayRunResponse> {
        val run = endOfDay.startManually(checkNotNull(request.businessDate), approvalToken)
        return ResponseEntity
            .created(URI.create("/api/v1/end-of-day/runs/${run.id}"))
            .body(EndOfDayRunResponse.of(run))
    }

    @GetMapping
    @Operation(
        operationId = "listEndOfDayRuns",
        summary = "Listar as execuções do fechamento do dia",
        description =
            "Paginação por cursor, da data operacional mais nova para a mais antiga (desempate pelo id), sem " +
                "filtros. Cada item traz as etapas em ordem. Exige `endofday.runs.read`.",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<EndOfDayRunResponse> = queries.listRuns(page).map(EndOfDayRunResponse::of)

    @GetMapping("/{id}")
    @Operation(
        operationId = "getEndOfDayRun",
        summary = "Consultar uma execução do fechamento do dia",
        description =
            "A execução com as etapas em ordem de posição; a `ETag` é a versão dela (sobe a cada mudança da " +
                "execução ou de uma etapa). Exige `endofday.runs.read`. Execução inexistente: 404 " +
                "`END_OF_DAY_RUN_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<EndOfDayRunResponse> {
        val run = queries.getRun(id)
        return ResponseEntity.ok().eTag(ETags.of(run.version)).body(EndOfDayRunResponse.of(run))
    }

    @GetMapping("/{id}/issues")
    @Operation(
        operationId = "listEndOfDayRunIssues",
        summary = "Listar as pendências de uma execução do fechamento do dia",
        description =
            "Paginação por cursor, da pendência mais antiga para a mais nova (`createdAt`, desempate pelo id). " +
                "Exige `endofday.runs.read`. Execução inexistente: 404 `END_OF_DAY_RUN_NOT_FOUND`.",
    )
    fun issues(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<EndOfDayIssueResponse> = queries.listIssues(id, page).map(EndOfDayIssueResponse::of)

    @PostMapping("/{id}/resume")
    @Idempotent
    @Operation(
        operationId = "resumeEndOfDayRun",
        summary = "Retomar o fechamento do dia manualmente",
        description =
            "Retoma a execução `FAILED` (a etapa que falhou volta a `PENDING`; as concluídas não rodam de novo) ou " +
                "aceita a `RUNNING` interrompida por uma queda, sem mudá-la, e a roda, esperando até " +
                "`zeny.endofday.manual-wait` (30 s por padrão). Sem corpo e sem `If-Match`: a trava do fechamento e " +
                "o `END_OF_DAY_RUN_NOT_RESUMABLE` já cobrem a corrida. A resposta traz a execução **no estado em " +
                "que estiver**: `COMPLETED`, `FAILED` ou `RUNNING` (o fechamento continua). A repetição com a mesma " +
                "`Idempotency-Key` devolve a resposta guardada (que pode ser `RUNNING`), não o estado atual; " +
                "acompanhe pelo `GET …/runs/{id}` ou pelo tópico `end-of-day`. Exige `Idempotency-Key` e " +
                "`endofday.runs.execute` ou a aprovação de um superior (ação `endofday.run.resume`, recurso " +
                "`endofday.run:<id>`); a autorização vem antes de saber se a execução existe. Recusas: execução " +
                "inexistente 404 `END_OF_DAY_RUN_NOT_FOUND`; `COMPLETED` 409 `END_OF_DAY_RUN_NOT_RESUMABLE`; outro " +
                "executor com a trava 409 `END_OF_DAY_RUN_IN_PROGRESS`. Publica `endofday.run` no tópico " +
                "`end-of-day` quando a execução muda.",
    )
    @ApiResponse(responseCode = "200", description = "Execução retomada, no estado em que estiver.")
    fun resume(
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @PathVariable id: UUID,
    ): EndOfDayRunResponse = EndOfDayRunResponse.of(endOfDay.resumeManually(id, approvalToken))
}

@Schema(description = "Uma pendência levantada pelo fechamento do dia.")
data class EndOfDayIssueResponse(
    val id: UUID,
    val runId: UUID,
    @field:Schema(description = "A etapa que levantou a pendência; nulo se não é de uma etapa.", nullable = true)
    val stepName: String?,
    @field:Schema(description = "O tipo da pendência, um código estável.")
    val kind: String,
    @field:Schema(description = "A referência opaca do que ficou pendente; nulo se não há.", nullable = true)
    val reference: String?,
    @field:Schema(description = "O texto da pendência, sem dado pessoal.")
    val message: String,
    @field:Schema(description = "Se a pendência já foi resolvida.")
    val resolved: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun of(issue: EndOfDayIssueView) =
            EndOfDayIssueResponse(
                issue.id,
                issue.runId,
                issue.stepName,
                issue.kind,
                issue.reference,
                issue.message,
                issue.resolved,
                issue.createdAt,
            )
    }
}

@Schema(description = "O fechamento manual de uma data.")
data class StartEndOfDayRunRequest(
    @field:Schema(description = "A data operacional a fechar: a corrente.")
    @field:NotNull
    val businessDate: LocalDate?,
)

@Schema(description = "Uma execução do fechamento do dia (RN-21: uma por data operacional), com as etapas em ordem.")
data class EndOfDayRunResponse(
    val id: UUID,
    @field:Schema(description = "A data operacional fechada pela execução.")
    val businessDate: LocalDate,
    @field:ExtensibleEnum
    @field:Schema(description = "Quem disparou: o horário (`AUTOMATIC`) ou alguém (`MANUAL`). $UNKNOWN")
    val trigger: EndOfDayRunTriggerView,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`RUNNING` (em andamento), `FAILED` (parada numa etapa, à espera da retomada) ou `COMPLETED` (data " +
                "fechada). $UNKNOWN",
    )
    val status: EndOfDayRunStatusView,
    @field:Schema(description = "Quem disparou o fechamento manual; nulo = o horário.", nullable = true)
    val triggeredBy: UUID?,
    val startedAt: Instant,
    @field:Schema(description = "Quando terminou (`COMPLETED` ou `FAILED`); nulo em andamento.", nullable = true)
    val finishedAt: Instant?,
    @field:Schema(description = "Versão da execução; sobe a cada mudança dela ou de uma etapa.")
    val version: Long,
    val steps: List<EndOfDayRunStepResponse>,
) {
    companion object {
        fun of(run: EndOfDayRunView) =
            EndOfDayRunResponse(
                run.id,
                run.businessDate,
                run.trigger,
                run.status,
                run.triggeredBy,
                run.startedAt,
                run.finishedAt,
                run.version,
                run.steps.map(EndOfDayRunStepResponse::of),
            )
    }
}

@Schema(description = "Uma etapa da execução do fechamento.")
data class EndOfDayRunStepResponse(
    @field:Schema(description = "O nome estável da etapa, como um código.")
    val name: String,
    @field:Schema(description = "A posição da etapa na ordem do fechamento.")
    val position: Int,
    @field:ExtensibleEnum
    @field:Schema(description = "A situação da etapa; `COMPLETED` e `SKIPPED` são finais. $UNKNOWN")
    val status: EndOfDayStepStatusView,
    @field:Schema(description = "O início da última tentativa; nulo se não começou.", nullable = true)
    val startedAt: Instant?,
    @field:Schema(description = "O fim da última tentativa; nulo se não terminou.", nullable = true)
    val finishedAt: Instant?,
    @field:Schema(
        description = "O resultado (um código) da etapa concluída, o motivo da falha ou da etapa pulada; nulo antes.",
        nullable = true,
    )
    val result: String?,
    @field:Schema(description = "Quantas vezes a etapa foi iniciada.")
    val attempts: Int,
) {
    companion object {
        fun of(step: EndOfDayRunStepView) =
            EndOfDayRunStepResponse(
                step.name,
                step.position,
                step.status,
                step.startedAt,
                step.finishedAt,
                step.result,
                step.attempts,
            )
    }
}
