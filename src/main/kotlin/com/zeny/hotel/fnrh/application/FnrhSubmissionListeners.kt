package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.reservations.GuestCheckInUndone
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.ReservationCancelled
import com.zeny.hotel.reservations.ReservationChanged
import com.zeny.hotel.reservations.ReservationConfirmed
import com.zeny.hotel.reservations.ReservationCreated
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationReactivated
import com.zeny.hotel.reservations.ReservationsApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Os consumidores dos eventos de reserva e de hospedagem: cada evento só grava uma [FnrhSubmission] (D-E08-1 e 2); o
 * envio ao Serpro é do job, fora da transação do evento (RF-FNRH-03: a falha da FNRH nunca bloqueia a recepção).
 * Assíncronos e em transação própria, como todo `@ApplicationModuleListener`. Idempotentes: a chave
 * `(reservationId, operation, eventKey)` faz a reentrega do outbox, inclusive fora de ordem ou com a submissão já
 * enviada, não gravar outra. A ordem de execução entre eles não importa: a fila ordena pela versão causal (D-E08-3).
 * `RoomMoved` não gera envio (a API v2.4 não tem UH).
 */
@Component
class FnrhSubmissionListeners(
    private val reservations: ReservationsApi,
    private val submissions: FnrhSubmissionStore,
    private val remoteIds: FnrhRemoteIds,
    private val connection: FnrhConnectionSource,
    private val clock: Clock,
) {
    @ApplicationModuleListener
    fun on(event: ReservationCreated) =
        record(event.reservationId, FnrhOperation.REGISTER_RESERVATION, "created", 0, null, event.createdAt)

    @ApplicationModuleListener
    fun on(event: ReservationChanged) =
        record(event.reservationId, FnrhOperation.UPDATE_RESERVATION, "changed", event.version, null, event.changedAt)

    @ApplicationModuleListener
    fun on(event: ReservationConfirmed) =
        record(
            event.reservationId,
            FnrhOperation.UPDATE_RESERVATION,
            "confirmed",
            event.version,
            null,
            event.confirmedAt,
        )

    @ApplicationModuleListener
    fun on(event: ReservationReactivated) =
        record(
            event.reservationId,
            FnrhOperation.UPDATE_RESERVATION,
            "reactivated",
            event.version,
            null,
            event.reactivatedAt,
        )

    @ApplicationModuleListener
    fun on(event: ReservationCancelled) =
        record(
            event.reservationId,
            FnrhOperation.CANCEL_RESERVATION,
            "cancelled",
            event.version,
            null,
            event.cancelledAt,
        )

    @ApplicationModuleListener
    fun on(event: GuestCheckedIn) =
        record(
            event.reservationId,
            FnrhOperation.CHECK_IN,
            "checked-in",
            event.version,
            event.performedBy,
            event.checkedInAt,
        )

    /** Grava o `UNDO_CHECK_IN` e cancela, na mesma transação, os `CHECK_IN` que não chegaram ao Serpro (D-E08-8). */
    @ApplicationModuleListener
    fun on(event: GuestCheckInUndone) {
        record(
            event.reservationId,
            FnrhOperation.UNDO_CHECK_IN,
            "check-in-undone",
            event.version,
            event.performedBy,
            event.undoneAt,
        )
        cancelUnsentCheckIns(event.reservationId, event.version)
    }

    @ApplicationModuleListener
    fun on(event: GuestCheckedOut) =
        record(
            event.reservationId,
            FnrhOperation.CHECK_OUT,
            "checked-out",
            event.version,
            event.performedBy,
            event.checkedOutAt,
        )

    @Suppress("LongParameterList") // os campos da submissão que o evento dá
    private fun record(
        reservationId: UUID,
        operation: FnrhOperation,
        kind: String,
        version: Long,
        performedBy: UUID?,
        occurredAt: Instant,
    ) {
        val code = reservations.reservation(ReservationId(reservationId))?.code
        if (code == null) {
            log.warn("FNRH: reserva {} não encontrada; {} não gravada", reservationId, operation)
            return
        }
        val eventKey = if (kind == CREATED_KEY) kind else "$kind:v$version"
        val inserted =
            submissions.recordIfAbsent(
                FnrhSubmission.create(
                    reservationId = reservationId,
                    reservationCode = code.toString(),
                    operation = operation,
                    eventKey = eventKey,
                    causalVersion = version,
                    performedBy = performedBy,
                    occurredAt = occurredAt,
                    now = clock.instant(),
                ),
            )
        if (!inserted) log.debug("FNRH: {} {} da reserva {} já gravada", operation, eventKey, reservationId)
    }

    /** Só os `CHECK_IN` anteriores ao desfazer: a reentrega, ou um check-in posterior, não cancela o check-in novo. */
    private fun cancelUnsentCheckIns(
        reservationId: UUID,
        undoVersion: Long,
    ) {
        val environment = connection.current()?.environment
        val hasRemoteGuest = environment != null && remoteIds.guests(reservationId, environment).isNotEmpty()
        if (hasRemoteGuest) return
        val now = clock.instant()
        submissions
            .unresolvedOf(reservationId, FnrhOperation.CHECK_IN)
            .filter { it.causalVersion < undoVersion && !it.inFlight }
            .forEach {
                it.cancel(now)
                submissions.save(it)
            }
    }

    private companion object {
        const val CREATED_KEY = "created"
        val log: Logger = LoggerFactory.getLogger(FnrhSubmissionListeners::class.java)
    }
}
