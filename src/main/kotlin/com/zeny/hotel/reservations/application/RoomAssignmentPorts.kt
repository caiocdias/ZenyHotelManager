package com.zeny.hotel.reservations.application

import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.RoomAssignmentEndReason
import com.zeny.hotel.reservations.RoomAssignmentId
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.domain.AssignmentPeriod
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate

/**
 * As alocações de UH (`reservations.room_assignment`, M4 E10), sempre na transação de quem chama.
 *
 * **Trava por UH** ([lockRooms]): uma trava consultiva da transação por id de UH
 * (`pg_advisory_xact_lock(hashtext('reservations.room'), hashtext(<id>))`), em ordem de id. A alocação, a realocação, a
 * alteração da reserva que mantém a UH e o listener de OOO a tomam **antes** de ler os períodos OOO da `RoomsApi` e as
 * alocações da UH: o OOO confirmado enquanto uma alocação é gravada é visto por ela (que recusa) ou pelo listener que
 * roda depois do commit dele (que marca a reserva). A trava da reserva, quando há, vem sempre antes.
 */
@Suppress("TooManyFunctions") // gravação, leituras, marca de realocação e a versão da reserva
interface RoomAssignmentStore {
    /** Trava as UHs [roomIds] até o fim da transação, em ordem de id. */
    fun lockRooms(roomIds: Collection<RoomId>)

    /** A alocação ativa da reserva; `null` se não tem. */
    fun active(reservationId: ReservationId): RoomAssignmentSummary?

    /** Todas as alocações da reserva (ativas e soltas), da mais recente à mais antiga. */
    fun history(reservationId: ReservationId): List<RoomAssignmentSummary>

    /**
     * As alocações ativas que ocupam alguma data de [period], de todas as UHs ou só da [roomId], em ordem de início
     * (desempate pelo id).
     */
    fun activeOverlapping(
        period: AssignmentPeriod,
        roomId: RoomId? = null,
    ): List<RoomAssignmentSummary>

    /**
     * Grava a alocação nova; `false` se o banco recusar a sobreposição com outra alocação ativa da UH
     * (`room_assignment_overlap_ex`, `23P01`). A transação fica abortada: quem chama recusa em seguida.
     */
    fun tryInsert(assignment: RoomAssignmentSummary): Boolean

    /** Solta a alocação ativa [id] (não bloqueia mais a UH); devolve a solta, ou `null` se já não era ativa. */
    fun release(
        id: RoomAssignmentId,
        now: Instant,
        by: UserId?,
        reason: RoomAssignmentEndReason,
    ): RoomAssignmentSummary?

    /** Marca ou desmarca "não mover" na alocação ativa [id]. */
    fun setDoNotMove(
        id: RoomAssignmentId,
        doNotMove: Boolean,
    )

    /**
     * Leva a alocação ativa [id] ao período [period] (a reserva mudou de datas e a UH continua livre), sem a marca de
     * realocação; `false` se o banco recusar a sobreposição (a transação fica abortada).
     */
    fun movePeriod(
        id: RoomAssignmentId,
        period: AssignmentPeriod,
    ): Boolean

    /**
     * Marca "requer realocação" pela [outOfOrderId] na alocação ativa e ainda sem marca [id]; `true` se marcou agora
     * (uma alocação já marcada, solta no meio ou inexistente não muda: o evento reentregue não marca de novo).
     */
    fun mark(
        id: RoomAssignmentId,
        outOfOrderId: OutOfOrderId,
        now: Instant,
    ): Boolean

    /**
     * Troca o período OOO que a marca da alocação ativa [id] cita, de [from] para [to] (o primeiro foi liberado ou
     * alterado e o segundo ainda atinge a estadia), mantendo o instante da marca; `true` se trocou.
     */
    fun remark(
        id: RoomAssignmentId,
        from: OutOfOrderId,
        to: OutOfOrderId,
    ): Boolean

    /** Tira a marca de realocação da alocação ativa [id]; `true` se tirou. */
    fun unmark(id: RoomAssignmentId): Boolean

    /**
     * As alocações ativas marcadas "requer realocação", com a reserva, em ordem de início (desempate pelo id), depois
     * da posição [after], até [limit].
     */
    fun reallocationRequired(
        after: Pair<LocalDate, RoomAssignmentId>?,
        limit: Int,
    ): List<ReallocationRow>

    /** Avança a versão da reserva (o `ETag` dela muda com a UH alocada). */
    fun touchReservation(
        reservationId: ReservationId,
        now: Instant,
    )
}

/** Uma alocação marcada "requer realocação", com o que a lista mostra da reserva. */
data class ReallocationRow(
    val assignment: RoomAssignmentSummary,
    val reservationCode: DocumentNumber,
    val status: ReservationStatus,
    val arrival: LocalDate,
    val departure: LocalDate,
)
