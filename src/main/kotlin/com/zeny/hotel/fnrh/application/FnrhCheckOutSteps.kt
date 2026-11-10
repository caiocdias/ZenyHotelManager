package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Os passos do check-out e do desfazer check-in (D-E08-3, 8 e 9). O check-out nunca é `SENT` sem ter enviado o de cada
 * hóspede com check-in: antes, registra no Serpro os que faltam. O desfazer não tem operação na API.
 */
@Component
class FnrhCheckOutSteps(
    private val sync: FnrhReservationSync,
    private val checkInStep: FnrhCheckInStep,
    private val gateway: FnrhGateway,
    private val remoteIds: FnrhRemoteIds,
    private val guests: GuestsApi,
) {
    /**
     * Com algum hóspede com check-in sem id remoto no ambiente, roda antes o check-in dos que faltam (e devolve o
     * resultado dele se não for `Sent`); depois faz o check-out de cada hóspede remoto ainda dentro, gravando cada um
     * logo após enviá-lo: uma nova tentativa não repete os já enviados.
     */
    fun checkOut(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val reservation = sync.reservationOf(submission)
        val env = connection.environment
        if (missingRemoteGuest(reservation, env)) {
            val registered = checkInStep.registerGuests(submission, reservation, connection)
            if (registered != FnrhStepOutcome.Sent) return registered
        }
        remoteIds
            .guests(reservation.id.value, env)
            .filter { it.guestRemoteId != null && !it.checkedOut }
            .sortedBy { it.guestRemoteId?.value }
            .forEach { checkOutGuest(it, reservation, submission) }
        return FnrhStepOutcome.Sent
    }

    /**
     * Sem hóspede remoto, nada a desfazer: `Sent` sem chamada; com algum (a API não tem a operação), `FailedLocally`
     * para a correção manual. Com a reserva de novo com check-in, o desfazer foi superado: `Sent` sem chamada.
     */
    fun undoCheckIn(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val reservation = sync.reservationOf(submission)
        val inRemote = remoteIds.guests(reservation.id.value, connection.environment).any { it.guestRemoteId != null }
        return if (reservation.status in CHECKED_IN || !inRemote) {
            FnrhStepOutcome.Sent
        } else {
            FnrhStepOutcome.FailedLocally(FnrhSubmissionFailure.NO_REMOTE_OPERATION)
        }
    }

    private fun missingRemoteGuest(
        reservation: ReservationSummary,
        env: FnrhEnvironment,
    ): Boolean {
        val remote =
            remoteIds
                .guests(reservation.id.value, env)
                .filter { it.guestRemoteId != null }
                .map { it.guestId }
                .toSet()
        return reservation.guests.any { it.checkedInAt != null && survivorOf(it.guestId).value !in remote }
    }

    /** `PATCH` do check-out do hóspede e, em seguida, a linha relida com `checkedOut`, sem perder os ids. */
    private fun checkOutGuest(
        row: FnrhRemoteGuest,
        reservation: ReservationSummary,
        submission: FnrhSubmission,
    ) {
        val at = checkedOutAt(row, reservation) ?: submission.occurredAt
        gateway.checkOut(checkNotNull(row.guestRemoteId), at, submission.id.toString())
        val current =
            remoteIds.guests(row.reservationId, row.environment).firstOrNull { it.guestId == row.guestId } ?: row
        remoteIds.saveGuest(current.copy(checkedOut = true), submission.id)
    }

    private fun checkedOutAt(
        row: FnrhRemoteGuest,
        reservation: ReservationSummary,
    ): Instant? = reservation.guests.firstOrNull { survivorOf(it.guestId).value == row.guestId }?.checkedOutAt

    private fun survivorOf(id: GuestId): GuestId = guests.identification(id)?.id ?: id

    private companion object {
        val CHECKED_IN = setOf(ReservationStatus.IN_HOUSE, ReservationStatus.CHECKED_OUT)
    }
}
