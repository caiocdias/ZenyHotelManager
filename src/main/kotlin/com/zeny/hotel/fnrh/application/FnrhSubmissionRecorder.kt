package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Grava o resultado do envio de uma submissão, cada um na sua transação e com trilha (ator: o sistema). Fica num bean
 * à parte do [FnrhSubmissionSender], que chama o Serpro fora de transação: a autoinvocação pularia o proxy, e com ele
 * o `@Transactional` e o `@Audited`.
 *
 * Numa submissão que já está em estado terminal (por exemplo, cancelada pelo listener do desfazer enquanto o job a
 * enviava), não grava nada e só registra no log (D-E08-17). Cada método devolve a situação da submissão depois dele.
 */
@Component
class FnrhSubmissionRecorder(
    private val store: FnrhSubmissionStore,
    private val audit: AuditContext,
    private val policy: FnrhRetryPolicy,
    private val clock: Clock,
) {
    /** O Serpro recebeu a operação (ou ela não precisava de chamada). */
    @Audited(FnrhAudit.SEND_SUBMISSION)
    @Transactional
    fun sent(id: UUID): FnrhSubmissionStatus = change(id) { markSent(clock.instant()) }

    /** O Serpro falhou: reagenda a temporária pela política ou passa a `FAILED` (D-E08-4). */
    @Audited(FnrhAudit.SEND_SUBMISSION)
    @Transactional
    fun failed(
        id: UUID,
        failure: FnrhSubmissionFailure,
    ): FnrhSubmissionStatus = change(id) { recordFailure(failure, clock.instant(), policy) }

    /** Falha nossa e permanente, sem chamada (`REMOTE_CANCELLED`, `NO_REMOTE_OPERATION`). */
    @Audited(FnrhAudit.SEND_SUBMISSION)
    @Transactional
    fun failedLocally(
        id: UUID,
        reason: FnrhSubmissionFailure,
    ): FnrhSubmissionStatus = change(id) { fail(reason, clock.instant()) }

    /** A validação local recusou os dados (D-E08-10): guarda os erros, sem valores. */
    @Audited(FnrhAudit.SEND_SUBMISSION)
    @Transactional
    fun invalid(
        id: UUID,
        errors: List<FnrhSubmissionError>,
    ): FnrhSubmissionStatus = change(id) { markInvalid(errors, clock.instant()) }

    private fun change(
        id: UUID,
        transition: FnrhSubmission.() -> Unit,
    ): FnrhSubmissionStatus {
        val submission = checkNotNull(store.find(id)) { "Submissão $id não encontrada." }
        if (submission.isTerminal) {
            log.info("FNRH: submissão {} já está {}; o resultado do envio não é gravado", id, submission.status)
            audit.noChanges()
            return submission.status
        }
        audit.track(FnrhAudit.SUBMISSION, id) { submission.auditState() }
        submission.transition()
        store.save(submission)
        return submission.status
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FnrhSubmissionRecorder::class.java)
    }
}
