package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftSummary
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeSummary
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A [BillingApi]: sem conferir permissão (quem chama já se autorizou), com as mesmas regras, trava, data operacional
 * e trilha da API HTTP ([BillingOperations]).
 */
@Service
class BillingApiService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val directory: BillingDirectory,
    private val cashier: CashierStore,
) : BillingApi {
    @Transactional(readOnly = true)
    override fun transactionCode(id: TransactionCodeId): TransactionCodeSummary? =
        store.transactionCode(id)?.view()?.summary()

    @Transactional(readOnly = true)
    override fun transactionCode(key: TransactionCodeKey): TransactionCodeSummary =
        checkNotNull(store.transactionCode(key)) { "Código pré-carregado $key ausente" }.view().summary()

    @Transactional(readOnly = true)
    override fun folio(id: FolioId): FolioSummary? = store.folio(id)?.let { operations.view(it).summary() }

    @Transactional(readOnly = true)
    override fun foliosOf(owner: FolioOwner): List<FolioSummary> {
        val folios =
            store.folios(directory.folios(FolioFilter(owner.type, owner.id), null, MAX_FOLIOS_PER_OWNER)).reversed()
        val balances = ledger.balances(folios.map { it.id })
        return folios.map { it.view(balances[it.id] ?: Money.ZERO).summary() }
    }

    @Audited(BillingAudit.OPEN_FOLIO)
    @Transactional
    override fun openFolio(folio: NewFolio): FolioSummary =
        operations
            .openFolio(FolioOwnerInput(folio.ownerType, folio.ownerId), folio.payerCompanyId, folio.creditLimit)
            .summary()

    @Audited(BillingAudit.CREATE_POSTING)
    @Transactional
    override fun post(
        folioId: FolioId,
        posting: NewPosting,
    ): PostingSummary =
        operations
            .post(
                folioId,
                posting.transactionCodeId,
                posting.quantity,
                posting.unitPrice,
                posting.description,
                posting.source,
                posting.serviceChargeRate,
            ).let { it.item.summary(it.serviceCharge) }

    /**
     * Sem `@Transactional`: roda na transação de escrita de quem chama, que segura a trava do turno até o commit
     * (fora de uma, `IllegalStateException`).
     */
    override fun requireOpenShift(user: UserId): CashierShiftSummary {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "requireOpenShift precisa da transação de escrita de quem recebe (trava o turno até o commit)" }
        val shift =
            cashier.openShiftOfForUpdate(user)
                ?: throw BusinessRuleException(
                    BillingErrorCodes.CASHIER_SHIFT_REQUIRED,
                    "Abra um turno de caixa antes de receber ou devolver.",
                )
        return shift.summary()
    }

    @Transactional(readOnly = true)
    override fun cashierShift(id: CashierShiftId): CashierShiftSummary? = cashier.shift(id)?.summary()

    private companion object {
        /** Teto da leitura das contas de um dono (uma reserva tem poucas). */
        const val MAX_FOLIOS_PER_OWNER = 200
    }
}
