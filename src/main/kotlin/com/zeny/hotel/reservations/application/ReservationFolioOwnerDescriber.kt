package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.FolioOwnerDescriber
import com.zeny.hotel.billing.FolioOwnerDescription
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A reserva como dona da conta no extrato (M5 E11, D-E11-12): o rótulo "Reserva <código>", o titular da reserva
 * agrupadora (o nome social quando houver, senão o civil; num perfil mesclado, o do perfil corrente, como na ficha de
 * registro), a UH alocada e as datas da estadia. Sem UH alocada, sem número; reserva inexistente, `null`.
 */
@Component
class ReservationFolioOwnerDescriber(
    private val reservations: ReservationsApi,
    private val guests: GuestsApi,
    private val rooms: RoomsApi,
) : FolioOwnerDescriber {
    override val ownerType: FolioOwnerType = FolioOwnerType.RESERVATION

    override fun describe(ownerId: UUID): FolioOwnerDescription? {
        val reservation = reservations.reservation(ReservationId(ownerId)) ?: return null
        return FolioOwnerDescription(
            label = "Reserva ${reservation.code}",
            guestName = holderName(reservation.bookingId),
            roomNumber = reservation.roomAssignment?.let { rooms.room(it.roomId)?.number },
            arrival = reservation.arrival,
            departure = reservation.departure,
        )
    }

    private fun holderName(bookingId: BookingId): String? {
        val holder = reservations.booking(bookingId)?.holderGuestId ?: return null
        // O perfil corrente: a identificação resolve o mesclado para o sobrevivente (o id dela).
        val current = guests.identification(holder)?.id ?: holder
        return guests.guest(current)?.let { it.socialName ?: it.fullName }
    }
}
