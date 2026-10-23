package com.zeny.hotel.frontdesk.domain

import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.sum
import java.time.LocalDate

/**
 * A diferença da tarifa na troca de UH (M5 E05, D8): a recepção compara a soma das noites **restantes** (da data
 * operacional em diante) antes e depois da troca. Com a tarifa mantida (`KEEP`) a diferença é zero; recalculada
 * (`RECALCULATE`), só as restantes mudam, e as passadas guardam o preço congelado (RN-14), então ficam fora da soma.
 */
object RoomMoveTariffs {
    /** A soma do `amount` (diária + pacotes adicionais) das [nights] com data igual ou posterior a [from]. */
    fun remaining(
        nights: List<NightlyRateSummary>,
        from: LocalDate,
    ): Money = nights.filter { !it.date.isBefore(from) }.map { it.amount }.sum()
}
