package com.zeny.hotel.endofday.infrastructure

import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.hotel.HotelApi
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * O que o motor do fechamento mede (M6 E10): a duração de cada chamada que termina a execução
 * (`zeny.endofday.run.duration`, tags `outcome` = `completed` ou `failed` e `resumed` = `true` ou `false`, sempre de
 * conjunto fechado) e as falhas de etapa (`zeny.endofday.run.failures`). No Prometheus:
 * `zeny_endofday_run_duration_seconds_*` e `zeny_endofday_run_failures_total`.
 */
@Component
class EndOfDayRunMetrics(
    private val registry: MeterRegistry,
) {
    private val failures: Counter =
        Counter
            .builder(FAILURES)
            .description("Falhas de etapa do fechamento do dia")
            .register(registry)

    /** Começa a medir uma chamada do motor. */
    fun start(): Timer.Sample = Timer.start(registry)

    /** Para a medida de uma chamada que terminou a execução no [status]; [resumed]: já havia trabalho de antes. */
    fun finished(
        sample: Timer.Sample,
        status: RunStatus,
        resumed: Boolean,
    ) {
        val outcome = if (status == RunStatus.COMPLETED) "completed" else "failed"
        sample.stop(
            Timer
                .builder(DURATION)
                .description("Duração da chamada do motor que termina a execução do fechamento do dia")
                .tag(OUTCOME_TAG, outcome)
                .tag(RESUMED_TAG, resumed.toString())
                .register(registry),
        )
    }

    /** Uma etapa falhou e a execução parou em `FAILED`. */
    fun failure() = failures.increment()

    companion object {
        const val DURATION = "zeny.endofday.run.duration"
        const val FAILURES = "zeny.endofday.run.failures"
        const val OUTCOME_TAG = "outcome"
        const val RESUMED_TAG = "resumed"
    }
}

/**
 * Os gauges do fechamento do dia (M6 E10), para alerta:
 *
 * - `zeny.endofday.runs.failed`: execuções `FAILED` (esperam a retomada manual);
 * - `zeny.endofday.overdue.seconds`: segundos desde o vencimento ([EndOfDaySchedule.dueAt]) da data operacional
 *   corrente sem execução `COMPLETED`. É `0` antes do vencimento e com o registro de etapas vazio (o automático não
 *   roda sem etapa: o alerta não dispara entre a E10 e a E11).
 *
 * Como o [com.zeny.hotel.shared.infrastructure.events.OutboxMetrics]: os valores são lidos a cada
 * `zeny.endofday.metrics.refresh-interval` e a raspagem só lê a última leitura. Se a leitura falhar (banco fora), os
 * gauges ficam `NaN` até a próxima dar certo. Sem ShedLock, de propósito: só lê, e cada instância precisa dos próprios
 * valores. O atraso é medido no relógio da raspagem, a partir do vencimento lido.
 */
@Component
class EndOfDayMetrics(
    private val hotel: HotelApi,
    private val runs: EndOfDayRunRepository,
    private val steps: EndOfDayStepRegistry,
    private val clock: Clock,
) : MeterBinder {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Snapshot? = null

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder(RUNS_FAILED) { snapshot?.failedRuns?.toDouble() ?: Double.NaN }
            .description("Execuções do fechamento do dia em FAILED, à espera da retomada")
            .register(registry)
        Gauge
            .builder(OVERDUE) { overdueSeconds() }
            .baseUnit("seconds")
            .description("Segundos desde o horário do fechamento da data corrente sem execução COMPLETED")
            .register(registry)
    }

    /** Lê o banco de novo; os gauges passam a mostrar esta leitura. */
    @Scheduled(fixedDelayString = "\${zeny.endofday.metrics.refresh-interval}")
    fun refresh() {
        snapshot =
            try {
                read()
            } catch (
                @Suppress("TooGenericExceptionCaught") error: RuntimeException, // qualquer falha da leitura é NaN
            ) {
                log.warn("Não foi possível ler as métricas do fechamento do dia ({})", error.javaClass.simpleName)
                null
            }
    }

    private fun read(): Snapshot = Snapshot(runs.countByStatus(RunStatus.FAILED), overdueSince())

    /** O vencimento da data corrente, se já passou e a data não está fechada; nulo se não há atraso a medir. */
    private fun overdueSince(): Instant? {
        val date = steps.slots.takeIf { it.isNotEmpty() }?.let { hotel.currentBusinessDate() } ?: return null
        val dueAt = EndOfDaySchedule.dueAt(date, hotel.operationalSettings().endOfDayTime, hotel.timezone())
        val closed = runs.findByBusinessDate(date)?.status == RunStatus.COMPLETED
        return dueAt.takeUnless { closed || clock.instant() < dueAt }
    }

    private fun overdueSeconds(): Double {
        val read = snapshot ?: return Double.NaN
        val since = read.overdueSince ?: clock.instant()
        return Duration.between(since, clock.instant()).toMillis().coerceAtLeast(0) / MILLIS_PER_SECOND
    }

    private data class Snapshot(
        val failedRuns: Long,
        val overdueSince: Instant?,
    )

    companion object {
        const val RUNS_FAILED = "zeny.endofday.runs.failed"
        const val OVERDUE = "zeny.endofday.overdue.seconds"
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
