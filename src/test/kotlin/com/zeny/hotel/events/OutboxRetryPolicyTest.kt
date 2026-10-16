package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.OutboxProperties
import com.zeny.hotel.shared.infrastructure.events.OutboxRetryPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.EventPublication.Status
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Backoff e abandono das publicações `FAILED` (H13 do roadmap do M2), sem banco. */
class OutboxRetryPolicyTest {
    private val policy = OutboxRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(24))
    private val publishedAt = Instant.parse("2026-10-15T12:00:00Z")

    @Test
    fun `the wait doubles from the initial backoff up to the maximum`() {
        (0..8).map { policy.backoff(it).toMinutes() } shouldBe listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L)
        policy.backoff(Int.MAX_VALUE) shouldBe Duration.ofHours(1)
        policy.backoff(-1) shouldBe Duration.ofMinutes(1)
    }

    @Test
    fun `the next attempt counts from the last resubmission or, without one, from the publication`() {
        // completion_attempts conta a entrega original: 1 = nenhum reenvio.
        val never = publication(Status.FAILED, attempts = 1, lastResubmittedAt = null)
        policy.nextAttemptAt(never) shouldBe publishedAt.plusSeconds(60)
        // Como o Modulith cria a linha: a data do último reenvio é a da publicação.
        val created = publication(Status.FAILED, attempts = 1, lastResubmittedAt = publishedAt)
        policy.nextAttemptAt(created) shouldBe publishedAt.plusSeconds(60)
        policy.isAbandoned(created) shouldBe false

        val third = publication(Status.FAILED, attempts = 4, lastResubmittedAt = publishedAt.plusSeconds(420))
        policy.nextAttemptAt(third) shouldBe publishedAt.plusSeconds(420).plus(Duration.ofMinutes(8))
    }

    @Test
    fun `only a failed publication whose wait is over is due`() {
        val failed = publication(Status.FAILED, attempts = 2, lastResubmittedAt = publishedAt.plusSeconds(60))
        policy.isDue(failed, publishedAt.plusSeconds(179)) shouldBe false
        policy.isDue(failed, publishedAt.plusSeconds(180)) shouldBe true

        val muchLater = publishedAt.plus(Duration.ofDays(1))
        Status.entries.filter { it != Status.FAILED }.forEach { status ->
            policy.isDue(publication(status, attempts = 1, lastResubmittedAt = null), muchLater) shouldBe false
        }
    }

    @Test
    fun `a failed attempt made at or after the give-up period abandons the publication`() {
        val beforeDeadline =
            publication(Status.FAILED, 29, publishedAt.plus(Duration.ofHours(24)).minusNanos(1_000))
        policy.isAbandoned(beforeDeadline) shouldBe false
        policy.isDue(beforeDeadline, publishedAt.plus(Duration.ofDays(30))) shouldBe true

        val atDeadline = publication(Status.FAILED, 30, publishedAt.plus(Duration.ofHours(24)))
        policy.isAbandoned(atDeadline) shouldBe true
        // Não depende do relógio: muito depois, continua abandonada e fora do reenvio.
        policy.isDue(atDeadline, publishedAt.plus(Duration.ofDays(365))) shouldBe false

        // Sem nenhum reenvio (parada até o staleness), não está abandonada: ainda terá uma tentativa.
        policy.isAbandoned(publication(Status.FAILED, 1, null)) shouldBe false
        // Só FAILED conta como abandonada.
        policy.isAbandoned(publication(Status.RESUBMITTED, 30, publishedAt.plus(Duration.ofHours(25)))) shouldBe false
    }

    @Test
    fun `the configuration refuses incoherent values`() {
        fun resubmission(
            initial: Duration = Duration.ofMinutes(1),
            max: Duration = Duration.ofHours(1),
            giveUp: Duration = Duration.ofHours(24),
            batch: Int = 100,
        ) = OutboxProperties.Resubmission(initial, max, giveUp, batch)

        shouldThrow<IllegalArgumentException> { resubmission(initial = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { resubmission(max = Duration.ofSeconds(30)) }
        shouldThrow<IllegalArgumentException> { resubmission(giveUp = Duration.ofMinutes(30)) }
        shouldThrow<IllegalArgumentException> { resubmission(batch = 0) }
        shouldThrow<IllegalArgumentException> { OutboxProperties(resubmission(), Duration.ofMinutes(5)) }
    }

    private fun publication(
        status: Status,
        attempts: Int,
        lastResubmittedAt: Instant?,
    ): EventPublication =
        object : EventPublication {
            override fun getIdentifier(): UUID = UUID.randomUUID()

            override fun getEvent(): Any = error("o evento não deve ser lido para decidir o reenvio")

            override fun getPublicationDate(): Instant = publishedAt

            override fun getCompletionDate(): Optional<Instant> = Optional.empty()

            override fun getStatus(): Status = status

            override fun getLastResubmissionDate(): Instant? = lastResubmittedAt

            override fun getCompletionAttempts(): Int = attempts
        }
}
