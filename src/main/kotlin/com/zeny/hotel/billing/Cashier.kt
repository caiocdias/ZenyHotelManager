package com.zeny.hotel.billing

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Id de um ponto de caixa (M3 E07). */
@JvmInline
value class CashPointId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CashPointId = CashPointId(UuidV7.generate())
    }
}

/** Id de um turno de caixa (M3 E07). */
@JvmInline
value class CashierShiftId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CashierShiftId = CashierShiftId(UuidV7.generate())
    }
}

/** Id de um movimento de caixa (sangria ou suprimento, M3 E07). */
@JvmInline
value class CashMovementId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CashMovementId = CashMovementId(UuidV7.generate())
    }
}

/**
 * Forma de pagamento (SPEC §8.2.7; decisão do usuário em 2026-10-19): lista fixa e enxuta, usada na contagem do
 * fechamento do turno (M3 E07) e nos pagamentos (E08). No contrato, nas respostas, é `x-extensible-enum`: o cliente
 * trata um valor desconhecido (o `CITY_LEDGER` das contas a receber entra no M10). A gaveta física só confere
 * dinheiro ([CASH]); as demais formas são conferidas contra o relatório do turno.
 */
enum class PaymentMethod(
    /** O nome em pt-BR, para o relatório e a notificação. */
    val label: String,
) {
    CASH("Dinheiro"),
    DEBIT_CARD("Cartão de débito"),
    CREDIT_CARD("Cartão de crédito"),
    PIX("Pix"),
    BANK_TRANSFER("Transferência bancária"),
    OTHER("Outra forma"),
}

/** Situação de um turno de caixa. Um turno fechado não reabre (fora do M3). */
enum class CashierShiftStatus {
    OPEN,
    CLOSED,
}

/** Tipo de movimento de caixa (RF-CTA-09), sempre em dinheiro (decisão do usuário em 2026-10-19). */
enum class CashMovementType {
    /** Sangria: dinheiro retirado da gaveta (vai ao cofre ou à tesouraria). */
    WITHDRAWAL,

    /** Suprimento: dinheiro posto na gaveta (troco). */
    SUPPLY,
}

/**
 * Um turno de caixa para os módulos de cima e os pagamentos (E08): o ponto, o operador, a situação e a abertura.
 * Nunca traz o valor esperado (o fechamento cego o esconde do operador até a contagem).
 */
data class CashierShiftSummary(
    val id: CashierShiftId,
    val number: DocumentNumber,
    val cashPointId: CashPointId,
    val operatorId: UserId,
    val status: CashierShiftStatus,
    val openedOn: LocalDate,
    val openedAt: Instant,
    val openingFloat: Money,
)

/**
 * Os tipos de notificação do `billing` (H14 do M3, M3 E07): a diferença no fechamento de um turno de caixa, para quem
 * confere os turnos (`billing.shifts.review`). Não é obrigatório no painel (a preferência o desliga).
 */
object BillingNotificationTypes : ModuleNotificationTypes {
    override val module = "billing"

    /** Turno fechado com diferença em alguma forma de pagamento. */
    val CASHIER_SHIFT_DIFFERENCE = NotificationTypeCode("billing.cashier-shift-difference")

    override val types =
        listOf(NotificationType(CASHIER_SHIFT_DIFFERENCE, "Diferença no fechamento de um turno de caixa"))
}
