package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.RegistrationCardService
import com.zeny.hotel.printing.PrintDocumentRequest
import com.zeny.hotel.printing.PrintJobAccepted
import com.zeny.hotel.reservations.ReservationId
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
 * A ficha de registro da hospedagem como documento (RF-REC, M5 E11; D-E11-9 e D-E11-10): o PDF e a impressão. As duas
 * rotas exigem `frontdesk.stays.checkin`; o `printing` não confere permissão (D2).
 */
@ApiV1Controller
@RequestMapping("/reservations/{id}/registration-card")
@Tag(name = FRONTDESK_TAG, description = FRONTDESK_TAG_DESCRIPTION)
class RegistrationCardController(
    private val cards: RegistrationCardService,
) {
    @GetMapping("/pdf")
    @Operation(
        operationId = "getRegistrationCardPdf",
        summary = "Ficha de registro da hospedagem em PDF",
        description =
            "A estadia (código, UH, datas) e, por hóspede, o nome (o social, se houver), a nacionalidade, o " +
                "nascimento, o endereço, os documentos mascarados e o motivo de viagem e o meio de transporte como " +
                "os códigos FNRH guardados, com a linha de assinatura. Nunca traz o número inteiro de documento nem " +
                "raça/cor ou deficiência. Exige `frontdesk.stays.checkin`. Reserva inexistente: 404 " +
                "`RESERVATION_NOT_FOUND`; fora de `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`: 409 " +
                "`REGISTRATION_CARD_UNAVAILABLE`. Qualquer erro sai em `application/problem+json`. O arquivo se " +
                "chama `ficha-<código da reserva>.pdf` e não é guardado em cache.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A ficha em PDF.",
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
        val card = cards.pdf(ReservationId(id))
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("ficha-${card.reservationCode}.pdf")
                    .build()
                    .toString(),
            ).cacheControl(CacheControl.noStore())
            .body(card.document.content)
    }

    @PostMapping("/print")
    @Idempotent
    @Operation(
        operationId = "printRegistrationCard",
        summary = "Imprimir a ficha de registro",
        description =
            "Enfileira a ficha na impressora `printerId` (quem tem `frontdesk.stays.checkin` imprime em qualquer " +
                "impressora ativa) e devolve o job. Exige `Idempotency-Key`: repetir devolve o mesmo job. Mesmas " +
                "recusas do PDF; impressora inexistente: 400 `UNKNOWN_PRINTER`; desativada: 422 `PRINTER_INACTIVE`.",
    )
    @ApiResponse(responseCode = "202", description = "O job de impressão criado.")
    fun print(
        @PathVariable id: UUID,
        @Valid @RequestBody request: PrintDocumentRequest,
    ): ResponseEntity<PrintJobAccepted> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(PrintJobAccepted(cards.print(ReservationId(id), request.printerId)))
}
