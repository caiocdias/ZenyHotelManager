package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.PendingGuaranteeKind
import com.zeny.hotel.reservations.ReservationHistoryEntry
import com.zeny.hotel.reservations.ReservationHistoryId
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
@Suppress("TooManyFunctions") // a gravação, as leituras, o ciclo de vida, o histórico e o reapontamento
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
     * A reserva com a linha travada (`SELECT … FOR UPDATE`) até o fim da transação (M4 E08): toda alteração,
     * cancelamento e reativação começa por aqui, antes do inventário e da conta, então duas escritas simultâneas na
     * mesma reserva se enfileiram e a segunda vê o resultado da primeira. `null` se não existe.
     */
    fun reservationForUpdate(id: ReservationId): ReservationSummary?

    /**
     * Grava o estado de [reservation] (a estadia, os termos, os totais, a situação, o cancelamento e as multas) com a
     * versão dela, que o chamador já avançou; com [replaceNights], troca também as noites.
     */
    fun update(
        reservation: ReservationSummary,
        now: Instant,
        replaceNights: Boolean,
    )

    /**
     * As pré-reservas vencidas em [now] (a validade igual ou anterior), da que venceu primeiro à última (desempate pelo
     * id), depois da posição [after] (a validade e o id da última lida), até [limit]: os candidatos do job de expiração
     * (E09), lidos sem trava (a expiração de cada uma trava a linha e confere de novo).
     */
    fun dueProvisionals(
        now: Instant,
        after: Pair<Instant, ReservationId>?,
        limit: Int,
    ): List<Pair<Instant, ReservationId>>

    /**
     * As garantias pendentes (E09): as pré-reservas e as reservas confirmadas sem garantia (a política copiada exige
     * cartão ou depósito e nenhuma foi registrada, ou a registrada é de outro meio), com o vencimento de [from]
     * (inclusive; nulo = sem início) a [to] (exclusive), do que vence primeiro ao último (desempate pelo id), depois
     * da posição [after], até [limit]. O vencimento da pré-reserva é a validade; o da reserva sem garantia, o horário
     * de check-in [checkInTime] no fuso [zone] do dia do prazo do depósito (política `DEPOSIT`) ou do dia da chegada
     * (política `CARD`).
     */
    @Suppress("LongParameterList") // o intervalo, a regra do vencimento e a posição
    fun pendingGuarantees(
        from: Instant?,
        to: Instant,
        checkInTime: LocalTime,
        zone: ZoneId,
        after: Pair<Instant, ReservationId>?,
        limit: Int,
    ): List<PendingGuaranteeRow>

    /** Acrescenta uma entrada ao histórico da reserva (*append-only*). */
    fun insertHistory(entry: ReservationHistoryEntry)

    /**
     * O histórico da reserva, da entrada mais recente para a mais antiga, até [limit] entradas depois do cursor
     * ([after]: o instante e o id da última entrada da página anterior).
     */
    fun history(
        id: ReservationId,
        after: Pair<Instant, ReservationHistoryId>?,
        limit: Int,
    ): List<ReservationHistoryEntry>

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

/**
 * O que o reapontamento de um hóspede mudou: os bookings (titular), as reservas, com os hóspedes antes e depois, e os
 * bloqueios (titular).
 */
data class GuestRepointing(
    val bookings: List<BookingId>,
    val reservations: Map<ReservationId, Pair<List<GuestId>, List<GuestId>>>,
    /** Os bloqueios de grupo cujo titular hóspede foi reapontado (E11). */
    val blocks: List<RoomBlockId> = emptyList(),
) {
    val empty: Boolean get() = bookings.isEmpty() && reservations.isEmpty() && blocks.isEmpty()
}

/** Uma garantia pendente (E09): só o que a lista mostra, numa consulta, com o vencimento já calculado. */
data class PendingGuaranteeRow(
    val id: ReservationId,
    val code: DocumentNumber,
    val bookingId: BookingId,
    val holderGuestId: GuestId,
    val kind: PendingGuaranteeKind,
    val status: ReservationStatus,
    val dueAt: Instant,
    val provisionalUntil: Instant?,
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val total: Money,
    val guaranteeTerms: GuaranteeTerms,
    val folioId: FolioId,
)
