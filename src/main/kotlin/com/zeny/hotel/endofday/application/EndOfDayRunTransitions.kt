package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.EndOfDayRunStep
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.DomainException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * As transições da execução do fechamento (M6 E10), cada uma na transação dela e auditada. Chamadas da thread do motor
 * (sem contexto de segurança), ficam na trilha com o ator sistema. Toda transição trava a execução
 * ([EndOfDayRunRepository.lockForUpdate]) antes de lê-la, então duas transições da mesma execução nunca se cruzam.
 * Qualquer exceção desfaz a transição, inclusive uma *checked* (`rollbackFor = Throwable`): o Kotlin deixa o trabalho
 * de uma etapa lançar `IOException` sem declarar, e o padrão do Spring confirmaria a transação nesse caso.
 */
@Component
class EndOfDayRunTransitions(
    private val runs: EndOfDayRunRepository,
    private val issues: EndOfDayIssueRepository,
    private val registry: EndOfDayStepRegistry,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * Reconcilia a execução com o [registry] e inicia a próxima etapa ([EndOfDayRun.beginNext]): abre uma pendência
     * [STEP_UNREGISTERED] por etapa pulada e devolve a etapa iniciada, com a tentativa, ou nulo quando a execução
     * terminou (`COMPLETED`, agora ou antes).
     */
    @Audited(EndOfDayAudit.BEGIN_STEP)
    @Transactional(rollbackFor = [Throwable::class])
    fun begin(runId: EndOfDayRunId): StepStart? {
        val run = locked(runId)
        if (run.status == RunStatus.COMPLETED) {
            audit.noChanges()
            return null
        }
        audit.track(EndOfDayAudit.RUN, run.id) { run.auditState() }
        val now = now()
        val result = run.beginNext(registry.slots, now)
        result.skipped.forEach { open(run, it, STEP_UNREGISTERED, it, UNREGISTERED_MESSAGE, now) }
        return result.started
    }

    /**
     * Roda o [work] da etapa [stepName] e a conclui com o resultado dele, na mesma transação. Antes, confere que a
     * etapa ainda está `RUNNING` com a mesma tentativa ([attempts]); se não está (outro executor passou à frente depois
     * de uma trava vencida), devolve `false` sem chamar o [work] e sem efeito. Uma exceção do [work] escapa e desfaz
     * tudo, inclusive as pendências levantadas por ele.
     */
    @Audited(EndOfDayAudit.COMPLETE_STEP)
    @Transactional(rollbackFor = [Throwable::class])
    fun complete(
        runId: EndOfDayRunId,
        stepName: String,
        attempts: Int,
        work: (EndOfDayStepContext) -> EndOfDayStepOutcome,
    ): Boolean {
        val run = locked(runId)
        val step = run.steps.firstOrNull { it.name == stepName }
        if (run.status != RunStatus.RUNNING || step?.status != StepStatus.RUNNING || step.attempts != attempts) {
            audit.noChanges()
            return false
        }
        audit.track(EndOfDayAudit.RUN, run.id) { run.auditState() }
        val sink = EndOfDayIssueSink { kind, reference, message -> save(run, stepName, kind, reference, message) }
        val outcome = work(EndOfDayStepContext(run.id, run.businessDate, sink))
        run.completeStep(stepName, outcome.result, now())
        return true
    }

    /**
     * A tentativa [attempts] da etapa [stepName] falhou pelo [reason] (um código: veja [reasonOf]): etapa e execução
     * `FAILED` e uma pendência [STEP_FAILED] com a referência na etapa e o código como mensagem; devolve `true`. Um
     * [reason] longo é cortado no tamanho do resultado da etapa, para a falha nunca falhar por causa dele.
     *
     * Se a etapa está `RUNNING` noutra tentativa (outro executor a reiniciou depois de uma trava vencida), devolve
     * `false` sem efeito nem pendência: a falha é de uma tentativa que já não vale. Etapa desconhecida, que não está
     * `RUNNING` (concluída por outro executor, por exemplo) ou execução fora de `RUNNING` são recusadas pelo agregado
     * (exceção; a transação é desfeita sem efeito).
     */
    @Audited(EndOfDayAudit.FAIL_STEP)
    @Transactional(rollbackFor = [Throwable::class])
    fun fail(
        runId: EndOfDayRunId,
        stepName: String,
        attempts: Int,
        reason: String,
    ): Boolean {
        val run = locked(runId)
        val step = run.steps.firstOrNull { it.name == stepName }
        if (run.status == RunStatus.RUNNING && step?.status == StepStatus.RUNNING && step.attempts != attempts) {
            audit.noChanges()
            return false
        }
        audit.track(EndOfDayAudit.RUN, run.id) { run.auditState() }
        val now = now()
        val code = reason.take(EndOfDayRunStep.RESULT_LENGTH)
        run.failStep(stepName, code, now)
        open(run, stepName, STEP_FAILED, stepName, code, now)
        return true
    }

    private fun locked(runId: EndOfDayRunId): EndOfDayRun =
        checkNotNull(runs.lockForUpdate(runId)) { "Execução do fechamento inexistente: ${runId.value}." }

    @Suppress("LongParameterList") // os campos da pendência
    private fun open(
        run: EndOfDayRun,
        stepName: String,
        kind: String,
        reference: String,
        message: String,
        now: Instant,
    ) {
        val issue = issues.save(EndOfDayIssue.open(run.id, stepName, kind, reference, message, now))
        audit.created(EndOfDayAudit.RUN_ISSUE, issue.id) { issue.auditState() }
    }

    private fun save(
        run: EndOfDayRun,
        stepName: String,
        kind: String,
        reference: String?,
        message: String,
    ) {
        issues.save(EndOfDayIssue.open(run.id, stepName, kind, reference, message, now()))
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        /** A pendência de uma etapa que falhou: referência = a etapa, mensagem = o código da falha. */
        const val STEP_FAILED = "STEP_FAILED"

        /** A pendência de uma etapa pulada por ter saído do registro: referência = a etapa. */
        const val STEP_UNREGISTERED = "STEP_UNREGISTERED"

        private const val UNREGISTERED_MESSAGE = "A etapa não está registrada nesta versão do servidor e foi pulada."

        /**
         * O motivo gravado de uma falha: o `ErrorCode` de uma [DomainException], senão o nome simples da classe da
         * exceção. **Nunca** a mensagem, que pode ter dado pessoal.
         */
        fun reasonOf(error: Throwable): String =
            if (error is DomainException) {
                error.code.value
            } else {
                error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }
            }
    }
}

/** Os códigos de auditoria do fechamento. */
internal object EndOfDayAudit {
    const val RUN = "endofday.run"
    const val RUN_ISSUE = "endofday.run-issue"
    const val BEGIN_STEP = "endofday.run-step.begin"
    const val COMPLETE_STEP = "endofday.run-step.complete"
    const val FAIL_STEP = "endofday.run-step.fail"
}

/** O retrato da execução para a trilha: situação e etapas (resultados são códigos). */
internal data class EndOfDayRunAuditState(
    val id: String,
    val businessDate: String,
    val status: String,
    val steps: List<EndOfDayRunStepAuditState>,
)

internal data class EndOfDayRunStepAuditState(
    val name: String,
    val status: String,
    val attempts: Int,
    val result: String?,
)

internal fun EndOfDayRun.auditState() =
    EndOfDayRunAuditState(
        id.value.toString(),
        businessDate.toString(),
        status.name,
        steps.map { EndOfDayRunStepAuditState(it.name, it.status.name, it.attempts, it.result) },
    )

/** O retrato da pendência para a trilha: sem a mensagem (fica só na API, para quem tem a permissão). */
internal data class EndOfDayIssueAuditState(
    val id: String,
    val runId: String,
    val stepName: String?,
    val kind: String,
    val reference: String?,
)

internal fun EndOfDayIssue.auditState() =
    EndOfDayIssueAuditState(id.toString(), runId.value.toString(), stepName, kind, reference)
