package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Percent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/** Os dados editáveis de um código de lançamento. */
data class TransactionCodeDetails(
    val code: String,
    val name: String,
    val serviceChargeApplies: Boolean = false,
    val serviceChargeRate: Percent? = null,
    val fiscalClassification: String? = null,
)

/**
 * Código de lançamento (RF-CTA-02, SPEC §8.2.7). O código curto, em maiúsculas, é único (o banco garante). O tipo
 * ([kind]) e o grupo de receita ([revenueGroup]) são fixos desde a criação: mudar a natureza de um código já usado
 * reclassificaria os lançamentos antigos (para outra natureza, crie outro código e desative este). A taxa de serviço
 * só se aplica a um código de consumo ([TransactionCodeKind.CHARGE]); [serviceChargeRate] nulo cai na padrão do hotel
 * (H4) e só existe com a taxa aplicável. [fiscalClassification] é texto livre opcional até o M7 (H2). Os oito
 * pré-carregados pela migração têm a [key], que nunca muda. Nunca é apagado (o banco recusa), só desativado.
 */
@Entity
@Table(name = "transaction_code", schema = "billing")
class TransactionCode private constructor(
    @Id
    val id: TransactionCodeId,
    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", updatable = false)
    val key: TransactionCodeKey?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val kind: TransactionCodeKind,
    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_group", nullable = false, updatable = false)
    val revenueGroup: RevenueGroup,
    details: TransactionCodeDetails,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var code: String = ""
        protected set

    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(name = "service_charge_applies", nullable = false)
    var serviceChargeApplies: Boolean = false
        protected set

    @Column(name = "service_charge_rate")
    var serviceChargeRate: Percent? = null
        protected set

    @Column(name = "fiscal_classification")
    var fiscalClassification: String? = null
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    init {
        apply(details)
    }

    /** Troca os dados editáveis; devolve se algo mudou. */
    fun update(
        details: TransactionCodeDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa; devolve se mudou. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    /** Exige o código ativo para um lançamento novo. */
    fun requireActive() {
        if (!active) {
            throw BusinessRuleException(
                BillingErrorCodes.TRANSACTION_CODE_INACTIVE,
                "O código de lançamento $code está desativado.",
            )
        }
    }

    private fun apply(details: TransactionCodeDetails): Boolean {
        val code = BillingRules.code(details.code)
        val name = BillingRules.required("name", details.name, MAX_NAME_LENGTH)
        val fiscal = BillingRules.optional("fiscalClassification", details.fiscalClassification, MAX_FISCAL_LENGTH)
        val rate = details.serviceChargeRate
        requireCoherentServiceCharge(details.serviceChargeApplies, rate)
        val changed =
            code != this.code ||
                name != this.name ||
                details.serviceChargeApplies != serviceChargeApplies ||
                rate != serviceChargeRate ||
                fiscal != fiscalClassification
        this.code = code
        this.name = name
        serviceChargeApplies = details.serviceChargeApplies
        serviceChargeRate = rate
        fiscalClassification = fiscal
        return changed
    }

    /** A taxa de serviço só num código de consumo; a taxa própria só com ela aplicável, de 0 a 100%. */
    private fun requireCoherentServiceCharge(
        applies: Boolean,
        rate: Percent?,
    ) {
        val violation =
            when {
                applies && kind != TransactionCodeKind.CHARGE -> {
                    "serviceChargeApplies" to "A taxa de serviço só se aplica a códigos de consumo (CHARGE)."
                }

                rate != null && !applies -> {
                    "serviceChargeRate" to "Informe a taxa de serviço própria só num código com a taxa aplicável."
                }

                rate != null && (rate < Percent.ZERO || rate > Percent.HUNDRED) -> {
                    "serviceChargeRate" to "A taxa de serviço vai de 0% a 100%."
                }

                else -> {
                    null
                }
            }
        if (violation != null) {
            throw BillingRules.invalid(BillingErrorCodes.TRANSACTION_CODE_INVALID, violation.first, violation.second)
        }
    }

    override fun toString(): String = "TransactionCode(id=$id, code=$code)"

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_FISCAL_LENGTH = 60

        fun create(
            kind: TransactionCodeKind,
            revenueGroup: RevenueGroup,
            details: TransactionCodeDetails,
            now: Instant,
        ): TransactionCode = TransactionCode(TransactionCodeId.new(), null, kind, revenueGroup, details, now)
    }
}
