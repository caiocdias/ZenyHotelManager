package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * As UHs com chegada prevista numa data (D8-E02), usadas pela geração automática ([HousekeepingTaskGeneration]), pela
 * solicitação avulsa ([HousekeepingTasksService]) e pela sugestão da distribuição (D3-E03, que a recalcula).
 */
@Component
@Suppress("MemberNameEqualsClassName") // o nome da consulta da E02, que a geração, a avulsa e a sugestão chamam
class ArrivalsByRoom(
    private val reservations: ReservationsApi,
    private val hotel: HotelApi,
) {
    /**
     * As UHs com chegada prevista na [date] (D8-E02): as alocadas às reservas confirmadas que chegam na data. Até a
     * data operacional, são as de `ReservationsApi.pendingArrivals(date)`, com chegada até a data (inclusive a
     * atrasada); numa data futura, só as que chegam exatamente nela (as pendentes na véspera terão chegado antes).
     * Um retrato do momento: alocação, cancelamento ou no-show posteriores não mudam as tarefas já geradas.
     */
    @Transactional(readOnly = true)
    fun arrivalsByRoom(date: LocalDate): Set<RoomId> {
        val pending = reservations.pendingArrivals(date)
        val arriving =
            if (date.isAfter(hotel.currentBusinessDate())) {
                pending - reservations.pendingArrivals(date.minusDays(1)).toSet()
            } else {
                pending
            }
        return reservations.assignedRooms(arriving).values.toSet()
    }
}
