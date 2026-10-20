package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.application.FolioReopeningView
import com.zeny.hotel.billing.application.FolioStatement
import com.zeny.hotel.billing.application.StatementAudience
import com.zeny.hotel.billing.application.StatementDay
import com.zeny.hotel.billing.application.StatementLine
import com.zeny.hotel.billing.application.StatementLineInternal
import com.zeny.hotel.billing.application.StatementLineKind
import com.zeny.hotel.billing.application.StatementPreAuthorization
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN_VALUE = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

@Schema(description = "O motivo da reabertura de uma conta encerrada (fica na reabertura, fora da trilha).")
data class FolioReopenRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
)

@Schema(
    description =
        "O extrato da conta em JSON (RF-CTA-12): as linhas agrupadas por data operacional e os totais. `balance` = " +
            "`chargesTotal` − `paymentsTotal` = a soma de `amount` de todas as linhas = o saldo da conta.",
)
data class FolioStatementResponse(
    val folio: StatementFolioResponse,
    @field:Schema(description = "`INTERNAL` (quem opera) ou `GUEST` (prévia para o hóspede, sem dados internos).")
    val audience: StatementAudience,
    val generatedAt: Instant,
    @field:Schema(description = "As datas operacionais com lançamento ou pagamento, em ordem.")
    val days: List<StatementDayResponse>,
    @field:Schema(description = "A soma dos lançamentos (débitos menos créditos e correções).")
    val chargesTotal: Money,
    @field:Schema(description = "O recebido líquido: recebimentos (inclusive depósitos e capturas) menos reembolsos.")
    val paymentsTotal: Money,
    @field:Schema(description = "O saldo da conta (negativo: a conta é credora).")
    val balance: Money,
    @field:Schema(
        description =
            "As cauções da conta (informativas: não entram no saldo; as capturas aparecem como pagamentos), com a " +
                "situação calculada agora.",
    )
    val preAuthorizations: List<StatementPreAuthorizationResponse>,
    @field:Schema(description = "As reaberturas da conta, com o motivo e a aprovação (nulo no extrato `GUEST`).")
    val reopenings: List<FolioReopeningResponse>?,
) {
    companion object {
        fun of(statement: FolioStatement) =
            with(statement) {
                FolioStatementResponse(
                    StatementFolioResponse(
                        folio.id.value,
                        folio.number,
                        FolioOwnerDto(folio.ownerType, folio.ownerId),
                        folio.payerCompanyId?.value,
                        folio.status,
                        folio.openedOn,
                        folio.closedOn,
                    ),
                    audience,
                    generatedAt,
                    days.map(StatementDayResponse::of),
                    chargesTotal,
                    paymentsTotal,
                    balance,
                    preAuthorizations.map(StatementPreAuthorizationResponse::of),
                    reopenings?.map(FolioReopeningResponse::of),
                )
            }
    }
}

@Schema(description = "A conta do extrato.")
data class StatementFolioResponse(
    val id: UUID,
    val number: DocumentNumber,
    val owner: FolioOwnerDto,
    val payerCompanyId: UUID?,
    val status: FolioStatus,
    val openedOn: LocalDate,
    @field:Schema(description = "Data operacional do encerramento (nula na conta aberta).")
    val closedOn: LocalDate?,
)

@Schema(description = "As linhas de uma data operacional, com os totais do dia e o saldo acumulado até o fim dele.")
data class StatementDayResponse(
    val businessDate: LocalDate,
    val lines: List<StatementLineResponse>,
    @field:Schema(description = "A soma dos lançamentos do dia.")
    val charges: Money,
    @field:Schema(description = "O recebido líquido do dia (recebimentos menos reembolsos).")
    val payments: Money,
    @field:Schema(description = "O saldo acumulado até o fim do dia.")
    val balanceAtEnd: Money,
) {
    companion object {
        fun of(day: StatementDay) =
            StatementDayResponse(
                day.businessDate,
                day.lines.map(StatementLineResponse::of),
                day.charges,
                day.payments,
                day.balanceAtEnd,
            )
    }
}

@Schema(
    description =
        "Uma linha do extrato: um lançamento (`POSTING`, com o código) ou um pagamento (`PAYMENT`: recebimento, " +
            "depósito, captura de caução ou reembolso, com a forma).",
)
data class StatementLineResponse(
    @field:Schema(description = "`POSTING` ou `PAYMENT`. $UNKNOWN_VALUE")
    @field:ExtensibleEnum
    val kind: StatementLineKind,
    @field:Schema(description = "O id do lançamento ou do pagamento.")
    val id: UUID,
    @field:Schema(description = "Tipo do lançamento (só em `POSTING`). $UNKNOWN_VALUE")
    @field:ExtensibleEnum
    val postingType: PostingType?,
    @field:Schema(description = "Tipo do pagamento (só em `PAYMENT`). $UNKNOWN_VALUE")
    @field:ExtensibleEnum
    val paymentType: PaymentType?,
    val businessDate: LocalDate,
    @field:Schema(description = "O instante do lançamento ou do pagamento.")
    val at: Instant,
    @field:Schema(
        description =
            "A descrição do lançamento ou, no pagamento, o que foi e a forma (\"Pagamento — Dinheiro\", \"Depósito — " +
                "Pix\", \"Captura de caução — Cartão de crédito\", \"Reembolso — Pix\").",
    )
    val description: String,
    @field:Schema(
        description =
            "O efeito no saldo: débito positivo; crédito, correção de débito e recebimento negativos; reembolso " +
                "positivo. A soma de todas as linhas é o saldo da conta.",
    )
    val amount: Money,
    @field:Schema(description = "Lançamento: o código curto atual.")
    val transactionCode: String?,
    @field:Schema(description = "Lançamento: o nome atual do código.")
    val transactionCodeName: String?,
    @field:Schema(description = "Lançamento: o grupo de receita do código.")
    val revenueGroup: RevenueGroup?,
    val quantity: BigDecimal?,
    val unitPrice: Money?,
    @field:Schema(description = "Taxa de serviço: o percentual aplicado.")
    val serviceChargeRate: Percent?,
    @field:Schema(description = "Estorno, ajuste ou saída de transferência: o lançamento corrigido (nesta conta).")
    val correctsPostingId: UUID?,
    @field:Schema(description = "Taxa de serviço: o item dela (nesta conta).")
    val serviceChargeForPostingId: UUID?,
    @field:Schema(description = "Pagamento: a forma. $UNKNOWN_VALUE")
    @field:ExtensibleEnum
    val method: PaymentMethod?,
    @field:Schema(description = "Pagamento: depósito (sinal antes da chegada).")
    val deposit: Boolean?,
    @field:Schema(description = "Pagamento em dinheiro: o valor entregue.")
    val tendered: Money?,
    @field:Schema(description = "Pagamento em dinheiro: o troco.")
    val change: Money?,
    @field:Schema(description = "Pagamento no cartão: a bandeira.")
    val cardBrand: String?,
    @field:Schema(description = "Pagamento no cartão: os 4 últimos dígitos.")
    val cardLast4: String?,
    @field:Schema(description = "Reembolso: o recebimento reembolsado.")
    val refundOfPaymentId: UUID?,
    @field:Schema(description = "Captura de caução: a caução.")
    val cardPreAuthorizationId: UUID?,
    @field:Schema(description = "Os dados internos da linha (nulo no extrato `GUEST`).")
    val internal: StatementLineInternalResponse?,
) {
    companion object {
        fun of(line: StatementLine) =
            StatementLineResponse(
                line.kind,
                line.id,
                line.postingType,
                line.paymentType,
                line.businessDate,
                line.at,
                line.description,
                line.amount,
                line.transactionCode,
                line.transactionCodeName,
                line.revenueGroup,
                line.quantity,
                line.unitPrice,
                line.serviceChargeRate,
                line.correctsPostingId,
                line.serviceChargeForPostingId,
                line.method,
                line.deposit,
                line.tendered,
                line.change,
                line.cardBrand,
                line.cardLast4,
                line.refundOfPaymentId,
                line.preAuthorizationId?.value,
                line.internal?.let(StatementLineInternalResponse::of),
            )
    }
}

@Schema(
    description =
        "O que só quem opera vê de uma linha: quem lançou ou recebeu, motivo, aprovação, NSU e autorização, turno de " +
            "caixa e os vínculos com outras contas.",
)
data class StatementLineInternalResponse(
    @field:Schema(description = "Quem lançou ou recebeu (nulo: o sistema).")
    val userId: UUID?,
    @field:Schema(description = "O motivo da correção, da transferência ou do reembolso.")
    val reason: String?,
    val approvalId: UUID?,
    val approvedBy: UUID?,
    val source: PostingSource?,
    val routedFromFolioId: UUID?,
    val routingRuleId: UUID?,
    val transferredFromPostingId: UUID?,
    val transferredFromFolioId: UUID?,
    val transferredToPostingId: UUID?,
    val transferredToFolioId: UUID?,
    val triggeredByPostingId: UUID?,
    val nsu: String?,
    val authorizationCode: String?,
    val cashierShiftId: UUID?,
    val paymentIntentId: UUID?,
) {
    companion object {
        fun of(internal: StatementLineInternal) =
            with(internal) {
                StatementLineInternalResponse(
                    user?.value,
                    reason,
                    approvalId,
                    approvedBy?.value,
                    source,
                    routedFromFolioId?.value,
                    routingRuleId?.value,
                    transferredFromPostingId,
                    transferredFromFolioId?.value,
                    transferredToPostingId,
                    transferredToFolioId?.value,
                    triggeredByPostingId,
                    nsu,
                    authorizationCode,
                    cashierShiftId?.value,
                    paymentIntentId,
                )
            }
    }
}

@Schema(
    description =
        "Uma caução no extrato (não entra no saldo). NSU, autorização e quem registrou ou liberou só no extrato " +
            "`INTERNAL`.",
)
data class StatementPreAuthorizationResponse(
    val id: UUID,
    @field:Schema(description = "Situação calculada agora. $UNKNOWN_VALUE")
    @field:ExtensibleEnum
    val status: CardPreAuthorizationStatus,
    val amount: Money,
    val capturedAmount: Money,
    val releasedAmount: Money?,
    val expiresAt: Instant,
    @field:Schema(description = "Data operacional do registro.")
    val businessDate: LocalDate,
    val cardBrand: String?,
    val cardLast4: String?,
    val nsu: String?,
    val authorizationCode: String?,
    val registeredBy: UUID?,
    val releasedBy: UUID?,
) {
    companion object {
        fun of(entry: StatementPreAuthorization) =
            with(entry) {
                StatementPreAuthorizationResponse(
                    id.value,
                    status,
                    amount,
                    capturedAmount,
                    releasedAmount,
                    expiresAt,
                    businessDate,
                    cardBrand,
                    cardLast4,
                    nsu,
                    authorizationCode,
                    registeredBy?.value,
                    releasedBy?.value,
                )
            }
    }
}

@Schema(description = "Uma reabertura da conta (ação sensível), com o motivo e a aprovação de superior, se houve.")
data class FolioReopeningResponse(
    val id: UUID,
    val reason: String,
    @field:Schema(description = "O encerramento desfeito: data operacional e instante.")
    val closedOn: LocalDate,
    val closedAt: Instant,
    @field:Schema(description = "Data operacional da reabertura.")
    val businessDate: LocalDate,
    val reopenedAt: Instant,
    val reopenedBy: UUID,
    val approvalId: UUID?,
    val approvedBy: UUID?,
) {
    companion object {
        fun of(reopening: FolioReopeningView) =
            with(reopening) {
                FolioReopeningResponse(
                    id,
                    reason,
                    closedOn,
                    closedAt,
                    businessDate,
                    reopenedAt,
                    reopenedBy.value,
                    approvalId,
                    approvedBy?.value,
                )
            }
    }
}
