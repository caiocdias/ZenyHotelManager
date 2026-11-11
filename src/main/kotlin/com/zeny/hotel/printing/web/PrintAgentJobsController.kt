package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.application.DeliveredJob
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ServicePrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.context.request.async.DeferredResult
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val JOB_ID = "/{id:[0-9a-fA-F-]+}"
private val TIMEOUT_MARGIN: Duration = Duration.ofSeconds(5)

/**
 * A entrega dos jobs ao agente (D-E10-5 e D-E10-6), autenticada pelo token do agente (`printAgentToken`). O long-poll é
 * assíncrono: este controller só liga o `DeferredResult` à espera do [PrintAgentJobsService.awaitJobs], que roda numa
 * *virtual thread* e não segura conexão de banco enquanto espera.
 */
@ApiV1Controller
@RequestMapping("/print-agents/me/jobs")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintAgentJobsController(
    private val service: PrintAgentJobsService,
) {
    @GetMapping
    @Operation(
        operationId = "pollPrintAgentJobs",
        summary = "Receber os jobs de impressão por long-poll",
        description =
            "Autenticado pelo token do agente. Reivindica os jobs `PENDING` das impressoras ativas do agente (eles " +
                "passam a `SENT`) e, sem nenhum, espera até `wait` segundos (0 a 30; padrão 25) consultando a fila. " +
                "Sem job até o fim da espera: `200` com `[]`. O agente também recebe `200` com `[]` na hora quando " +
                "abre mais conexões que o limite (o long-poll mais antigo é despejado) e quando o servidor para: " +
                "volte a pedir. Na reentrega de um job já impresso, confirme de novo sem imprimir. `wait` fora de " +
                "0 a 30: 400 `VALIDATION_FAILED`.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Os jobs reivindicados; `[]` sem job até o fim da espera ou quando a conexão é despejada.",
    )
    fun poll(
        @AuthenticationPrincipal principal: ServicePrincipal,
        @Parameter(description = "Segundos de espera, de 0 a 30 (padrão 25).")
        @RequestParam(required = false) wait: Int?,
    ): DeferredResult<List<DeliveredJobResponse>> {
        val duration = service.resolveWait(wait)
        val result = DeferredResult<List<DeliveredJobResponse>>((duration + TIMEOUT_MARGIN).toMillis())
        val connection = service.open(principal)
        result.onTimeout { result.setResult(emptyList()) }
        result.onCompletion { connection.close() }
        Thread.ofVirtual().name("print-long-poll").start {
            try {
                result.setResult(service.awaitJobs(principal, duration, connection).map(DeliveredJobResponse::of))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // qualquer falha vira a resposta de erro
            ) {
                result.setErrorResult(e)
            } finally {
                connection.close()
            }
        }
        return result
    }

    @GetMapping("$JOB_ID/content")
    @Operation(
        operationId = "getPrintJobContent",
        summary = "Ler o conteúdo de um job entregue ao agente",
        description =
            "Autenticado pelo token do agente. Só o job `SENT` entregue a este agente, de uma impressora que ainda é " +
                "dele; qualquer outro caso (de outro agente, inexistente, `PENDING`, `PRINTED`, `FAILED`): 404 " +
                "`PRINT_JOB_NOT_FOUND`. O tipo de mídia é `application/pdf` ou `application/octet-stream`.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "O conteúdo do documento.",
        content =
            [
                io.swagger.v3.oas.annotations.media.Content(
                    mediaType = "application/pdf",
                    schema = Schema(type = "string", format = "binary"),
                ),
                io.swagger.v3.oas.annotations.media.Content(
                    mediaType = "application/octet-stream",
                    schema = Schema(type = "string", format = "binary"),
                ),
            ],
    )
    fun content(
        @AuthenticationPrincipal principal: ServicePrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        val content = service.content(principal, id)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(content.mediaType))
            .cacheControl(CacheControl.noStore())
            .body(content.bytes)
    }

    @PostMapping("$JOB_ID/ack")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "acknowledgePrintJob",
        summary = "Confirmar um job de impressão",
        description =
            "Autenticado pelo token do agente. `PRINTED` ou `FAILED` (com `failureCode` opcional, " +
                "`[A-Z][A-Z0-9_]{0,49}`, nunca texto livre: 400 `INVALID_FAILURE_CODE`). Job de outro agente ou " +
                "inexistente: 404 `PRINT_JOB_NOT_FOUND`; job deste agente que já não está `SENT` (já confirmado, " +
                "reenviado ou expirado): 409 `PRINT_JOB_NOT_ACKNOWLEDGEABLE`.",
    )
    @ApiResponse(responseCode = "204", description = "Job confirmado.")
    fun acknowledge(
        @AuthenticationPrincipal principal: ServicePrincipal,
        @PathVariable id: UUID,
        @RequestBody request: PrintJobAckRequest,
    ) = service.acknowledge(principal, id, request.result == PrintJobAckResult.PRINTED, request.failureCode)
}

@Schema(description = "Um job entregue ao agente, sem o conteúdo (busque-o em `/content`).")
data class DeliveredJobResponse(
    val id: UUID,
    val printerId: UUID,
    val documentType: String,
    @field:Schema(description = "O tipo de mídia do conteúdo.", example = "application/pdf")
    val mediaType: String,
    @field:Schema(description = "Quantas vezes o job foi entregue, esta inclusive.")
    val attempts: Int,
    val createdAt: Instant,
) {
    companion object {
        fun of(job: DeliveredJob) =
            DeliveredJobResponse(job.id, job.printerId, job.documentType, job.mediaType, job.attempts, job.createdAt)
    }
}

enum class PrintJobAckResult { PRINTED, FAILED }

@Schema(description = "A confirmação do agente: o job foi impresso ou falhou.")
data class PrintJobAckRequest(
    val result: PrintJobAckResult,
    @field:Schema(
        description = "Código da falha (só com `FAILED`), `[A-Z][A-Z0-9_]{0,49}`. Nunca texto livre.",
        nullable = true,
        example = "PAPER_JAM",
    )
    val failureCode: String? = null,
)
