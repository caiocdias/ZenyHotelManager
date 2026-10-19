package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Quem e quando: o ator (nulo = o sistema), o instante do relógio e a data operacional (RN-01). */
data class PostingStamp(
    val businessDate: LocalDate,
    val postedAt: Instant,
    val postedBy: UserId?,
)

/**
 * O roteamento de um lançamento (RF-CTA-04, M3 E05): a conta em que ele foi pedido e a regra dela que o desviou (a
 * cadeia inteira, quando houve mais de uma regra, fica na trilha).
 */
data class PostingRouting(
    val fromFolioId: FolioId,
    val ruleId: RoutingRuleId,
)

/** A aprovação de superior que autorizou uma correção (step-up). */
data class PostingApproval(
    val approvalId: UUID,
    val approvedBy: UserId,
)

/**
 * Lançamento (RF-CTA-02 e 03, RN-01 a 03): um fato imutável, só inserido (a tabela é *append-only* para o papel da
 * aplicação e um trigger recusa `UPDATE`/`DELETE` até do dono). O normal ([PostingType.REGULAR]) tem quantidade e
 * valor unitário positivos e o valor com o sinal do tipo do código; o estorno e o ajuste corrigem um normal da mesma
 * conta ([correctsId]), com motivo, quantidade 1 e o valor unitário igual ao valor. A transferência (M3 E05) é um par:
 * a saída ([PostingType.TRANSFER_OUT]) na conta de origem cita o lançamento transferido ([correctsId]) com o inverso
 * da parte, e a entrada ([PostingType.TRANSFER_IN]) na conta de destino cita a saída ([transferredFromId]) com a
 * parte; o lançamento transferido nunca muda. Um normal roteado guarda a origem ([routing]). Classe sem JPA: a
 * persistência só insere.
 */
@Suppress("LongParameterList") // os campos do fato
class Posting(
    val id: PostingId,
    val folioId: FolioId,
    val transactionCodeId: TransactionCodeId,
    val type: PostingType,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val amount: Money,
    val description: String,
    val source: PostingSource,
    val businessDate: LocalDate,
    val postedAt: Instant,
    val postedBy: UserId?,
    val correctsId: PostingId? = null,
    val reason: String? = null,
    val approval: PostingApproval? = null,
    val routing: PostingRouting? = null,
    val transferredFromId: PostingId? = null,
) {
    init {
        require(!amount.isZero()) { "Lançamento com valor zero" }
        require((type in CORRECTABLE) == (correctsId == null)) { "Correção ou saída sem o lançamento citado" }
        require((type == PostingType.TRANSFER_IN) == (transferredFromId != null)) { "Entrada sem a saída" }
        require(routing == null || (type == PostingType.REGULAR && routing.fromFolioId != folioId)) {
            "Roteamento fora de um lançamento normal ou para a própria conta"
        }
    }

    override fun toString(): String = "Posting(id=$id, folioId=$folioId, type=$type, amount=$amount)"

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 200
        const val MAX_REASON_LENGTH = 500

        /** Os tipos que se estornam, ajustam, transferem e dividem: o normal e a entrada de uma transferência. */
        val CORRECTABLE = setOf(PostingType.REGULAR, PostingType.TRANSFER_IN)
        internal const val QUANTITY_SCALE = 3
        private val MAX_QUANTITY = BigDecimal("9999.999")
        private val MAX_UNIT_PRICE = Money.of("1000000.00")

        /**
         * Um lançamento normal: valor = quantidade × valor unitário, arredondado pela regra única de [Money]
         * (RN-02), negativo num código [TransactionCodeKind.CREDIT]. A descrição vazia vira o nome do código.
         */
        @Suppress("LongParameterList") // os dados do lançamento
        fun regular(
            folio: Folio,
            code: TransactionCode,
            quantity: BigDecimal,
            unitPrice: Money,
            description: String?,
            source: PostingSource,
            stamp: PostingStamp,
            routing: PostingRouting? = null,
        ): Posting {
            folio.requireOpen()
            code.requireActive()
            val gross = gross(quantity, unitPrice)
            val text =
                BillingRules.optional(
                    "description",
                    description,
                    MAX_DESCRIPTION_LENGTH,
                    BillingErrorCodes.POSTING_INVALID,
                )
            return Posting(
                id = PostingId.new(),
                folioId = folio.id,
                transactionCodeId = code.id,
                type = PostingType.REGULAR,
                quantity = quantity.setScale(QUANTITY_SCALE),
                unitPrice = unitPrice,
                amount = if (code.kind == TransactionCodeKind.CREDIT) -gross else gross,
                description = text ?: code.name,
                source = source,
                businessDate = stamp.businessDate,
                postedAt = stamp.postedAt,
                postedBy = stamp.postedBy,
                routing = routing,
            )
        }

        /** Quantidade × valor unitário, com as faixas conferidas, arredondado pela regra de [Money]. */
        private fun gross(
            quantity: BigDecimal,
            unitPrice: Money,
        ): Money {
            val violation =
                when {
                    quantity.signum() <= 0 || quantity > MAX_QUANTITY ||
                        quantity.stripTrailingZeros().scale() > QUANTITY_SCALE -> {
                        "quantity" to "A quantidade vai de 0,001 a 9999,999, com até 3 casas."
                    }

                    !unitPrice.isPositive() || unitPrice > MAX_UNIT_PRICE -> {
                        "unitPrice" to "O valor unitário vai de 0,01 a 1.000.000,00."
                    }

                    (unitPrice * quantity).isZero() -> {
                        "quantity" to "Quantidade × valor unitário arredonda a zero."
                    }

                    else -> {
                        null
                    }
                }
            if (violation != null) throw invalid(violation.first, violation.second)
            return unitPrice * quantity
        }

        /** O motivo obrigatório de uma correção. */
        fun reason(value: String?): String =
            BillingRules.required("reason", value, MAX_REASON_LENGTH, BillingErrorCodes.POSTING_INVALID)

        /** O motivo opcional de uma transferência ou divisão. */
        fun optionalReason(value: String?): String? =
            BillingRules.optional("reason", value, MAX_REASON_LENGTH, BillingErrorCodes.POSTING_INVALID)

        private fun invalid(
            field: String,
            message: String,
        ) = BillingRules.invalid(BillingErrorCodes.POSTING_INVALID, field, message)
    }
}

/**
 * Um lançamento normal e as correções dele (RF-CTA-03; semântica decidida pelo usuário em 2026-10-17):
 *
 * - **Ajuste**: lançamento novo no mesmo código, com sinal oposto ao do original e motivo; parcial, até o que ainda
 *   resta do original ([remaining]); acima, `422 ADJUSTMENT_EXCEEDS_REMAINING`.
 * - **Estorno**: o inverso de tudo o que ainda resta (o original menos os ajustes), um só por original
 *   (`409 POSTING_ALREADY_REVERSED`, também no banco); com os ajustes já zerando o original,
 *   `422 POSTING_NOTHING_TO_REVERSE`. Depois do estorno, nenhum ajuste (`409 POSTING_ALREADY_REVERSED`).
 * - Só um lançamento normal (ou a entrada de uma transferência, que vale como um normal na conta de destino) é
 *   corrigido: estornar ou ajustar um estorno, um ajuste ou uma saída de transferência é
 *   `422 POSTING_NOT_CORRECTABLE` (para desfazer uma correção, lança-se de novo).
 * - **Transferência e divisão** (RF-CTA-05, M3 E05): a saída tira do que resta a parte transferida, que entra na
 *   conta de destino; sem nada restando, `422 POSTING_NOTHING_TO_TRANSFER`; depois do estorno,
 *   `409 POSTING_ALREADY_REVERSED`. Para desfazer, transfere-se a entrada de volta.
 *
 * Assim a soma dos ajustes, das saídas e do estorno nunca passa do valor do original, e o original estornado soma
 * zero.
 */
class PostingCorrections(
    val original: Posting,
    corrections: List<Posting>,
) {
    private val corrections: List<Posting> = corrections.filter { it.correctsId == original.id }

    /** O que ainda resta do original: ele mais as correções (zero depois do estorno). */
    val remaining: Money = this.corrections.fold(original.amount) { sum, posting -> sum + posting.amount }

    val isReversed: Boolean get() = corrections.any { it.type == PostingType.REVERSAL }

    /** Tudo o que ainda resta do original, para transferir (com o sinal dele), conferindo as regras. */
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun transferable(folio: Folio): Money {
        if (original.type !in Posting.CORRECTABLE) {
            throw BusinessRuleException(
                BillingErrorCodes.POSTING_NOT_TRANSFERABLE,
                "Só um lançamento normal ou a entrada de uma transferência é transferido ou dividido.",
            )
        }
        if (isReversed) {
            throw ConflictException(BillingErrorCodes.POSTING_ALREADY_REVERSED, "Este lançamento já foi estornado.")
        }
        folio.requireOpen()
        if (remaining.isZero()) {
            throw BusinessRuleException(
                BillingErrorCodes.POSTING_NOTHING_TO_TRANSFER,
                "Não resta nada deste lançamento a transferir.",
            )
        }
        return remaining
    }

    /**
     * O par de uma transferência de [part] (com o sinal do original, até o que resta) para [target]: a saída nesta
     * conta, que cita o original, e a entrada no destino, que cita a saída. A conta [target] precisa estar aberta.
     */
    fun transfer(
        folio: Folio,
        part: Money,
        target: Folio,
        reason: String?,
        stamp: PostingStamp,
    ): Pair<Posting, Posting> {
        val left = transferable(folio)
        require(target.id != folio.id) { "Transferência para a própria conta" }
        require(!part.isZero() && part.isNegative() == left.isNegative() && part.abs() <= left.abs()) {
            "Parte $part fora do que resta ($left)"
        }
        target.requireOpen()
        val outgoing = movement(PostingType.TRANSFER_OUT, folio.id, -part, reason, stamp, correctsId = original.id)
        val incoming =
            movement(PostingType.TRANSFER_IN, target.id, part, reason, stamp, transferredFromId = outgoing.id)
        return outgoing to incoming
    }

    @Suppress("LongParameterList") // os campos de um movimento
    private fun movement(
        type: PostingType,
        folioId: FolioId,
        amount: Money,
        reason: String?,
        stamp: PostingStamp,
        correctsId: PostingId? = null,
        transferredFromId: PostingId? = null,
    ) = Posting(
        id = PostingId.new(),
        folioId = folioId,
        transactionCodeId = original.transactionCodeId,
        type = type,
        quantity = BigDecimal.ONE.setScale(Posting.QUANTITY_SCALE),
        unitPrice = amount,
        amount = amount,
        description = original.description,
        source = PostingSource.MANUAL,
        businessDate = stamp.businessDate,
        postedAt = stamp.postedAt,
        postedBy = stamp.postedBy,
        correctsId = correctsId,
        reason = reason,
        transferredFromId = transferredFromId,
    )

    /** O estorno de tudo o que resta. */
    fun reverse(
        folio: Folio,
        reason: String,
        stamp: PostingStamp,
        approval: PostingApproval? = null,
    ): Posting {
        val amount = reversalAmount(folio)
        return correction(PostingType.REVERSAL, amount, Posting.reason(reason), stamp, approval)
    }

    /** O valor do estorno (para o limite da aprovação), conferindo as regras antes de autorizar. */
    fun reversalAmount(folio: Folio): Money {
        requireCorrectable(folio)
        if (remaining.isZero()) {
            throw BusinessRuleException(
                BillingErrorCodes.POSTING_NOTHING_TO_REVERSE,
                "Não resta nada deste lançamento a estornar: os ajustes ou as transferências já o zeraram.",
            )
        }
        return -remaining
    }

    /** Um ajuste de [magnitude] (positivo), com o sinal oposto ao do original. */
    fun adjust(
        folio: Folio,
        magnitude: Money,
        reason: String,
        stamp: PostingStamp,
        approval: PostingApproval? = null,
    ): Posting {
        val amount = adjustmentAmount(folio, magnitude)
        return correction(PostingType.ADJUSTMENT, amount, Posting.reason(reason), stamp, approval)
    }

    /** O valor do ajuste (com sinal), conferindo as regras antes de autorizar. */
    fun adjustmentAmount(
        folio: Folio,
        magnitude: Money,
    ): Money {
        if (!magnitude.isPositive()) {
            throw BillingRules.invalid(BillingErrorCodes.POSTING_INVALID, "amount", "O ajuste precisa ser positivo.")
        }
        requireCorrectable(folio)
        if (magnitude > remaining.abs()) {
            throw BusinessRuleException(
                BillingErrorCodes.ADJUSTMENT_EXCEEDS_REMAINING,
                "O ajuste passa do que ainda resta do lançamento (${remaining.abs().amount.toPlainString()}).",
            )
        }
        return if (original.amount.isNegative()) magnitude else -magnitude
    }

    private fun requireCorrectable(folio: Folio) {
        if (original.type !in Posting.CORRECTABLE) {
            throw BusinessRuleException(
                BillingErrorCodes.POSTING_NOT_CORRECTABLE,
                "Só um lançamento normal é estornado ou ajustado; um estorno, um ajuste ou uma saída de " +
                    "transferência, não.",
            )
        }
        if (isReversed) {
            throw ConflictException(BillingErrorCodes.POSTING_ALREADY_REVERSED, "Este lançamento já foi estornado.")
        }
        folio.requireOpen()
    }

    private fun correction(
        type: PostingType,
        amount: Money,
        reason: String,
        stamp: PostingStamp,
        approval: PostingApproval?,
    ) = Posting(
        id = PostingId.new(),
        folioId = original.folioId,
        transactionCodeId = original.transactionCodeId,
        type = type,
        quantity = BigDecimal.ONE.setScale(Posting.QUANTITY_SCALE),
        unitPrice = amount,
        amount = amount,
        description = original.description,
        source = PostingSource.MANUAL,
        businessDate = stamp.businessDate,
        postedAt = stamp.postedAt,
        postedBy = stamp.postedBy,
        correctsId = original.id,
        reason = reason,
        approval = approval,
    )
}

/**
 * O valor que o limite `maxAmount` das correções confere (RF-ACS-06; cumulativo contra o fracionamento): o valor
 * absoluto das correções (estornos e ajustes) já feitas em **toda a linhagem** do lançamento normal de origem — ele e
 * as entradas de transferência que descendem dele, recursivamente, em qualquer conta — mais o desta. Assim dividir ou
 * transferir antes de estornar não passa o teto (decisão do usuário em 2026-10-19). As saídas e entradas de
 * transferência não são correções e não contam.
 */
object CorrectionLimit {
    fun cumulative(
        lineageCorrections: Collection<Posting>,
        amount: Money,
    ): Money =
        lineageCorrections
            .filter { it.type == PostingType.REVERSAL || it.type == PostingType.ADJUSTMENT }
            .fold(amount.abs()) { sum, posting -> sum + posting.amount.abs() }
}

/** O saldo de uma conta: a soma dos lançamentos (os pagamentos entram na E08). */
object FolioBalance {
    fun of(postings: Collection<Posting>): Money = postings.fold(Money.ZERO) { sum, posting -> sum + posting.amount }
}

/** Como se reparte um lançamento numa divisão (RF-CTA-05): por percentual ou por valor, uma parte por conta. */
sealed interface SplitShares {
    val size: Int

    /** Percentuais positivos que somam exatamente 100; os valores saem de `Money.allocate` (sem perder centavos). */
    data class ByPercent(
        val percents: List<Percent>,
    ) : SplitShares {
        override val size: Int get() = percents.size
    }

    /** Valores positivos que somam exatamente o que resta do lançamento (em valor absoluto). */
    data class ByAmount(
        val amounts: List<Money>,
    ) : SplitShares {
        override val size: Int get() = amounts.size
    }
}

/**
 * A divisão de um lançamento (RF-CTA-05, M3 E05): o que ainda resta dele repartido entre 2 a 10 contas, na ordem das
 * partes, com o sinal do lançamento. A soma das partes é **sempre exatamente** o que resta: por percentual, pelo
 * método do maior resto de `Money.allocate` (os percentuais precisam somar 100); por valor, as partes precisam somar
 * o que resta (senão `422 SPLIT_TOTAL_MISMATCH`). Uma parte que arredonda a zero não move nada.
 */
object PostingSplit {
    const val MIN_PARTS = 2
    const val MAX_PARTS = 10

    fun amounts(
        remaining: Money,
        shares: SplitShares,
    ): List<Money> {
        if (shares.size !in MIN_PARTS..MAX_PARTS) {
            throw invalid("parts", "A divisão tem de $MIN_PARTS a $MAX_PARTS partes.")
        }
        return when (shares) {
            is SplitShares.ByPercent -> byPercent(remaining, shares.percents)
            is SplitShares.ByAmount -> byAmount(remaining, shares.amounts)
        }
    }

    private fun byPercent(
        remaining: Money,
        percents: List<Percent>,
    ): List<Money> {
        percents.forEachIndexed { index, percent ->
            if (percent <= Percent.ZERO || percent > Percent.HUNDRED) {
                throw invalid("parts[$index].percent", "Cada percentual vai de 0,0001 a 100.")
            }
        }
        val total = percents.fold(BigDecimal.ZERO) { sum, percent -> sum + percent.value }
        if (total.compareTo(Percent.HUNDRED.value) != 0) {
            throw invalid("parts", "Os percentuais precisam somar exatamente 100 (somam ${total.toPlainString()}).")
        }
        return remaining.allocate(percents.map { it.value })
    }

    private fun byAmount(
        remaining: Money,
        amounts: List<Money>,
    ): List<Money> {
        amounts.forEachIndexed { index, amount ->
            if (!amount.isPositive()) throw invalid("parts[$index].amount", "Cada valor precisa ser positivo.")
        }
        val total = amounts.fold(Money.ZERO) { sum, amount -> sum + amount }
        if (total != remaining.abs()) {
            throw BusinessRuleException(
                BillingErrorCodes.SPLIT_TOTAL_MISMATCH,
                "As partes somam ${total.amount.toPlainString()}, mas resta " +
                    "${remaining.abs().amount.toPlainString()} do lançamento.",
            )
        }
        return if (remaining.isNegative()) amounts.map { -it } else amounts
    }

    private fun invalid(
        field: String,
        message: String,
    ) = BillingRules.invalid(BillingErrorCodes.SPLIT_INVALID, field, message)
}
