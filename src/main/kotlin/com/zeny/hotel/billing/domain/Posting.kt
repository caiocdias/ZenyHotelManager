package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
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

/** A aprovação de superior que autorizou uma correção (step-up). */
data class PostingApproval(
    val approvalId: UUID,
    val approvedBy: UserId,
)

/**
 * Lançamento (RF-CTA-02 e 03, RN-01 a 03): um fato imutável, só inserido (a tabela é *append-only* para o papel da
 * aplicação e um trigger recusa `UPDATE`/`DELETE` até do dono). O normal ([PostingType.REGULAR]) tem quantidade e
 * valor unitário positivos e o valor com o sinal do tipo do código; o estorno e o ajuste corrigem um normal da mesma
 * conta ([correctsId]), com motivo, quantidade 1 e o valor unitário igual ao valor. Classe sem JPA: a persistência
 * só insere.
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
) {
    init {
        require(!amount.isZero()) { "Lançamento com valor zero" }
        require((type == PostingType.REGULAR) == (correctsId == null)) { "Correção sem o lançamento corrigido" }
    }

    override fun toString(): String = "Posting(id=$id, folioId=$folioId, type=$type, amount=$amount)"

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 200
        const val MAX_REASON_LENGTH = 500
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
 * - Só um lançamento normal é corrigido: estornar ou ajustar um estorno ou um ajuste é
 *   `422 POSTING_NOT_CORRECTABLE` (para desfazer uma correção, lança-se de novo).
 *
 * Assim a soma dos ajustes com o estorno nunca passa do valor do original, e o original estornado soma zero.
 */
class PostingCorrections(
    val original: Posting,
    corrections: List<Posting>,
) {
    private val corrections: List<Posting> = corrections.filter { it.correctsId == original.id }

    /** O que ainda resta do original: ele mais as correções (zero depois do estorno). */
    val remaining: Money = this.corrections.fold(original.amount) { sum, posting -> sum + posting.amount }

    val isReversed: Boolean get() = corrections.any { it.type == PostingType.REVERSAL }

    /**
     * O total das correções do original contando com uma nova de [amount]: o valor absoluto das já feitas mais o
     * desta. É o valor que o limite `maxAmount` confere (cumulativo por lançamento, contra o fracionamento).
     */
    fun cumulativeWith(amount: Money): Money =
        corrections.fold(amount.abs()) { sum, posting ->
            sum +
                posting.amount.abs()
        }

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
                "Os ajustes já zeraram este lançamento; não resta nada a estornar.",
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
        if (original.type != PostingType.REGULAR) {
            throw BusinessRuleException(
                BillingErrorCodes.POSTING_NOT_CORRECTABLE,
                "Só um lançamento normal é estornado ou ajustado; um estorno ou um ajuste, não.",
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

/** O saldo de uma conta: a soma dos lançamentos (os pagamentos entram na E08). */
object FolioBalance {
    fun of(postings: Collection<Posting>): Money = postings.fold(Money.ZERO) { sum, posting -> sum + posting.amount }
}
