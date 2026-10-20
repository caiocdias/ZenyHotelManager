package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.CashMovementType
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.application.CashMovementView
import com.zeny.hotel.billing.application.CashPointView
import com.zeny.hotel.billing.application.CashierShiftListView
import com.zeny.hotel.billing.application.CashierShiftReport
import com.zeny.hotel.billing.application.CashierShiftView
import com.zeny.hotel.billing.application.DeclaredCount
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Os dados de um ponto de caixa.")
data class CashPointRequest(
    @field:Schema(description = "Nome único, sem diferenciar maiúsculas.", example = "Recepção")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(
        description =
            "Fechamento cego (padrão): o operador só vê o valor esperado depois de declarar a contagem. Vale para " +
                "os turnos abertos depois da mudança.",
    )
    val blindClose: Boolean = true,
)

@Schema(description = "Um ponto de caixa.")
data class CashPointResponse(
    val id: UUID,
    val name: String,
    val blindClose: Boolean,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: CashPointView) =
            CashPointResponse(view.id.value, view.name, view.blindClose, view.active, view.createdAt, view.updatedAt)
    }
}

@Schema(description = "A abertura de um turno de caixa do próprio usuário.")
data class CashierShiftOpenRequest(
    @field:NotNull
    val cashPointId: UUID?,
    @field:Schema(description = "Fundo de troco, em dinheiro (0,00 a 1.000.000,00).", example = "200.00")
    @field:NotNull
    val openingFloat: Money?,
)

@Schema(description = "Uma sangria ou um suprimento, em dinheiro.")
data class CashMovementRequest(
    @field:Schema(description = "`WITHDRAWAL` (sangria) ou `SUPPLY` (suprimento).")
    @field:NotNull
    val type: CashMovementType?,
    @field:Schema(description = "Valor positivo (até 1.000.000,00).", example = "150.00")
    @field:NotNull
    val amount: Money?,
    @field:Schema(description = "Motivo (até 200 caracteres).", example = "Recolhimento ao cofre")
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String?,
)

@Schema(description = "O valor declarado de uma forma de pagamento na contagem do fechamento.")
data class CashCountRequest(
    @field:NotNull
    val method: PaymentMethod?,
    @field:Schema(description = "Valor contado (0,00 a 1.000.000,00).", example = "350.00")
    @field:NotNull
    val amount: Money?,
) {
    fun toCount() = DeclaredCount(checkNotNull(method), checkNotNull(amount))
}

@Schema(description = "A contagem declarada no fechamento; a forma omitida vale zero.")
data class CashierShiftCloseRequest(
    @field:NotNull
    @field:Size(max = 20)
    val counts: List<@Valid CashCountRequest>?,
)

@Schema(description = "Uma sangria ou um suprimento registrado (imutável).")
data class CashMovementResponse(
    val id: UUID,
    val shiftId: UUID,
    val type: CashMovementType,
    val amount: Money,
    val reason: String,
    @field:Schema(description = "Data operacional do registro.")
    val businessDate: LocalDate,
    val recordedAt: Instant,
    val recordedBy: UUID,
) {
    companion object {
        fun of(movement: CashMovementView) =
            CashMovementResponse(
                movement.id.value,
                movement.shiftId.value,
                movement.type,
                movement.amount,
                movement.reason,
                movement.businessDate,
                movement.recordedAt,
                movement.recordedBy.value,
            )
    }
}

@Schema(
    description =
        "O valor esperado de uma forma de pagamento: no dinheiro, fundo + suprimentos − sangrias + pagamentos; nas " +
            "demais, os pagamentos.",
)
data class ExpectedAmountResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Forma de pagamento (trate um valor desconhecido).")
    val method: PaymentMethod,
    val openingFloat: Money,
    val supplies: Money,
    val withdrawals: Money,
    @field:Schema(description = "Pagamentos recebidos no turno nesta forma (os pagamentos chegam na E08).")
    val payments: Money,
    val expected: Money,
)

@Schema(description = "A contagem do fechamento de uma forma de pagamento.")
data class CashCountResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Forma de pagamento (trate um valor desconhecido).")
    val method: PaymentMethod,
    val declared: Money,
    val expected: Money,
    @field:Schema(description = "Declarado − esperado: positivo é sobra; negativo, falta.")
    val difference: Money,
)

@Schema(description = "Um turno de caixa numa listagem.")
data class CashierShiftListItem(
    val id: UUID,
    @field:Schema(description = "Número legível, como `CX-2026-000123`.")
    val number: DocumentNumber,
    val cashPointId: UUID,
    val operatorId: UUID,
    val status: CashierShiftStatus,
    val blindClose: Boolean,
    val openingFloat: Money,
    val openedOn: LocalDate,
    val openedAt: Instant,
    val closedOn: LocalDate?,
    val closedAt: Instant?,
    @field:Schema(description = "Soma das diferenças por forma (só depois do fechamento).")
    val differenceTotal: Money?,
) {
    companion object {
        fun of(view: CashierShiftListView) =
            CashierShiftListItem(
                view.id.value,
                view.number,
                view.cashPointId.value,
                view.operatorId.value,
                view.status,
                view.blindClose,
                view.openingFloat,
                view.openedOn,
                view.openedAt,
                view.closedOn,
                view.closedAt,
                view.differenceTotal,
            )
    }
}

@Schema(description = "Um turno de caixa, com os movimentos e, quando visível, o esperado.")
data class CashierShiftResponse(
    val id: UUID,
    @field:Schema(description = "Número legível, como `CX-2026-000123`.")
    val number: DocumentNumber,
    val cashPointId: UUID,
    val cashPointName: String,
    val operatorId: UUID,
    val status: CashierShiftStatus,
    @field:Schema(description = "Fechamento cego (o do ponto de caixa na abertura).")
    val blindClose: Boolean,
    val openingFloat: Money,
    @field:Schema(description = "Data operacional da abertura.")
    val openedOn: LocalDate,
    val openedAt: Instant,
    @field:Schema(description = "Data operacional do fechamento.")
    val closedOn: LocalDate?,
    val closedAt: Instant?,
    val movements: List<CashMovementResponse>,
    @field:Schema(
        description =
            "Se o valor esperado é visível a quem consulta: falso num turno cego aberto, salvo para quem tem " +
                "`billing.shifts.review`.",
    )
    val expectedVisible: Boolean,
    @field:Schema(description = "O esperado por forma de pagamento; nulo quando não é visível.")
    val expected: List<ExpectedAmountResponse>?,
    @field:Schema(description = "A contagem do fechamento por forma; nula num turno aberto.")
    val counts: List<CashCountResponse>?,
    @field:Schema(description = "Total esperado; nulo quando não é visível.")
    val expectedTotal: Money?,
    @field:Schema(description = "Total declarado (só depois do fechamento).")
    val declaredTotal: Money?,
    @field:Schema(description = "Soma das diferenças por forma (só depois do fechamento).")
    val differenceTotal: Money?,
    @field:Schema(description = "Alguma forma com diferença (só depois do fechamento).")
    val hasDifference: Boolean?,
) {
    companion object {
        fun of(view: CashierShiftView) =
            CashierShiftResponse(
                view.id.value,
                view.number,
                view.cashPointId.value,
                view.cashPointName,
                view.operatorId.value,
                view.status,
                view.blindClose,
                view.openingFloat,
                view.openedOn,
                view.openedAt,
                view.closedOn,
                view.closedAt,
                view.movements.map(CashMovementResponse::of),
                view.expectedVisible,
                view.expected?.map {
                    ExpectedAmountResponse(
                        it.method,
                        it.openingFloat,
                        it.supplies,
                        it.withdrawals,
                        it.payments,
                        it.expected,
                    )
                },
                view.counts?.map { CashCountResponse(it.method, it.declared, it.expected, it.difference) },
                view.expectedTotal,
                view.declaredTotal,
                view.differenceTotal,
                view.hasDifference,
            )
    }
}

@Schema(description = "O relatório de fechamento de um turno de caixa (em JSON; o PDF é do M5).")
data class CashierShiftReportResponse(
    val shift: CashierShiftResponse,
    val generatedAt: Instant,
) {
    companion object {
        fun of(report: CashierShiftReport) =
            CashierShiftReportResponse(CashierShiftResponse.of(report.shift), report.generatedAt)
    }
}
