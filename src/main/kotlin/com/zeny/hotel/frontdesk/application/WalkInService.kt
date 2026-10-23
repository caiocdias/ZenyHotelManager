package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.billing.NewCardPreAuthorization
import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NewStayGuest
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * O walk-in pedido à recepção (RF-REC-02, H24): o titular (nulo = o hóspede principal), a UH escolhida, o tipo de UH
 * (nulo = o da UH), o plano, a saída (a chegada é a data operacional; igual = day use), a ocupação, o segmento, os
 * pedidos especiais e os dados do check-in (os mesmos do check-in de uma reserva, inclusive a caução e o valor
 * cotado do early check-in, que o walk-in aceita porque chega na data operacional). Os hóspedes já
 * estão cadastrados no `guests` (H24).
 */
data class WalkInCommand(
    val holderGuestId: GuestId?,
    val roomId: RoomId,
    val roomTypeId: RoomTypeId?,
    val ratePlanId: RatePlanId,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    val segment: MarketSegment,
    val specialRequests: String?,
    val guests: List<CheckInGuest>,
    val guestCount: Int?,
    val preAuthorization: NewCardPreAuthorization?,
    val earlyCheckInFee: Money? = null,
)

/** O walk-in feito: a reserva agrupadora criada e o check-in da reserva dela. */
data class WalkInOutcome(
    val booking: BookingSummary,
    val checkIn: CheckInOutcome,
)

/**
 * O walk-in da recepção (M5 E03; RF-REC-02, RN-04, RN-05, RN-14, RN-24, H11 a H14, H24): a reserva criada, a UH
 * alocada e o check-in feito **numa transação só** (ARCHITECTURE §6.1): qualquer recusa desfaz tudo (a reserva
 * agrupadora e a reserva, a conta, a unidade do inventário, a alocação, a caução, a transição, a ocupação da UH, a
 * trilha e os eventos do outbox).
 *
 * **Ordem** (e das travas): as permissões (`frontdesk.stays.checkin`, `reservations.reservations.manage` e, como no
 * check-in com a UH no corpo, `reservations.assignments.manage`); o pedido (o número de hóspedes, a UH existente); a
 * identificação dos hóspedes **sem número** e os anexos dos menores, sem escrita ([CheckInSteps.prepareGuests]); o
 * early check-in aceito pela política vigente para a chegada na data operacional ([CheckInSteps.acceptedEarlyFee]:
 * `422 EARLY_LATE_NOT_OFFERED`, `409 EARLY_LATE_FEE_CHANGED`), antes de qualquer escrita; a
 * criação pela `ReservationsApi.createBooking` (origem `FRONT_DESK`, canal `DIRECT`, a chegada na data operacional, o
 * preço **calculado**: sem diária editada nem desconto, H24; a venda conferida pelo `rates`, `422` com `refusals`; a
 * unidade tomada com as linhas do inventário **travadas**, `409 ROOM_TYPE_UNAVAILABLE`; a conta aberta); a
 * pré-reserva (plano com garantia) confirmada com a garantia que a política copiada pede (cartão: o hóspede está no
 * balcão; depósito: `422 DEPOSIT_INSUFFICIENT`, a conta acabou de nascer); e o fim do check-in da E02
 * ([CheckInSteps.complete]: a alocação, que trava a UH pela trava consultiva; a UH pronta; a transição, que trava a
 * reserva; a caução, que trava a conta; a ocupação, que trava a linha da UH). A reserva e a conta nascem nesta
 * transação, então ninguém mais as trava; a ordem inventário → UH é a mesma da alteração de uma reserva.
 */
@Service
class FrontdeskWalkInService(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val steps: CheckInSteps,
) {
    @Audited(FrontdeskAudit.WALK_IN)
    @Transactional
    fun walkIn(command: WalkInCommand): WalkInOutcome {
        authorization.require(FrontdeskPermissions.STAYS_CHECK_IN)
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
        val guestCount = StayRules.guestCount(command.guestCount, command.guests.size)
        val room =
            rooms.room(command.roomId)
                ?: throw InvalidInputException(
                    FrontdeskErrorCodes.UNKNOWN_ROOM,
                    "A UH informada não existe.",
                    listOf(FieldViolation("roomId", FrontdeskErrorCodes.UNKNOWN_ROOM.value, "A UH não existe.")),
                )
        val businessDate = hotel.currentBusinessDate()
        val holder =
            command.holderGuestId ?: (command.guests.firstOrNull { it.primary } ?: command.guests.first()).guestId
        val prepared = steps.prepareGuests(command.guests, businessDate, alsoAlert = listOf(holder))
        steps.acceptedEarlyFee(businessDate, command.earlyCheckInFee)
        val booking = create(command, holder, command.roomTypeId ?: room.roomTypeId, businessDate)
        val reservation = confirmed(booking.reservations.single())
        StayRules.requireCheckIn(reservation, businessDate)
        val checkIn =
            steps.complete(
                reservation,
                CheckInCommand(
                    command.roomId,
                    command.guests,
                    guestCount,
                    command.preAuthorization,
                    command.earlyCheckInFee,
                ),
                guestCount,
                prepared,
                trail = mapOf("walkIn" to true, "bookingId" to booking.id.value),
            )
        return WalkInOutcome(checkNotNull(reservations.booking(booking.id)) { "Walk-in sem a reserva criada" }, checkIn)
    }

    /**
     * A reserva agrupadora com a reserva do walk-in pela `ReservationsApi.createBooking`; os campos recusados pelo
     * `reservations` (`reservations[0].…`) voltam com os nomes do pedido do walk-in.
     */
    private fun create(
        command: WalkInCommand,
        holder: GuestId,
        roomTypeId: RoomTypeId,
        businessDate: LocalDate,
    ): BookingSummary =
        try {
            reservations.createBooking(
                NewBooking(
                    holderGuestId = holder,
                    origin = ReservationOrigin.FRONT_DESK,
                    reservations =
                        listOf(
                            NewReservation(
                                roomTypeId = roomTypeId,
                                ratePlanId = command.ratePlanId,
                                arrival = businessDate,
                                departure = command.departure,
                                adults = command.adults,
                                childAges = command.childAges,
                                segment = command.segment,
                                specialRequests = command.specialRequests,
                                guests = command.guests.map { NewStayGuest(it.guestId, it.primary) },
                            ),
                        ),
                ),
            )
        } catch (refused: InvalidInputException) {
            throw WalkInInvalidInputException(refused)
        }

    /**
     * A reserva confirmada: a pré-reserva (o plano pede garantia) é confirmada com a garantia que a política copiada
     * pede, pela `ReservationsApi.confirmReservation` (histórico `CONFIRM` e `ReservationConfirmed`): o cartão
     * informado pelo hóspede no balcão; o depósito só com o depósito na conta (`422 DEPOSIT_INSUFFICIENT`: a conta do
     * walk-in acabou de nascer, então um plano com depósito antecipado não serve ao walk-in).
     */
    private fun confirmed(reservation: ReservationSummary): ReservationSummary {
        if (reservation.status != ReservationStatus.PROVISIONAL) return reservation
        val method =
            when (reservation.guaranteeTerms.type) {
                GuaranteeType.CARD -> GuaranteeMethod.CARD
                GuaranteeType.DEPOSIT -> GuaranteeMethod.DEPOSIT
                GuaranteeType.NONE -> error("Pré-reserva sem garantia exigida")
            }
        return reservations.confirmReservation(reservation.id, method)
    }
}

/**
 * Uma recusa de formato do `reservations` com os campos renomeados para o pedido do walk-in (`reservations[0].x` vira
 * `x`), com as mesmas propriedades do problema.
 */
class WalkInInvalidInputException(
    original: InvalidInputException,
) : InvalidInputException(
        original.code,
        original.message,
        original.violations.map { it.copy(field = it.field.removePrefix(RESERVATION_FIELD_PREFIX)) },
        original,
    ) {
    private val properties = original.problemProperties

    override val problemProperties: Map<String, Any>
        get() = properties
}

private const val RESERVATION_FIELD_PREFIX = "reservations[0]."
