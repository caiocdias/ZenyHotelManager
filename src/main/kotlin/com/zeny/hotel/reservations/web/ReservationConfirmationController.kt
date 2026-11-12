package com.zeny.hotel.reservations.web

import com.zeny.hotel.printing.PrintDocumentRequest
import com.zeny.hotel.printing.PrintJobAccepted
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.application.ReservationConfirmations
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
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
import java.util.UUID

/**
 * A confirmação da reserva como documento (RF-RES-12, M5 E11; D-E11-9 e D-E11-10): o PDF e a impressão. As duas rotas
 * exigem `reservations.reservations.read`; o `printing` não confere permissão (D2).
 */
@ApiV1Controller
@RequestMapping("/reservations/{id}/confirmation")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class ReservationConfirmationController(
    private val confirmations: ReservationConfirmations,
) {
    @GetMapping("/pdf")
    @Operation(
        operationId = "getReservationConfirmationPdf",
        summary = "Confirmação da reserva em PDF",
        description =
            "O código, o titular (o nome social, se houver), o tipo de UH, o plano, as datas, as noites, a diária " +
                "e o total, os termos de cancelamento e de garantia copiados na reserva (RN-15) e, da política do " +
                "hotel, os horários, a janela de arrumação, o early e o late com o preço e os textos (RN-11). " +
                "Exige `reservations.reservations.read`. Reserva inexistente: 404 `RESERVATION_NOT_FOUND`; " +
                "cancelada ou no-show: 409 `CONFIRMATION_UNAVAILABLE`. Qualquer erro sai em " +
                "`application/problem+json`. O arquivo se chama `confirmacao-<código da reserva>.pdf` e não é " +
                "guardado em cache.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A confirmação em PDF.",
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
    ): ResponseEntity<ByteArray> {
        val confirmation = confirmations.pdf(ReservationId(id))
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("confirmacao-${confirmation.reservationCode}.pdf")
                    .build()
                    .toString(),
            ).cacheControl(CacheControl.noStore())
            .body(confirmation.document.content)
    }

    @PostMapping("/print")
    @Idempotent
    @Operation(
        operationId = "printReservationConfirmation",
        summary = "Imprimir a confirmação da reserva",
        description =
            "Enfileira a confirmação na impressora `printerId` (quem tem `reservations.reservations.read` imprime " +
                "em qualquer impressora ativa) e devolve o job. Exige `Idempotency-Key`: repetir devolve o mesmo " +
                "job. Mesmas recusas do PDF; impressora inexistente: 400 `UNKNOWN_PRINTER`; desativada: 422 " +
                "`PRINTER_INACTIVE`.",
    )
    @ApiResponse(responseCode = "202", description = "O job de impressão criado.")
    fun print(
        @PathVariable id: UUID,
        @Valid @RequestBody request: PrintDocumentRequest,
    ): ResponseEntity<PrintJobAccepted> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(PrintJobAccepted(confirmations.print(ReservationId(id), request.printerId)))
}
