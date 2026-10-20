package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.application.CardPreAuthorizationService
import com.zeny.hotel.billing.application.CardPreAuthorizationView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * Cauções registradas manualmente (RF-CTA-08 no recorte manual, M3 E09): a pré-autorização de cartão de crédito feita
 * numa maquininha não integrada, capturada total ou parcialmente (cada captura é um recebimento na conta) e com a sobra
 * liberada. Consultar exige `billing.folios.read`; registrar, capturar e liberar, `billing.payments.receive`. Só a
 * captura exige o turno de caixa aberto do próprio usuário. Os POSTs exigem `Idempotency-Key`.
 */
@ApiV1Controller
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class CardPreAuthorizationsController(
    private val preAuthorizations: CardPreAuthorizationService,
) {
    @GetMapping("/folios/{folioId}/card-pre-authorizations")
    @Operation(
        operationId = "listCardPreAuthorizations",
        summary = "Listar as cauções de uma conta",
        description =
            "Em ordem de registro, com a situação calculada agora (`EXPIRED` quando venceu sem ser capturada por " +
                "inteiro nem liberada). Exige `billing.folios.read`. Conta inexistente: 404 `FOLIO_NOT_FOUND`.",
    )
    fun list(
        @PathVariable folioId: UUID,
        @Valid page: PageParams,
    ): CursorPage<CardPreAuthorizationResponse> =
        preAuthorizations.list(FolioId(folioId), page).map(CardPreAuthorizationResponse::of)

    @GetMapping("/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}")
    @Operation(
        operationId = "getCardPreAuthorization",
        summary = "Consultar uma caução",
        description = "Exige `billing.folios.read`. Inexistente nesta conta: 404 `PRE_AUTHORIZATION_NOT_FOUND`.",
    )
    fun get(
        @PathVariable folioId: UUID,
        @PathVariable preAuthorizationId: UUID,
    ): ResponseEntity<CardPreAuthorizationResponse> =
        ok(preAuthorizations.get(FolioId(folioId), CardPreAuthorizationId(preAuthorizationId)))

    @PostMapping("/folios/{folioId}/card-pre-authorizations")
    @Idempotent
    @Operation(
        operationId = "registerCardPreAuthorization",
        summary = "Registrar uma caução na conta",
        description =
            "Registro manual da pré-autorização de cartão de crédito feita na maquininha não integrada, na data " +
                "operacional. **Não altera o saldo da conta**: só as capturas entram nele. Não exige turno de caixa. " +
                "Exige `billing.payments.receive`. Conta inexistente: 404 `FOLIO_NOT_FOUND`; encerrada: 409 " +
                "`FOLIO_CLOSED`; formato (valor, validade de agora a 90 dias, autorização obrigatória, NSU, " +
                "bandeira, últimos dígitos): 400 `PRE_AUTHORIZATION_INVALID` (com o campo). Nunca envie o número do " +
                "cartão nem o CVV.",
    )
    @ApiResponse(responseCode = "201", description = "Caução registrada.")
    fun register(
        @PathVariable folioId: UUID,
        @Valid @RequestBody request: RegisterPreAuthorizationRequest,
    ): ResponseEntity<CardPreAuthorizationResponse> {
        val view = preAuthorizations.register(FolioId(folioId), request.toInput())
        return ResponseEntity
            .created(URI.create("/api/v1/folios/${view.folioId}/card-pre-authorizations/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CardPreAuthorizationResponse.of(view))
    }

    @PostMapping("/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}/capture")
    @Idempotent
    @Operation(
        operationId = "captureCardPreAuthorization",
        summary = "Capturar uma caução",
        description =
            "Captura total ou parcial: gera um recebimento no cartão de crédito (com a bandeira e os últimos " +
                "dígitos da caução) pelo fluxo dos pagamentos, que entra no saldo da conta e no esperado do turno de " +
                "caixa aberto do próprio usuário (sem turno: 422 `CASHIER_SHIFT_REQUIRED`). Depois de uma captura " +
                "parcial, o restante continua capturável (`PARTIALLY_CAPTURED`) até vencer ou ser liberado; a sobra " +
                "sai pela liberação. Exige `billing.payments.receive`. Acima do que ainda está autorizado: 422 " +
                "`CAPTURE_EXCEEDS_AUTHORIZED`; vencida: 422 `PRE_AUTHORIZATION_EXPIRED`; já capturada por inteiro ou " +
                "liberada: 409 `PRE_AUTHORIZATION_SETTLED`; inexistente nesta conta: 404 " +
                "`PRE_AUTHORIZATION_NOT_FOUND`; conta encerrada: 409 `FOLIO_CLOSED`; formato: 400 " +
                "`PRE_AUTHORIZATION_INVALID` (com o campo).",
    )
    @ApiResponse(responseCode = "201", description = "Capturada; o recebimento foi registrado na conta.")
    fun capture(
        @PathVariable folioId: UUID,
        @PathVariable preAuthorizationId: UUID,
        @Valid @RequestBody request: CapturePreAuthorizationRequest,
    ): ResponseEntity<PreAuthorizationCaptureResponse> {
        val capture =
            preAuthorizations.capture(
                FolioId(folioId),
                CardPreAuthorizationId(preAuthorizationId),
                checkNotNull(request.amount),
                request.nsu,
                request.authorizationCode,
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/folios/${capture.payment.folioId}/payments/${capture.payment.id}"))
            .body(PreAuthorizationCaptureResponse.of(capture))
    }

    @PostMapping("/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}/release")
    @Idempotent
    @Operation(
        operationId = "releaseCardPreAuthorization",
        summary = "Liberar a sobra de uma caução",
        description =
            "Registra que o que ainda estava autorizado (o autorizado menos o capturado) foi liberado na " +
                "maquininha e encerra a caução (`RELEASED`). Não exige turno de caixa; vale também com a conta " +
                "encerrada e depois da validade (a caução vencida termina `RELEASED`). Exige " +
                "`billing.payments.receive`. Já capturada por inteiro ou liberada: 409 `PRE_AUTHORIZATION_SETTLED`; " +
                "inexistente nesta conta: 404 `PRE_AUTHORIZATION_NOT_FOUND`.",
    )
    fun release(
        @PathVariable folioId: UUID,
        @PathVariable preAuthorizationId: UUID,
    ): ResponseEntity<CardPreAuthorizationResponse> =
        ok(preAuthorizations.release(FolioId(folioId), CardPreAuthorizationId(preAuthorizationId)))

    @GetMapping("/card-pre-authorizations/expiring")
    @Operation(
        operationId = "listExpiringCardPreAuthorizations",
        summary = "Listar as cauções a vencer",
        description =
            "As cauções ainda não encerradas (nem capturadas por inteiro nem liberadas) cuja validade cai no " +
                "período, em datas no fuso do hotel, inclusive; em ordem de validade. As que já venceram no período " +
                "vêm com `EXPIRED`. Exige `billing.folios.read`. Fim antes do início: 400 " +
                "`PRE_AUTHORIZATION_INVALID` (campo `to`).",
    )
    fun expiring(
        @Parameter(description = "Primeiro dia do período (padrão: a data operacional).")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Último dia do período, inclusive.")
        @RequestParam to: LocalDate,
        @Valid page: PageParams,
    ): CursorPage<CardPreAuthorizationResponse> =
        preAuthorizations.expiring(from, to, page).map(CardPreAuthorizationResponse::of)

    private fun ok(view: CardPreAuthorizationView) =
        ResponseEntity
            .ok()
            .eTag(ETags.of(view.version))
            .body(CardPreAuthorizationResponse.of(view))
}
