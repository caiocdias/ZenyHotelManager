package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.billing.application.PaymentInput
import com.zeny.hotel.billing.application.PaymentView
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As formas aceitas no pedido de recebimento: as de `PaymentMethod` e `CITY_LEDGER` (faturado para contas a receber),
 * que é recusado com `422 PAYMENT_METHOD_NOT_AVAILABLE` até o M10.
 */
enum class PaymentMethodRequest {
    CASH,
    DEBIT_CARD,
    CREDIT_CARD,
    PIX,
    BANK_TRANSFER,
    OTHER,
    CITY_LEDGER,
}

private const val REFERENCE_DESCRIPTION =
    "Até 20 letras e dígitos (guardado em maiúsculas), com no máximo 12 dígitos: uma sequência de 13 a 19 dígitos " +
        "tem cara de número de cartão e é recusada (400 `PAYMENT_INVALID`). Não existe no dinheiro."

@Schema(
    description =
        "Um recebimento na conta, registrado manualmente (RF-PAG-03). **Nunca** envie o número do cartão nem o CVV " +
            "(PCI-DSS, RNF-10): só a bandeira e os 4 últimos dígitos, além do NSU e da autorização da maquininha.",
)
data class ReceivePaymentRequest(
    @field:Schema(
        description =
            "Forma de pagamento. `CITY_LEDGER` (faturado) ainda não está disponível: 422 " +
                "`PAYMENT_METHOD_NOT_AVAILABLE`.",
    )
    @field:NotNull
    val method: PaymentMethodRequest?,
    @field:Schema(
        description =
            "Valor aplicado na conta (0,01 a 1.000.000,00); pode passar do saldo devedor (a conta fica credora).",
        example = "150.00",
    )
    @field:NotNull
    val amount: Money?,
    @field:Schema(
        description =
            "Só no dinheiro: o valor entregue pelo cliente (maior ou igual a `amount`); o troco é a diferença. O " +
                "esperado do turno soma só o valor aplicado.",
        example = "200.00",
    )
    val tendered: Money? = null,
    @field:Schema(description = "Marca o pagamento como depósito (sinal antes da chegada, RF-CTA-07).")
    val deposit: Boolean = false,
    @field:Schema(description = "NSU da transação. $REFERENCE_DESCRIPTION", maxLength = 20, example = "004512")
    val nsu: String? = null,
    @field:Schema(description = "Código de autorização. $REFERENCE_DESCRIPTION", maxLength = 20, example = "A1B2C3")
    val authorizationCode: String? = null,
    @field:Schema(
        description = "Só no cartão: a bandeira (só letras e espaços, até 20).",
        maxLength = 20,
        example = "VISA",
    )
    val cardBrand: String? = null,
    @field:Schema(
        description = "Só no cartão: exatamente os 4 últimos dígitos (nunca o número completo).",
        pattern = "^[0-9]{4}$",
        example = "1234",
    )
    val cardLast4: String? = null,
) {
    fun toInput() =
        PaymentInput(
            checkNotNull(method).name,
            checkNotNull(amount),
            tendered,
            deposit,
            nsu,
            authorizationCode,
            cardBrand,
            cardLast4,
        )
}

@Schema(
    description =
        "O reembolso de um recebimento (RF-CTA-15), sempre na mesma forma dele: no dinheiro, sai da gaveta do " +
            "turno de quem devolve; nas outras formas, registra a devolução feita fora (Pix, estorno na " +
            "maquininha, transferência).",
)
data class RefundPaymentRequest(
    @field:Schema(description = "Valor devolvido (0,01 até o reembolsável do pagamento).", example = "50.00")
    @field:NotNull
    val amount: Money?,
    @field:Schema(description = "Motivo (até 200 caracteres).", example = "Cancelamento dentro da política")
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String?,
    @field:Schema(description = "NSU da devolução. $REFERENCE_DESCRIPTION", maxLength = 20)
    val nsu: String? = null,
    @field:Schema(description = "Autorização da devolução. $REFERENCE_DESCRIPTION", maxLength = 20)
    val authorizationCode: String? = null,
)

@Schema(description = "Um recebimento ou um reembolso na conta (imutável).")
data class PaymentResponse(
    val id: UUID,
    val folioId: UUID,
    @field:Schema(
        description =
            "`PAYMENT` (recebimento) ou `REFUND` (reembolso de um recebimento). A lista pode crescer " +
                "(`x-extensible-enum`): trate um valor desconhecido sem falhar.",
    )
    @field:ExtensibleEnum
    val type: PaymentType,
    @field:Schema(description = "Forma de pagamento (trate um valor desconhecido sem falhar).")
    @field:ExtensibleEnum
    val method: PaymentMethod,
    @field:Schema(description = "Valor, sempre positivo; o `type` diz o sentido.")
    val amount: Money,
    @field:Schema(description = "Valor entregue em dinheiro (só no recebimento em dinheiro, se informado).")
    val tendered: Money?,
    @field:Schema(description = "Troco: o entregue menos o valor (só com `tendered`).")
    val change: Money?,
    @field:Schema(description = "Depósito (sinal antes da chegada); o reembolso de um depósito também é.")
    val deposit: Boolean,
    val nsu: String?,
    val authorizationCode: String?,
    val cardBrand: String?,
    @field:Schema(description = "Os 4 últimos dígitos do cartão (nunca o número completo).")
    val cardLast4: String?,
    @field:Schema(description = "A intenção de pagamento confirmada que originou o recebimento (só no recebimento).")
    val paymentIntentId: UUID?,
    @field:Schema(description = "O recebimento reembolsado (só no reembolso).")
    val refundOfPaymentId: UUID?,
    @field:Schema(description = "Motivo do reembolso.")
    val reason: String?,
    @field:Schema(description = "O turno de caixa de quem recebeu ou devolveu.")
    val cashierShiftId: UUID,
    @field:Schema(description = "Data operacional do registro (RN-01).")
    val businessDate: LocalDate,
    val receivedAt: Instant,
    @field:Schema(description = "Quem recebeu ou devolveu.")
    val receivedBy: UUID,
    @field:Schema(description = "A aprovação de superior que autorizou o reembolso, se houve.")
    val approvalId: UUID?,
    val approvedBy: UUID?,
    @field:Schema(description = "Só no recebimento: o total já reembolsado.")
    val refundedAmount: Money?,
    @field:Schema(description = "Só no recebimento: o que ainda é reembolsável (o valor menos os reembolsos).")
    val refundableAmount: Money?,
) {
    companion object {
        fun of(view: PaymentView) =
            PaymentResponse(
                view.id.value,
                view.folioId.value,
                view.type,
                view.method,
                view.amount,
                view.tendered,
                view.change,
                view.deposit,
                view.nsu,
                view.authorizationCode,
                view.cardBrand,
                view.cardLast4,
                view.paymentIntentId,
                view.refundOfId?.value,
                view.reason,
                view.cashierShiftId.value,
                view.businessDate,
                view.receivedAt,
                view.receivedBy.value,
                view.approvalId,
                view.approvedBy?.value,
                view.refunded,
                view.refundable,
            )
    }
}
