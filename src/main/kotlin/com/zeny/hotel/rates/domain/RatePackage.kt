package com.zeny.hotel.rates.domain

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.shared.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Os dados de um pacote, como chegam para criar ou editar. */
data class PackageDetails(
    val code: String,
    val name: String,
    val price: Money,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val transactionCodeId: TransactionCodeId,
)

/**
 * Pacote ou serviço vendido com a diária (RF-TAR-06, SPEC §8.2.5): café, estacionamento, transfer, decoração. Código
 * único em maiúsculas, nome, preço (`Money`) por noite, por estadia ou por pessoa/noite, ritmo de lançamento (diário
 * ou na chegada), se é incluso na diária ou adicional e o **código de lançamento** do `billing` em que é lançado
 * (sempre presente: o banco recusa um pacote sem ele). O preço por estadia só se lança na chegada. O código de
 * lançamento é conferido pelo caso de uso na `BillingApi` (existente, ativo e de consumo). Nunca é apagado, só
 * desativado, e só fora de planos ativos.
 */
@Entity
@Table(name = "rate_package", schema = "rates")
class RatePackage private constructor(
    @Id
    val id: PackageId,
    details: PackageDetails,
    createdAt: Instant,
) : RatesRecord(createdAt) {
    @Column(nullable = false)
    var code: String = ""
        protected set

    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(nullable = false)
    var price: Money = details.price
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var pricing: PackagePricing = details.pricing
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_rhythm", nullable = false)
    var postingRhythm: PackagePostingRhythm = details.postingRhythm
        protected set

    @Column(name = "included_in_rate", nullable = false)
    var includedInRate: Boolean = false
        protected set

    @Column(name = "transaction_code_id", nullable = false)
    var transactionCodeId: TransactionCodeId = details.transactionCodeId
        protected set

    init {
        apply(details)
    }

    val details: PackageDetails
        get() = PackageDetails(code, name, price, pricing, postingRhythm, includedInRate, transactionCodeId)

    /** Troca os dados; devolve se algo mudou. A conferência do código de lançamento é do caso de uso. */
    fun update(
        details: PackageDetails,
        now: Instant,
    ): Boolean = touched(apply(details), now)

    private fun apply(details: PackageDetails): Boolean {
        val valid = validate(details)
        val changed = valid != this.details
        code = valid.code
        name = valid.name
        price = valid.price
        pricing = valid.pricing
        postingRhythm = valid.postingRhythm
        includedInRate = valid.includedInRate
        transactionCodeId = valid.transactionCodeId
        return changed
    }

    override fun toString(): String = "RatePackage(id=$id, code=$code)"

    companion object {
        const val MAX_NAME_LENGTH = 80
        val MIN_PRICE: Money = Money.of("0.01")
        val MAX_PRICE: Money = Money.of("1000000.00")

        fun create(
            details: PackageDetails,
            now: Instant,
        ): RatePackage = RatePackage(PackageId.new(), details, now)

        /** Os dados válidos (código em maiúsculas, nome sem espaços nas pontas); senão `400`. */
        fun validate(details: PackageDetails): PackageDetails {
            val code = RatesRules.code(details.code)
            val name = RatesRules.required("name", details.name, MAX_NAME_LENGTH)
            if (details.price < MIN_PRICE || details.price > MAX_PRICE) {
                throw RatesRules.invalid(
                    RatesErrorCodes.PACKAGE_INVALID,
                    "price",
                    "Informe o preço de R$ 0,01 a R$ 1.000.000,00.",
                )
            }
            val perStay = details.pricing == PackagePricing.PER_STAY
            if (perStay && details.postingRhythm != PackagePostingRhythm.ON_ARRIVAL) {
                throw RatesRules.invalid(
                    RatesErrorCodes.PACKAGE_INVALID,
                    "postingRhythm",
                    "O pacote com preço por estadia é lançado uma vez, na chegada (ON_ARRIVAL).",
                )
            }
            return details.copy(code = code, name = name)
        }
    }
}
