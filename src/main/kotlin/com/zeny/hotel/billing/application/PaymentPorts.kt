package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.shared.Money
import java.time.Instant
import java.util.UUID

/** Os pagamentos e reembolsos, só inseridos e lidos (RN-03, M3 E08). */
interface PaymentLedger {
    fun add(payment: Payment)

    fun payment(id: PaymentId): Payment?

    /** Os reembolsos do recebimento, em ordem de registro. */
    fun refundsOf(id: PaymentId): List<Payment>

    /** Se algum recebimento já nasceu da intenção de pagamento (o listener de `PaymentSucceeded` a ignora). */
    fun existsForIntent(intentId: UUID): Boolean

    /** Os pagamentos e reembolsos da conta em ordem de registro, depois da posição, com o total reembolsado. */
    fun page(
        folioId: FolioId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<PaymentRow>

    fun row(id: PaymentId): PaymentRow?

    /**
     * O que o turno recebeu por forma de pagamento: os recebimentos menos os reembolsos registrados nele (só as formas
     * com algum registro).
     */
    fun shiftTotals(shiftId: CashierShiftId): Map<PaymentMethod, Money>
}

/** Um pagamento com o total já reembolsado dele (só no recebimento; nulo no reembolso). */
data class PaymentRow(
    val payment: Payment,
    val refunded: Money?,
)
