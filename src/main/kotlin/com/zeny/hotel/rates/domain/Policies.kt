package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Os termos de uma política de cancelamento, como chegam para criar ou editar. */
data class CancellationTerms(
    val name: String,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent? = null,
    val penaltyNights: Int? = null,
    val penaltyAmount: Money? = null,
)

/**
 * Política de cancelamento (RF-TAR-07, SPEC §8.2.5): nome (único sem diferenciar maiúsculas), prazo em horas antes da
 * chegada (contado até o horário de check-in da data de chegada, no fuso do hotel, H15) e a multa, em percentual da
 * estadia, número de diárias ou valor fixo; só o valor do tipo é guardado (o banco repete a regra). Multa zero é
 * permitida (política sem multa).
 *
 * **Não é versionada** (decisão do usuário em 2026-10-20): a edição vale na hora para as reservas criadas depois; a
 * reserva copia os termos na criação (RN-15, M4 E07), então as existentes não mudam. Nunca é apagada, só desativada,
 * e só sem plano ativo que a use (o caso de uso confere).
 */
@Entity
@Table(name = "cancellation_policy", schema = "rates")
class CancellationPolicy private constructor(
    @Id
    val id: CancellationPolicyId,
    terms: CancellationTerms,
    createdAt: Instant,
) : RatesRecord(createdAt) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(name = "deadline_hours", nullable = false)
    var deadlineHours: Int = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false)
    var penaltyType: CancellationPenaltyType = terms.penaltyType
        protected set

    @Column(name = "penalty_percent")
    var penaltyPercent: Percent? = null
        protected set

    @Column(name = "penalty_nights")
    var penaltyNights: Int? = null
        protected set

    @Column(name = "penalty_amount")
    var penaltyAmount: Money? = null
        protected set

    init {
        apply(terms)
    }

    val terms: CancellationTerms
        get() = CancellationTerms(name, deadlineHours, penaltyType, penaltyPercent, penaltyNights, penaltyAmount)

    /** Troca os termos; devolve se algo mudou. */
    fun update(
        terms: CancellationTerms,
        now: Instant,
    ): Boolean = touched(apply(terms), now)

    private fun apply(terms: CancellationTerms): Boolean {
        val valid = validate(terms)
        val changed = valid != this.terms
        name = valid.name
        deadlineHours = valid.deadlineHours
        penaltyType = valid.penaltyType
        penaltyPercent = valid.penaltyPercent
        penaltyNights = valid.penaltyNights
        penaltyAmount = valid.penaltyAmount
        return changed
    }

    override fun toString(): String = "CancellationPolicy(id=$id, name=$name)"

    companion object {
        const val MAX_NAME_LENGTH = 80

        /** Um ano de antecedência, em horas. */
        const val MAX_DEADLINE_HOURS = 8760

        const val MAX_PENALTY_NIGHTS = 365

        val MAX_PENALTY_AMOUNT: Money = Money.of("1000000.00")

        fun create(
            terms: CancellationTerms,
            now: Instant,
        ): CancellationPolicy = CancellationPolicy(CancellationPolicyId.new(), terms, now)

        /** Os termos válidos (nome sem espaços nas pontas); senão `400`. */
        @Suppress("ThrowsCount") // uma recusa por campo
        fun validate(terms: CancellationTerms): CancellationTerms {
            val name = RatesRules.required("name", terms.name, MAX_NAME_LENGTH)
            if (terms.deadlineHours !in 0..MAX_DEADLINE_HOURS) {
                throw invalid("deadlineHours", "Informe o prazo de 0 a $MAX_DEADLINE_HOURS horas antes da chegada.")
            }
            val present =
                mapOf(
                    CancellationPenaltyType.PERCENT_OF_STAY to (terms.penaltyPercent != null),
                    CancellationPenaltyType.NIGHTS to (terms.penaltyNights != null),
                    CancellationPenaltyType.FIXED_AMOUNT to (terms.penaltyAmount != null),
                )
            val field =
                mapOf(
                    CancellationPenaltyType.PERCENT_OF_STAY to "penaltyPercent",
                    CancellationPenaltyType.NIGHTS to "penaltyNights",
                    CancellationPenaltyType.FIXED_AMOUNT to "penaltyAmount",
                )
            if (present[terms.penaltyType] != true) {
                throw invalid(field.getValue(terms.penaltyType), "Informe o valor da multa ${terms.penaltyType}.")
            }
            present.filter { (type, given) -> given && type != terms.penaltyType }.keys.firstOrNull()?.let {
                throw invalid(field.getValue(it), "Informe só o valor da multa do tipo ${terms.penaltyType}.")
            }
            terms.penaltyPercent?.let {
                if (it < Percent.ZERO || it > Percent.HUNDRED) {
                    throw invalid("penaltyPercent", "A multa vai de 0% a 100%.")
                }
            }
            terms.penaltyNights?.let {
                if (it !in 0..MAX_PENALTY_NIGHTS) {
                    throw invalid("penaltyNights", "Informe de 0 a $MAX_PENALTY_NIGHTS diárias.")
                }
            }
            terms.penaltyAmount?.let {
                if (it.isNegative() || it > MAX_PENALTY_AMOUNT) {
                    throw invalid("penaltyAmount", "Informe a multa de R$ 0,00 a R$ 1.000.000,00.")
                }
            }
            return terms.copy(name = name)
        }

        private fun invalid(
            field: String,
            message: String,
        ) = RatesRules.invalid(RatesErrorCodes.CANCELLATION_POLICY_INVALID, field, message)
    }
}

/** Os termos de uma política de garantia, como chegam para criar ou editar. */
data class GuaranteeTerms(
    val name: String,
    val type: GuaranteeType,
    val depositPercent: Percent? = null,
    val dueDaysBefore: Int? = null,
)

/**
 * Política de garantia (RF-TAR-07, SPEC §8.2.5): nome (único sem diferenciar maiúsculas) e o tipo: nenhuma, cartão ou
 * depósito de [depositPercent] da estadia até [dueDaysBefore] dias antes da chegada (os dois só no depósito; o banco
 * repete a regra). Como a de cancelamento, **não é versionada**: a reserva copia os termos (RN-15). Nunca é apagada.
 */
@Entity
@Table(name = "guarantee_policy", schema = "rates")
class GuaranteePolicy private constructor(
    @Id
    val id: GuaranteePolicyId,
    terms: GuaranteeTerms,
    createdAt: Instant,
) : RatesRecord(createdAt) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "guarantee_type", nullable = false)
    var type: GuaranteeType = terms.type
        protected set

    @Column(name = "deposit_percent")
    var depositPercent: Percent? = null
        protected set

    @Column(name = "due_days_before")
    var dueDaysBefore: Int? = null
        protected set

    init {
        apply(terms)
    }

    val terms: GuaranteeTerms get() = GuaranteeTerms(name, type, depositPercent, dueDaysBefore)

    /** Troca os termos; devolve se algo mudou. */
    fun update(
        terms: GuaranteeTerms,
        now: Instant,
    ): Boolean = touched(apply(terms), now)

    private fun apply(terms: GuaranteeTerms): Boolean {
        val valid = validate(terms)
        val changed = valid != this.terms
        name = valid.name
        type = valid.type
        depositPercent = valid.depositPercent
        dueDaysBefore = valid.dueDaysBefore
        return changed
    }

    override fun toString(): String = "GuaranteePolicy(id=$id, name=$name)"

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_DUE_DAYS_BEFORE = 365

        fun create(
            terms: GuaranteeTerms,
            now: Instant,
        ): GuaranteePolicy = GuaranteePolicy(GuaranteePolicyId.new(), terms, now)

        /** Os termos válidos; senão `400`. */
        @Suppress("ThrowsCount") // uma recusa por campo
        fun validate(terms: GuaranteeTerms): GuaranteeTerms {
            val name = RatesRules.required("name", terms.name, MAX_NAME_LENGTH)
            if (terms.type == GuaranteeType.DEPOSIT) {
                val percent =
                    terms.depositPercent ?: throw invalid("depositPercent", "Informe o percentual do depósito.")
                if (percent <= Percent.ZERO || percent > Percent.HUNDRED) {
                    throw invalid("depositPercent", "O depósito vai de mais de 0% a 100% da estadia.")
                }
                val days = terms.dueDaysBefore ?: throw invalid("dueDaysBefore", "Informe o prazo do depósito.")
                if (days !in 0..MAX_DUE_DAYS_BEFORE) {
                    throw invalid("dueDaysBefore", "Informe o prazo de 0 a $MAX_DUE_DAYS_BEFORE dias antes da chegada.")
                }
            } else {
                if (terms.depositPercent != null) {
                    throw invalid("depositPercent", "O percentual do depósito só vale na garantia DEPOSIT.")
                }
                if (terms.dueDaysBefore != null) {
                    throw invalid("dueDaysBefore", "O prazo do depósito só vale na garantia DEPOSIT.")
                }
            }
            return terms.copy(name = name)
        }

        private fun invalid(
            field: String,
            message: String,
        ) = RatesRules.invalid(RatesErrorCodes.GUARANTEE_POLICY_INVALID, field, message)
    }
}
