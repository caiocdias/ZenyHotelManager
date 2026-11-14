package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reservations.ReservationsApi
import org.springframework.stereotype.Component

/**
 * A primeira etapa do fechamento (RF-FEC-01, RF-RES-07, RN-15): marca como no-show cada reserva confirmada cuja chegada
 * já é da data que fecha ou anterior ([ReservationsApi.pendingArrivals]). O [ReservationsApi.markNoShow] lança a multa
 * da política copiada na reserva e solta a UH no mesmo commit; cada reserva roda na transação dela
 * ([EndOfDayItems.each]), e a recusa de uma (por exemplo, conta encerrada) vira a pendência [ISSUE_KIND] com o id da
 * reserva, sem parar o fechamento.
 *
 * Idempotente pela data: a reserva que já saiu de `CONFIRMED` deixa de ser pendente, e a multa tem chave de origem
 * por reserva e data (RN-21). Sem reserva pendente, a etapa é [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class NoShowStep(
    private val reservations: ReservationsApi,
    private val items: EndOfDayItems,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val pending = reservations.pendingArrivals(context.businessDate)
        if (pending.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        items.each(context, ISSUE_KIND, pending, { it.value.toString() }) { reservations.markNoShow(it) }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "no-show"

        /** A posição: a primeira etapa de negócio. */
        const val POSITION = 100

        /** A pendência de uma reserva cujo no-show foi recusado; a referência é o id da reserva. */
        const val ISSUE_KIND = "NO_SHOW_FAILED"
    }
}
