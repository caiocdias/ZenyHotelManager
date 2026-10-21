package com.zeny.hotel.reservations.domain

import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Money
import java.time.Instant

/**
 * A garantia da pré-reserva na confirmação (M4 E09; RF-RES-08 no recorte manual, RF-TAR-07; decisões do implementador,
 * pendentes de confirmação do usuário):
 * - a garantia informada tem de ser a que a política **copiada** na reserva pede (RN-15): a de depósito só aceita
 *   `DEPOSIT`, a de cartão só `CARD` (o cartão é só informado até o M7); a política sem garantia aceita qualquer uma;
 * - no depósito, o exigido é o percentual da política sobre o total da reserva ([GuaranteeTerms.requiredDeposit]) e o
 *   total líquido de depósitos da conta da reserva (os recebidos menos os reembolsados, pela `BillingApi`) precisa ser
 *   igual ou maior.
 */
object GuaranteeRules {
    /**
     * Se a reserva deve a garantia: uma confirmada cuja política copiada exige cartão ou depósito e sem garantia
     * registrada que valha para ela — nenhuma (a troca para um plano com garantia na alteração, a reativação de uma
     * pré-reserva expirada) ou uma de outro meio (a garantida por cartão levada pela alteração a um plano de depósito,
     * ou o contrário; a garantia antiga fica gravada até a nova a substituir, e o histórico a preserva).
     */
    fun unguaranteed(reservation: ReservationSummary): Boolean =
        reservation.status == ReservationStatus.CONFIRMED &&
            reservation.guaranteeTerms.type != GuaranteeType.NONE &&
            reservation.guarantee?.method?.let { accepts(reservation.guaranteeTerms.type, it) } != true

    /** Se a política do tipo [type] aceita a garantia [method] (a sem garantia aceita qualquer uma). */
    fun accepts(
        type: GuaranteeType,
        method: GuaranteeMethod,
    ): Boolean =
        when (type) {
            GuaranteeType.NONE -> true
            GuaranteeType.CARD -> method == GuaranteeMethod.CARD
            GuaranteeType.DEPOSIT -> method == GuaranteeMethod.DEPOSIT
        }

    /**
     * O que `POST …/confirm` faz com a reserva: confirma a pré-reserva ([GuaranteeAction.CONFIRM]) ou registra a
     * garantia da confirmada sem garantia ([GuaranteeAction.REGISTER]); fora disso,
     * `409 RESERVATION_TRANSITION_NOT_ALLOWED` (ação `CONFIRM`).
     */
    fun actionFor(reservation: ReservationSummary): GuaranteeAction {
        if (unguaranteed(reservation)) return GuaranteeAction.REGISTER
        ReservationTransitions.require(ReservationAction.CONFIRM, reservation.status)
        return GuaranteeAction.CONFIRM
    }

    fun requireAccepted(
        terms: GuaranteeTerms,
        method: GuaranteeMethod,
    ) {
        if (!accepts(terms.type, method)) throw GuaranteeMethodNotAcceptedException(terms.type, method)
    }

    fun requireDeposit(
        required: Money,
        received: Money,
    ) {
        if (received < required) throw DepositInsufficientException(required, received)
    }
}

/** O que a confirmação faz: confirma a pré-reserva ou registra a garantia de uma confirmada sem garantia. */
enum class GuaranteeAction {
    CONFIRM,
    REGISTER,
}

/**
 * A validade da pré-reserva (RN-23, H15): o instante da criação mais o prazo de expiração do hotel
 * (`HotelApi.operationalSettings().preReservationExpiry`), gravado na reserva. Vence **no** instante da validade: dali
 * em diante a confirmação é recusada e o job de expiração a cancela.
 */
object ProvisionalValidity {
    fun expired(
        provisionalUntil: Instant,
        now: Instant,
    ): Boolean = !now.isBefore(provisionalUntil)
}

/** `422 GUARANTEE_METHOD_NOT_ACCEPTED`, com o tipo da política em `guaranteeType` e a garantia em `method`. */
class GuaranteeMethodNotAcceptedException(
    val guaranteeType: GuaranteeType,
    val method: GuaranteeMethod,
) : BusinessRuleException(
        ReservationsErrorCodes.GUARANTEE_METHOD_NOT_ACCEPTED,
        "A política de garantia da reserva pede ${LABELS.getValue(guaranteeType)}.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("guaranteeType" to guaranteeType.name, "method" to method.name)

    private companion object {
        val LABELS =
            mapOf(
                GuaranteeType.NONE to "nenhuma garantia",
                GuaranteeType.CARD to "o cartão",
                GuaranteeType.DEPOSIT to "o depósito",
            )
    }
}

/** `422 DEPOSIT_INSUFFICIENT`, com o exigido em `requiredDeposit` e o recebido em `depositReceived`. */
class DepositInsufficientException(
    val required: Money,
    val received: Money,
) : BusinessRuleException(
        ReservationsErrorCodes.DEPOSIT_INSUFFICIENT,
        "O depósito recebido na conta da reserva não cobre o depósito exigido pela política de garantia.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("requiredDeposit" to required.toString(), "depositReceived" to received.toString())
}
