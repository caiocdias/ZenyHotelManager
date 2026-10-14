package com.zeny.hotel.shared.infrastructure.events

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Métricas do outbox (`shared.event_publication`, ARCHITECTURE §5 e §16), para alerta:
 *
 * - `zeny.outbox.publications{state}`: publicações incompletas, `pending` (ainda a entregar:
 *   `PUBLISHED`, `PROCESSING`, `RESUBMITTED`) ou `failed` (o listener falhou e o
 *   [EventPublicationResubmissionJob] vai tentar de novo, sem limite de tentativas);
 * - `zeny.outbox.publications.oldest.age{state}`: idade, desde a publicação, da mais antiga de
 *   cada estado (zero se não houver nenhuma).
 *
 * No Prometheus: `zeny_outbox_publications` e `zeny_outbox_publications_oldest_age_seconds`.
 *
 * Os valores são recalculados a cada `zeny.events.metrics.refresh-interval`, e a raspagem só lê
 * a última leitura: assim o `/actuator/prometheus` não espera pelo banco. Se a leitura falhar
 * (banco fora), os gauges ficam `NaN` até a próxima dar certo. A tarefa não usa ShedLock de
 * propósito: só lê, e cada instância precisa dos próprios valores.
 */
@Component
class OutboxMetrics(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : MeterBinder {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Map<State, StateSnapshot>? = null

    override fun bindTo(registry: MeterRegistry) {
        State.entries.forEach { state ->
            Gauge
                .builder(PUBLICATIONS, this) {
                    it.snapshot?.let { s -> s.getValue(state).count.toDouble() }
                        ?: Double.NaN
                }.tag(STATE_TAG, state.tag)
                .description("Publicações incompletas do outbox (shared.event_publication) por estado")
                .register(registry)
            TimeGauge
                .builder(OLDEST_AGE, this, TimeUnit.SECONDS) {
                    it.snapshot?.let { s -> s.getValue(state).oldestAge.toMillis() / MILLIS_PER_SECOND } ?: Double.NaN
                }.tag(STATE_TAG, state.tag)
                .description("Idade da publicação incompleta mais antiga do outbox por estado")
                .register(registry)
        }
    }

    /** Lê o outbox de novo; os gauges passam a mostrar esta leitura. */
    @Scheduled(fixedDelayString = "\${zeny.events.metrics.refresh-interval}")
    fun refresh() {
        snapshot =
            try {
                read()
            } catch (ex: DataAccessException) {
                log.warn("Não foi possível ler as métricas do outbox: {}", ex.mostSpecificCause.toString())
                null
            }
    }

    private fun read(): Map<State, StateSnapshot> {
        val now = clock.instant()
        val rows =
            jdbc
                .sql(
                    """
                    SELECT CASE WHEN status = 'FAILED' THEN 'FAILED' ELSE 'PENDING' END AS state,
                           count(*) AS publications,
                           min(publication_date) AS oldest
                      FROM shared.event_publication
                     WHERE completion_date IS NULL
                       AND status IS DISTINCT FROM 'COMPLETED'
                     GROUP BY 1
                    """.trimIndent(),
                ).query { rs, _ ->
                    State.valueOf(rs.getString("state")) to
                        StateSnapshot(
                            count = rs.getLong("publications"),
                            oldestAge = ageOf(rs.getObject("oldest", OffsetDateTime::class.java).toInstant(), now),
                        )
                }.list()
                .toMap()
        return State.entries.associateWith { rows[it] ?: StateSnapshot.EMPTY }
    }

    private fun ageOf(
        publishedAt: Instant,
        now: Instant,
    ): Duration = Duration.between(publishedAt, now).takeUnless { it.isNegative } ?: Duration.ZERO

    enum class State(
        val tag: String,
    ) {
        PENDING("pending"),
        FAILED("failed"),
    }

    private data class StateSnapshot(
        val count: Long,
        val oldestAge: Duration,
    ) {
        companion object {
            val EMPTY = StateSnapshot(0, Duration.ZERO)
        }
    }

    companion object {
        const val PUBLICATIONS = "zeny.outbox.publications"
        const val OLDEST_AGE = "zeny.outbox.publications.oldest.age"
        const val STATE_TAG = "state"
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
