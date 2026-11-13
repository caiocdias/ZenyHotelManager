package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reservations.NightChargeKind
import com.zeny.hotel.reservations.ReservationsApi
import org.springframework.stereotype.Component

/**
 * A terceira etapa do fechamento (RF-FEC-01, RF-TAR-06, RN-14): lança na conta de cada hospedada que já chegou até a
 * data que fecha ([ReservationsApi.inHouse]) as cobranças devidas até essa data ([ReservationsApi.chargesDue]): a
 * hospedagem sem os pacotes inclusos, cada pacote da noite e, fora do day use, a taxa de turismo vigente só na noite
 * que fecha, com origem `END_OF_DAY` e sem taxa de serviço no que já está na tarifa
 * ([com.zeny.hotel.reservations.NightCharge.toNewPosting]). A noite recuperada (anterior à que fecha) não leva taxa de
 * turismo, inclusive depois de uma falha de item ([ISSUE_KIND]) recuperada no fechamento seguinte; o operador lança a
 * taxa à mão. Os códigos `ROOM_CHARGE` e `TOURISM_FEE` são resolvidos uma vez por execução da etapa.
 *
 * Cada hospedada roda na transação dela ([EndOfDayItems.each]); a recusa de uma (por exemplo, conta encerrada) vira a
 * pendência [ISSUE_KIND] com o id da reserva, sem parar o fechamento. A reserva que saiu de `IN_HOUSE` depois da
 * listagem (check-out no meio da etapa) não tem cobrança devida e fica de fora.
 *
 * Idempotente pela data: cada cobrança leva a chave da noite (`NewPosting.sourceReference`, RN-21); a chave já lançada
 * devolve o lançamento existente sem gravar nada, então a repetição e a retomada lançam cada noite uma vez (RN-03,
 * RF-FEC-03). O hóspede além da saída não ganha noite automática (não há noite congelada); o check-in feito depois da
 * listagem é cobrado pelo fechamento seguinte. Sem hospedada, a etapa é [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class RoomChargesStep(
    private val reservations: ReservationsApi,
    private val billing: BillingApi,
    private val items: EndOfDayItems,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val stays = reservations.inHouse(context.businessDate)
        if (stays.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        val codes =
            mapOf(
                NightChargeKind.ROOM to billing.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
                NightChargeKind.TOURISM_FEE to billing.transactionCode(TransactionCodeKey.TOURISM_FEE).id,
            )
        items.each(context, ISSUE_KIND, stays, { it.value.toString() }) { id ->
            reservations.chargesDue(id, context.businessDate)?.let { due ->
                due.charges.forEach { billing.post(due.folioId, it.toNewPosting(codes::getValue)) }
            }
        }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "room-charges"

        /** A posição: depois das saídas pendentes. */
        const val POSITION = 300

        /** A pendência de uma hospedada cujo lançamento foi recusado; a referência é o id da reserva. */
        const val ISSUE_KIND = "ROOM_CHARGE_FAILED"
    }
}
