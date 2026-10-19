package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.math.BigDecimal

/**
 * A taxa de serviço (RF-CTA-14, RN-02, RN-10, H4, H5; M3 E06; decisões do usuário em 2026-10-19). O motor é genérico
 * por código de lançamento: um item cujo código tem `serviceChargeApplies` leva um lançamento **separado**
 * ([PostingType.SERVICE_CHARGE]) no código `TX-SERVICO`, na mesma conta do item e vinculado a ele, com o percentual
 * guardado; o valor é `percentual × valor do item` pela regra única de [Money] (`percent.of(money)`), nunca embutido no
 * item. Depois, a taxa acompanha o item: o estorno do item a estorna, o ajuste a ajusta na proporção do percentual
 * guardado, a transferência e a divisão a levam na mesma proporção (`Money.allocate`); à parte, só sai pela remoção a
 * pedido do cliente ([REMOVAL_REASON]), um estorno não sensível.
 */
object ServiceCharge {
    /** O motivo fixo da remoção a pedido do cliente (RN-10), gravado no estorno da taxa. */
    const val REMOVAL_REASON = "Taxa de serviço removida a pedido do cliente."

    /**
     * O percentual que vale para um item de [code] (H4): o [informed] pelo módulo de origem, senão o do código, senão
     * o [hotelDefault]; nulo se o código não aceita a taxa (o percentual informado não a cria). Informado fora de 0 a
     * 100: `400 POSTING_INVALID` (campo `serviceChargeRate`).
     */
    fun rate(
        code: TransactionCode,
        informed: Percent?,
        hotelDefault: Percent,
    ): Percent? {
        if (informed != null && (informed < Percent.ZERO || informed > Percent.HUNDRED)) {
            throw BillingRules.invalid(
                BillingErrorCodes.POSTING_INVALID,
                "serviceChargeRate",
                "A taxa de serviço vai de 0% a 100%.",
            )
        }
        if (!code.serviceChargeApplies) return null
        return informed ?: code.serviceChargeRate ?: hotelDefault
    }

    /**
     * A taxa sobre o [item] normal recém-lançado, no [chargeCode] (`TX-SERVICO`), na conta e com a data, o instante,
     * o autor e a origem do item; nula se o valor arredonda a zero (ou o percentual é zero). Só uma taxa que existe
     * confere o código: `TX-SERVICO` desativado recusa o item (`422 TRANSACTION_CODE_INACTIVE`), salvo com a taxa zero,
     * em que o item passa sem taxa (decisão do usuário em 2026-10-19).
     */
    fun on(
        item: Posting,
        chargeCode: TransactionCode,
        rate: Percent,
        stamp: PostingStamp,
    ): Posting? {
        require(item.type == PostingType.REGULAR && item.serviceCharge == null) { "Taxa fora de um item normal" }
        val amount = rate.of(item.amount)
        if (amount.isZero()) return null
        chargeCode.requireActive()
        return Posting(
            id = PostingId.new(),
            folioId = item.folioId,
            transactionCodeId = chargeCode.id,
            type = PostingType.SERVICE_CHARGE,
            quantity = BigDecimal.ONE.setScale(Posting.QUANTITY_SCALE),
            unitPrice = amount,
            amount = amount,
            description = chargeCode.name,
            source = item.source,
            businessDate = stamp.businessDate,
            postedAt = stamp.postedAt,
            postedBy = stamp.postedBy,
            serviceCharge = ServiceChargeLink(item.id, rate),
        )
    }

    /**
     * O ajuste (valor positivo) da taxa que acompanha o ajuste do item: deixa o que resta da taxa igual ao percentual
     * guardado sobre o que resta do item depois do ajuste ([itemRemainingAfter]), arredondado por [Money], sem deriva
     * de centavos (o item zerado zera a taxa). Nunca aumenta a taxa nem passa do que resta dela; sem nada a acompanhar
     * (removida, estornada ou zerada), zero.
     */
    fun followingAdjustment(
        charge: PostingCorrections,
        itemRemainingAfter: Money,
    ): Money {
        val left = charge.followable?.abs() ?: return Money.ZERO
        val rate = checkNotNull(charge.original.serviceCharge) { "Ajuste de taxa fora de uma taxa" }.rate
        val excess = left - rate.of(itemRemainingAfter.abs())
        return if (excess.isPositive()) excess else Money.ZERO
    }

    /**
     * O que resta da taxa repartido na proporção das partes do item numa divisão ([itemParts], com o sinal do item),
     * pelo método do maior resto de `Money.allocate`: as partes somam exatamente o que resta, e a parte de um item
     * zerada não leva taxa.
     */
    fun alongside(
        chargeRemaining: Money,
        itemParts: List<Money>,
    ): List<Money> {
        if (itemParts.all { it.isZero() }) return itemParts.map { Money.ZERO }
        return chargeRemaining.allocate(itemParts.map { it.abs().amount })
    }
}
