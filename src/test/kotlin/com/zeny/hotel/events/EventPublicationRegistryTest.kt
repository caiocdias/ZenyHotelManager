package com.zeny.hotel.events

import com.zeny.hotel.ZenyHotelApplication
import com.zeny.hotel.shared.infrastructure.events.EventPublicationResubmissionJob
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Event Publication Registry (outbox do Spring Modulith, ARCHITECTURE §5 e §13): o evento
 * publicado numa transação é gravado junto com ela, entregue depois do commit a um listener
 * assíncrono e, se o listener falhar, fica pendente até ser reprocessado — pelo job agendado
 * ou no boot seguinte.
 */
@IntegrationTest
@Import(ProbeListenerConfiguration::class)
class EventPublicationRegistryTest(
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val resubmissionJob: EventPublicationResubmissionJob,
    @Autowired private val scheduledTaskHolders: List<ScheduledTaskHolder>,
) {
    @BeforeEach
    fun cleanUp() {
        jdbc.sql("DELETE FROM shared.event_publication").update()
        ProbeState.reset()
    }

    @Test
    fun `stores the publication in the publishing transaction and completes it after delivery`() {
        val event = ProbeEvent(UUID.randomUUID(), "ok")

        transactionTemplate.executeWithoutResult {
            events.publishEvent(event)
            // Ainda dentro da transação: a publicação já está gravada e o listener não rodou.
            publicationOf(event).shouldNotBeNull().status shouldBe "PUBLISHED"
            ProbeState.deliveriesOf(event.id) shouldBe 0
        }

        awaitPublication(event) { it.status == "COMPLETED" && it.completedAt != null }
        ProbeState.deliveriesOf(event.id) shouldBe 1
    }

    @Test
    fun `rolled back transaction neither stores nor delivers the event`() {
        val event = ProbeEvent(UUID.randomUUID(), "rollback")

        transactionTemplate.executeWithoutResult { status ->
            events.publishEvent(event)
            status.setRollbackOnly()
        }

        await().during(Duration.ofMillis(SETTLE_MILLIS)).atMost(Duration.ofSeconds(2)).until {
            ProbeState.deliveriesOf(event.id) == 0
        }
        publicationOf(event).shouldBeNull()
    }

    @Test
    fun `failed listener leaves the publication pending until the scheduled job resubmits it`() {
        val event = ProbeEvent(UUID.randomUUID(), "fails once")
        ProbeState.failNext(event.id, times = 1)

        transactionTemplate.executeWithoutResult { events.publishEvent(event) }

        val failed = awaitPublication(event) { it.status == "FAILED" }
        failed.completedAt.shouldBeNull()
        ProbeState.deliveriesOf(event.id) shouldBe 0

        resubmissionJob.resubmitFailedPublications()

        val completed = awaitPublication(event) { it.status == "COMPLETED" && it.completedAt != null }
        completed.attempts shouldBeGreaterThanOrEqual 1
        ProbeState.deliveriesOf(event.id) shouldBe 1
        // O job roda sob trava do ShedLock, gravada na tabela do schema shared.
        jdbc
            .sql("SELECT locked_by FROM shared.shedlock WHERE name = :name")
            .param("name", EventPublicationResubmissionJob.LOCK_NAME)
            .query(String::class.java)
            .optional()
            .isPresent shouldBe true
    }

    @Test
    fun `failed publication is resubmitted when the application boots again`() {
        val event = ProbeEvent(UUID.randomUUID(), "fails until restart")
        ProbeState.failNext(event.id, times = 1)

        transactionTemplate.executeWithoutResult { events.publishEvent(event) }
        awaitPublication(event) { it.status == "FAILED" }

        // Segundo processo sobre o mesmo banco: no boot, reenvia as publicações incompletas.
        SpringApplicationBuilder(
            ZenyHotelApplication::class.java,
            ProbeListenerConfiguration::class.java,
        ).profiles("local")
            .initializers(PostgresInitializer(), TestEncryptionKeysInitializer(), TestAuthInitializer())
            // Como argumentos de linha de comando: `properties()` teria prioridade abaixo do application.yaml.
            // Porta aleatória: a 8080 pode estar ocupada (bootRun, proxy do compose).
            .run("--$TEST_MIGRATIONS", "--$RESUBMISSION_JOB_DEFERRED", "--server.port=0")
            .use { _ ->
                awaitPublication(event) { it.status == "COMPLETED" && it.completedAt != null }
            }
        ProbeState.deliveriesOf(event.id) shouldBe 1
    }

    @Test
    fun `resubmission job is scheduled every minute`() {
        val tasks =
            scheduledTaskHolders
                .flatMap { it.scheduledTasks }
                .map { it.task }
                .filterIsInstance<FixedDelayTask>()
                // O Spring embrulha o ScheduledMethodRunnable; o toString é "Classe.metodo".
                .filter { it.runnable.toString().endsWith(JOB_METHOD) }

        tasks.map { it.intervalDuration } shouldContain Duration.ofMinutes(1)
    }

    private data class Publication(
        val status: String?,
        val completedAt: OffsetDateTime?,
        val attempts: Int,
    )

    private fun publicationOf(event: ProbeEvent): Publication? =
        jdbc
            .sql(
                """
                SELECT status, completion_date, completion_attempts FROM shared.event_publication
                WHERE event_type = :type AND serialized_event LIKE :id
                """.trimIndent(),
            ).param("type", ProbeEvent::class.java.name)
            .param("id", "%${event.id}%")
            .query { rs, _ ->
                Publication(
                    status = rs.getString("status"),
                    completedAt = rs.getObject("completion_date", OffsetDateTime::class.java),
                    attempts = rs.getInt("completion_attempts"),
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

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val SETTLE_MILLIS = 500L
        const val JOB_METHOD = "EventPublicationResubmissionJob.resubmitFailedPublications"
    }
}
