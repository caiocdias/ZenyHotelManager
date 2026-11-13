package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayRunInProgressException
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.EndOfDayRunStep
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Quem disparou a execução, como a API mostra. */
enum class EndOfDayRunTriggerView { AUTOMATIC, MANUAL }

/** A situação da execução, como a API mostra. */
enum class EndOfDayRunStatusView { RUNNING, FAILED, COMPLETED }

/** A situação de uma etapa, como a API mostra. */
enum class EndOfDayStepStatusView { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

/** Uma etapa da execução, como a API mostra. */
data class EndOfDayRunStepView(
    val name: String,
    val position: Int,
    val status: EndOfDayStepStatusView,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val result: String?,
    val attempts: Int,
)

/** A execução do fechamento, com as etapas em ordem de posição, como a API mostra. */
data class EndOfDayRunView(
    val id: UUID,
    val businessDate: LocalDate,
    val trigger: EndOfDayRunTriggerView,
    val status: EndOfDayRunStatusView,
    val triggeredBy: UUID?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val version: Long,
    val steps: List<EndOfDayRunStepView>,
)

/**
 * A fachada do fechamento do dia para a API (M6 E10). Sem transação própria: cada passo confirma a dele antes do
 * seguinte, porque o motor roda a execução em outra thread e só enxerga o que já foi confirmado.
 */
@Component
class EndOfDayService(
    private val commands: EndOfDayRunCommands,
    private val engine: EndOfDayEngine,
    private val runs: EndOfDayRunRepository,
    @Value("\${zeny.endofday.manual-wait}") private val manualWait: Duration,
    transactionManager: PlatformTransactionManager,
) {
    private val reading = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * O fechamento manual da [businessDate] ([EndOfDayRunCommands.createManually]: a permissão ou a aprovação de
     * superior pelo [approvalToken] vêm antes de tudo): cria a execução, roda-a pelo motor esperando até
     * `zeny.endofday.manual-wait` e devolve a execução relida **no estado em que estiver**: `COMPLETED`, `FAILED`, ou
     * `RUNNING` quando o prazo passou e o motor continua, ou quando outro executor tem a trava.
     */
    fun startManually(
        businessDate: LocalDate,
        approvalToken: String?,
    ): EndOfDayRunView {
        val run = commands.createManually(businessDate, approvalToken)
        engine.execute(run.id, manualWait)
        return view(run.id)
    }

    /**
     * A retomada manual da execução [runId] ([EndOfDayRunCommands.resumeManually]: a permissão ou a aprovação de
     * superior pelo [approvalToken] vêm antes de tudo): confirma a retomada, roda o motor esperando até
     * `zeny.endofday.manual-wait` e devolve a execução relida no estado em que estiver. Outro executor com a trava é
     * [EndOfDayRunInProgressException].
     */
    fun resumeManually(
        runId: UUID,
        approvalToken: String?,
    ): EndOfDayRunView {
        val id = commands.resumeManually(EndOfDayRunId(runId), approvalToken).id
        if (engine.execute(id, manualWait) == EndOfDayExecution.Busy) throw EndOfDayRunInProgressException()
        return view(id)
    }

    private fun view(id: EndOfDayRunId): EndOfDayRunView =
        checkNotNull(reading.execute { runs.findById(id.value).orElse(null)?.toView() }) {
            "Execução do fechamento inexistente: ${id.value}."
        }
}

private fun EndOfDayRun.toView() =
    EndOfDayRunView(
        id.value,
        businessDate,
        trigger.toView(),
        status.toView(),
        triggeredBy?.value,
        startedAt,
        finishedAt,
        version,
        steps.map { it.toView() },
    )

private fun EndOfDayRunStep.toView() =
    EndOfDayRunStepView(name, position, status.toView(), startedAt, finishedAt, result, attempts)

private fun RunTrigger.toView() =
    when (this) {
        RunTrigger.AUTOMATIC -> EndOfDayRunTriggerView.AUTOMATIC
        RunTrigger.MANUAL -> EndOfDayRunTriggerView.MANUAL
    }

private fun RunStatus.toView() =
    when (this) {
        RunStatus.RUNNING -> EndOfDayRunStatusView.RUNNING
        RunStatus.FAILED -> EndOfDayRunStatusView.FAILED
        RunStatus.COMPLETED -> EndOfDayRunStatusView.COMPLETED
    }

private fun StepStatus.toView() =
    when (this) {
        StepStatus.PENDING -> EndOfDayStepStatusView.PENDING
        StepStatus.RUNNING -> EndOfDayStepStatusView.RUNNING
        StepStatus.COMPLETED -> EndOfDayStepStatusView.COMPLETED
        StepStatus.FAILED -> EndOfDayStepStatusView.FAILED
        StepStatus.SKIPPED -> EndOfDayStepStatusView.SKIPPED
    }
