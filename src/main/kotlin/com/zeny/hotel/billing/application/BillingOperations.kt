package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.billing.domain.PostingRouting
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.RoutingNetwork
import com.zeny.hotel.billing.domain.ServiceCharge
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * As escritas de contas e lançamentos sem conferir permissão, na transação de um caso de uso auditado (a API HTTP ou a
 * [com.zeny.hotel.billing.BillingApi]): conferem a empresa pela `GuestsApi`, numeram a conta com o ano da data
 * operacional, travam a conta (`SELECT … FOR UPDATE`) antes de lançar, carimbam o lançamento com a data operacional do
 * hotel (RN-01) e o instante do [Clock], e descrevem a mudança na trilha. O lançamento passa pelo roteamento
 * (RF-CTA-04, M3 E05): as regras vigentes na data operacional desviam-no, em cadeia, para a conta final; e, num código
 * que aceita a taxa de serviço (RF-CTA-14, M3 E06), leva junto a taxa, na conta final.
 */
@Component
@Suppress("LongParameterList") // as portas que as escritas usam, injetadas pelo Spring
class BillingOperations(
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val preAuthorizations: CardPreAuthorizationStore,
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

    /**
     * Encerra a conta (RF-CTA-11, RN-06, M3 E10): trava a linha (`SELECT … FOR UPDATE`) e só então lê o saldo e as
     * cauções, então nenhum lançamento, pagamento ou caução (que travam a conta antes de gravar) entra entre a
     * conferência e o encerramento. Saldo diferente de zero: `409 FOLIO_BALANCE_NOT_ZERO`; caução ativa ou parcialmente
     * capturada ainda não vencida pelo relógio (a vencida não impede: o emissor já liberou o valor; decisão do usuário
     * em 2026-10-20): `409 FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION`; já encerrada: `409 FOLIO_CLOSED`.
     */
    fun closeFolio(id: FolioId): FolioView {
        val folio = store.folioForUpdate(id) ?: throw folioNotFound()
        val balance = ledger.balances(listOf(id))[id] ?: Money.ZERO
        val stamp = stamp()
        val active =
            preAuthorizations
                .unsettledOf(id)
                .filter { it.statusAt(stamp.postedAt) != CardPreAuthorizationStatus.EXPIRED }
                .map { it.id }
        audit.track(BillingAudit.FOLIO, id.value) { folio.auditState() }
        folio.close(balance, active, stamp)
        store.flush()
        return folio.view(balance)
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

    /**
     * Lança na conta pedida ou, se uma regra vigente na data operacional cobre o código, na conta final da cadeia de
     * roteamento, com a origem registrada (a conta pedida e a regra dela; a cadeia inteira na trilha). Lê as regras
     * com a trava compartilhada delas (nenhuma escrita de regra no meio, `BillingStore.lockRoutingRulesShared`) e
     * trava a conta pedida e a final juntas, em ordem de id (sem deadlock entre lançamentos roteados opostos). Conta
     * pedida encerrada: `409 FOLIO_CLOSED`; conta final encerrada: `409 ROUTING_TARGET_CLOSED`.
     *
     * Taxa de serviço (M3 E06; decisão do usuário em 2026-10-19): num código com `serviceChargeApplies`, lança junto a
     * taxa ([ServiceCharge.on]) na mesma conta final do item (sem rotear de novo pelas regras do código
     * `TX-SERVICO`), com o percentual [serviceChargeRate] informado pelo módulo de origem, senão o do código, senão o
     * padrão do hotel (H4). O código `TX-SERVICO` desativado recusa o item (`422 TRANSACTION_CODE_INACTIVE`), salvo com
     * a taxa zero (percentual zero ou valor que arredonda a zero), em que o item passa sem taxa.
     */
    @Suppress("LongParameterList", "ThrowsCount") // os dados do lançamento; uma recusa por motivo
    fun post(
        folioId: FolioId,
        transactionCodeId: TransactionCodeId,
        quantity: BigDecimal,
        unitPrice: Money,
        description: String?,
        source: PostingSource,
        serviceChargeRate: Percent? = null,
    ): PostedItem {
        store.folio(folioId) ?: throw folioNotFound()
        val code =
            store.transactionCode(transactionCodeId)
                ?: throw BillingRules.invalid(
                    BillingErrorCodes.UNKNOWN_TRANSACTION_CODE,
                    "transactionCodeId",
                    "Código de lançamento inexistente.",
                )
        val rate = ServiceCharge.rate(code, serviceChargeRate, hotel.taxSettings().serviceChargeRate)
        val chargeCode = rate?.let { serviceChargeCode() }
        val stamp = stamp()
        store.lockRoutingRulesShared()
        val path =
            RoutingNetwork(store.routingRulesOn(stamp.businessDate)) { null }
                .route(folioId, code.id, code.revenueGroup, stamp.businessDate)
        val destinationId = path.lastOrNull()?.targetFolioId ?: folioId
        val locked = store.foliosForUpdate(setOf(folioId, destinationId))
        val requested = locked[folioId] ?: throw folioNotFound()
        requested.requireOpen()
        val destination = checkNotNull(locked[destinationId]) { "Conta de destino $destinationId ausente" }
        if (path.isNotEmpty()) destination.requireOpenAsRoutingTarget()
        val routing = path.firstOrNull()?.let { PostingRouting(folioId, it.id) }
        val posting = Posting.regular(destination, code, quantity, unitPrice, description, source, stamp, routing)
        val charge = chargeCode?.let { ServiceCharge.on(posting, it, checkNotNull(rate), stamp) }
        val chain = path.map { it.id.toString() }
        record(destination, posting) {
            val routed = if (chain.isEmpty()) it else it + ("routingRuleIds" to chain)
            if (charge == null) routed else routed + ("serviceChargePostingId" to charge.id.toString())
        }
        if (charge != null) check(record(destination, charge)) { "Taxa de serviço ${charge.id} recusada pelo banco" }
        return PostedItem(posting, charge)
    }

    /** O código da taxa de serviço (`TX-SERVICO`, pré-carregado). */
    private fun serviceChargeCode(): TransactionCode =
        checkNotNull(store.transactionCode(TransactionCodeKey.SERVICE_CHARGE)) { "Código TX-SERVICO ausente" }

    /** A taxa de serviço vinculada ao [item] na conta dele, com as correções e saídas dela; `null` se não tem. */
    fun serviceChargeOf(item: Posting): PostingCorrections? =
        ledger.serviceChargeOf(item.id)?.let { PostingCorrections(it, ledger.correctionsOf(it.id)) }

    /**
     * Grava um lançamento (normal, correção ou movimento de transferência) na conta já travada e o descreve na
     * trilha ([extra] acrescenta ao retrato).
     */
    fun record(
        folio: Folio,
        posting: Posting,
        extra: (Map<String, Any?>) -> Map<String, Any?> = { it },
    ): Boolean {
        audit.created(BillingAudit.POSTING, posting.id.value) { extra(posting.auditState()) }
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

/** Um item lançado e a taxa de serviço lançada junto (nula sem taxa). */
data class PostedItem(
    val item: Posting,
    val serviceCharge: Posting?,
)
