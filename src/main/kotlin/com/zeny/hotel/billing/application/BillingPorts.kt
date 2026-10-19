package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.RoutingRule
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Os agregados mutáveis do `billing` (códigos, contas e regras de roteamento), na transação corrente. */
@Suppress("TooManyFunctions") // os três agregados mutáveis do módulo, na mesma unidade de persistência
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

    /**
     * As contas com as linhas travadas, **sempre em ordem de id** (texto do UUID), para duas escritas que travam as
     * mesmas contas em ordens diferentes (transferências opostas, lançamentos roteados) nunca se travarem
     * mutuamente. Só as que existem.
     */
    fun foliosForUpdate(ids: Collection<FolioId>): Map<FolioId, Folio>

    /** Avança a versão da conta já (a linha continua travada), sem outra mudança: o `ETag` muda com o saldo. */
    fun markChanged(folio: Folio)

    fun flush()

    /**
     * Trava consultiva única das escritas de regras de roteamento (`pg_advisory_xact_lock`), tomada antes de qualquer
     * outra trava: duas escritas nunca conferem sobreposição e ciclo sem ver uma à outra.
     */
    fun lockRoutingRules()

    /**
     * A mesma trava em modo compartilhado (`pg_advisory_xact_lock_shared`), tomada pelo lançamento antes de ler as
     * regras e de travar as contas: uma escrita de regra em andamento termina antes, e nenhuma começa enquanto o
     * lançamento não confirmar (as regras lidas valem até o fim da transação). Lançamentos não se bloqueiam entre si.
     */
    fun lockRoutingRulesShared()

    fun routingRule(id: RoutingRuleId): RoutingRule?

    fun routingRules(ids: List<UUID>): List<RoutingRule>

    /** As regras ativas (todas: a conferência de ciclo percorre o grafo inteiro). */
    fun activeRoutingRules(): List<RoutingRule>

    /** As regras ativas vigentes na data operacional [date]. */
    fun routingRulesOn(date: LocalDate): List<RoutingRule>

    fun add(rule: RoutingRule)
}

/** Os lançamentos, só inseridos e lidos (RN-03). */
interface PostingLedger {
    /** Insere; `false` se o original já tem um estorno (`posting_reversal_uk`). */
    fun tryAdd(posting: Posting): Boolean

    fun posting(id: PostingId): Posting?

    /** A taxa de serviço vinculada ao item (M3 E06), na conta dele; `null` se não tem. */
    fun serviceChargeOf(itemId: PostingId): Posting?

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

    /**
     * O lançamento normal de onde [id] descende: ele mesmo, se é um normal; numa entrada de transferência, sobe pela
     * saída até o lançamento transferido, recursivamente.
     */
    fun lineageRoot(id: PostingId): PostingId

    /**
     * Os estornos e ajustes de toda a linhagem de [root]: ele e as entradas de transferência que descendem dele,
     * recursivamente, em qualquer conta, e as taxas de serviço vinculadas a cada um (M3 E06: o item com a taxa
     * dele, inclusive a remoção a pedido).
     */
    fun lineageCorrections(root: PostingId): List<Posting>

    /**
     * Trava consultiva da linhagem de [root] (`pg_advisory_xact_lock`), tomada depois da trava da conta: duas
     * correções simultâneas em contas diferentes da mesma linhagem conferem o limite cumulativo uma depois da outra.
     */
    fun lockLineage(root: PostingId)
}

/**
 * Um lançamento com o que as correções fizeram dele (só no normal, na entrada de transferência e na taxa de serviço):
 * o estorno, se houve, e o que ainda resta; numa saída de transferência, a entrada e a conta dela; numa entrada, a
 * conta da saída; num item, a taxa de serviço dele nesta conta.
 */
data class PostingRow(
    val posting: Posting,
    val reversedBy: PostingId?,
    val remaining: Money?,
    val transferredToId: PostingId? = null,
    val transferredToFolioId: FolioId? = null,
    val transferredFromFolioId: FolioId? = null,
    val serviceChargeId: PostingId? = null,
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

    fun routingRules(
        filter: RoutingRuleFilter,
        afterId: UUID?,
        limit: Int,
    ): List<UUID>
}

data class RoutingRuleFilter(
    val sourceFolioId: FolioId? = null,
    val targetFolioId: FolioId? = null,
    val active: Boolean? = null,
)

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
