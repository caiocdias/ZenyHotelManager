package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reservations.ReservationsApi
import org.springframework.stereotype.Component

/**
 * A quarta etapa do fechamento (RF-FEC-01, RN-16): libera cada bloqueio de grupo cujo corte chegou
 * ([ReservationsApi.blocksDueForRelease]), devolvendo ao inventário as unidades que não viraram reserva
 * ([ReservationsApi.releaseUnused]). Só o fechamento chama a liberação. Cada bloqueio roda na transação dele
 * ([EndOfDayItems.each]), e a recusa de um vira a pendência [ISSUE_KIND] com o id do bloqueio, sem parar o fechamento.
 *
 * Idempotente pela data: o bloqueio liberado deixa de estar vencido, e liberar de novo não muda nada. Sem bloqueio
 * vencido, a etapa é [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class BlockReleaseStep(
    private val reservations: ReservationsApi,
    private val items: EndOfDayItems,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val due = reservations.blocksDueForRelease()
        if (due.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        items.each(context, ISSUE_KIND, due, { it.value.toString() }) { reservations.releaseUnused(it) }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "block-release"

        /** A posição: depois da diária e antes da expiração das provisórias. */
        const val POSITION = 400

        /** A pendência de um bloqueio cuja liberação foi recusada; a referência é o id do bloqueio. */
        const val ISSUE_KIND = "BLOCK_RELEASE_FAILED"
    }
}
