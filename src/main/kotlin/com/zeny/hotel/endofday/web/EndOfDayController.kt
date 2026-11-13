package com.zeny.hotel.endofday.web

import com.zeny.hotel.endofday.application.EndOfDayRunStatusView
import com.zeny.hotel.endofday.application.EndOfDayRunStepView
import com.zeny.hotel.endofday.application.EndOfDayRunTriggerView
import com.zeny.hotel.endofday.application.EndOfDayRunView
import com.zeny.hotel.endofday.application.EndOfDayService
import com.zeny.hotel.endofday.application.EndOfDayStepStatusView
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
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
 * O fechamento do dia (M6 E10, RF-FEC, RN-21): o disparo manual, ação sensível (RF-ACS-08) com aprovação de superior
 * para quem não tem `endofday.runs.execute`.
 */
@ApiV1Controller
@RequestMapping("/end-of-day/runs")
@Tag(name = "end-of-day", description = "Fechamento do dia: execuções e etapas.")
class EndOfDayController(
    private val endOfDay: EndOfDayService,
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
                "registrada 409 `END_OF_DAY_NOT_CONFIGURED`. Publica `endofday.run` no tópico `end-of-day`.",
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
