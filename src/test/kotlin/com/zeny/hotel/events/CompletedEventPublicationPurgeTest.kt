package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.CompletedEventPublicationPurgeJob
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
 * Expurgo das publicações concluídas do outbox (H13 do roadmap do M2): concluídas há mais de
 * `zeny.events.completed-retention` (7 dias) são apagadas, contadas pelo `Clock` da aplicação; as
 * recentes e as incompletas (pendentes, com falha ou abandonadas) ficam.
 */
@IntegrationTest
@Import(ProbeListenerConfiguration::class, MutableClockConfiguration::class)
class CompletedEventPublicationPurgeTest(
    @Autowired private val purgeJob: CompletedEventPublicationPurgeJob,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val scheduledTaskHolders: List<ScheduledTaskHolder>,
) {
    @BeforeEach
    @AfterEach
    fun cleanUp() {
        jdbc.sql("DELETE FROM shared.event_publication").update()
        ProbeState.reset()
    }

    @Test
    fun `deletes completed publications older than the retention and keeps the recent and the incomplete ones`() {
        val now = clock.instant()
        val oldCompleted =
            insert("COMPLETED", now.minus(Duration.ofDays(9)), completedAt = now.minus(Duration.ofDays(8)))
        val justOverRetention =
            insert("COMPLETED", now.minus(Duration.ofDays(8)), completedAt = now.minus(RETENTION).minusSeconds(1))
        val recentCompleted =
            insert("COMPLETED", now.minus(Duration.ofDays(30)), completedAt = now.minus(Duration.ofDays(6)))
        val withinRetention =
            insert("COMPLETED", now.minus(Duration.ofDays(8)), completedAt = now.minus(RETENTION).plusSeconds(60))
        // Incompletas, publicadas há muito tempo: nunca são apagadas.
        val failed = insert("FAILED", now.minus(Duration.ofDays(30)))
        val abandoned =
            insert("FAILED", now.minus(Duration.ofDays(30)), lastResubmittedAt = now.minus(Duration.ofDays(29)))
        val published = insert("PUBLISHED", now.minus(Duration.ofDays(30)))
        val processing = insert("PROCESSING", now.minus(Duration.ofDays(30)))
        val resubmitted =
            insert("RESUBMITTED", now.minus(Duration.ofDays(30)), lastResubmittedAt = now.minus(Duration.ofDays(20)))

        purgeJob.purgeCompletedPublications()

        remainingIds() shouldContainExactlyInAnyOrder
            listOf(recentCompleted, withinRetention, failed, abandoned, published, processing, resubmitted)
        listOf(oldCompleted, justOverRetention).forEach { remainingIds().contains(it) shouldBe false }
        // O job roda sob trava do ShedLock.
        jdbc
            .sql("SELECT count(*) FROM shared.shedlock WHERE name = :name")
            .param("name", CompletedEventPublicationPurgeJob.LOCK_NAME)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `a delivered publication is purged once the application clock passes the retention`() {
        val event = ProbeEvent(UUID.randomUUID(), "delivered")
        transactionTemplate.executeWithoutResult { events.publishEvent(event) }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { statusOf(event) == "COMPLETED" }

        clock.advance(RETENTION.minusMinutes(1))
        purgeJob.purgeCompletedPublications()
        statusOf(event) shouldBe "COMPLETED"

        clock.advance(Duration.ofMinutes(2))
        purgeJob.purgeCompletedPublications()
        statusOf(event) shouldBe null
    }

    @Test
    fun `the purge job is scheduled every hour`() {
        val intervals =
            scheduledTaskHolders
                .flatMap { it.scheduledTasks }
                .map { it.task }
                .filterIsInstance<FixedDelayTask>()
                .filter { it.runnable.toString().endsWith(JOB_METHOD) }
                .map { it.intervalDuration }

        intervals shouldContain Duration.ofHours(1)
    }

    private fun statusOf(event: ProbeEvent): String? =
        jdbc
            .sql("SELECT status FROM shared.event_publication WHERE event_type = :type AND serialized_event LIKE :id")
            .param("type", ProbeEvent::class.java.name)
            .param("id", "%${event.id}%")
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun remainingIds(): List<UUID> =
        jdbc
            .sql("SELECT id FROM shared.event_publication")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    private fun insert(
        status: String,
        publishedAt: Instant,
        completedAt: Instant? = null,
        lastResubmittedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO shared.event_publication
                    (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                     completion_attempts, last_resubmission_date)
                VALUES (:id, 'purge.probe', 'purge.Probe', :payload, :publishedAt, :completedAt, :status, 0,
                        :lastResubmittedAt)
                """.trimIndent(),
            ).param("id", id)
            .param("payload", "{\"n\":\"$id\"}")
            .param("publishedAt", utc(publishedAt))
            .param("completedAt", completedAt?.let(::utc))
            .param("status", status)
            .param("lastResubmittedAt", lastResubmittedAt?.let(::utc))
            .update()
        return id
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        val RETENTION: Duration = Duration.ofDays(7)
        const val TIMEOUT_SECONDS = 15L
        const val JOB_METHOD = "CompletedEventPublicationPurgeJob.purgeCompletedPublications"
    }
}
