package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import java.time.Instant
import java.util.UUID

/** A fila de submissões à FNRH (D-E08-2, D-E08-3). */
interface FnrhSubmissionStore {
    /** Grava a submissão se a chave `(reservationId, operation, eventKey)` ainda não existe; `false` se já existia. */
    fun recordIfAbsent(submission: FnrhSubmission): Boolean

    fun find(id: UUID): FnrhSubmission?

    fun save(submission: FnrhSubmission)

    /**
     * Grava a escrita em curso (D-E08-15) em transação própria (`REQUIRES_NEW`), que sobrevive ao chamador. Só marca a
     * submissão não resolvida (`PENDING`, `FAILED`, `INVALID`); numa terminal, não faz nada.
     */
    fun markInFlight(id: UUID)

    /**
     * Cancela a submissão `PENDING`, `FAILED` ou `INVALID` **sem escrita em curso**, de forma condicional no banco (um
     * só `UPDATE`): se o job marcou `in_flight` entre a leitura e o cancelamento, não cancela. `false` se não cancelou.
     */
    fun cancelIfIdle(
        id: UUID,
        now: Instant,
    ): Boolean

    /**
     * As cabeças vencidas da fila: por reserva, a de menor `(causalVersion, posto, sequence)` entre as não resolvidas
     * (`PENDING`, `FAILED`, `INVALID`), e só se estiver `PENDING` com `nextAttemptAt <= now`. Ordenadas por `sequence`.
     */
    fun dueHeads(
        now: Instant,
        limit: Int,
        reservationId: UUID? = null,
    ): List<UUID>

    /** As submissões não resolvidas (`PENDING`, `FAILED`, `INVALID`) da reserva e da operação. */
    fun unresolvedOf(
        reservationId: UUID,
        operation: FnrhOperation,
    ): List<FnrhSubmission>

    /** Uma página por `sequence` crescente, depois de [after] e, se dado, só do [status]. */
    fun page(
        status: FnrhSubmissionStatus?,
        after: Long?,
        limit: Int,
    ): List<FnrhSubmission>

    fun countByStatus(): Map<FnrhSubmissionStatus, Long>
}

/** O id da reserva no Serpro. O [toString] não mostra o link de pré-check-in (URL de acesso a dados pessoais). */
data class FnrhRemoteReservation(
    val reservationId: UUID,
    val environment: FnrhEnvironment,
    val remoteId: FnrhRemoteId,
    val precheckinLink: String?,
    val cancelled: Boolean,
) {
    override fun toString(): String =
        "FnrhRemoteReservation(reservationId=$reservationId, environment=$environment, remoteId=$remoteId, " +
            "cancelled=$cancelled)"
}

/** Os ids da pessoa e do hóspede no Serpro para um hóspede da reserva. */
data class FnrhRemoteGuest(
    val reservationId: UUID,
    val guestId: UUID,
    val environment: FnrhEnvironment,
    val personId: FnrhRemoteId,
    val guestRemoteId: FnrhRemoteId?,
    val checkedOut: Boolean,
)

/**
 * Os ids que o Serpro devolveu, por ambiente (D-E08-13). Toda escrita roda em transação própria (`REQUIRES_NEW`),
 * porque é chamada entre chamadas ao Serpro, e limpa o `in_flight` da submissão `submissionId` na mesma transação
 * em que grava o id (D-E08-15).
 */
interface FnrhRemoteIds {
    fun reservation(
        reservationId: UUID,
        env: FnrhEnvironment,
    ): FnrhRemoteReservation?

    fun saveReservation(
        r: FnrhRemoteReservation,
        submissionId: UUID,
    )

    fun guests(
        reservationId: UUID,
        env: FnrhEnvironment,
    ): List<FnrhRemoteGuest>

    fun saveGuest(
        g: FnrhRemoteGuest,
        submissionId: UUID,
    )

    /** O `person_id` que o hóspede já tem no ambiente, de qualquer reserva (D-E08-7). */
    fun knownPerson(
        guestId: UUID,
        env: FnrhEnvironment,
    ): FnrhRemoteId?
}
