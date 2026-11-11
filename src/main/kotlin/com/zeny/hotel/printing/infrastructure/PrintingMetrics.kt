package com.zeny.hotel.printing.infrastructure

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.application.PrintJobStore
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * O gauge `zeny.printing.jobs` com a tag `status` (D-E10-10), lido do banco a cada raspagem: `PENDING` e `SENT` são a
 * fila; `FAILED` conta só os terminados dentro de `content-retention`, para mostrar falhas recentes e não um total que
 * só cresce. Sem alerta: o módulo não chama `notifications`. Uma leitura que falha dá `NaN`, nunca derruba a raspagem.
 */
@Component
class PrintingMetrics(
    private val store: ObjectProvider<PrintJobStore>,
    private val properties: PrintingProperties,
    private val clock: Clock,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        listOf(PrintJobStatus.PENDING, PrintJobStatus.SENT, PrintJobStatus.FAILED).forEach { status ->
            Gauge
                .builder(JOBS) { count(status) }
                .tag("status", status.name)
                .description("Jobs de impressão: a fila (PENDING, SENT) e as falhas recentes (FAILED).")
                .register(registry)
        }
    }

    private fun count(status: PrintJobStatus): Double =
        try {
            val jobs = store.getObject()
            if (status == PrintJobStatus.FAILED) {
                jobs.countFailedSince(clock.instant().minus(properties.contentRetention)).toDouble()
            } else {
                (jobs.countByStatus()[status] ?: 0L).toDouble()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("Impressão: a contagem dos jobs falhou ({})", e.javaClass.simpleName)
            Double.NaN
        }

    companion object {
        const val JOBS = "zeny.printing.jobs"
        private val log = LoggerFactory.getLogger(PrintingMetrics::class.java)
    }
}
