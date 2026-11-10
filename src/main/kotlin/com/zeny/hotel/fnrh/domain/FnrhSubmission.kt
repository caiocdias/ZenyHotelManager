package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A operação que uma submissão leva ao Serpro. [rank] é o posto na ordem causal da reserva (D-E08-3). */
@Suppress("MagicNumber") // os postos são o dado da enumeração
enum class FnrhOperation(
    val rank: Int,
) {
    REGISTER_RESERVATION(0),
    UPDATE_RESERVATION(1),
    CANCEL_RESERVATION(2),
    CHECK_IN(3),
    UNDO_CHECK_IN(4),
    CHECK_OUT(5),
}

/** `FAILED` e `INVALID` seguram a fila da reserva até o `retry` ou o `dismiss` (D-E08-3). */
enum class FnrhSubmissionStatus { PENDING, SENT, FAILED, INVALID, CANCELLED, DISMISSED }

/**
 * Por que uma submissão falhou. [temporary] diz se vale tentar de novo. Os valores até `UNKNOWN_OUTCOME` espelham
 * `FnrhFailure` (sem `NOT_CONFIGURED`); os demais nascem do nosso lado.
 */
enum class FnrhSubmissionFailure(
    val temporary: Boolean,
) {
    VALIDATION(false),
    UNAUTHORIZED(false),
    FORBIDDEN(false),
    NOT_FOUND(false),
    SERVER_ERROR(true),
    UNAVAILABLE(true),
    UNKNOWN_OUTCOME(true),

    /** Exceção inesperada no envio (D-E08-17): conta como falha temporária. */
    INTERNAL_ERROR(true),

    /** A reserva remota foi cancelada por nós e a API não a reativa (D-E08-9). */
    REMOTE_CANCELLED(false),

    /** A API não tem a operação (desfazer check-in já enviado, D-E08-8). */
    NO_REMOTE_OPERATION(false),
}

/** Um erro de validação local de um hóspede: o campo e o código, **nunca o valor** (D-E08-10). */
@Embeddable
data class FnrhSubmissionError(
    @Column(name = "guest_id", nullable = false)
    val guestId: UUID,
    @Column(nullable = false)
    val field: String,
    @Column(nullable = false)
    val code: String,
)

/**
 * Um envio à FNRH, um por evento (D-E08-2, RF-FNRH-03). Só a cabeça da reserva, a de menor
 * `(causalVersion, operation.rank, sequence)` entre as não resolvidas, é enviada (D-E08-3).
 *
 * Fim de linha: `SENT`, `CANCELLED` e `DISMISSED` ([isTerminal]) não mudam mais; transição neles é erro de programação
 * (`IllegalStateException`), salvo `retry` e `dismiss`, que respondem `409`. [inFlight] marca a escrita em curso
 * (D-E08-15) e só `clearInFlight` e `markSent` a desligam.
 *
 * Nada de dado de hóspede: o [reservationCode] e os [errors] ficam fora do `toString`.
 */
@Entity
@Table(name = "submission", schema = "fnrh")
@Suppress("TooManyFunctions", "LongParameterList") // uma função por transição; reconstituída com todos os campos
class FnrhSubmission private constructor(
    @Id
    val id: UUID,
    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,
    @Column(name = "reservation_code", nullable = false)
    val reservationCode: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val operation: FnrhOperation,
    @Column(name = "event_key", nullable = false)
    val eventKey: String,
    @Column(name = "causal_version", nullable = false)
    val causalVersion: Long,
    @Column(name = "performed_by")
    val performedBy: UUID?,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    /** O posto da operação, gravado junto para a fila ordenar no banco (D-E08-3). */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "operation_rank", nullable = false)
    val operationRank: Int = operation.rank

    /** O cursor da API: gerado pelo banco (identidade) ao inserir; `null` até lá. */
    @Generated(event = [EventType.INSERT])
    @Column(insertable = false, updatable = false)
    var sequence: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: FnrhSubmissionStatus = FnrhSubmissionStatus.PENDING
        protected set

    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = createdAt
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure")
    var lastFailure: FnrhSubmissionFailure? = null
        protected set

    // updatable = false: quem grava e limpa a marca é o `FnrhSubmissionStore` (D-E08-15); um `save` com a entidade
    // lida antes da marca não pode desfazê-la. O banco é a verdade; a entidade só a reflete na leitura.
    @Column(name = "in_flight", nullable = false, updatable = false)
    var inFlight: Boolean = false
        protected set

    @Column(name = "sent_at")
    var sentAt: Instant? = null
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "submission_error", schema = "fnrh", joinColumns = [JoinColumn(name = "submission_id")])
    private val errorList: MutableList<FnrhSubmissionError> = mutableListOf()

    @Version
    @Column(nullable = false)
    var version: Long = 0
        protected set

    /** Os erros da validação local (`INVALID`), sem valores. */
    val errors: List<FnrhSubmissionError> get() = errorList.toList()

    /** Fim de linha: não muda mais e não segura a fila. */
    val isTerminal: Boolean
        get() =
            status == FnrhSubmissionStatus.SENT ||
                status == FnrhSubmissionStatus.CANCELLED ||
                status == FnrhSubmissionStatus.DISMISSED

    /** Grava, em transação própria e antes da chamada, que uma escrita que cria recurso está em curso. */
    fun markInFlight() {
        requireOpen("marcar a escrita em curso")
        inFlight = true
    }

    /** O id remoto já foi gravado: a escrita em curso terminou. */
    fun clearInFlight() {
        requireOpen("limpar a escrita em curso")
        inFlight = false
    }

    fun markSent(now: Instant) {
        requireOpen("marcar como enviada")
        status = FnrhSubmissionStatus.SENT
        sentAt = now
        inFlight = false
    }

    /**
     * Conta uma tentativa com falha. Permanente, ou a [FnrhRetryPolicy.maxAttempts]-ésima temporária: `FAILED` e
     * devolve `true` (o alerta é de quem chamou). Senão reagenda pela política e devolve `false`.
     */
    fun recordFailure(
        failure: FnrhSubmissionFailure,
        now: Instant,
        policy: FnrhRetryPolicy,
    ): Boolean {
        requireOpen("registrar falha")
        attempts++
        lastFailure = failure
        if (!failure.temporary || attempts >= policy.maxAttempts) {
            status = FnrhSubmissionStatus.FAILED
            return true
        }
        nextAttemptAt = now.plus(policy.delayAfter(attempts))
        return false
    }

    /** Falha local e permanente, sem chamada (`REMOTE_CANCELLED`, `NO_REMOTE_OPERATION`). */
    fun fail(
        reason: FnrhSubmissionFailure,
        now: Instant,
    ) {
        requireOpen("falhar")
        lastFailure = reason
        status = FnrhSubmissionStatus.FAILED
        nextAttemptAt = now
    }

    /** A validação local recusou o check-in: guarda os erros (campo e código) e segura a fila. */
    fun markInvalid(
        errors: List<FnrhSubmissionError>,
        now: Instant,
    ) {
        requireOpen("invalidar")
        errorList.clear()
        errorList.addAll(errors)
        status = FnrhSubmissionStatus.INVALID
        nextAttemptAt = now
    }

    fun cancel(now: Instant) {
        requireOpen("cancelar")
        status = FnrhSubmissionStatus.CANCELLED
        nextAttemptAt = now
    }

    /** Volta `FAILED` ou `INVALID` à fila, sem tentativas nem erros. Preserva [inFlight] (D-E08-15). */
    fun retry(now: Instant) {
        if (status != FnrhSubmissionStatus.FAILED && status != FnrhSubmissionStatus.INVALID) {
            throw ConflictException(
                FnrhErrorCodes.SUBMISSION_NOT_RETRYABLE,
                "Só uma submissão com falha ou inválida pode ser reenviada.",
            )
        }
        status = FnrhSubmissionStatus.PENDING
        attempts = 0
        errorList.clear()
        lastFailure = null
        nextAttemptAt = now
    }

    /** Encerra à mão `FAILED` ou `INVALID`: `DISMISSED` não segura a fila (D-E08-16). */
    @Suppress("UNUSED_PARAMETER") // mantém a assinatura simétrica das demais transições
    fun dismiss(now: Instant) {
        if (status != FnrhSubmissionStatus.FAILED && status != FnrhSubmissionStatus.INVALID) {
            throw ConflictException(
                FnrhErrorCodes.SUBMISSION_NOT_DISMISSABLE,
                "Só uma submissão com falha ou inválida pode ser encerrada.",
            )
        }
        status = FnrhSubmissionStatus.DISMISSED
    }

    private fun requireOpen(action: String) {
        check(!isTerminal) { "Submissão $id em estado terminal ($status): não pode $action." }
    }

    override fun toString(): String = "FnrhSubmission(id=$id, operation=$operation, status=$status, attempts=$attempts)"

    companion object {
        @Suppress("LongParameterList") // os campos do evento que a originou
        fun create(
            reservationId: UUID,
            reservationCode: String,
            operation: FnrhOperation,
            eventKey: String,
            causalVersion: Long,
            performedBy: UUID?,
            occurredAt: Instant,
            now: Instant,
        ): FnrhSubmission =
            FnrhSubmission(
                id = UuidV7.generate(),
                reservationId = reservationId,
                reservationCode = reservationCode,
                operation = operation,
                eventKey = eventKey,
                causalVersion = causalVersion,
                performedBy = performedBy,
                occurredAt = occurredAt,
                createdAt = now,
            )
    }
}
