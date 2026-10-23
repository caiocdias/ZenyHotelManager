package com.zeny.hotel.testsupport

import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.RoomMoved
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.context.event.EventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Faz dois check-ins na mesma UH "armada" se encontrarem **depois** da conferência sem trava da UH e **antes** de
 * ocupá-la (M5 E02, B1 da revisão humana): um listener **síncrono** de `GuestCheckedIn` (publicado dentro do
 * `ReservationsApi.checkIn`, na transação do check-in) espera na barreira da UH até as duas transações chegarem.
 * Assim a corrida acontece sempre: sem a trava da UH na ocupação, os dois passariam; com ela, um só. Desde a E05, a
 * troca de UH entra na mesma barreira pelo `RoomMoved` (publicado dentro do `ReservationsApi.moveRoom`), pela UH de
 * destino: uma troca e um check-in na mesma UH se encontram antes de ocupá-la (Review Focus 1 da E05). Inerte para as
 * UHs não armadas.
 *
 * Na revisão final da E05, o check-out e a troca **da mesma reserva** "armada" em [stays] se encontram com uma já
 * travando a reserva e a outra depois da leitura sem trava e antes de travá-la (um aspecto antes de
 * `ReservationsApi.checkOut` ou `ReservationsApi.moveRoom`, e o listener síncrono do evento que a primeira publica com
 * a reserva travada). Assim a segunda sempre trava a reserva que a primeira já mudou, com a versão lida velha. Inerte
 * para as reservas não armadas.
 */
@Aspect
open class CheckInRendezvousProbe {
    @EventListener
    open fun on(event: GuestCheckedIn) {
        armed[event.roomId]?.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @EventListener
    open fun on(event: RoomMoved) {
        armed[event.toRoomId]?.await(WAIT_SECONDS, TimeUnit.SECONDS)
        meet(event.reservationId, StayStep.CHECK_OUT)
    }

    @EventListener
    open fun on(event: GuestCheckedOut) {
        meet(event.reservationId, StayStep.MOVE_ROOM)
    }

    /** O check-out que trava a reserva **depois** da troca armada (a troca já a travou e espera aqui). */
    @Before("execution(* com.zeny.hotel.reservations.ReservationsApi+.checkOut*(..))")
    open fun beforeCheckOut(point: JoinPoint) {
        reservationOf(point)?.let { meet(it, StayStep.CHECK_OUT) }
    }

    /** A troca que trava a reserva **depois** do check-out armado (o check-out já a travou e espera aqui). */
    @Before("execution(* com.zeny.hotel.reservations.ReservationsApi+.moveRoom*(..))")
    open fun beforeMoveRoom(point: JoinPoint) {
        reservationOf(point)?.let { meet(it, StayStep.MOVE_ROOM) }
    }

    /** Espera na barreira da reserva [id] se ela está armada com [late] como o passo que trava por último. */
    private fun meet(
        id: UUID,
        late: StayStep,
    ) {
        stays[id]?.takeIf { it.late == late }?.barrier?.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** O id da reserva do primeiro argumento (o `ReservationId` chega desembrulhado, como `UUID`). */
    private fun reservationOf(point: JoinPoint): UUID? =
        when (val id = point.args.firstOrNull()) {
            is UUID -> id
            is ReservationId -> id.value
            else -> null
        }

    /** O passo que trava a reserva por último, com a leitura sem trava já feita. */
    enum class StayStep { CHECK_OUT, MOVE_ROOM }

    /** Uma reserva armada: a barreira de duas partes e o passo [late] que trava por último. */
    data class ArmedStay(
        val late: StayStep,
        val barrier: CyclicBarrier = CyclicBarrier(2),
    )

    companion object {
        const val WAIT_SECONDS = 20L

        val armed: MutableMap<UUID, CyclicBarrier> = ConcurrentHashMap()

        /** As reservas armadas para o encontro do check-out com a troca. */
        val stays: MutableMap<UUID, ArmedStay> = ConcurrentHashMap()
    }
}
