package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
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

    /** A data operacional do encerramento corrente (nula na conta aberta). */
    @Column(name = "closed_on")
    var closedOn: LocalDate? = null
        protected set

    @Column(name = "closed_at")
    var closedAt: Instant? = null
        protected set

    @Column(name = "closed_by")
    private var closedByValue: UUID? = null

    /** A data operacional da última reabertura (fica depois de um novo encerramento). */
    @Column(name = "reopened_on")
    var reopenedOn: LocalDate? = null
        protected set

    @Column(name = "reopened_at")
    var reopenedAt: Instant? = null
        protected set

    @Column(name = "reopened_by")
    private var reopenedByValue: UUID? = null

    /** Quem encerrou (nulo: o sistema, ou a conta aberta). */
    val closedBy: UserId? get() = closedByValue?.let(::UserId)

    val reopenedBy: UserId? get() = reopenedByValue?.let(::UserId)

    @Version
    var version: Long? = null
        protected set

    val owner: FolioOwner get() = FolioOwner(ownerType, ownerId)

    /** Troca o pagador e o limite de crédito; devolve os campos que mudaram. Conta encerrada: `409 FOLIO_CLOSED`. */
    fun update(
        payerCompanyId: CompanyId?,
        creditLimit: Money?,
        now: Instant,
    ): List<String> {
        requireValidCreditLimit(creditLimit)
        requireOpen()
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

    /**
     * Encerra a conta (RF-CTA-11, RN-06, M3 E10): só aberta (`409 FOLIO_CLOSED`), com o [balance] zero (`409
     * FOLIO_BALANCE_NOT_ZERO`, sem tolerância: o saldo é exato em centavos) e sem caução ativa ou parcialmente
     * capturada ainda não vencida ([activePreAuthorizations]; `409 FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION`). Quem chama
     * trava a conta antes de ler o saldo e as cauções, então nada entra nela entre a conferência e o encerramento.
     */
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun close(
        balance: Money,
        activePreAuthorizations: List<CardPreAuthorizationId>,
        stamp: PostingStamp,
    ) {
        if (status == FolioStatus.CLOSED) {
            throw ConflictException(BillingErrorCodes.FOLIO_CLOSED, "A conta $number já está encerrada.")
        }
        if (!balance.isZero()) throw FolioNotSettledException(number, balance)
        if (activePreAuthorizations.isNotEmpty()) {
            throw FolioHasActivePreAuthorizationException(number, activePreAuthorizations)
        }
        status = FolioStatus.CLOSED
        closedOn = stamp.businessDate
        closedAt = stamp.postedAt
        closedByValue = stamp.postedBy?.value
        updatedAt = stamp.postedAt
    }

    /**
     * Reabre a conta encerrada (RF-CTA-11; ação sensível, conferida por quem chama): aberta, `409 FOLIO_NOT_CLOSED`.
     * Devolve a reabertura, com o [reason] (obrigatório, até 500), o encerramento desfeito e a [approval], se houve.
     */
    fun reopen(
        reason: String?,
        stamp: PostingStamp,
        approval: PostingApproval?,
    ): FolioReopening {
        val text = FolioReopening.reason(reason)
        requireClosed()
        val by = checkNotNull(stamp.postedBy) { "A reabertura é feita por um usuário" }
        val reopening =
            FolioReopening(
                UuidV7.generate(),
                id,
                text,
                checkNotNull(closedOn),
                checkNotNull(closedAt),
                stamp.businessDate,
                stamp.postedAt,
                by,
                approval,
            )
        status = FolioStatus.OPEN
        closedOn = null
        closedAt = null
        closedByValue = null
        reopenedOn = stamp.businessDate
        reopenedAt = stamp.postedAt
        reopenedByValue = by.value
        updatedAt = stamp.postedAt
        return reopening
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

    /** Exige a conta encerrada para reabrir (antes da aprovação de superior). */
    fun requireClosed() {
        if (status != FolioStatus.CLOSED) {
            throw ConflictException(BillingErrorCodes.FOLIO_NOT_CLOSED, "A conta $number não está encerrada.")
        }
    }

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

/**
 * Uma reabertura de conta (RF-CTA-11, M3 E10), imutável: o motivo (texto livre de quem reabre: fica só aqui, fora da
 * trilha e das views), o encerramento desfeito, a data operacional, o instante, quem reabriu e a aprovação de superior
 * (ação sensível), se houve.
 */
data class FolioReopening(
    val id: UUID,
    val folioId: FolioId,
    val reason: String,
    val closedOn: LocalDate,
    val closedAt: Instant,
    val businessDate: LocalDate,
    val reopenedAt: Instant,
    val reopenedBy: UserId,
    val approval: PostingApproval?,
) {
    override fun toString(): String = "FolioReopening(id=$id, folioId=$folioId)"

    companion object {
        const val MAX_REASON_LENGTH = 500

        /** O motivo da reabertura: obrigatório, sem espaços nas pontas, até 500 (`400 BILLING_DATA_INVALID`). */
        fun reason(value: String?): String = BillingRules.required("reason", value, MAX_REASON_LENGTH)
    }
}

/** Encerramento recusado: a conta tem saldo (`409 FOLIO_BALANCE_NOT_ZERO`, com o saldo em `balance` no problema). */
class FolioNotSettledException(
    number: DocumentNumber,
    val balance: Money,
) : ConflictException(
        BillingErrorCodes.FOLIO_BALANCE_NOT_ZERO,
        "A conta $number tem saldo de ${balance.amount.toPlainString()}: só se encerra com saldo zero.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("balance" to balance.amount.toPlainString())
}

/**
 * Encerramento recusado: há caução ativa ou parcialmente capturada ainda não vencida (`409
 * FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION`, com os ids em `preAuthorizationIds` no problema): capture-a ou libere-a antes.
 */
class FolioHasActivePreAuthorizationException(
    number: DocumentNumber,
    val preAuthorizationIds: List<CardPreAuthorizationId>,
) : ConflictException(
        BillingErrorCodes.FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION,
        "A conta $number tem caução em aberto: capture ou libere a caução antes de encerrar.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("preAuthorizationIds" to preAuthorizationIds.map { it.value.toString() })
}
