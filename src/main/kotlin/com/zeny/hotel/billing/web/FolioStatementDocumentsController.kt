package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.application.FolioService
import com.zeny.hotel.billing.application.FolioStatementDocuments
import com.zeny.hotel.billing.application.StatementAudience
import com.zeny.hotel.printing.PrintDocumentRequest
import com.zeny.hotel.printing.PrintJobAccepted
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

private const val AUDIENCE_DESCRIPTION =
    "`GUEST` (padrão, o documento vai ao hóspede) ou `INTERNAL`, com usuários, motivos e o bloco interno."

/**
 * O extrato da conta como documento (RF-CTA-12, M5 E11; D-E11-9 e D-E11-10): o PDF e a impressão. As duas rotas
 * exigem `billing.folios.read`; o `printing` não confere permissão (D2).
 */
@ApiV1Controller
@RequestMapping("/folios/{id}/statement")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class FolioStatementDocumentsController(
    private val documents: FolioStatementDocuments,
    private val folios: FolioService,
) {
    @GetMapping("/pdf")
    @Operation(
        operationId = "getFolioStatementPdf",
        summary = "Extrato da conta em PDF",
        description =
            "O mesmo extrato de `GET /folios/{id}/statement`, como PDF (A4), com o logotipo do hotel se houver " +
                "(sem ele, o documento sai igual). Exige `billing.folios.read`. `audience` padrão `GUEST`. Conta " +
                "inexistente: 404 `FOLIO_NOT_FOUND`. Qualquer erro sai em `application/problem+json`, mesmo com " +
                "`Accept: application/pdf`. O arquivo se chama `extrato-<número da conta>.pdf` e não é guardado em " +
                "cache.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O extrato em PDF.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_PDF_VALUE,
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
        ],
    )
    fun pdf(
        @PathVariable id: UUID,
        @Parameter(description = AUDIENCE_DESCRIPTION)
        @RequestParam(defaultValue = "GUEST") audience: StatementAudience,
    ): ResponseEntity<ByteArray> {
        val document = documents.pdf(FolioId(id), audience)
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("extrato-${folios.get(FolioId(id)).number}.pdf")
                    .build()
                    .toString(),
            ).cacheControl(CacheControl.noStore())
            .body(document.content)
    }

    @PostMapping("/print")
    @Idempotent
    @Operation(
        operationId = "printFolioStatement",
        summary = "Imprimir o extrato da conta",
        description =
            "Enfileira o extrato na impressora `printerId` (quem tem `billing.folios.read` imprime em qualquer " +
                "impressora ativa) e devolve o job. Exige `Idempotency-Key`: repetir devolve o mesmo job. " +
                "`audience` padrão `GUEST`. Impressora inexistente: 400 `UNKNOWN_PRINTER`; desativada: 422 " +
                "`PRINTER_INACTIVE`.",
    )
    @ApiResponse(responseCode = "202", description = "O job de impressão criado.")
    fun print(
        @PathVariable id: UUID,
        @Parameter(description = AUDIENCE_DESCRIPTION)
        @RequestParam(defaultValue = "GUEST") audience: StatementAudience,
        @Valid @RequestBody request: PrintDocumentRequest,
    ): ResponseEntity<PrintJobAccepted> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(PrintJobAccepted(documents.print(FolioId(id), audience, request.printerId)))
}
