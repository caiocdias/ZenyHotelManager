package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.application.CardPreAuthorizationView
import com.zeny.hotel.billing.application.PreAuthorizationCapture
import com.zeny.hotel.billing.application.PreAuthorizationInput
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TERMINAL_REFERENCE =
    "Até 20 letras e dígitos (guardado em maiúsculas), com no máximo 12 dígitos: uma sequência de 13 a 19 dígitos " +
        "tem cara de número de cartão e é recusada (400 `PRE_AUTHORIZATION_INVALID`)."

@Schema(
    description =
        "Uma caução (pré-autorização de cartão de crédito) feita numa maquininha não integrada, registrada na conta " +
            "(RF-CTA-08). Não entra no saldo da conta até ser capturada. **Nunca** envie o número do cartão nem o " +
            "CVV (PCI-DSS, RNF-10): só a bandeira e os 4 últimos dígitos, além do NSU e da autorização.",
)
data class RegisterPreAuthorizationRequest(
    @field:Schema(description = "Valor autorizado (0,01 a 1.000.000,00).", example = "500.00")
    @field:NotNull
    val amount: Money?,
    @field:Schema(
        description =
            "Validade da autorização na adquirente (instante em UTC): depois de agora e até 90 dias. Vencida, a " +
                "caução não é mais capturada (situação `EXPIRED`, calculada na leitura), mas a sobra ainda pode ser " +
                "liberada.",
        example = "2026-10-27T15:00:00Z",
    )
    @field:NotNull
    val expiresAt: Instant?,
    @field:Schema(description = "NSU da pré-autorização. $TERMINAL_REFERENCE", maxLength = 20, example = "004512")
    val nsu: String? = null,
    @field:Schema(
        description = "Código de autorização da pré-autorização (obrigatório). $TERMINAL_REFERENCE",
        maxLength = 20,
        example = "A1B2C3",
    )
    val authorizationCode: String? = null,
    @field:Schema(description = "A bandeira (só letras e espaços, até 20).", maxLength = 20, example = "VISA")
    val cardBrand: String? = null,
    @field:Schema(
        description = "Exatamente os 4 últimos dígitos do cartão (nunca o número completo).",
        pattern = "^[0-9]{4}$",
        example = "1234",
    )
    val cardLast4: String? = null,
) {
    fun toInput() =
        PreAuthorizationInput(
            checkNotNull(amount),
            checkNotNull(expiresAt),
            nsu,
            authorizationCode,
            cardBrand,
            cardLast4,
        )
}

@Schema(
    description =
        "A captura de uma caução (total ou parcial): gera um recebimento no cartão de crédito, com a bandeira e os " +
            "últimos dígitos da caução.",
)
data class CapturePreAuthorizationRequest(
    @field:Schema(
        description = "Valor capturado (0,01 até o que ainda está autorizado na caução).",
        example = "320.00",
    )
    @field:NotNull
    val amount: Money?,
    @field:Schema(description = "NSU da captura. $TERMINAL_REFERENCE", maxLength = 20)
    val nsu: String? = null,
    @field:Schema(description = "Autorização da captura. $TERMINAL_REFERENCE", maxLength = 20)
    val authorizationCode: String? = null,
)

@Schema(description = "Uma caução na conta, com a situação calculada no instante da leitura.")
data class CardPreAuthorizationResponse(
    val id: UUID,
    val folioId: UUID,
    @field:Schema(
        description =
            "`ACTIVE` (nada capturado), `PARTIALLY_CAPTURED` (o restante continua capturável), `CAPTURED` " +
                "(capturada por inteiro), `RELEASED` (sobra liberada) ou `EXPIRED` (venceu sem ser capturada por " +
                "inteiro nem liberada; calculada na leitura). A lista pode crescer (`x-extensible-enum`): trate um " +
                "valor desconhecido sem falhar.",
    )
    @field:ExtensibleEnum
    val status: CardPreAuthorizationStatus,
    @field:Schema(description = "Valor autorizado.")
    val amount: Money,
    @field:Schema(description = "Total capturado (a soma dos recebimentos das capturas).")
    val capturedAmount: Money,
    @field:Schema(description = "O que ainda pode ser capturado agora (zero se encerrada ou vencida).")
    val capturableAmount: Money,
    @field:Schema(description = "A sobra liberada (só depois da liberação).")
    val releasedAmount: Money?,
    @field:Schema(description = "Validade da autorização.")
    val expiresAt: Instant,
    val nsu: String?,
    val authorizationCode: String,
    val cardBrand: String?,
    @field:Schema(description = "Os 4 últimos dígitos do cartão (nunca o número completo).")
    val cardLast4: String?,
    @field:Schema(description = "Data operacional do registro (RN-01).")
    val businessDate: LocalDate,
    val registeredAt: Instant,
    @field:Schema(description = "Quem registrou.")
    val registeredBy: UUID,
    val releasedAt: Instant?,
    @field:Schema(description = "Quem registrou a liberação.")
    val releasedBy: UUID?,
    @field:Schema(description = "Os recebimentos gerados pelas capturas, em ordem de registro.")
    val capturePaymentIds: List<UUID>,
) {
    companion object {
        fun of(view: CardPreAuthorizationView) =
            CardPreAuthorizationResponse(
                view.id.value,
                view.folioId.value,
                view.status,
                view.amount,
                view.capturedAmount,
                view.capturableAmount,
                view.releasedAmount,
                view.expiresAt,
                view.nsu,
                view.authorizationCode,
                view.cardBrand,
                view.cardLast4,
                view.businessDate,
                view.registeredAt,
                view.registeredBy.value,
                view.releasedAt,
                view.releasedBy?.value,
                view.captureIds.map { it.value },
            )
    }
}

@Schema(description = "O resultado de uma captura: a caução depois dela e o recebimento gerado.")
data class PreAuthorizationCaptureResponse(
    val preAuthorization: CardPreAuthorizationResponse,
    val payment: PaymentResponse,
) {
    companion object {
        fun of(capture: PreAuthorizationCapture) =
            PreAuthorizationCaptureResponse(
                CardPreAuthorizationResponse.of(capture.preAuthorization),
                PaymentResponse.of(capture.payment),
            )
    }
}
