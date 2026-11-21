package com.zeny.hotel.reporting.domain

import com.zeny.hotel.shared.Percent
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A regra única da ocupação, do painel do dia e do snapshot diário: as noites que contam sobre as disponíveis, em
 * percentual com 2 casas (`HALF_UP`).
 */
object Occupancy {
    private const val PERCENT = 100L
    private const val SCALE = 2

    /** O maior percentual que o `Percent` guarda (`numeric(7,4)`). */
    private val MAX = BigDecimal("999.9999")

    /** `null` sem disponíveis e acima de 999,9999% (disponibilidade quase toda fora de ordem com noites vendidas). */
    fun of(
        occupied: Long,
        available: Long,
    ): Percent? =
        available
            .takeIf { it > 0 }
            ?.let { BigDecimal(occupied * PERCENT).divide(BigDecimal(it), SCALE, RoundingMode.HALF_UP) }
            ?.takeIf { it <= MAX }
            ?.let(Percent::of)
}
