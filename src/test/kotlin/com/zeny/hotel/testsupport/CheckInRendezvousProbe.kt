package com.zeny.hotel.testsupport

import com.zeny.hotel.reservations.GuestCheckedIn
import org.springframework.context.event.EventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Faz dois check-ins na mesma UH "armada" se encontrarem **depois** da conferência sem trava da UH e **antes** de
 * ocupá-la (M5 E02, B1 da revisão humana): um listener **síncrono** de `GuestCheckedIn` (publicado dentro do
 * `ReservationsApi.checkIn`, na transação do check-in) espera na barreira da UH até as duas transações chegarem.
 * Assim a corrida acontece sempre: sem a trava da UH na ocupação, os dois passariam; com ela, um só. Inerte para as UHs
 * não armadas.
 */
open class CheckInRendezvousProbe {
    @EventListener
    open fun on(event: GuestCheckedIn) {
        armed[event.roomId]?.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        const val WAIT_SECONDS = 20L

        val armed: MutableMap<UUID, CyclicBarrier> = ConcurrentHashMap()
    }
}
