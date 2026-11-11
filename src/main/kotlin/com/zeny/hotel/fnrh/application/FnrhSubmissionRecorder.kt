package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * Grava o resultado do envio de uma submissão, cada um na sua transação e com trilha (ator: o sistema). Fica num bean
 * à parte do [FnrhSubmissionSender], que chama o Serpro fora de transação: a autoinvocação pularia o proxy, e com ele
 * o `@Transactional` e o `@Audited`.
 *
 * Numa submissão que já está em estado terminal (por exemplo, cancelada pelo listener do desfazer enquanto o job a
 * enviava), não grava nada, não alerta e só registra no log (D-E08-17). Cada método devolve a situação da submissão
 * depois dele.
 *
 * Quando a submissão passa a `FAILED` ou `INVALID`, notifica quem tem `fnrh.submissions.manage` (D-E08-11) **depois
 * do commit**, em transação própria: uma falha do alerta não desfaz o resultado gravado. O texto leva só o código da
 * reserva e rótulos fixos, nunca dado de hóspede.
 */
@Component
class FnrhSubmissionRecorder(
    private val store: FnrhSubmissionStore,
    private val audit: AuditContext,
    private val policy: FnrhRetryPolicy,
    private val clock: Clock,
    private val notifications: NotificationsApi,
    transactionManager: PlatformTransactionManager,
) {
    private val alertTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

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
        if (submission.status != FnrhSubmissionStatus.PENDING) {
            log.info("FNRH: submissão {} já está {}; o resultado do envio não é gravado", id, submission.status)
            audit.noChanges()
            return submission.status
        }
        audit.track(FnrhAudit.SUBMISSION, id) { submission.auditState() }
        submission.transition()
        store.save(submission)
        if (submission.status == FnrhSubmissionStatus.FAILED || submission.status == FnrhSubmissionStatus.INVALID) {
            alertAfterCommit(submission)
        }
        return submission.status
    }

    /**
     * O resultado é gravado independentemente do alerta: este sai depois do commit, em transação própria, e uma
     * falha do `notifications` só vai ao log (id, operação e classe da exceção), sem desfazer nada. Custo aceito: o
     * alerta pode se perder; a submissão segue visível na fila e na métrica.
     */
    private fun alertAfterCommit(submission: FnrhSubmission) {
        val id = submission.id
        val operation = submission.operation
        val text = alertText(submission)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    @Suppress("TooGenericExceptionCaught") // o alerta nunca pode derrubar o envio
                    try {
                        alertTransaction.executeWithoutResult { notify(text) }
                    } catch (e: Exception) {
                        log.warn("FNRH: alerta da submissão {} ({}) não enviado: {}", id, operation, e.javaClass.name)
                    }
                }
            },
        )
    }

    private fun notify(text: String) {
        notifications.notify(
            NotificationsTypes.INTEGRATION_FAILURE,
            "Falha no envio à FNRH",
            text,
            NotificationRecipients.withPermission(FnrhPermissions.SUBMISSIONS_MANAGE),
        )
    }

    private fun alertText(submission: FnrhSubmission): String {
        val situation = if (submission.status == FnrhSubmissionStatus.INVALID) "dados inválidos" else "falhou"
        return "Reserva ${submission.reservationCode}: ${label(submission.operation)} — $situation"
    }

    private fun label(operation: FnrhOperation): String =
        when (operation) {
            FnrhOperation.REGISTER_RESERVATION -> "registro da reserva"
            FnrhOperation.UPDATE_RESERVATION -> "atualização da reserva"
            FnrhOperation.CANCEL_RESERVATION -> "cancelamento da reserva"
            FnrhOperation.CHECK_IN -> "check-in"
            FnrhOperation.UNDO_CHECK_IN -> "desfazer check-in"
            FnrhOperation.CHECK_OUT -> "check-out"
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FnrhSubmissionRecorder::class.java)
    }
}
