package com.zeny.hotel.frontdesk.domain

import com.zeny.hotel.billing.OwnerBalance
import com.zeny.hotel.frontdesk.EarlyLateOffer
import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.reservations.NightCharge
import com.zeny.hotel.reservations.ReservationNightCharges
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
import java.time.LocalDate

/**
 * As cobranças que o check-out lança (M5 E04, H14; decisões D2 e D3), pela regra única do `reservations`
 * ([ReservationNightCharges], a mesma do fechamento do dia). Só quando a data operacional é a da chegada (day use, ou
 * pernoite que sai no dia em que chegou): a noite `date == arrival`. Sem taxa de turismo: na saída no dia da chegada a
 * estadia vira day use e não há noite (a reserva lida aqui ainda tem a saída original). Nos demais casos as diárias
 * são do fechamento do dia (M6).
 */
object CheckOutCharges {
    fun pending(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ): List<NightCharge> =
        if (businessDate == reservation.arrival) {
            ReservationNightCharges.of(reservation, reservation.arrival, tourismFeePerPersonNight = null)
        } else {
            emptyList()
        }
}

/**
 * O early check-in ou o late check-out do pedido (E04 D4, E05 D6): o hóspede só paga o valor que lhe foi cotado e que
 * a política vigente ainda oferece.
 */
object EarlyLateFeeRules {
    /**
     * O valor a lançar: nulo sem [quotedFee] (nada se cobra); `422 EARLY_LATE_NOT_OFFERED` se a política vigente não
     * oferece; `409 EARLY_LATE_FEE_CHANGED` se o valor difere do vigente; senão o `fee` da [offer]. [label] só
     * compõe a mensagem (`late check-out` ou `early check-in`).
     */
    fun acceptedFee(
        offer: EarlyLateOffer,
        quotedFee: Money?,
        label: String = LATE_LABEL,
    ): Money? {
        if (quotedFee == null) return null
        val current = offer.fee
        if (!offer.offered || current == null) {
            throw BusinessRuleException(
                FrontdeskErrorCodes.EARLY_LATE_NOT_OFFERED,
                "O hotel não oferece $label pela política vigente.",
            )
        }
        if (quotedFee != current) throw EarlyLateFeeChangedException(current, label)
        return current
    }

    const val LATE_LABEL = "late check-out"
    const val EARLY_LABEL = "early check-in"
}

/** `409 EARLY_LATE_FEE_CHANGED`, com o valor vigente em `currentFee` (texto). */
class EarlyLateFeeChangedException(
    val currentFee: Money,
    label: String = EarlyLateFeeRules.LATE_LABEL,
) : ConflictException(
        FrontdeskErrorCodes.EARLY_LATE_FEE_CHANGED,
        "O valor do $label mudou desde a cotação: confirme o valor vigente.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("currentFee" to currentFee.amount.toPlainString())
}

/**
 * `409 RESERVATION_CHANGED`: a reserva travada não é mais a lida (outra recepção alterou, trocou a UH ou fez o
 * check-out entre a leitura e a trava; M5 E05). Converte o `412` da `ReservationsApi`, porque o pedido não tem
 * `If-Match`; nada muda.
 */
class ReservationChangedException :
    ConflictException(
        FrontdeskErrorCodes.RESERVATION_CHANGED,
        "A reserva mudou durante a operação (outra recepção alterou, trocou a UH ou fez o check-out). Leia de novo " +
            "e repita.",
    )

/**
 * `409 FOLIO_BALANCE_NOT_ZERO` no check-out (RN-06, D5): cada conta da reserva com o saldo (calculado depois das
 * cobranças pendentes e do late) e o total. Só ids, números de conta, situações e valores.
 */
class StayNotSettledException(
    val balance: OwnerBalance,
) : ConflictException(
        FrontdeskErrorCodes.FOLIO_BALANCE_NOT_ZERO,
        "Há conta da reserva com saldo: quite todas as contas antes do check-out.",
    ) {
    override val problemProperties: Map<String, Any>
        get() =
            mapOf(
                "folios" to
                    balance.folios.map {
                        mapOf(
                            "folioId" to it.id.toString(),
                            "number" to it.number.toString(),
                            "status" to it.status.name,
                            "balance" to it.balance.amount.toPlainString(),
                        )
                    },
                "total" to balance.total.amount.toPlainString(),
            )
}
