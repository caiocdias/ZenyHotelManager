package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CardPreAuthorizationSummary
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftSummary
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.NewCardPreAuthorization
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.OwnerBalance
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeSummary
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

/**
 * A [BillingApi]: sem conferir permissão (quem chama já se autorizou), com as mesmas regras, trava, data operacional
 * e trilha da API HTTP ([BillingOperations]).
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // a API pública inteira do módulo (BillingApi) e as portas dela
class BillingApiService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val directory: BillingDirectory,
    private val cashier: CashierStore,
    private val preAuthorizations: CardPreAuthorizationService,
    private val payments: PaymentLedger,
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
    override fun foliosOf(owner: FolioOwner): List<FolioSummary> = summariesOf(owner)

    @Transactional(readOnly = true)
    override fun balanceOf(owner: FolioOwner): OwnerBalance = OwnerBalance(owner, summariesOf(owner))

    @Audited(BillingAudit.CLOSE_FOLIO)
    @Transactional
    override fun closeFolio(id: FolioId): FolioSummary = operations.closeFolio(id).summary()

    /** Todas as contas do dono (em páginas), da mais antiga para a mais nova, com o saldo. */
    private fun summariesOf(owner: FolioOwner): List<FolioSummary> {
        val ids = mutableListOf<UUID>()
        do {
            val page = directory.folios(FolioFilter(owner.type, owner.id), ids.lastOrNull(), FOLIO_PAGE)
            ids += page
        } while (page.size == FOLIO_PAGE)
        val folios = store.folios(ids).reversed()
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
                posting.sourceReference,
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
        return cashier.requireOpenShiftOf(user).summary()
    }

    @Transactional(readOnly = true)
    override fun cashierShift(id: CashierShiftId): CashierShiftSummary? = cashier.shift(id)?.summary()

    override fun registerCardPreAuthorization(
        folioId: FolioId,
        preAuthorization: NewCardPreAuthorization,
    ): CardPreAuthorizationSummary =
        preAuthorizations
            .registerAuthorized(
                folioId,
                PreAuthorizationInput(
                    preAuthorization.amount,
                    preAuthorization.expiresAt,
                    preAuthorization.nsu,
                    preAuthorization.authorizationCode,
                    preAuthorization.cardBrand,
                    preAuthorization.cardLast4,
                ),
            ).summary()

    override fun unsettledCardPreAuthorizations(folioId: FolioId): List<CardPreAuthorizationSummary> =
        preAuthorizations.unsettledOf(folioId)

    override fun releaseCardPreAuthorization(
        folioId: FolioId,
        id: CardPreAuthorizationId,
    ): CardPreAuthorizationSummary = preAuthorizations.releaseAuthorized(folioId, id).summary()

    override fun cardPreAuthorizationsExpiring(
        from: Instant,
        to: Instant,
    ): List<CardPreAuthorizationSummary> = preAuthorizations.expiringBetween(from, to)

    @Transactional(readOnly = true)
    override fun netDeposits(folioId: FolioId): Money = netDeposits(listOf(folioId)).getValue(folioId)

    @Transactional(readOnly = true)
    override fun netDeposits(folioIds: Collection<FolioId>): Map<FolioId, Money> {
        if (folioIds.isEmpty()) return emptyMap()
        val sums = payments.netDeposits(folioIds)
        return folioIds.associateWith { sums[it] ?: Money.ZERO }
    }

    private companion object {
        /** O tamanho das páginas da leitura das contas de um dono (uma reserva tem poucas). */
        const val FOLIO_PAGE = 200
    }
}
