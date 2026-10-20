package com.zeny.hotel.payments.domain

import com.zeny.hotel.payments.PaymentIntentId
import com.zeny.hotel.payments.PaymentIntentStatus
import com.zeny.hotel.payments.PaymentProviderCode
import com.zeny.hotel.payments.PaymentPurpose
import com.zeny.hotel.payments.PaymentTarget
import com.zeny.hotel.payments.PaymentsErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Intenção de pagamento (SPEC §8.2.8, H1 do M3): a finalidade e o destino opacos, o valor e o provedor; nasce
 * pendente e o provedor a confirma ([markSucceeded]) ou recusa ([markFailed]). Confirmada ou recusada, não muda mais
 * (o banco recusa). Nunca é apagada. Não guarda dado de cartão: só a referência curta do provedor.
 */
@Entity
@Table(name = "payment_intent", schema = "payments")
@Suppress("LongParameterList") // os dados fixos da intenção
class PaymentIntent private constructor(
    @Id
    val id: PaymentIntentId,
    @Column(name = "purpose", nullable = false, updatable = false)
    private val purposeValue: String,
    @Column(name = "target_type", nullable = false, updatable = false)
    private val targetType: String,
    @Column(name = "target_id", nullable = false, updatable = false)
    private val targetId: UUID,
    @Column(nullable = false, updatable = false)
    val amount: Money,
    @Column(name = "provider", nullable = false, updatable = false)
    private val providerValue: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "created_by", updatable = false)
    private val createdByValue: UUID?,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentIntentStatus = PaymentIntentStatus.PENDING
        protected set

    @Column(name = "provider_reference")
    var providerReference: String? = null
        protected set

    @Column(name = "succeeded_at")
    var succeededAt: Instant? = null
        protected set

    @Column(name = "failed_at")
    var failedAt: Instant? = null
        protected set

    @Column(name = "failure_reason")
    var failureReason: String? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val purpose: PaymentPurpose get() = PaymentPurpose(purposeValue)

    val target: PaymentTarget get() = PaymentTarget(targetType, targetId)

    val provider: PaymentProviderCode get() = PaymentProviderCode(providerValue)

    val createdBy: UserId? get() = createdByValue?.let(::UserId)

    /**
     * O provedor confirmou, com a referência dele (no manual, a informada). `true` se a intenção passou a confirmada;
     * `false` se já estava (a confirmação repetida do provedor não muda nada). Recusada antes: `IllegalStateException`.
     */
    fun markSucceeded(
        reference: String?,
        now: Instant,
    ): Boolean {
        if (status == PaymentIntentStatus.SUCCEEDED) return false
        check(status == PaymentIntentStatus.PENDING) { "Intenção $id já recusada não é confirmada" }
        providerReference = reference(reference)
        succeededAt = now
        status = PaymentIntentStatus.SUCCEEDED
        return true
    }

    /** O provedor recusou (M7). Já confirmada: `IllegalStateException`; já recusada: nada muda. */
    fun markFailed(
        reason: String,
        now: Instant,
    ): Boolean {
        if (status == PaymentIntentStatus.FAILED) return false
        check(status == PaymentIntentStatus.PENDING) { "Intenção $id já confirmada não é recusada" }
        failureReason = reason.trim().take(MAX_FAILURE_REASON_LENGTH).ifEmpty { "Recusada pelo provedor" }
        failedAt = now
        status = PaymentIntentStatus.FAILED
        return true
    }

    /** O provedor ainda vai confirmar (M7): guarda a referência da cobrança. */
    fun markPending(reference: String?) {
        check(status == PaymentIntentStatus.PENDING) { "Intenção $id já concluída" }
        providerReference = reference(reference)
    }

    override fun toString(): String = "PaymentIntent(id=$id, status=$status)"

    companion object {
        const val MAX_REFERENCE_LENGTH = 40
        const val MAX_FAILURE_REASON_LENGTH = 200

        /** Menos dígitos que o menor número de cartão (13): uma referência nunca carrega um cartão (RNF-10). */
        const val MAX_REFERENCE_DIGITS = 12

        private val REFERENCE = Regex("^[A-Za-z0-9._-]{1,$MAX_REFERENCE_LENGTH}$")

        /** Uma intenção nova, pendente, com o valor positivo. */
        @Suppress("LongParameterList") // os dados da intenção
        fun start(
            purpose: PaymentPurpose,
            target: PaymentTarget,
            amount: Money,
            provider: PaymentProviderCode,
            createdBy: UserId?,
            now: Instant,
        ): PaymentIntent {
            if (!amount.isPositive()) throw invalid("amount", "O valor da intenção de pagamento precisa ser positivo.")
            return PaymentIntent(
                PaymentIntentId.new(),
                purpose.value,
                target.type,
                target.id,
                amount,
                provider.value,
                now,
                createdBy?.value,
            )
        }

        /**
         * A referência do provedor normalizada (sem espaços nas pontas; vazia vira nula): até 40 letras, dígitos, `.`,
         * `_` ou `-`, com no máximo 12 dígitos (uma sequência de 13 a 19 dígitos tem cara de número de cartão).
         */
        fun reference(value: String?): String? {
            val trimmed = value?.trim()?.ifEmpty { null } ?: return null
            if (!REFERENCE.matches(trimmed) || trimmed.count(Char::isDigit) > MAX_REFERENCE_DIGITS) {
                throw invalid(
                    "reference",
                    "A referência do pagamento aceita até 40 letras, dígitos, `.`, `_` ou `-`, com no máximo 12 " +
                        "dígitos (nunca o número do cartão).",
                )
            }
            return trimmed
        }

        private fun invalid(
            field: String,
            message: String,
        ) = InvalidInputException(
            PaymentsErrorCodes.PAYMENT_INTENT_INVALID,
            message,
            listOf(FieldViolation(field, PaymentsErrorCodes.PAYMENT_INTENT_INVALID.value, message)),
        )
    }
}
