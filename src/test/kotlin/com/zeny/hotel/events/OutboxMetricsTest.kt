package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.OutboxMetrics
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Métricas do outbox (ARCHITECTURE §16; decisão da revisão humana da E02): publicações
 * pendentes, com falha e abandonadas (H13 do M2) em `shared.event_publication`, com a idade da mais antiga de cada
 * estado, expostas no Prometheus para alerta.
 */
@IntegrationTest
class OutboxMetricsTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val metrics: OutboxMetrics,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
) {
    @BeforeEach
    @AfterEach
    fun cleanUp() {
        jdbc.sql("DELETE FROM shared.event_publication").update()
        metrics.refresh()
    }

    @Test
    fun `an empty outbox reports zero pending and zero failed publications`() {
        metrics.refresh()

        count("pending") shouldBe 0.0
        count("failed") shouldBe 0.0
        count("abandoned") shouldBe 0.0
        oldestAgeSeconds("pending") shouldBe 0.0
        oldestAgeSeconds("failed") shouldBe 0.0
        oldestAgeSeconds("abandoned") shouldBe 0.0
    }

    @Test
    fun `counts incomplete publications by state and measures the oldest of each`() {
        val now = Instant.now()
        // Pendentes com menos de 10 min, para o monitor de staleness não as passar a FAILED.
        insert("PUBLISHED", now.minus(Duration.ofMinutes(6)))
        insert("PROCESSING", now.minus(Duration.ofMinutes(2)))
        insert("RESUBMITTED", now.minus(Duration.ofMinutes(1)))
        insert("FAILED", now.minus(Duration.ofHours(2)))
        insert("FAILED", now.minus(Duration.ofMinutes(30)))
        // Ainda tentando: o último reenvio foi antes das 24 h desde a publicação.
        insert("FAILED", now.minus(Duration.ofHours(23)), lastResubmittedAt = now.minus(Duration.ofMinutes(10)))
        // Abandonadas: uma tentativa depois das 24 h falhou (a regra só olha as datas da linha).
        insert("FAILED", now.minus(Duration.ofHours(30)), lastResubmittedAt = now.minus(Duration.ofHours(5)))
        insert("FAILED", now.minus(Duration.ofHours(25)), lastResubmittedAt = now.minus(Duration.ofHours(1)))
        insert("COMPLETED", now.minus(Duration.ofHours(5)), completedAt = now.minus(Duration.ofHours(4)))

        metrics.refresh()

        count("pending") shouldBe 3.0
        count("failed") shouldBe 3.0
        count("abandoned") shouldBe 2.0
        oldestAgeSeconds("pending").shouldBeBetween(MINUTES_6, MINUTES_6 + SLACK_SECONDS, 0.0)
        oldestAgeSeconds("failed").shouldBeBetween(HOURS_23, HOURS_23 + SLACK_SECONDS, 0.0)
        oldestAgeSeconds("abandoned").shouldBeBetween(HOURS_30, HOURS_30 + SLACK_SECONDS, 0.0)
    }

    @Test
    fun `the prometheus endpoint exposes the outbox metrics`() {
        insert("FAILED", Instant.now().minus(Duration.ofMinutes(15)))
        metrics.refresh()

        val body =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString

        body shouldContain "zeny_outbox_publications{application=\"zeny-hotel-manager\",state=\"failed\"} 1.0"
        body shouldContain "zeny_outbox_publications{application=\"zeny-hotel-manager\",state=\"pending\"} 0.0"
        body shouldContain "zeny_outbox_publications{application=\"zeny-hotel-manager\",state=\"abandoned\"} 0.0"
        body shouldContain
            "zeny_outbox_publications_oldest_age_seconds{application=\"zeny-hotel-manager\",state=\"failed\"}"
        body shouldContain
            "zeny_outbox_publications_oldest_age_seconds{application=\"zeny-hotel-manager\",state=\"pending\"} 0.0"
    }

    private fun count(state: String): Double =
        registry
            .get("zeny.outbox.publications")
            .tag("state", state)
            .gauge()
            .value()

    private fun oldestAgeSeconds(state: String): Double =
        registry
            .get("zeny.outbox.publications.oldest.age")
            .tag("state", state)
            .timeGauge()
            .value(TimeUnit.SECONDS)

    private fun insert(
        status: String,
        publishedAt: Instant,
        completedAt: Instant? = null,
        lastResubmittedAt: Instant? = null,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO shared.event_publication
                    (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                     completion_attempts, last_resubmission_date)
                VALUES (:id, 'metrics.probe', 'metrics.Probe', :payload, :publishedAt, :completedAt, :status, 1,
                        :lastResubmittedAt)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("payload", "{\"n\":\"${UUID.randomUUID()}\"}")
            .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
            .param("completedAt", completedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .param("status", status)
            .param("lastResubmittedAt", lastResubmittedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .update()
    }

    private companion object {
        const val MINUTES_6 = 6 * 60.0
        const val HOURS_23 = 23 * 3600.0
        const val HOURS_30 = 30 * 3600.0
        const val SLACK_SECONDS = 60.0
    }
}
