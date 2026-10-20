package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.application.PaymentService
import com.zeny.hotel.billing.application.PaymentView
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI
import java.util.UUID

/**
 * Pagamentos e reembolsos na conta (RF-CTA-06, 07, 10 e 15, RF-PAG-03, M3 E08). Consultar exige `billing.folios.read`;
 * receber, `billing.payments.receive`; reembolsar é sensível (`billing.payments.refund`, com limite). Todo recebimento
 * e devolução sai do turno de caixa aberto do próprio usuário. Os POSTs exigem `Idempotency-Key`. Pagamentos nunca
 * mudam nem são apagados (RN-03).
 */
@ApiV1Controller
@RequestMapping("/folios/{folioId}/payments")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class PaymentsController(
    private val payments: PaymentService,
) {
    @GetMapping
    @Operation(
        operationId = "listPayments",
        summary = "Listar os pagamentos e reembolsos de uma conta",
        description =
            "Em ordem de registro, com o total reembolsado e o reembolsável de cada recebimento. Exige " +
                "`billing.folios.read`. Conta inexistente: 404 `FOLIO_NOT_FOUND`.",
    )
    fun list(
        @PathVariable folioId: UUID,
        @Valid page: PageParams,
    ): CursorPage<PaymentResponse> = payments.list(FolioId(folioId), page).map(PaymentResponse::of)

    @GetMapping("/{paymentId}")
    @Operation(
        operationId = "getPayment",
        summary = "Consultar um pagamento ou reembolso",
        description = "Exige `billing.folios.read`. Inexistente nesta conta: 404 `PAYMENT_NOT_FOUND`.",
    )
    fun get(
        @PathVariable folioId: UUID,
        @PathVariable paymentId: UUID,
    ): PaymentResponse = PaymentResponse.of(payments.get(FolioId(folioId), PaymentId(paymentId)))

    @PostMapping
    @Idempotent
    @Operation(
        operationId = "receivePayment",
        summary = "Receber um pagamento na conta",
        description =
            "Registro manual (maquininha não integrada, Pix conferido, dinheiro, transferência) na data operacional, " +
                "no turno de caixa aberto do próprio usuário (sem turno: 422 `CASHIER_SHIFT_REQUIRED`), que entra no " +
                "esperado do turno na forma. Passa por uma intenção de pagamento do provedor `MANUAL`, confirmada na " +
                "hora (`paymentIntentId`). Vários pagamentos por conta; o valor pode passar do saldo devedor " +
                "(a conta fica credora). Troco só no dinheiro (`tendered` − `amount`). `deposit` marca o depósito. " +
                "Exige `billing.payments.receive`. Conta inexistente: 404 `FOLIO_NOT_FOUND`; encerrada: 409 " +
                "`FOLIO_CLOSED`; `CITY_LEDGER`: 422 `PAYMENT_METHOD_NOT_AVAILABLE`; formato: 400 `PAYMENT_INVALID` " +
                "(com o campo). Nunca envie o número do cartão nem o CVV.",
    )
    @ApiResponse(responseCode = "201", description = "Recebido.")
    fun receive(
        @PathVariable folioId: UUID,
        @Valid @RequestBody request: ReceivePaymentRequest,
    ): ResponseEntity<PaymentResponse> = created(payments.receive(FolioId(folioId), request.toInput()))

    @PostMapping("/{paymentId}/refund")
    @Idempotent
    @Operation(
        operationId = "refundPayment",
        summary = "Reembolsar um pagamento",
        description =
            "Devolução vinculada ao recebimento, na mesma forma (no dinheiro, sai da gaveta), com motivo, no turno " +
                "de caixa aberto do próprio usuário (sem turno: 422 `CASHIER_SHIFT_REQUIRED`), que a tira do " +
                "esperado do turno. Exige antes `billing.folios.read` (sem ela, 403 `PERMISSION_DENIED`). Até o " +
                "reembolsável (o pago menos os reembolsos anteriores; acima: 422 `REFUND_EXCEEDS_REFUNDABLE`); " +
                "reembolso de um reembolso: 422 `PAYMENT_NOT_REFUNDABLE`; inexistente nesta conta: 404 " +
                "`PAYMENT_NOT_FOUND`; conta encerrada: 409 `FOLIO_CLOSED`. Essas regras vêm antes da aprovação. " +
                "Ação **sensível** com o limite `maxAmount` sobre o total reembolsado do pagamento (os anteriores " +
                "mais este): sem `billing.payments.refund`, ou acima do limite, 403 `APPROVAL_REQUIRED` (com " +
                "`action`, `permission`, `resourceRef` e `limit`); repita com o `X-Approval-Token` de " +
                "`POST /api/v1/auth/approvals` e **outra** `Idempotency-Key`.",
    )
    @ApiResponse(responseCode = "201", description = "Reembolsado.")
    fun refund(
        @PathVariable folioId: UUID,
        @PathVariable paymentId: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: RefundPaymentRequest,
    ): ResponseEntity<PaymentResponse> =
        created(
            payments.refund(
                FolioId(folioId),
                PaymentId(paymentId),
                checkNotNull(request.amount),
                request.reason,
                request.nsu,
                request.authorizationCode,
                approvalToken,
            ),
        )

    private fun created(view: PaymentView) =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/folios/${view.folioId}/payments/${view.id}"))
            .body(PaymentResponse.of(view))
}
