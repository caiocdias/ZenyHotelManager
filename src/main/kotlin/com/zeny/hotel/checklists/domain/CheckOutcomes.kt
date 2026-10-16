package com.zeny.hotel.checklists.domain

import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.math.BigDecimal

/**
 * As respostas de marcação (`CHECK`) contadas por resultado, a base da conformidade (RF-CHK-09). "Não se aplica"
 * não é conforme nem não conforme: fica fora do denominador. [evaluated] = OK + NÃO OK; sem nada avaliado, não há
 * taxa (nunca zero, que diria "tudo reprovado").
 */
data class CheckOutcomes(
    val ok: Long,
    val notOk: Long,
    val notApplicable: Long,
) {
    init {
        require(ok >= 0 && notOk >= 0 && notApplicable >= 0) { "Contagens negativas: $this" }
    }

    /** As respostas que contam na taxa: OK e NÃO OK. */
    val evaluated: Long get() = ok + notOk

    /** A conformidade: OK sobre os avaliados, em pontos percentuais com 4 casas; `null` sem nada avaliado. */
    val complianceRate: Percent? get() = rate(ok)

    /** A reprovação: NÃO OK sobre os avaliados; `null` sem nada avaliado. */
    val failureRate: Percent? get() = rate(notOk)

    private fun rate(part: Long): Percent? =
        if (evaluated == 0L) {
            null
        } else {
            Percent.of(
                BigDecimal
                    .valueOf(part)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(evaluated), Percent.SCALE, Money.ROUNDING_MODE),
            )
        }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    }
}
