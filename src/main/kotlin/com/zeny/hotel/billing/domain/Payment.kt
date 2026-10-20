package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.sum
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O que quem opera informa da transação feita fora do sistema (RF-PAG-03, RNF-10): o NSU e a autorização (fora do
 * dinheiro) e, no cartão, a bandeira e os 4 últimos dígitos. **Nunca** o número do cartão nem o CVV: não há campo para
 * eles, e o NSU e a autorização com cara de número de cartão (13 dígitos ou mais) são recusados.
 */
data class PaymentReferences(
    val nsu: String? = null,
    val authorizationCode: String? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
) {
    /** A referência que vai à intenção de pagamento (o NSU, senão a autorização). */
    val providerReference: String? get() = nsu ?: authorizationCode

    companion object {
        const val MAX_REFERENCE_LENGTH = 20

        /** Menos dígitos que o menor número de cartão (13). */
        const val MAX_REFERENCE_DIGITS = 12
        const val MAX_BRAND_LENGTH = 20

        private val REFERENCE = Regex("^[A-Z0-9]{1,$MAX_REFERENCE_LENGTH}$")
        private val BRAND = Regex("^[A-Z][A-Z ]{0,${MAX_BRAND_LENGTH - 1}}$")
        private val LAST4 = Regex("^[0-9]{4}$")

        /**
         * Confere e normaliza (maiúsculas, sem espaços nas pontas; vazio vira nulo) os dados da transação para a
         * [method]: no dinheiro, nenhum; NSU e autorização com até 20 letras e dígitos e no máximo 12 dígitos; bandeira
         * (só letras e espaços, até 20) e últimos dígitos (exatamente 4) só no cartão. Fora disso,
         * `400 PAYMENT_INVALID` (ou o [errorCode] de quem chama, como a caução) com o campo.
         */
        @Suppress("ThrowsCount", "LongParameterList") // uma recusa por campo; os dados e o código da recusa
        fun of(
            method: PaymentMethod,
            nsu: String?,
            authorizationCode: String?,
            cardBrand: String? = null,
            cardLast4: String? = null,
            errorCode: ErrorCode = BillingErrorCodes.PAYMENT_INVALID,
        ): PaymentReferences {
            fun invalid(
                field: String,
                message: String,
            ) = BillingRules.invalid(errorCode, field, message)

            val references =
                PaymentReferences(
                    reference("nsu", normalize(nsu), errorCode),
                    reference("authorizationCode", normalize(authorizationCode), errorCode),
                    normalize(cardBrand)?.replace(Regex(" +"), " ")?.also {
                        if (!BRAND.matches(it)) {
                            throw invalid("cardBrand", "A bandeira do cartão usa só letras e espaços (até 20).")
                        }
                    },
                    normalize(cardLast4)?.also {
                        if (!LAST4.matches(it)) {
                            throw invalid("cardLast4", "Informe exatamente os 4 últimos dígitos do cartão.")
                        }
                    },
                )
            if (method == PaymentMethod.CASH) {
                if (references.nsu != null) throw invalid("nsu", "Pagamento em dinheiro não tem NSU.")
                if (references.authorizationCode != null) {
                    throw invalid("authorizationCode", "Pagamento em dinheiro não tem autorização.")
                }
            }
            if (method !in CARD_METHODS) {
                if (references.cardBrand != null) throw invalid("cardBrand", "A bandeira é só de pagamento com cartão.")
                if (references.cardLast4 != null) {
                    throw invalid("cardLast4", "Os últimos dígitos são só de pagamento com cartão.")
                }
            }
            return references
        }

        val CARD_METHODS = setOf(PaymentMethod.CREDIT_CARD, PaymentMethod.DEBIT_CARD)

        private fun normalize(value: String?): String? = value?.trim()?.uppercase()?.ifEmpty { null }

        private fun reference(
            field: String,
            value: String?,
            errorCode: ErrorCode,
        ): String? {
            if (value == null) return null
            if (!REFERENCE.matches(value) || value.count(Char::isDigit) > MAX_REFERENCE_DIGITS) {
                throw BillingRules.invalid(
                    errorCode,
                    field,
                    "`$field` aceita até 20 letras e dígitos, com no máximo 12 dígitos (nunca o número do cartão).",
                )
            }
            return value
        }
    }
}

/**
 * Um recebimento conferido, antes de existir (o formato vem antes do turno e da conta): a forma, o valor aplicado na
 * conta, o valor entregue em dinheiro (para o troco), se é depósito e os dados da transação.
 */
data class PaymentDraft(
    val method: PaymentMethod,
    val amount: Money,
    val tendered: Money?,
    val deposit: Boolean,
    val references: PaymentReferences,
) {
    /** O troco em dinheiro: o entregue menos o aplicado; nulo sem valor entregue. */
    val change: Money? get() = tendered?.let { it - amount }

    companion object {
        /** O valor de um pagamento ou reembolso, do valor entregue e do total reembolsado vai até 1.000.000,00. */
        val MAX_AMOUNT: Money = Money.of("1000000.00")

        /**
         * Confere o recebimento (decisões do usuário em 2026-10-19): a forma ([PaymentMethods.accepted]); o valor
         * aplicado de 0,01 a 1.000.000,00 (pode passar do saldo devedor: a conta fica credora); o troco só no dinheiro,
         * com o valor entregue maior ou igual ao aplicado; os dados da transação ([PaymentReferences.of]).
         */
        @Suppress("LongParameterList") // os campos do recebimento
        fun of(
            method: String,
            amount: Money,
            tendered: Money?,
            deposit: Boolean,
            nsu: String?,
            authorizationCode: String?,
            cardBrand: String?,
            cardLast4: String?,
        ): PaymentDraft {
            val accepted = PaymentMethods.accepted(method)
            requireAmount("amount", amount)
            if (tendered != null) {
                if (accepted != PaymentMethod.CASH) {
                    throw invalid("tendered", "O valor entregue (troco) é só de pagamento em dinheiro.")
                }
                if (tendered < amount || tendered > MAX_AMOUNT) {
                    throw invalid(
                        "tendered",
                        "O valor entregue vai do valor do pagamento a 1.000.000,00 (o troco é o entregue menos o " +
                            "pagamento).",
                    )
                }
            }
            val references = PaymentReferences.of(accepted, nsu, authorizationCode, cardBrand, cardLast4)
            return PaymentDraft(accepted, amount, tendered, deposit, references)
        }

        fun requireAmount(
            field: String,
            amount: Money,
        ) {
            if (!amount.isPositive() || amount > MAX_AMOUNT) {
                throw invalid(field, "O valor vai de 0,01 a 1.000.000,00.")
            }
        }
    }
}

/** As formas de pagamento aceitas no recebimento. */
object PaymentMethods {
    /** Faturado para contas a receber (RF-CTA-13): só no M10. */
    const val CITY_LEDGER = "CITY_LEDGER"

    /**
     * A forma pedida: uma de [PaymentMethod]; `CITY_LEDGER` é `422 PAYMENT_METHOD_NOT_AVAILABLE` até o M10; outra,
     * `400 PAYMENT_INVALID` (campo `method`).
     */
    fun accepted(name: String): PaymentMethod {
        if (name == CITY_LEDGER) {
            throw BusinessRuleException(
                BillingErrorCodes.PAYMENT_METHOD_NOT_AVAILABLE,
                "O pagamento faturado (contas a receber) ainda não está disponível.",
            )
        }
        return PaymentMethod.entries.firstOrNull { it.name == name }
            ?: throw invalid("method", "Forma de pagamento desconhecida.")
    }
}

/**
 * Um pagamento ou um reembolso na conta (RF-CTA-06, 07 e 15, RN-03, SPEC §8.2.7): fato imutável, só inserido (o banco
 * recusa mudança e exclusão). [amount] é sempre positivo; o [type] diz o sentido ([received]). Todo registro sai do
 * turno de caixa aberto de quem recebe ou devolve ([shiftId], RF-CTA-10). O recebimento nasce de uma intenção de
 * pagamento confirmada ([paymentIntentId], H1); o reembolso cita o recebimento ([refundOfId]), na mesma forma, com
 * motivo e, se houve, a aprovação de superior. [deposit] marca o depósito (sinal antes da chegada, RF-CTA-07),
 * informado por quem opera; o reembolso de um depósito também é marcado.
 */
data class Payment(
    val id: PaymentId,
    val folioId: FolioId,
    val type: PaymentType,
    val method: PaymentMethod,
    val amount: Money,
    val tendered: Money?,
    val deposit: Boolean,
    val references: PaymentReferences,
    val paymentIntentId: UUID?,
    val refundOfId: PaymentId?,
    val reason: String?,
    val shiftId: CashierShiftId,
    val businessDate: LocalDate,
    val receivedAt: Instant,
    val receivedBy: UserId,
    val approval: PostingApproval?,
    val preAuthorizationId: CardPreAuthorizationId? = null,
) {
    /** O troco entregue (só num recebimento em dinheiro com o valor entregue). */
    val change: Money? get() = tendered?.let { it - amount }

    /** O efeito no que a conta recebeu e no esperado do turno: o recebimento soma, o reembolso tira. */
    val received: Money get() = if (type == PaymentType.PAYMENT) amount else -amount

    override fun toString(): String = "Payment(id=$id, folioId=$folioId, type=$type, method=$method)"

    companion object {
        const val MAX_REASON_LENGTH = 200

        /**
         * O recebimento na conta (aberta), no turno aberto de quem recebe, da intenção já confirmada; numa captura de
         * caução (E09), com a caução capturada.
         */
        @Suppress("LongParameterList") // os dados do recebimento
        fun receive(
            folio: Folio,
            draft: PaymentDraft,
            shift: CashierShift,
            paymentIntentId: UUID,
            stamp: PostingStamp,
            preAuthorizationId: CardPreAuthorizationId? = null,
        ): Payment {
            folio.requireOpen()
            val receivedBy = checkNotNull(stamp.postedBy) { "Recebimento sem usuário" }
            shift.requireOperator(receivedBy)
            shift.requireOpen()
            return Payment(
                PaymentId.new(),
                folio.id,
                PaymentType.PAYMENT,
                draft.method,
                draft.amount,
                draft.tendered,
                draft.deposit,
                draft.references,
                paymentIntentId,
                null,
                null,
                shift.id,
                stamp.businessDate,
                stamp.postedAt,
                receivedBy,
                null,
                preAuthorizationId,
            )
        }

        fun reason(reason: String?): String =
            BillingRules.required("reason", reason, MAX_REASON_LENGTH, BillingErrorCodes.PAYMENT_INVALID)
    }
}

/**
 * Um recebimento com os reembolsos dele (RF-CTA-15): o reembolsável é o valor pago menos os reembolsos anteriores
 * (decisão do usuário em 2026-10-19: sempre na mesma forma do recebimento).
 */
class PaymentRefunds(
    val original: Payment,
    refunds: List<Payment>,
) {
    val refunded: Money = refunds.filter { it.refundOfId == original.id }.map { it.amount }.sum()

    val refundable: Money get() = original.amount - refunded

    /**
     * Confere o reembolso de [amount]: só de um recebimento (`422 PAYMENT_NOT_REFUNDABLE`) e até o reembolsável
     * (`422 REFUND_EXCEEDS_REFUNDABLE`). Devolve o total reembolsado com este, que o limite `maxAmount` confere.
     */
    fun requireRefundable(amount: Money): Money {
        if (original.type != PaymentType.PAYMENT) {
            throw BusinessRuleException(
                BillingErrorCodes.PAYMENT_NOT_REFUNDABLE,
                "Só um recebimento é reembolsado, nunca um reembolso.",
            )
        }
        if (amount > refundable) {
            throw BusinessRuleException(
                BillingErrorCodes.REFUND_EXCEEDS_REFUNDABLE,
                "O reembolso passa do que ainda é reembolsável deste pagamento " +
                    "(R$ ${refundable.amount.toPlainString()}).",
            )
        }
        return refunded + amount
    }

    /**
     * O reembolso na mesma conta (aberta) e na mesma forma do recebimento, no turno aberto de quem devolve, com o
     * motivo, os dados da devolução feita fora (NSU e autorização, fora do dinheiro; a bandeira e os últimos dígitos
     * vêm do recebimento) e a aprovação de superior, se houve.
     */
    @Suppress("LongParameterList") // os dados do reembolso
    fun refund(
        folio: Folio,
        amount: Money,
        reason: String,
        references: PaymentReferences,
        shift: CashierShift,
        stamp: PostingStamp,
        approval: PostingApproval?,
    ): Payment {
        folio.requireOpen()
        check(original.folioId == folio.id) { "Reembolso ${original.id} em outra conta" }
        requireRefundable(amount)
        val refundedBy = checkNotNull(stamp.postedBy) { "Reembolso sem usuário" }
        shift.requireOperator(refundedBy)
        shift.requireOpen()
        return Payment(
            PaymentId.new(),
            folio.id,
            PaymentType.REFUND,
            original.method,
            amount,
            null,
            original.deposit,
            references.copy(
                cardBrand = original.references.cardBrand,
                cardLast4 = original.references.cardLast4,
            ),
            null,
            original.id,
            reason,
            shift.id,
            stamp.businessDate,
            stamp.postedAt,
            refundedBy,
            approval,
        )
    }
}

private fun invalid(
    field: String,
    message: String,
) = BillingRules.invalid(BillingErrorCodes.PAYMENT_INVALID, field, message)
