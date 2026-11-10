package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Quantas submissões uma execução do envio deixou em cada situação. */
data class FnrhSendRun(
    val sent: Int,
    val postponed: Int,
    val failed: Int,
    val invalid: Int,
)

/**
 * O envio de uma operação ao Serpro, pelo estado atual da reserva (D-E08-9). Roda fora de transação. A falha do
 * Serpro sobe como [FnrhGatewayException]; o resultado é gravado pelo [FnrhSubmissionRecorder].
 */
fun interface FnrhOperationStep {
    fun send(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome
}

/** O que o passo concluiu sem falha do Serpro. */
sealed interface FnrhStepOutcome {
    /** Enviado, ou sem chamada a fazer. */
    data object Sent : FnrhStepOutcome

    /** A validação local recusou os dados (D-E08-10). */
    data class Invalid(
        val errors: List<FnrhSubmissionError>,
    ) : FnrhStepOutcome

    /** Falha nossa e permanente, sem chamada (D-E08-8, D-E08-9). */
    data class FailedLocally(
        val reason: FnrhSubmissionFailure,
    ) : FnrhStepOutcome
}

/**
 * A submissão passou a um estado terminal depois de marcada a escrita em curso (o listener do desfazer a cancelou):
 * o Serpro não é chamado e nada é gravado.
 */
class FnrhSubmissionClosedException(
    submissionId: UUID,
) : RuntimeException("Submissão $submissionId encerrada durante o envio.")

/**
 * A reserva no Serpro, comum a todos os passos: os dados que vão no corpo, o id remoto guardado e o registro com a
 * escrita em curso (D-E08-6, D-E08-13, D-E08-15). O `correlationRef` é sempre o id da submissão.
 */
@Component
class FnrhReservationSync(
    private val reservations: ReservationsApi,
    private val guests: GuestsApi,
    private val gateway: FnrhGateway,
    private val store: FnrhSubmissionStore,
    private val remoteIds: FnrhRemoteIds,
) {
    /** A reserva da submissão; sem ela, a submissão está envenenada (falha temporária, D-E08-17). */
    fun reservationOf(submission: FnrhSubmission): ReservationSummary =
        checkNotNull(reservations.reservation(ReservationId(submission.reservationId))) {
            "Reserva ${submission.reservationId} da submissão ${submission.id} não encontrada."
        }

    /**
     * O id remoto da reserva no ambiente da [connection]: o guardado; senão, com a escrita em curso, o que o
     * `findReservationByCode` achar, gravado (sem link: só o `POST /reservas` o devolve); senão o registro novo.
     */
    fun ensureReservation(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ): FnrhRemoteId = existing(submission, reservation, connection) ?: register(submission, reservation, connection)

    /** O id remoto guardado ou, com a escrita em curso, o conciliado; `null` se o Serpro não tem a reserva. */
    fun existing(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ): FnrhRemoteId? =
        remoteIds.reservation(reservation.id.value, connection.environment)?.remoteId
            ?: if (submission.inFlight) reconcile(submission, reservation, connection) else null

    /** `POST /reservas` com a escrita em curso marcada antes; grava o id e o link, o que limpa a marca. */
    fun register(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ): FnrhRemoteId {
        beginWrite(submission)
        val registered = gateway.registerReservation(data(reservation), submission.id.toString())
        remoteIds.saveReservation(
            FnrhRemoteReservation(
                reservation.id.value,
                connection.environment,
                registered.reservationId,
                registered.precheckinLink,
                cancelled = false,
            ),
            submission.id,
        )
        return registered.reservationId
    }

    /**
     * Marca a escrita em curso (transação própria) antes de uma chamada que cria recurso e relê a submissão: se ela
     * ficou terminal no meio tempo, lança [FnrhSubmissionClosedException] e o Serpro não é chamado.
     */
    fun beginWrite(submission: FnrhSubmission) {
        store.markInFlight(submission.id)
        if (store.find(submission.id)?.isTerminal != false) throw FnrhSubmissionClosedException(submission.id)
    }

    /**
     * O corpo da reserva: o `externalId` do `booking` como código da OTA; os menores são o maior entre as idades de
     * crianças informadas e os hóspedes listados com menos de 18 anos na chegada.
     */
    fun data(reservation: ReservationSummary): FnrhReservationData {
        val booking =
            checkNotNull(reservations.booking(reservation.bookingId)) {
                "Reserva agrupadora da reserva ${reservation.id} não encontrada."
            }
        return FnrhReservationData(
            reservationCode = reservation.code.toString(),
            otaCode = booking.externalId,
            arrival = reservation.arrival,
            departure = reservation.departure,
            adults = reservation.adults,
            minors = maxOf(reservation.childAges.size, listedMinors(reservation)),
        )
    }

    private fun listedMinors(reservation: ReservationSummary): Int =
        reservation.guests.count { stayGuest ->
            val birthDate = guests.guest(stayGuest.guestId)?.birthDate
            birthDate != null && birthDate.plusYears(ADULT_AGE).isAfter(reservation.arrival)
        }

    private fun reconcile(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ): FnrhRemoteId? {
        val found = gateway.findReservationByCode(reservation.code.toString(), submission.id.toString())
        found?.let {
            remoteIds.saveReservation(
                FnrhRemoteReservation(reservation.id.value, connection.environment, it, null, cancelled = false),
                submission.id,
            )
        }
        return found
    }

    private companion object {
        const val ADULT_AGE = 18L
    }
}

/** Os passos das operações da reserva: registro, alteração e cancelamento (D-E08-9). */
@Component
class FnrhReservationSteps(
    private val sync: FnrhReservationSync,
    private val gateway: FnrhGateway,
    private val remoteIds: FnrhRemoteIds,
) {
    /** Registra a reserva pelo estado atual (ver `registerIfValid`). */
    fun register(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        registerIfValid(submission, sync.reservationOf(submission), connection)
        return FnrhStepOutcome.Sent
    }

    /**
     * A remota cancelada não reativa; sem id remoto, faz o que o registro faria (sem o `PUT`); senão, `PUT` com os
     * dados atuais.
     */
    fun update(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val reservation = sync.reservationOf(submission)
        val remote = remoteIds.reservation(reservation.id.value, connection.environment)
        when {
            remote?.cancelled == true -> return FnrhStepOutcome.FailedLocally(FnrhSubmissionFailure.REMOTE_CANCELLED)
            remote == null -> registerIfValid(submission, reservation, connection)
            else -> gateway.updateReservation(remote.remoteId, sync.data(reservation), submission.id.toString())
        }
        return FnrhStepOutcome.Sent
    }

    /** Sem id remoto (ou com a remota já cancelada), nada a cancelar; senão cancela e guarda, mantendo o link. */
    fun cancel(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val remote = remoteIds.reservation(submission.reservationId, connection.environment)
        if (remote != null && !remote.cancelled) {
            gateway.cancelReservation(remote.remoteId, submission.id.toString())
            remoteIds.saveReservation(remote.copy(cancelled = true), submission.id)
        }
        return FnrhStepOutcome.Sent
    }

    /**
     * Pelo estado atual (D-E08-9): com a escrita em curso, concilia antes (e guarda o id mesmo com a reserva já
     * cancelada, para o cancelamento seguinte a cancelar); sem id remoto, a reserva cancelada ou no-show não é
     * registrada; a demais é.
     */
    private fun registerIfValid(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ) {
        val known = sync.existing(submission, reservation, connection)
        if (known == null && reservation.status !in NOT_REGISTERED) sync.register(submission, reservation, connection)
    }

    private companion object {
        val NOT_REGISTERED = setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW)
    }
}

/**
 * O envio da fila à FNRH (RF-FNRH-03; D-E08-3, 5, 17). **Sem transação**: lê a fila, chama o Serpro pelo passo da
 * operação e grava o resultado pelo [FnrhSubmissionRecorder], em transação própria. Sem conexão configurada, não toca
 * em nada (D-E08-5). Cada execução envia, em rodadas, as cabeças vencidas das reservas: enviada uma, a seguinte da
 * mesma reserva pode sair na rodada seguinte, até [batchSize] submissões. Cada submissão é isolada: exceção que não é
 * do Serpro conta como falha temporária `INTERNAL_ERROR`, com log só de ids.
 */
@Component
class FnrhSubmissionSender(
    private val store: FnrhSubmissionStore,
    private val connections: FnrhConnectionSource,
    private val recorder: FnrhSubmissionRecorder,
    reservationSteps: FnrhReservationSteps,
    private val clock: Clock,
    @Value("\${zeny.fnrh.submissions.batch-size}") private val batchSize: Int,
) {
    private val steps: Map<FnrhOperation, FnrhOperationStep> =
        mapOf(
            FnrhOperation.REGISTER_RESERVATION to FnrhOperationStep(reservationSteps::register),
            FnrhOperation.UPDATE_RESERVATION to FnrhOperationStep(reservationSteps::update),
            FnrhOperation.CANCEL_RESERVATION to FnrhOperationStep(reservationSteps::cancel),
        )

    /** As cabeças vencidas de todas as reservas, até [batchSize]. */
    fun sendDue(): FnrhSendRun = run { now, limit -> store.dueHeads(now, limit) }

    /** As cabeças vencidas só das reservas dadas, nessa ordem, até [batchSize]. */
    fun sendDueFor(vararg reservationIds: UUID): FnrhSendRun =
        run { now, limit -> reservationIds.flatMap { store.dueHeads(now, limit, it) } }

    private fun run(heads: (Instant, Int) -> List<UUID>): FnrhSendRun {
        val connection = connections.current() ?: return FnrhSendRun(0, 0, 0, 0)
        var result = FnrhSendRun(0, 0, 0, 0)
        val attempted = mutableSetOf<UUID>()
        var stopped = false
        while (!stopped && attempted.size < batchSize) {
            val due = heads(clock.instant(), batchSize).filterNot { it in attempted }.take(batchSize - attempted.size)
            stopped = due.isEmpty()
            for (id in due) {
                attempted += id
                val outcome = sendOne(id, connection)
                stopped = outcome == Outcome.STOP
                if (stopped) break
                result = result.with(outcome)
            }
        }
        return result
    }

    private fun sendOne(
        id: UUID,
        connection: FnrhConnection,
    ): Outcome {
        val submission = store.find(id)?.takeIf { it.status == FnrhSubmissionStatus.PENDING }
        val step = submission?.let { steps[it.operation] }
        return when {
            submission == null -> Outcome.SKIPPED
            step == null -> Outcome.SKIPPED.also { log.warn("FNRH: sem passo para {} ({})", submission.operation, id) }
            else -> deliver(submission, step, connection)
        }
    }

    private fun deliver(
        submission: FnrhSubmission,
        step: FnrhOperationStep,
        connection: FnrhConnection,
    ): Outcome {
        val sent = runCatching { step.send(submission, connection) }
        val error = sent.exceptionOrNull()
        // A integração foi desligada no meio da execução: nada é gravado, e o resto fica para quando voltar.
        if (error is FnrhGatewayException && error.failure == FnrhFailure.NOT_CONFIGURED) return Outcome.STOP
        return runCatching { Outcome.of(record(submission, sent)) }.getOrElse {
            log.warn("FNRH: resultado do envio da submissão {} não gravado ({})", submission.id, it.javaClass.name)
            Outcome.SKIPPED
        }
    }

    private fun record(
        submission: FnrhSubmission,
        sent: Result<FnrhStepOutcome>,
    ): FnrhSubmissionStatus? =
        sent.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    FnrhStepOutcome.Sent -> recorder.sent(submission.id)
                    is FnrhStepOutcome.Invalid -> recorder.invalid(submission.id, outcome.errors)
                    is FnrhStepOutcome.FailedLocally -> recorder.failedLocally(submission.id, outcome.reason)
                }
            },
            onFailure = { error -> recordError(submission, error) },
        )

    private fun recordError(
        submission: FnrhSubmission,
        error: Throwable,
    ): FnrhSubmissionStatus? =
        when (error) {
            is FnrhSubmissionClosedException -> {
                log.info("FNRH: submissão {} encerrada durante o envio; nada é gravado", submission.id)
                null
            }

            is FnrhGatewayException -> {
                recorder.failed(submission.id, error.failure.toSubmissionFailure())
            }

            else -> {
                log.warn(
                    "FNRH: envio da submissão {} ({}) falhou com {}",
                    submission.id,
                    submission.operation,
                    error.javaClass.name,
                )
                recorder.failed(submission.id, FnrhSubmissionFailure.INTERNAL_ERROR)
            }
        }

    private fun FnrhSendRun.with(outcome: Outcome): FnrhSendRun =
        when (outcome) {
            Outcome.SENT -> copy(sent = sent + 1)
            Outcome.POSTPONED -> copy(postponed = postponed + 1)
            Outcome.FAILED -> copy(failed = failed + 1)
            Outcome.INVALID -> copy(invalid = invalid + 1)
            Outcome.SKIPPED, Outcome.STOP -> this
        }

    private enum class Outcome {
        SENT,
        POSTPONED,
        FAILED,
        INVALID,
        SKIPPED,
        STOP,
        ;

        companion object {
            fun of(status: FnrhSubmissionStatus?): Outcome =
                when (status) {
                    FnrhSubmissionStatus.SENT -> SENT
                    FnrhSubmissionStatus.PENDING -> POSTPONED
                    FnrhSubmissionStatus.FAILED -> FAILED
                    FnrhSubmissionStatus.INVALID -> INVALID
                    FnrhSubmissionStatus.CANCELLED, FnrhSubmissionStatus.DISMISSED, null -> SKIPPED
                }
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FnrhSubmissionSender::class.java)
    }
}
