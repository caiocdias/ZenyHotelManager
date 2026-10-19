package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.FollowedItem
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.billing.domain.PostingSplit
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.ServiceCharge
import com.zeny.hotel.billing.domain.SplitShares
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Uma transferência feita: o lançamento de onde a parte saiu, a saída e a entrada, e, com um item com taxa de serviço
 * (M3 E06), a saída e a entrada da parte da taxa que foi junto.
 */
data class TransferView(
    val originalId: PostingId,
    val outgoing: PostingView,
    val incoming: PostingView,
    val serviceChargeOutgoing: PostingView? = null,
    val serviceChargeIncoming: PostingView? = null,
)

/** Como se reparte uma divisão. */
enum class SplitBy {
    /** Por percentual: os percentuais somam exatamente 100, repartidos por `Money.allocate`. */
    PERCENT,

    /** Por valor: os valores somam exatamente o que resta do lançamento. */
    AMOUNT,
}

/**
 * Uma parte de uma divisão: a conta (a própria conta de origem = a parte que fica) e o percentual (modo
 * [SplitBy.PERCENT]) ou o valor (modo [SplitBy.AMOUNT]), só o do modo.
 */
data class SplitPartCommand(
    val folioId: FolioId,
    val percent: Percent? = null,
    val amount: Money? = null,
)

/**
 * Transferência e divisão de lançamentos entre contas (RF-CTA-05, RN-02, RN-03; M3 E05). Exigem
 * `billing.postings.transfer` (não sensível). Nada muda no lançamento transferido: cada parte movida é um par novo,
 * a saída na conta de origem (o inverso da parte, citando o lançamento) e a entrada na conta de destino (a parte,
 * citando a saída), com o código e a descrição do lançamento, na data operacional (RN-01); o banco confere que a
 * entrada repete a saída com o sinal trocado e que nenhuma saída fica sem entrada. A transferência vai exatamente para
 * a conta escolhida (as regras de roteamento do destino não se aplicam). Todas as contas envolvidas são travadas
 * juntas, em ordem de id: duas transferências opostas entre as mesmas contas se enfileiram, sem deadlock. Conta de
 * origem inexistente `404 FOLIO_NOT_FOUND`; de destino `400 UNKNOWN_FOLIO`; encerrada `409 FOLIO_CLOSED`.
 *
 * Taxa de serviço (M3 E06; decisão do usuário em 2026-10-19): a taxa vinculada a um item vai junto, na mesma proporção
 * (na transferência, tudo o que resta dela; na divisão, repartida pelas partes do item com `Money.allocate`), por um
 * par próprio cuja saída cita a saída do item e cuja entrada cita a entrada do item: item e taxa ficam juntos em cada
 * conta. A taxa não é transferida nem dividida à parte (`422 SERVICE_CHARGE_FOLLOWS_ITEM`).
 */
@Service
class PostingTransferService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val authorization: Authorization,
) {
    /**
     * Transfere tudo o que ainda resta de cada lançamento ([postingIds], de 1 a 100, da conta [sourceFolioId]) para
     * [targetFolioId]. Lançamento de outra conta `404 POSTING_NOT_FOUND`; estorno, ajuste ou saída
     * `422 POSTING_NOT_TRANSFERABLE`; já estornado `409 POSTING_ALREADY_REVERSED`; zerado
     * `422 POSTING_NOTHING_TO_TRANSFER`. Tudo ou nada.
     */
    @Audited(BillingAudit.TRANSFER_POSTINGS)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun transfer(
        sourceFolioId: FolioId,
        targetFolioId: FolioId,
        postingIds: List<PostingId>,
        reason: String?,
    ): List<TransferView> {
        authorization.require(BillingPermissions.POSTINGS_TRANSFER)
        val text = Posting.optionalReason(reason)
        when {
            postingIds.isEmpty() || postingIds.size > MAX_POSTINGS -> {
                throw invalid("postingIds", "Informe de 1 a $MAX_POSTINGS lançamentos.")
            }

            postingIds.toSet().size != postingIds.size -> {
                throw invalid("postingIds", "Um lançamento aparece mais de uma vez.")
            }

            targetFolioId == sourceFolioId -> {
                throw invalid("targetFolioId", "Escolha uma conta de destino diferente da de origem.")
            }
        }
        val folios = store.foliosForUpdate(setOf(sourceFolioId, targetFolioId))
        val source = folios[sourceFolioId] ?: throw folioNotFound()
        val target = folios[targetFolioId] ?: throw unknownFolio("targetFolioId")
        source.requireOpen()
        target.requireOpen()
        val stamp = operations.stamp()
        return postingIds.map { id ->
            val corrections = load(source, id)
            val part = corrections.transferable(source)
            val charge = operations.serviceChargeOf(corrections.original)
            move(source, target, corrections, part, text, stamp, charge to charge?.followable)
        }
    }

    /**
     * Divide o que ainda resta do lançamento entre as contas das [parts] (de 2 a 10, sem repetir; a própria conta de
     * origem é a parte que fica), na ordem, por percentual (somando 100, repartido por `Money.allocate`) ou por valor
     * (somando exatamente o que resta, senão `422 SPLIT_TOTAL_MISMATCH`). As partes somam sempre exatamente o que
     * restava; uma parte que arredonda a zero não move nada. Mesmas recusas da transferência; conta de uma parte
     * inexistente `400 UNKNOWN_FOLIO` (campo `parts[i].folioId`).
     */
    @Audited(BillingAudit.SPLIT_POSTING)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun split(
        sourceFolioId: FolioId,
        postingId: PostingId,
        by: SplitBy,
        parts: List<SplitPartCommand>,
        reason: String?,
    ): List<TransferView> {
        authorization.require(BillingPermissions.POSTINGS_TRANSFER)
        val text = Posting.optionalReason(reason)
        val shares = shares(by, parts)
        if (parts.size !in PostingSplit.MIN_PARTS..PostingSplit.MAX_PARTS) {
            throw splitInvalid(
                "parts",
                "A divisão tem de ${PostingSplit.MIN_PARTS} a ${PostingSplit.MAX_PARTS} partes.",
            )
        }
        val folioIds = parts.map { it.folioId }
        val repeated = folioIds.withIndex().firstOrNull { (index, folio) -> folioIds.indexOf(folio) != index }
        if (repeated != null) throw splitInvalid("parts[${repeated.index}].folioId", "Uma conta aparece duas vezes.")
        val folios = store.foliosForUpdate(parts.map { it.folioId } + sourceFolioId)
        val source = folios[sourceFolioId] ?: throw folioNotFound()
        val targets =
            parts.mapIndexed { index, part -> folios[part.folioId] ?: throw unknownFolio("parts[$index].folioId") }
        source.requireOpen()
        targets.forEach(Folio::requireOpen)
        val corrections = load(source, postingId)
        val amounts = PostingSplit.amounts(corrections.transferable(source), shares)
        val charge = operations.serviceChargeOf(corrections.original)
        val chargeAmounts =
            charge?.followable?.let { ServiceCharge.alongside(it, amounts) } ?: amounts.map { null }
        val stamp = operations.stamp()
        val moved =
            targets.indices
                .map { Triple(targets[it], amounts[it], chargeAmounts[it]) }
                .filter { (target, amount, _) -> target.id != source.id && !amount.isZero() }
        if (moved.isEmpty()) {
            throw splitInvalid("parts", "A divisão não move nada para outra conta.")
        }
        var current = corrections
        var currentCharge = charge
        return moved.map { (target, amount, chargeAmount) ->
            move(source, target, current, amount, text, stamp, currentCharge to chargeAmount).also {
                current = PostingCorrections(current.original, ledger.correctionsOf(current.original.id))
                currentCharge =
                    currentCharge?.let { known ->
                        PostingCorrections(known.original, ledger.correctionsOf(known.original.id))
                    }
            }
        }
    }

    /** Os percentuais ou os valores das partes, só o campo do modo em cada uma. */
    private fun shares(
        by: SplitBy,
        parts: List<SplitPartCommand>,
    ): SplitShares {
        parts.forEachIndexed { index, part ->
            val (present, absent) =
                when (by) {
                    SplitBy.PERCENT -> (part.percent != null) to (part.amount == null)
                    SplitBy.AMOUNT -> (part.amount != null) to (part.percent == null)
                }
            if (!present || !absent) {
                val field = if (by == SplitBy.PERCENT) "percent" else "amount"
                throw splitInvalid("parts[$index].$field", "No modo $by, cada parte informa só `$field`.")
            }
        }
        return when (by) {
            SplitBy.PERCENT -> SplitShares.ByPercent(parts.map { checkNotNull(it.percent) })
            SplitBy.AMOUNT -> SplitShares.ByAmount(parts.map { checkNotNull(it.amount) })
        }
    }

    /**
     * Lê o lançamento da conta já travada, com as correções e saídas dele; a taxa de serviço vinculada não se move à
     * parte (`422 SERVICE_CHARGE_FOLLOWS_ITEM`).
     */
    private fun load(
        source: Folio,
        id: PostingId,
    ): PostingCorrections {
        val original = ledger.posting(id)?.takeIf { it.folioId == source.id } ?: throw postingNotFound()
        original.requireNotServiceCharge()
        return PostingCorrections(original, ledger.correctionsOf(id))
    }

    /**
     * Grava o par (saída e entrada) de uma parte e o descreve na trilha; com a taxa do item ([charge]: a taxa e a
     * parte dela, que vai junto se não for nula nem zero), grava também o par da taxa, vinculado ao do item.
     */
    @Suppress("LongParameterList") // o par e o contexto
    private fun move(
        source: Folio,
        target: Folio,
        corrections: PostingCorrections,
        part: Money,
        reason: String?,
        stamp: PostingStamp,
        charge: Pair<PostingCorrections?, Money?>,
    ): TransferView {
        val (outgoing, incoming) = corrections.transfer(source, part, target, reason, stamp)
        check(operations.record(source, outgoing)) { "Saída ${outgoing.id} recusada pelo banco" }
        check(operations.record(target, incoming)) { "Entrada ${incoming.id} recusada pelo banco" }
        val (chargeCorrections, chargePart) = charge
        val chargePair =
            if (chargeCorrections != null && chargePart != null && !chargePart.isZero()) {
                chargeCorrections
                    .transfer(source, chargePart, target, reason, stamp, FollowedItem(outgoing.id, incoming.id))
                    .also { (out, into) ->
                        check(operations.record(source, out)) { "Saída da taxa ${out.id} recusada pelo banco" }
                        check(operations.record(target, into)) { "Entrada da taxa ${into.id} recusada pelo banco" }
                    }
            } else {
                null
            }
        return TransferView(
            corrections.original.id,
            checkNotNull(ledger.row(outgoing.id)).view(),
            checkNotNull(ledger.row(incoming.id)).view(),
            chargePair?.let { checkNotNull(ledger.row(it.first.id)).view() },
            chargePair?.let { checkNotNull(ledger.row(it.second.id)).view() },
        )
    }

    private fun invalid(
        field: String,
        message: String,
    ) = BillingRules.invalid(BillingErrorCodes.TRANSFER_INVALID, field, message)

    private fun splitInvalid(
        field: String,
        message: String,
    ) = BillingRules.invalid(BillingErrorCodes.SPLIT_INVALID, field, message)

    private companion object {
        const val MAX_POSTINGS = 100
    }
}
