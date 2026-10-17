package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.Money
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * As escritas de contas e lançamentos sem conferir permissão, na transação de um caso de uso auditado (a API HTTP ou a
 * [com.zeny.hotel.billing.BillingApi]): conferem a empresa pela `GuestsApi`, numeram a conta com o ano da data
 * operacional, travam a conta (`SELECT … FOR UPDATE`) antes de lançar, carimbam o lançamento com a data operacional do
 * hotel (RN-01) e o instante do [Clock], e descrevem a mudança na trilha.
 */
@Component
@Suppress("LongParameterList") // as portas que as escritas usam, injetadas pelo Spring
class BillingOperations(
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val numbers: DocumentNumberGenerator,
    private val hotel: HotelApi,
    private val guests: GuestsApi,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val clock: Clock,
) {
    fun openFolio(
        owner: FolioOwnerInput,
        payerCompanyId: CompanyId?,
        creditLimit: Money?,
    ): FolioView {
        Folio.requireOwnerId(owner.type, owner.id)
        Folio.requireValidCreditLimit(creditLimit)
        if (owner.type == FolioOwnerType.COMPANY) requireCompany(CompanyId(checkNotNull(owner.id)), "owner.id")
        payerCompanyId?.let { requireCompany(it, "payerCompanyId") }
        val businessDate = hotel.currentBusinessDate()
        val folio =
            Folio.open(
                numbers.next(FOLIO_NUMBER, businessDate.year),
                owner.type,
                owner.id,
                payerCompanyId,
                creditLimit,
                businessDate,
                currentActor.currentOrNull()?.userId,
                now(),
            )
        audit.created(BillingAudit.FOLIO, folio.id.value) { folio.auditState() }
        store.add(folio)
        return folio.view(Money.ZERO)
    }

    /** Confere um pagador novo (o atual, mesmo desativado depois, fica). */
    fun requirePayer(
        folio: Folio,
        payerCompanyId: CompanyId?,
    ) {
        if (payerCompanyId != null && payerCompanyId != folio.payerCompanyId) {
            requireCompany(payerCompanyId, "payerCompanyId")
        }
    }

    @Suppress("LongParameterList") // os dados do lançamento
    fun post(
        folioId: FolioId,
        transactionCodeId: TransactionCodeId,
        quantity: BigDecimal,
        unitPrice: Money,
        description: String?,
        source: PostingSource,
    ): Posting {
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        val code =
            store.transactionCode(transactionCodeId)
                ?: throw BillingRules.invalid(
                    BillingErrorCodes.UNKNOWN_TRANSACTION_CODE,
                    "transactionCodeId",
                    "Código de lançamento inexistente.",
                )
        val posting = Posting.regular(folio, code, quantity, unitPrice, description, source, stamp())
        record(folio, posting)
        return posting
    }

    /** Grava um lançamento (normal ou correção) na conta já travada e o descreve na trilha. */
    fun record(
        folio: Folio,
        posting: Posting,
    ): Boolean {
        audit.created(BillingAudit.POSTING, posting.id.value) { posting.auditState() }
        if (!ledger.tryAdd(posting)) return false
        store.markChanged(folio)
        return true
    }

    /** A data operacional (RN-01), o instante do relógio e o ator corrente (nulo = o sistema). */
    fun stamp(): PostingStamp = PostingStamp(hotel.currentBusinessDate(), now(), currentActor.currentOrNull()?.userId)

    fun view(folio: Folio): FolioView = folio.view(ledger.balances(listOf(folio.id))[folio.id] ?: Money.ZERO)

    /** O instante do relógio na precisão do banco (microssegundos). */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun requireCompany(
        id: CompanyId,
        field: String,
    ) {
        val company =
            guests.company(id)
                ?: throw BillingRules.invalid(BillingErrorCodes.UNKNOWN_COMPANY, field, "Empresa inexistente.")
        if (!company.active) {
            throw BusinessRuleException(BillingErrorCodes.COMPANY_INACTIVE, "A empresa está desativada.")
        }
    }

    private companion object {
        val FOLIO_NUMBER = DocumentNumberType("C", "billing.folio_number_seq")
    }
}
