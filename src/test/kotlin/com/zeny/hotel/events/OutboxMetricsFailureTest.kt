package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.OutboxMetrics
import com.zeny.hotel.shared.infrastructure.events.OutboxProperties
import io.kotest.matchers.doubles.shouldBeNaN
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Com o banco fora, os gauges do outbox ficam `NaN` em vez de mostrar uma leitura antiga. */
class OutboxMetricsFailureTest {
    @Test
    fun `gauges are NaN when the outbox cannot be read`() {
        val jdbc = mockk<JdbcClient>()
        every { jdbc.sql(any()) } throws CannotGetJdbcConnectionException("banco fora")
        val registry = SimpleMeterRegistry()
        val properties =
            OutboxProperties(
                OutboxProperties.Resubmission(
                    initialBackoff = Duration.ofMinutes(1),
                    maxBackoff = Duration.ofHours(1),
                    giveUpAfter = Duration.ofHours(24),
                    batchSize = 100,
                ),
                completedRetention = Duration.ofDays(7),
            )
        val metrics = OutboxMetrics(jdbc, Clock.systemUTC(), properties).also { it.bindTo(registry) }

        metrics.refresh()

        listOf("pending", "failed", "abandoned").forEach { state ->
            registry
                .get(OutboxMetrics.PUBLICATIONS)
                .tag(OutboxMetrics.STATE_TAG, state)
                .gauge()
                .value()
                .shouldBeNaN()
            registry
                .get(OutboxMetrics.OLDEST_AGE)
                .tag(OutboxMetrics.STATE_TAG, state)
                .timeGauge()
                .value(TimeUnit.SECONDS)
                .shouldBeNaN()
        }
    }
}
