package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/*
 * Os enums que a web expõe, espelhando os do domínio com os mesmos nomes (a web só fala com `application`, ARCHITECTURE
 * §4). O mapeamento é por nome: valor novo no domínio sem o espelho falha alto nos testes da API.
 */

/** A operação de uma submissão, como a API a mostra. */
enum class FnrhSubmissionOperationView {
    REGISTER_RESERVATION,
    UPDATE_RESERVATION,
    CANCEL_RESERVATION,
    CHECK_IN,
    UNDO_CHECK_IN,
    CHECK_OUT,
}

/** A situação de uma submissão, como a API a mostra. */
enum class FnrhSubmissionStatusView { PENDING, SENT, FAILED, INVALID, CANCELLED, DISMISSED }

/** Por que uma submissão falhou, como a API mostra. */
enum class FnrhSubmissionFailureView {
    VALIDATION,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    SERVER_ERROR,
    UNAVAILABLE,
    UNKNOWN_OUTCOME,
    INTERNAL_ERROR,
    REMOTE_CANCELLED,
    NO_REMOTE_OPERATION,
}

/** Um erro de validação local de um hóspede, como a API o mostra: campo e código, nunca o valor. */
data class FnrhSubmissionErrorView(
    val guestId: UUID,
    val field: String,
    val code: String,
)

/** Uma submissão à FNRH como a API a mostra. Sem dado de hóspede e sem o link de pré-check-in. */
data class FnrhSubmissionView(
    val id: UUID,
    val reservationId: UUID,
    val reservationCode: String,
    val operation: FnrhSubmissionOperationView,
    val status: FnrhSubmissionStatusView,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastFailure: FnrhSubmissionFailureView?,
    val errors: List<FnrhSubmissionErrorView>,
    val createdAt: Instant,
    val sentAt: Instant?,
)

/**
 * As pendências do envio à FNRH (RF-FNRH-03, D-E08-12 e 16): listar exige `fnrh.submissions.read`; reenviar e
 * encerrar à mão exigem `fnrh.submissions.manage`. Usa a entidade, nunca o `FnrhSubmissionRecorder` (que só aceita
 * `PENDING`).
 */
@Service
class FnrhSubmissionsService(
    private val store: FnrhSubmissionStore,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Uma página por `sequence` crescente; o cursor é a `sequence` da última submissão devolvida. */
    @Transactional(readOnly = true)
    fun list(
        status: FnrhSubmissionStatusView?,
        page: PageParams,
    ): CursorPage<FnrhSubmissionView> {
        authorization.require(FnrhPermissions.SUBMISSIONS_READ)
        val after = page.decodedCursor()?.long(0)
        val rows = store.page(status?.let { FnrhSubmissionStatus.valueOf(it.name) }, after, page.limit + 1)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(checkNotNull(it.sequence).toString()) }
            .map { it.view() }
    }

    /** Volta `FAILED` ou `INVALID` à fila, sem tentativas nem erros (D-E08-12). */
    @Audited(FnrhAudit.RETRY_SUBMISSION)
    @Transactional
    fun retry(id: UUID): FnrhSubmissionView = change(id) { retry(clock.instant()) }

    /** Encerra à mão `FAILED` ou `INVALID`: deixa de segurar a fila (D-E08-16). */
    @Audited(FnrhAudit.DISMISS_SUBMISSION)
    @Transactional
    fun dismiss(id: UUID): FnrhSubmissionView = change(id) { dismiss() }

    private fun change(
        id: UUID,
        transition: FnrhSubmission.() -> Unit,
    ): FnrhSubmissionView {
        authorization.require(FnrhPermissions.SUBMISSIONS_MANAGE)
        val submission =
            store.find(id)
                ?: throw NotFoundException(FnrhErrorCodes.SUBMISSION_NOT_FOUND, "Submissão à FNRH não encontrada.")
        audit.track(FnrhAudit.SUBMISSION, id) { submission.auditState() }
        submission.transition()
        store.save(submission)
        return submission.view()
    }

    private fun FnrhSubmission.view() =
        FnrhSubmissionView(
            id = id,
            reservationId = reservationId,
            reservationCode = reservationCode,
            operation = FnrhSubmissionOperationView.valueOf(operation.name),
            status = FnrhSubmissionStatusView.valueOf(status.name),
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            lastFailure = lastFailure?.let { FnrhSubmissionFailureView.valueOf(it.name) },
            errors = errors.map { FnrhSubmissionErrorView(it.guestId, it.field, it.code) },
            createdAt = createdAt,
            sentAt = sentAt,
        )
}
