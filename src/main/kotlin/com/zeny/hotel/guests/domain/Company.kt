package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/** Os dados de uma empresa como chegam para criar ou editar (RF-HOS-06), com o endereço já montado. */
data class CompanyDetails(
    val legalName: String,
    val tradeName: String? = null,
    val cnpj: Cnpj,
    val stateRegistration: String? = null,
    val municipalRegistration: String? = null,
    val address: Address? = null,
    val contactName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val billingEmail: String? = null,
    val creditLimit: Money? = null,
)

/**
 * Empresa, pessoa jurídica (RF-HOS-06, SPEC §8.2.4): pagadora de contas, tomadora de nota, cliente de eventos
 * e devedora em contas a receber (M10). O CNPJ é único sempre (decisão do usuário em 2026-10-16; o banco
 * garante): a desativada continua dona dele e é reativada, não recadastrada. [creditLimit] nulo: sem crédito.
 * Nunca é apagada (o banco recusa), só desativada.
 */
@Entity
@Table(name = "company", schema = "guests")
class Company private constructor(
    @Id
    val id: CompanyId,
    details: CompanyDetails,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "legal_name", nullable = false)
    var legalName: String = ""
        protected set

    @Column(name = "trade_name")
    var tradeName: String? = null
        protected set

    @Column(name = "search_name", nullable = false)
    private var searchName: String = ""

    @Column(nullable = false)
    var cnpj: Cnpj = details.cnpj
        protected set

    @Column(name = "state_registration")
    var stateRegistration: String? = null
        protected set

    @Column(name = "municipal_registration")
    var municipalRegistration: String? = null
        protected set

    @Embedded
    var address: Address? = null
        protected set

    @Column(name = "contact_name")
    var contactName: String? = null
        protected set

    var phone: String? = null
        protected set

    var email: String? = null
        protected set

    @Column(name = "billing_email")
    var billingEmail: String? = null
        protected set

    @Column(name = "credit_limit")
    var creditLimit: Money? = null
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

    /** Troca os dados; devolve os campos que mudaram (para a trilha). */
    fun update(
        details: CompanyDetails,
        now: Instant,
    ): List<String> {
        val changed = apply(details)
        if (changed.isNotEmpty()) updatedAt = now
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

    private fun apply(details: CompanyDetails): List<String> {
        val legalName = GuestsRules.required("legalName", details.legalName, MAX_NAME_LENGTH)
        val tradeName = GuestsRules.optional("tradeName", details.tradeName, MAX_NAME_LENGTH)
        val stateRegistration =
            GuestsRules.optional("stateRegistration", details.stateRegistration, MAX_REGISTRATION_LENGTH)
        val municipalRegistration =
            GuestsRules.optional("municipalRegistration", details.municipalRegistration, MAX_REGISTRATION_LENGTH)
        val contactName = GuestsRules.optional("contactName", details.contactName, MAX_NAME_LENGTH)
        val phone = GuestsRules.phone("phone", details.phone)
        val email = GuestsRules.email("email", details.email)
        val billingEmail = GuestsRules.email("billingEmail", details.billingEmail)
        if (details.creditLimit?.isNegative() == true) {
            throw GuestsRules.invalid(
                GuestsErrorCodes.GUESTS_DATA_INVALID,
                "creditLimit",
                "O limite de crédito não pode ser negativo.",
            )
        }
        val changes = ChangedFields()
        changes.set("legalName", this.legalName, legalName) { this.legalName = it }
        changes.set("tradeName", this.tradeName, tradeName) { this.tradeName = it }
        changes.set("cnpj", cnpj, details.cnpj) { cnpj = it }
        changes.set("stateRegistration", this.stateRegistration, stateRegistration) { this.stateRegistration = it }
        changes.set("municipalRegistration", this.municipalRegistration, municipalRegistration) {
            this.municipalRegistration = it
        }
        changes.set("address", address, details.address) { address = it }
        changes.set("contactName", this.contactName, contactName) { this.contactName = it }
        changes.set("phone", this.phone, phone) { this.phone = it }
        changes.set("email", this.email, email) { this.email = it }
        changes.set("billingEmail", this.billingEmail, billingEmail) { this.billingEmail = it }
        changes.set("creditLimit", creditLimit, details.creditLimit) { creditLimit = it }
        searchName = GuestsRules.searchKey(this.legalName, this.tradeName)
        return changes.fields
    }

    override fun toString(): String = "Company(id=$id)"

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_REGISTRATION_LENGTH = 30

        fun create(
            details: CompanyDetails,
            now: Instant,
        ): Company = Company(CompanyId.new(), details, now)
    }
}
