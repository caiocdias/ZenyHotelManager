package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.EventPublicationResubmissionJob
import com.zeny.hotel.shared.infrastructure.events.OutboxMetrics
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reenvio do outbox com backoff e abandono (H13 do roadmap do M2): o relógio da aplicação
 * ([MutableClock], o mesmo `Clock` que o Modulith usa para gravar as datas) é avançado à mão e o job
 * é chamado em cada instante, para medir quando ele reenvia.
 */
@IntegrationTest
@Import(ProbeListenerConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans conferidos direto: job, relógio, métricas e o agendamento do Modulith
class OutboxRetryTest(
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val resubmissionJob: EventPublicationResubmissionJob,
    @Autowired private val clock: MutableClock,
    @Autowired private val metrics: OutboxMetrics,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val scheduledTaskHolders: List<ScheduledTaskHolder>,
) {
    @BeforeEach
    @AfterEach
    fun cleanUp() {
        jdbc.sql("DELETE FROM shared.event_publication").update()
        ProbeState.reset()
    }

    @Test
    fun `a transient failure is resubmitted at growing intervals and completes`() {
        val event = ProbeEvent(UUID.randomUUID(), "fails twice")
        ProbeState.failNext(event.id, times = 2)
        publish(event)
        val published = awaitPublication(event) { it.status == "FAILED" && invocations(event) == 1 }

        // 1ª nova tentativa: 1 min depois da publicação, nem um segundo antes.
        // A entrega original já conta como uma (completion_attempts = 1), e o Modulith grava a data da
        // publicação como a do último reenvio. As datas vêm do banco (em microssegundos): o relógio é
        // levado a instantes exatos a partir delas.
        published.attempts shouldBe 1
        published.lastResubmittedAt shouldBe published.publishedAt
        clock.advanceTo(published.publishedAt.plusSeconds(59))
        resubmissionJob.resubmitFailedPublications()
        publicationOf(event).shouldNotBeNull().attempts shouldBe 1

        clock.advanceTo(published.publishedAt.plusSeconds(60))
        resubmissionJob.resubmitFailedPublications()
        val first = awaitPublication(event) { it.status == "FAILED" && it.attempts == 2 && invocations(event) == 2 }
        first.lastResubmittedAt shouldBe published.publishedAt.plus(Duration.ofMinutes(1))

        // 2ª: 2 min depois da 1ª.
        val firstResubmittedAt = first.lastResubmittedAt.shouldNotBeNull()
        clock.advanceTo(firstResubmittedAt.plusSeconds(119))
        resubmissionJob.resubmitFailedPublications()
        publicationOf(event).shouldNotBeNull().attempts shouldBe 2

        clock.advanceTo(firstResubmittedAt.plusSeconds(120))
        resubmissionJob.resubmitFailedPublications()
        val completed = awaitPublication(event) { it.status == "COMPLETED" && it.completedAt != null }
        completed.attempts shouldBe 3
        completed.lastResubmittedAt shouldBe firstResubmittedAt.plus(Duration.ofMinutes(2))
        ProbeState.deliveriesOf(event.id) shouldBe 1
        invocations(event) shouldBe 3
    }

    @Test
    fun `a permanent failure is abandoned after the give-up period, counted and never resubmitted on its own`() {
        val event = ProbeEvent(UUID.randomUUID(), "always fails")
        ProbeState.failAlways(event.id)
        publish(event)
        val publishedAt = awaitPublication(event) { it.status == "FAILED" && invocations(event) == 1 }.publishedAt

        // Cada tentativa vence 1, 2, 4, 8, 16, 32 min e depois 1 h depois da anterior: o job é chamado
        // um pouco antes (não reenvia) e no vencimento (reenvia), até parar de reenviar.
        var resubmissions = 0
        var last = publishedAt
        val delays = mutableListOf<Duration>()
        while (resubmissions < MAX_RESUBMISSIONS_GUARD) {
            val due = last.plus(expectedBackoff(resubmissions))
            clock.advanceTo(due.minusSeconds(1))
            resubmissionJob.resubmitFailedPublications()
            publicationOf(event).shouldNotBeNull().attempts shouldBe resubmissions + 1

            clock.advanceTo(due)
            resubmissionJob.resubmitFailedPublications()
            val current = publicationOf(event).shouldNotBeNull()
            if (current.attempts == resubmissions + 1) break
            resubmissions++
            current.attempts shouldBe resubmissions + 1
            current.lastResubmittedAt shouldBe due
            awaitPublication(event) { it.status == "FAILED" && invocations(event) == resubmissions + 1 }
            delays += Duration.between(last, due)
            last = due
        }

        // 29 reenvios: o último, 24 h 3 min depois da publicação, já passou do prazo de 24 h.
        resubmissions shouldBe 29
        delays.take(7).map { it.toMinutes() } shouldBe listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L)
        last shouldBeGreaterThanOrEqualTo publishedAt.plus(GIVE_UP_AFTER)
        Duration.between(publishedAt, last.minus(delays.last())) shouldBeLessThan GIVE_UP_AFTER

        metrics.refresh()
        count("abandoned") shouldBe 1.0
        count("failed") shouldBe 0.0
        count("pending") shouldBe 0.0

        // Não volta sozinha: nem o job (muito depois), nem o monitor de staleness.
        clock.advance(Duration.ofDays(3))
        resubmissionJob.resubmitFailedPublications()
        runStalenessMonitor()
        resubmissionJob.resubmitFailedPublications()
        await().during(Duration.ofMillis(SETTLE_MILLIS)).atMost(Duration.ofSeconds(2)).until {
            invocations(event) == resubmissions + 1
        }
        publicationOf(event).shouldNotBeNull().let {
            it.status shouldBe "FAILED"
            it.attempts shouldBe 30
            it.lastResubmittedAt shouldBe last
        }
    }

    @Test
    fun `the documented manual resubmission makes an abandoned publication run again`() {
        val event = ProbeEvent(UUID.randomUUID(), "abandoned, then fixed")
        val id = abandonedPublication(event)

        resubmissionJob.resubmitFailedPublications()
        runStalenessMonitor()
        await().during(Duration.ofMillis(SETTLE_MILLIS)).atMost(Duration.ofSeconds(2)).until {
            invocations(event) == 1
        }
        metrics.refresh()
        count("abandoned") shouldBe 1.0

        // O listener foi corrigido: o reenvio manual (CLAUDE.md, "Eventos e jobs") devolve a
        // publicação ao job, que a reenvia na próxima execução.
        ProbeState.failNext(event.id, times = 0)
        manualResubmission(id) shouldBe 1

        resubmissionJob.resubmitFailedPublications()
        val completed = awaitPublication(event) { it.status == "COMPLETED" && it.completedAt != null }
        completed.attempts shouldBe 31
        ProbeState.deliveriesOf(event.id) shouldBe 1
        metrics.refresh()
        count("abandoned") shouldBe 0.0
    }

    @Test
    fun `a manual resubmission that fails again is abandoned again after that single attempt`() {
        val event = ProbeEvent(UUID.randomUUID(), "abandoned, still broken")
        val id = abandonedPublication(event)

        manualResubmission(id) shouldBe 1
        resubmissionJob.resubmitFailedPublications()
        awaitPublication(event) { it.status == "FAILED" && it.attempts == 31 && invocations(event) == 2 }

        clock.advance(Duration.ofHours(2))
        resubmissionJob.resubmitFailedPublications()
        await().during(Duration.ofMillis(SETTLE_MILLIS)).atMost(Duration.ofSeconds(2)).until {
            invocations(event) == 2
        }
        metrics.refresh()
        count("abandoned") shouldBe 1.0
    }

    @Test
    fun `the manual resubmission does not touch publications that are not abandoned`() {
        val event = ProbeEvent(UUID.randomUUID(), "completes")
        publish(event)
        val completed = awaitPublication(event) { it.status == "COMPLETED" }

        manualResubmission(idOf(event)) shouldBe 0

        publicationOf(event) shouldBe completed
    }

    /** Uma publicação real que falhou e foi levada, por SQL, ao estado de abandonada. */
    private fun abandonedPublication(event: ProbeEvent): UUID {
        ProbeState.failAlways(event.id)
        publish(event)
        awaitPublication(event) { it.status == "FAILED" && invocations(event) == 1 }
        val now = clock.instant()
        val id = idOf(event)
        jdbc
            .sql(
                """
                UPDATE shared.event_publication
                   SET publication_date = :publishedAt, last_resubmission_date = :lastResubmittedAt,
                       completion_attempts = 30
                 WHERE id = :id
                """.trimIndent(),
            ).param("publishedAt", utc(now.minus(Duration.ofHours(25))))
            .param("lastResubmittedAt", utc(now.minus(Duration.ofMinutes(30))))
            .param("id", id)
            .update()
        return id
    }

    /** O procedimento documentado no CLAUDE.md, com o papel da aplicação (o dono também pode). */
    private fun manualResubmission(id: UUID): Int =
        jdbc
            .sql(
                """
                UPDATE shared.event_publication
                   SET last_resubmission_date = publication_date
                 WHERE id = :id
                   AND status = 'FAILED'
                   AND last_resubmission_date >= publication_date + interval '24 hours'
                """.trimIndent(),
            ).param("id", id)
            .update()

    /**
     * Roda uma vez a tarefa do monitor de staleness do Modulith (a mesma que ele agenda a cada minuto,
     * com `spring.modulith.events.staleness.*`): ela só passa publicações paradas a `FAILED`.
     */
    private fun runStalenessMonitor() {
        val monitor =
            scheduledTaskHolders
                .flatMap { it.scheduledTasks }
                .map { it.task }
                .filterIsInstance<FixedDelayTask>()
                .single { it.runnable.toString().contains("StalenessMonitorConfiguration") }
        monitor.runnable.run()
    }

    private fun expectedBackoff(resubmissions: Int): Duration =
        minOf(Duration.ofMinutes(1).multipliedBy(1L shl minOf(resubmissions, 20)), Duration.ofHours(1))

    private fun MutableClock.advanceTo(instant: Instant) {
        val delta = Duration.between(instant(), instant)
        if (!delta.isNegative) advance(delta)
    }

    private fun publish(event: ProbeEvent) = transactionTemplate.executeWithoutResult { events.publishEvent(event) }

    private fun invocations(event: ProbeEvent) = ProbeState.invocationsOf(event.id)

    private fun count(state: String): Double =
        meters
            .get(OutboxMetrics.PUBLICATIONS)
            .tag(OutboxMetrics.STATE_TAG, state)
            .gauge()
            .value()

    private data class Publication(
        val status: String?,
        val publishedAt: Instant,
        val completedAt: Instant?,
        val attempts: Int,
        val lastResubmittedAt: Instant?,
    )

    private fun idOf(event: ProbeEvent): UUID =
        jdbc
            .sql("SELECT id FROM shared.event_publication WHERE event_type = :type AND serialized_event LIKE :id")
            .param("type", ProbeEvent::class.java.name)
            .param("id", "%${event.id}%")
            .query(UUID::class.java)
            .single()

    private fun publicationOf(event: ProbeEvent): Publication? =
        jdbc
            .sql(
                """
                SELECT status, publication_date, completion_date, completion_attempts, last_resubmission_date
                  FROM shared.event_publication
                 WHERE event_type = :type AND serialized_event LIKE :id
                """.trimIndent(),
            ).param("type", ProbeEvent::class.java.name)
            .param("id", "%${event.id}%")
            .query { rs, _ ->
                Publication(
                    status = rs.getString("status"),
                    publishedAt = rs.getObject("publication_date", OffsetDateTime::class.java).toInstant(),
                    completedAt = rs.getObject("completion_date", OffsetDateTime::class.java)?.toInstant(),
                    attempts = rs.getInt("completion_attempts"),
                    lastResubmittedAt = rs.getObject("last_resubmission_date", OffsetDateTime::class.java)?.toInstant(),
                )
            }.optional()
            .orElse(null)

    private fun awaitPublication(
        event: ProbeEvent,
        condition: (Publication) -> Boolean,
    ): Publication {
        var last: Publication? = null
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            last = publicationOf(event)
            last?.let(condition) == true
        }
        return last.shouldNotBeNull()
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val SETTLE_MILLIS = 500L
        const val MAX_RESUBMISSIONS_GUARD = 40
        val GIVE_UP_AFTER: Duration = Duration.ofHours(24)
    }
}
