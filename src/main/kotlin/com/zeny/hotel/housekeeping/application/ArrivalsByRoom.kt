package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

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
    fun arrivalsByRoom(date: LocalDate): Set<RoomId> = reservations.assignedRooms(arrivingOn(date)).values.toSet()

    /**
     * As mesmas UHs de [arrivalsByRoom], cada uma com a menor hora prevista de chegada (M6 E22, D7) entre as reservas
     * que chegam nela, ou `null` se nenhuma tem hora: uma reserva sem hora não apaga a hora de outra da mesma UH. Só a
     * sugestão da distribuição a usa; a geração de tarefas segue em [arrivalsByRoom], sem a consulta das horas.
     */
    @Transactional(readOnly = true)
    fun arrivalTimesByRoom(date: LocalDate): Map<RoomId, LocalTime?> {
        val arriving = arrivingOn(date)
        val rooms = reservations.assignedRooms(arriving)
        val times = reservations.expectedArrivalTimes(rooms.keys)
        val earliest = rooms.entries.groupBy({ it.value }, { times[it.key] })
        return earliest.mapValues { (_, byReservation) -> byReservation.filterNotNull().minOrNull() }
    }

    private fun arrivingOn(date: LocalDate): List<ReservationId> {
        val pending = reservations.pendingArrivals(date)
        return if (date.isAfter(hotel.currentBusinessDate())) {
            pending - reservations.pendingArrivals(date.minusDays(1)).toSet()
        } else {
            pending
        }
    }
}
