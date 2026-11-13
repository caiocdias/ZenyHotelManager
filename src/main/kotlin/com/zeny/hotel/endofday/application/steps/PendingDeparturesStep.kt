package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reservations.ReservationsApi
import org.springframework.stereotype.Component

/**
 * A segunda etapa do fechamento (RF-FEC-01): levanta a pendência [ISSUE_KIND] para cada hospedada cuja saída é da data
 * que fecha ou anterior ([ReservationsApi.pendingDepartures]). A etapa **nunca faz check-out**: o check-out cobra,
 * libera a UH e depende de decisão humana; a pendência avisa quem acompanha o fechamento. A referência é o id da
 * reserva (RN-13: nada de dado pessoal).
 *
 * Idempotente pela data: só lê e levanta pendência, sem efeito no domínio. Sem saída pendente, a etapa é
 * [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class PendingDeparturesStep(
    private val reservations: ReservationsApi,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val pending = reservations.pendingDepartures(context.businessDate)
        if (pending.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        pending.forEach { context.issues.raise(ISSUE_KIND, it.value.toString(), MESSAGE) }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "pending-departures"

        /** A posição: depois do no-show. */
        const val POSITION = 200

        /** A pendência de uma saída que o fechamento não resolve; a referência é o id da reserva. */
        const val ISSUE_KIND = "PENDING_DEPARTURE"

        private const val MESSAGE =
            "Saída pendente: faça o check-out ou altere a saída; o fechamento não faz check-out."
    }
}
