package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
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
import java.time.LocalDate
import java.util.UUID

/**
 * Conta (folio) por pagador (RF-CTA-01, SPEC §8.2.7). O dono é um [FolioOwner] opaco, fixo desde a abertura; o
 * pagador (empresa) e o limite de crédito mudam. O saldo não fica aqui: é a soma dos lançamentos, fatos imutáveis
 * guardados à parte ([Posting]); o que muda é só o estado da conta (situação, pagador, limite) e a versão, que cada
 * lançamento também avança (o `ETag` da conta muda com o saldo). Nunca é apagada (o banco recusa).
 */
@Entity
@Table(name = "folio", schema = "billing")
@Suppress("LongParameterList") // os dados fixos da abertura
class Folio private constructor(
    @Id
    val id: FolioId,
    @Column(nullable = false, updatable = false)
    val number: DocumentNumber,
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false)
    val ownerType: FolioOwnerType,
    @Column(name = "owner_id", nullable = false, updatable = false)
    val ownerId: UUID,
    @Column(name = "opened_on", nullable = false, updatable = false)
    val openedOn: LocalDate,
    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant,
    @Column(name = "opened_by", updatable = false)
    private val openedByValue: UUID?,
) {
    @Column(name = "payer_company_id")
    private var payerCompanyValue: UUID? = null

    val openedBy: UserId? get() = openedByValue?.let(::UserId)

    val payerCompanyId: CompanyId? get() = payerCompanyValue?.let(::CompanyId)

    @Column(name = "credit_limit")
    var creditLimit: Money? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: FolioStatus = FolioStatus.OPEN
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = openedAt
        protected set

    @Version
    var version: Long? = null
        protected set

    val owner: FolioOwner get() = FolioOwner(ownerType, ownerId)

    /** Troca o pagador e o limite de crédito; devolve os campos que mudaram. */
    fun update(
        payerCompanyId: CompanyId?,
        creditLimit: Money?,
        now: Instant,
    ): List<String> {
        requireValidCreditLimit(creditLimit)
        val changed =
            buildList {
                if (payerCompanyId != this@Folio.payerCompanyId) add("payerCompanyId")
                if (creditLimit != this@Folio.creditLimit) add("creditLimit")
            }
        payerCompanyValue = payerCompanyId?.value
        this.creditLimit = creditLimit
        if (changed.isNotEmpty()) updatedAt = now
        return changed
    }

    /** Exige a conta aberta para lançar ou corrigir. */
    fun requireOpen() {
        if (status != FolioStatus.OPEN) {
            throw ConflictException(BillingErrorCodes.FOLIO_CLOSED, "A conta $number está encerrada.")
        }
    }

    /** Exige a conta aberta como destino de roteamento (de uma regra nova ou de um lançamento roteado). */
    fun requireOpenAsRoutingTarget() {
        if (status != FolioStatus.OPEN) {
            throw ConflictException(
                BillingErrorCodes.ROUTING_TARGET_CLOSED,
                "A conta de destino $number do roteamento está encerrada.",
            )
        }
    }

    override fun toString(): String = "Folio(id=$id, number=$number)"

    companion object {
        /**
         * Abre uma conta. [ownerId] nulo só com [FolioOwnerType.WALK_IN]: o dono passa a ser a própria conta.
         */
        @Suppress("LongParameterList") // os dados da abertura
        fun open(
            number: DocumentNumber,
            ownerType: FolioOwnerType,
            ownerId: UUID?,
            payerCompanyId: CompanyId?,
            creditLimit: Money?,
            businessDate: LocalDate,
            openedBy: UserId?,
            now: Instant,
        ): Folio {
            val id = FolioId.new()
            val owner =
                ownerId
                    ?: if (ownerType == FolioOwnerType.WALK_IN) {
                        id.value
                    } else {
                        throw BillingRules.invalid(
                            BillingErrorCodes.FOLIO_OWNER_INVALID,
                            "owner.id",
                            "Informe o id do dono da conta (só a conta avulsa dispensa).",
                        )
                    }
            requireValidCreditLimit(creditLimit)
            return Folio(id, number, ownerType, owner, businessDate, now, openedBy?.value).also {
                it.payerCompanyValue = payerCompanyId?.value
                it.creditLimit = creditLimit
            }
        }

        /** Exige o id do dono quando o tipo não é avulso (antes de gerar o número da conta). */
        fun requireOwnerId(
            ownerType: FolioOwnerType,
            ownerId: UUID?,
        ) {
            if (ownerId == null && ownerType != FolioOwnerType.WALK_IN) {
                throw BillingRules.invalid(
                    BillingErrorCodes.FOLIO_OWNER_INVALID,
                    "owner.id",
                    "Informe o id do dono da conta (só a conta avulsa dispensa).",
                )
            }
        }

        fun requireValidCreditLimit(creditLimit: Money?) {
            if (creditLimit?.isNegative() == true) {
                throw BillingRules.invalid(
                    BillingErrorCodes.BILLING_DATA_INVALID,
                    "creditLimit",
                    "O limite de crédito não pode ser negativo.",
                )
            }
        }
    }
}
