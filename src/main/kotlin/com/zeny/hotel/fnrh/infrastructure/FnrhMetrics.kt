package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhStatus
import com.zeny.hotel.fnrh.application.FnrhStatusReader
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * As métricas `zeny.fnrh.*` (nenhum `HealthIndicator`): os gauges do último sucesso e da última falha (época em
 * segundos do ambiente atual, `NaN` sem registro) e `zeny.fnrh.configured` (0 ou 1) leem do banco, como o status. O
 * contador `zeny.fnrh.calls` é incrementado pelo [JdbcFnrhIntegrationLog], a cada chamada. Uma leitura que falha dá
 * `NaN`, nunca derruba a raspagem.
 */
@Component
class FnrhMetrics(
    private val reader: ObjectProvider<FnrhStatusReader>,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        gauge(registry, LAST_SUCCESS, "Época do último sucesso na FNRH (ambiente atual).") { it.lastSuccessAt.epoch() }
        gauge(registry, LAST_FAILURE, "Época da última falha na FNRH (ambiente atual).") { it.lastFailureAt.epoch() }
        Gauge
            .builder(CONFIGURED) { read()?.let { if (it.configured) 1.0 else 0.0 } ?: Double.NaN }
            .description("1 se a integração com a FNRH tem usuário, senha e CPF padrão.")
            .register(registry)
    }

    private fun gauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        value: (FnrhStatus) -> Double,
    ) {
        Gauge
            .builder(name) { read()?.let(value) ?: Double.NaN }
            .baseUnit("seconds")
            .description(description)
            .register(registry)
    }

    private fun read(): FnrhStatus? =
        try {
            reader.getObject().read()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("FNRH: a leitura das métricas falhou ({})", e.javaClass.simpleName)
            null
        }

    private fun Instant?.epoch(): Double = this?.epochSecond?.toDouble() ?: Double.NaN

    companion object {
        const val CALLS = "zeny.fnrh.calls"
        const val LAST_SUCCESS = "zeny.fnrh.last.success"
        const val LAST_FAILURE = "zeny.fnrh.last.failure"
        const val CONFIGURED = "zeny.fnrh.configured"
        private val log = LoggerFactory.getLogger(FnrhMetrics::class.java)
    }
}

/**
 * O gauge `zeny.fnrh.submissions` com a tag `status` (`pending`, `failed`, `invalid`): quantas submissões estão em cada
 * situação que pede atenção, lido do banco a cada raspagem. Uma leitura que falha dá `NaN`, nunca derruba a raspagem.
 * Fica à parte do [FnrhMetrics] porque lê da fila, não do status.
 */
@Component
class FnrhSubmissionMetrics(
    private val store: ObjectProvider<FnrhSubmissionStore>,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        WATCHED.forEach { status ->
            Gauge
                .builder(SUBMISSIONS) { count(status) }
                .tag("status", status.name.lowercase())
                .description("Submissões à FNRH na situação.")
                .register(registry)
        }
    }

    private fun count(status: FnrhSubmissionStatus): Double =
        try {
            (store.getObject().countByStatus()[status] ?: 0L).toDouble()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("FNRH: a contagem das submissões falhou ({})", e.javaClass.simpleName)
            Double.NaN
        }

    companion object {
        const val SUBMISSIONS = "zeny.fnrh.submissions"
        private val WATCHED =
            listOf(FnrhSubmissionStatus.PENDING, FnrhSubmissionStatus.FAILED, FnrhSubmissionStatus.INVALID)
        private val log = LoggerFactory.getLogger(FnrhSubmissionMetrics::class.java)
    }
}
