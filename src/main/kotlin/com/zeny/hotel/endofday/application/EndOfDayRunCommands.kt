package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayAlreadyClosedException
import com.zeny.hotel.endofday.EndOfDayDateMismatchException
import com.zeny.hotel.endofday.EndOfDayNotConfiguredException
import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.EndOfDayRunExistsException
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.endofday.infrastructure.MAX_STORABLE_DATE
import com.zeny.hotel.endofday.infrastructure.MIN_STORABLE_DATE
import com.zeny.hotel.endofday.infrastructure.RUN_BUSINESS_DATE_UK
import com.zeny.hotel.endofday.infrastructure.violatesUnique
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A criação da execução do fechamento (M6 E10): pelo gatilho do horário ([createAutomatic], ator sistema) ou pelo
 * operador ([createManually], ação sensível com aprovação de superior). Só cria a execução `RUNNING` com as etapas do
 * registro; quem a roda é o [EndOfDayEngine]. RN-21: uma execução por data.
 */
@Component
@Suppress("LongParameterList") // as portas do caso de uso
class EndOfDayRunCommands(
    private val runs: EndOfDayRunRepository,
    private val hotel: HotelApi,
    private val registry: EndOfDayStepRegistry,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
    private val realtime: RealtimePublisher,
) {
    /** Cria a execução da [businessDate] disparada pelo horário: ator sistema, sem `triggeredBy`. */
    @Audited(START)
    @Transactional
    fun createAutomatic(businessDate: LocalDate): EndOfDayRun = create(businessDate, RunTrigger.AUTOMATIC, null)

    /**
     * Cria a execução da [businessDate] a pedido do operador. A autorização vem primeiro, antes de qualquer checagem
     * que revele o estado do fechamento: a permissão `endofday.runs.execute` ou a aprovação de superior
     * ([approvalToken]).
     */
    @Audited(START)
    @Transactional
    fun createManually(
        businessDate: LocalDate,
        approvalToken: String?,
    ): EndOfDayRun {
        authorization.requireOrApproval(
            SensitiveAction(START, EndOfDayPermissions.RUNS_EXECUTE, "endofday.business-date:$businessDate"),
            approvalToken,
        )
        return create(businessDate, RunTrigger.MANUAL, actor.current().userId)
    }

    private fun create(
        businessDate: LocalDate,
        trigger: RunTrigger,
        triggeredBy: UserId?,
    ): EndOfDayRun {
        checkCanStart(businessDate)
        val run =
            EndOfDayRun.start(
                businessDate,
                trigger,
                triggeredBy,
                registry.slots,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        try {
            runs.saveAndFlush(run)
        } catch (e: DataIntegrityViolationException) {
            // Outra transação criou a execução da data entre a checagem e a gravação. A execução dela não pode ser lida
            // nesta transação, que já falhou, então o problema sai sem `runId`. Qualquer outra violação sobe como está.
            if (!e.violatesUnique(RUN_BUSINESS_DATE_UK)) throw e
            throw EndOfDayRunExistsException(businessDate, null)
        }
        audit.created(EndOfDayAudit.RUN, run.id) { run.auditState() }
        publishRunChanged(runs, realtime, run)
        return run
    }

    /** As recusas, nesta ordem: data fechada, execução existente, data não corrente e registro vazio. */
    private fun checkCanStart(businessDate: LocalDate) {
        checkNoRun(businessDate)
        val current = hotel.currentBusinessDate()
        if (businessDate != current) throw EndOfDayDateMismatchException(current)
        if (registry.slots.isEmpty()) throw EndOfDayNotConfiguredException()
    }

    /**
     * Uma data fora do alcance do banco ([MIN_STORABLE_DATE] a [MAX_STORABLE_DATE]) não tem execução e não é
     * consultada (a consulta falharia com um 500); cai na recusa seguinte, a da data não corrente.
     */
    private fun checkNoRun(businessDate: LocalDate) {
        if (businessDate !in MIN_STORABLE_DATE..MAX_STORABLE_DATE) return
        val run = runs.findByBusinessDate(businessDate) ?: return
        if (run.status == RunStatus.COMPLETED) throw EndOfDayAlreadyClosedException(businessDate)
        throw EndOfDayRunExistsException(businessDate, run.id.value)
    }

    private companion object {
        const val START = "endofday.run.start"
    }
}
