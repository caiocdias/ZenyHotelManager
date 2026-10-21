package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import java.time.Instant
import java.time.LocalDate

/**
 * As reservas agrupadoras e as reservas (`reservations.booking`, `reservation`, `reservation_guest` e `nightly_rate`),
 * sempre na transação de quem chama.
 *
 * **Travas por hóspede** ([lockGuests]): uma trava consultiva da transação por id de hóspede
 * (`pg_advisory_xact_lock[_shared](hashtext('reservations.guest'), hashtext(<id>))`), em ordem de id. A criação toma a
 * compartilhada dos perfis que lê antes de lê-los; o listener de `GuestsMerged` e a checagem de estadia ativa da
 * anonimização, a exclusiva. Assim, uma mesclagem ou uma anonimização que confirma enquanto uma reserva é criada com o
 * mesmo perfil espera a criação terminar (e então a vê) ou é esperada por ela (e então a criação lê o perfil já
 * mesclado ou anonimizado).
 */
interface BookingStore {
    /** Trava os hóspedes [guestIds] até o fim da transação, em ordem de id; [exclusive] ou compartilhada. */
    fun lockGuests(
        guestIds: Collection<GuestId>,
        exclusive: Boolean,
    )

    /** Grava a reserva agrupadora com as reservas, os hóspedes e as noites. */
    fun insert(booking: BookingSummary)

    fun booking(id: BookingId): BookingSummary?

    fun reservation(id: ReservationId): ReservationSummary?

    /**
     * Reponta as referências do hóspede [merged] para [target] (titular dos bookings e hóspedes das reservas; quando a
     * reserva já tem os dois, fica um só, principal se algum era), avançando a versão do que mudou. Devolve o que
     * mudou, para a trilha.
     */
    fun repointGuest(
        merged: GuestId,
        target: GuestId,
        now: Instant,
    ): GuestRepointing

    /** Se algum dos hóspedes é titular ou hóspede de uma reserva ativa (H12: pré-reserva, confirmada ou hospedada). */
    fun hasActiveStay(guestIds: Collection<GuestId>): Boolean

    /** As reservas em que algum dos hóspedes é titular do booking ou hóspede da estadia, em ordem de criação. */
    fun reservationsOfGuests(guestIds: Collection<GuestId>): List<ReservationId>

    /**
     * As unidades vendidas por tipo e noite (de [from] a [to], inclusive) pelas reservas ativas que tomam inventário
     * (fora o day use, H5), para a recontagem.
     */
    fun soldUnits(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage>
}

/** O que o reapontamento de um hóspede mudou: os bookings (titular) e as reservas, com os hóspedes antes e depois. */
data class GuestRepointing(
    val bookings: List<BookingId>,
    val reservations: Map<ReservationId, Pair<List<GuestId>, List<GuestId>>>,
) {
    val empty: Boolean get() = bookings.isEmpty() && reservations.isEmpty()
}
