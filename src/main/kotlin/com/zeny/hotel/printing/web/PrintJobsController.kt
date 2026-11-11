package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.NewPrintJob
import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.application.PrintJobView
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val BASE64_GROUP = 4
private const val UNKNOWN = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

/**
 * A fila de impressão (D-E10-5, D-E10-7 e D-E10-8). Criar exige `printing.jobs.manage`; listar, `printing.jobs.read`.
 * O conteúdo entra em base64 e nunca volta em resposta, log nem problema.
 */
@ApiV1Controller
@RequestMapping("/print-jobs")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintJobsController(
    private val service: PrintJobsService,
) {
    @PostMapping
    @Idempotent
    @ApiResponse(responseCode = "201", description = "Job enfileirado.")
    @Operation(
        operationId = "createPrintJob",
        summary = "Enfileirar um job de impressão manual",
        description =
            "Exige `printing.jobs.manage` e `Idempotency-Key`. O conteúdo vai em base64 e tem no máximo 700 KiB " +
                "(716800 bytes) decodificado; acima disso, 422 `PRINT_CONTENT_TOO_LARGE`. Corpo acima de 1 MB: 413 " +
                "`PAYLOAD_TOO_LARGE`, recusado pela idempotência antes de chegar aqui. Base64 inválido: 400 " +
                "`VALIDATION_FAILED` no campo `content`. Conteúdo vazio: 422 `PRINT_CONTENT_EMPTY`; impressora " +
                "A4_PDF " +
                "com conteúdo que não começa com `%PDF-`: 422 `PRINT_CONTENT_NOT_PDF`. Impressora inexistente: 400 " +
                "`UNKNOWN_PRINTER`; desativada: 422 `PRINTER_INACTIVE`. O conteúdo nunca volta na resposta.",
    )
    fun create(
        @Valid @RequestBody request: PrintJobRequest,
    ): ResponseEntity<PrintJobResponse> =
        service
            .create(NewPrintJob(request.printerId, request.documentType, decode(request.content)))
            .let { ResponseEntity.status(HttpStatus.CREATED).body(PrintJobResponse.of(it)) }

    @GetMapping
    @Operation(
        operationId = "listPrintJobs",
        summary = "Listar os jobs de impressão",
        description =
            "Exige `printing.jobs.read`. Paginação por cursor, dos mais novos aos mais antigos; `status` e " +
                "`printerId` filtram. Sem o conteúdo.",
    )
    fun list(
        @Parameter(description = "Só os jobs nesta situação.")
        @RequestParam(required = false) status: PrintJobStatus?,
        @Parameter(description = "Só os jobs desta impressora.")
        @RequestParam(required = false) printerId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<PrintJobResponse> = service.jobs(status, printerId, page).map(PrintJobResponse::of)

    /** Base64 padrão com preenchimento (RFC 4648 §4); inválido vira `400` no campo `content`, sem eco do valor. */
    private fun decode(content: String): ByteArray =
        try {
            require(content.length % BASE64_GROUP == 0)
            Base64.getDecoder().decode(content)
        } catch (_: IllegalArgumentException) {
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                "O conteúdo não é base64 válido.",
                listOf(FieldViolation("content", "INVALID_FORMAT", "O conteúdo deve estar em base64.")),
            )
        }
}

@Schema(description = "O job de impressão a enfileirar.")
data class PrintJobRequest(
    @field:Schema(description = "A impressora de destino (ativa).")
    val printerId: UUID,
    @field:Schema(
        description = "O tipo do documento, `[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+` (até 100).",
        example = "frontdesk.registration-card",
    )
    val documentType: String,
    @field:Schema(
        description = "O conteúdo em base64, até 716800 bytes decodificado. PDF para A4_PDF.",
        format = "byte",
    )
    val content: String,
) {
    /** Sem o conteúdo: o pedido nunca o põe em log. */
    override fun toString(): String = "PrintJobRequest(printerId=$printerId, documentType=$documentType)"
}

@Schema(description = "Um job de impressão, sem o conteúdo.")
data class PrintJobResponse(
    val id: UUID,
    val printerId: UUID,
    val documentType: String,
    @field:ExtensibleEnum
    @field:Schema(description = "A situação do job. $UNKNOWN")
    val status: PrintJobStatus,
    val attempts: Int,
    val createdAt: Instant,
    val sentAt: Instant?,
    val finishedAt: Instant?,
    @field:Schema(description = "O código de falha, quando `FAILED`.", nullable = true)
    val failureCode: String?,
) {
    companion object {
        fun of(view: PrintJobView) =
            PrintJobResponse(
                view.id,
                view.printerId,
                view.documentType,
                view.status,
                view.attempts,
                view.createdAt,
                view.sentAt,
                view.finishedAt,
                view.failureCode,
            )
    }
}
