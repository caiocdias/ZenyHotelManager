package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.Money
import java.time.Instant
import java.util.UUID

/** Os agregados mutáveis do `billing` (códigos e contas), na transação corrente. */
interface BillingStore {
    fun transactionCode(id: TransactionCodeId): TransactionCode?

    fun transactionCode(key: TransactionCodeKey): TransactionCode?

    fun transactionCodes(ids: List<UUID>): List<TransactionCode>

    /** Grava um código novo; `false` se o código curto já existe. */
    fun tryAdd(code: TransactionCode): Boolean

    /** Grava as mudanças pendentes; `false` se o código curto ficou repetido. */
    fun tryFlushCode(): Boolean

    fun folio(id: FolioId): Folio?

    /** A conta com a linha travada (`SELECT … FOR UPDATE`): lançamentos e correções da conta se enfileiram. */
    fun folioForUpdate(id: FolioId): Folio?

    fun folios(ids: List<UUID>): List<Folio>

    fun add(folio: Folio)

    /** Avança a versão da conta já (a linha continua travada), sem outra mudança: o `ETag` muda com o saldo. */
    fun markChanged(folio: Folio)

    fun flush()
}

/** Os lançamentos, só inseridos e lidos (RN-03). */
interface PostingLedger {
    /** Insere; `false` se o original já tem um estorno (`posting_reversal_uk`). */
    fun tryAdd(posting: Posting): Boolean

    fun posting(id: PostingId): Posting?

    /** As correções (estornos e ajustes) do lançamento. */
    fun correctionsOf(id: PostingId): List<Posting>

    /** O saldo de cada conta (a soma dos lançamentos; zero sem lançamento). */
    fun balances(folioIds: Collection<FolioId>): Map<FolioId, Money>

    /** Os lançamentos da conta em ordem de lançamento, depois da posição, com o resumo das correções. */
    fun page(
        folioId: FolioId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<PostingRow>

    /** Um lançamento com o resumo das correções. */
    fun row(id: PostingId): PostingRow?
}

/** Um lançamento com o que as correções fizeram dele (só no normal): o estorno, se houve, e o que ainda resta. */
data class PostingRow(
    val posting: Posting,
    val reversedBy: PostingId?,
    val remaining: Money?,
)

/** As listagens de códigos e contas (só ids e chaves do cursor). */
interface BillingDirectory {
    fun transactionCodes(
        filter: TransactionCodeFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    fun folios(
        filter: FolioFilter,
        afterId: UUID?,
        limit: Int,
    ): List<UUID>
}

data class TransactionCodeFilter(
    val active: Boolean? = null,
    val revenueGroup: RevenueGroup? = null,
)

data class FolioFilter(
    val ownerType: FolioOwnerType? = null,
    val ownerId: UUID? = null,
    val status: FolioStatus? = null,
    val payerCompanyId: CompanyId? = null,
)

/** O dono como chega para abrir uma conta (id nulo só na avulsa). */
data class FolioOwnerInput(
    val type: FolioOwnerType,
    val id: UUID?,
)
