package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reservations.ReservationsApi
import org.springframework.stereotype.Component

/**
 * A quinta etapa do fechamento (RF-FEC-01, RN-23): expira cada pré-reserva com a validade vencida
 * ([ReservationsApi.dueProvisionals]), cancelando-a como `EXPIRED`, sem multa e devolvendo o inventário
 * ([ReservationsApi.expireProvisional]). Convive com o `ProvisionalExpiryJob`, que usa o mesmo critério e o mesmo caso
 * de uso: a reserva que o job expirou antes (ou que uma confirmação tirou daqui) é pulada sem pendência, porque a
 * expiração reconfere sob a trava. Expira até 1000 por execução ([ReservationsApi.dueProvisionals]); o resto fica para
 * o `ProvisionalExpiryJob` ou para o próximo fechamento. Cada reserva roda na transação dela ([EndOfDayItems.each]), e
 * a recusa de uma vira a pendência [ISSUE_KIND] com o id da reserva, sem parar o fechamento.
 *
 * Idempotente pela data: a expirada deixa de ser pré-reserva vencida. Sem pré-reserva vencida, a etapa é
 * [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class ProvisionalExpiryStep(
    private val reservations: ReservationsApi,
    private val items: EndOfDayItems,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val due = reservations.dueProvisionals()
        if (due.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        items.each(context, ISSUE_KIND, due, { it.value.toString() }) { reservations.expireProvisional(it) }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "provisional-expiry"

        /** A posição: depois da liberação dos bloqueios e antes das pré-autorizações de cartão. */
        const val POSITION = 500

        /** A pendência de uma pré-reserva cuja expiração falhou; a referência é o id da reserva. */
        const val ISSUE_KIND = "PROVISIONAL_EXPIRY_FAILED"
    }
}
