package com.zeny.hotel.events

import com.zeny.hotel.shared.infrastructure.events.OutboxMetrics
import com.zeny.hotel.shared.infrastructure.events.OutboxProperties
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Um refresh que começou antes e terminou depois nunca sobrescreve a leitura de um refresh que
 * começou depois (o gauge não regride a uma leitura mais antiga).
 */
class OutboxMetricsOrderingTest {
    private val firstQueryReached = CountDownLatch(1)
    private val releaseFirstQuery = CountDownLatch(1)
    private val secondQueryReached = CountDownLatch(1)
    private val queries = AtomicInteger()

    @Test
    fun `an older refresh finishing late does not overwrite the newer reading`() {
        val registry = SimpleMeterRegistry()
        val metrics = OutboxMetrics(controllableJdbc(), Clock.systemUTC(), properties()).also { it.bindTo(registry) }

        val older = Thread { metrics.refresh() }.also { it.start() }
        firstQueryReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        val newer = Thread { metrics.refresh() }.also { it.start() }
        // Sem serialização, o refresh novo lê e publica enquanto o antigo ainda espera.
        secondQueryReached.await(GRACE_MILLIS, TimeUnit.MILLISECONDS)
        newer.join(GRACE_MILLIS)
        releaseFirstQuery.countDown()
        older.join(TIMEOUT_SECONDS * MILLIS)
        newer.join(TIMEOUT_SECONDS * MILLIS)

        registry
            .get(OutboxMetrics.PUBLICATIONS)
            .tag(OutboxMetrics.STATE_TAG, "pending")
            .gauge()
            .value() shouldBe NEWER_COUNT.toDouble()
    }

    /** A 1ª consulta devolve 1 publicação e espera a liberação; a 2ª devolve 2 na hora. */
    private fun controllableJdbc(): JdbcClient {
        val jdbc = mockk<JdbcClient>()
        val statement = mockk<JdbcClient.StatementSpec>()
        val mapped = mockk<JdbcClient.MappedQuerySpec<Any>>()
        every { jdbc.sql(any()) } answers { statement }
        every { statement.param(any<String>(), any()) } returns statement
        every { statement.query(any<RowMapper<Any>>()) } answers {
            val mapper = firstArg<RowMapper<Any>>()
            val call = queries.incrementAndGet()
            every { mapped.list() } answers {
                if (call == 1) {
                    firstQueryReached.countDown()
                    releaseFirstQuery.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } else {
                    secondQueryReached.countDown()
                }
                listOf(mapper.mapRow(row(if (call == 1) OLDER_COUNT else NEWER_COUNT), 0))
            }
            mapped
        }
        return jdbc
    }

    private fun row(count: Long): ResultSet {
        val rs = mockk<ResultSet>()
        every { rs.getString("state") } returns "PENDING"
        every { rs.getLong("publications") } returns count
        every { rs.getObject("oldest", OffsetDateTime::class.java) } returns OffsetDateTime.now().minusMinutes(1)
        return rs
    }

    private fun properties() =
        OutboxProperties(
            OutboxProperties.Resubmission(
                initialBackoff = Duration.ofMinutes(1),
                maxBackoff = Duration.ofHours(1),
                giveUpAfter = Duration.ofHours(24),
                batchSize = 100,
            ),
            completedRetention = Duration.ofDays(7),
        )

    private companion object {
        const val OLDER_COUNT = 1L
        const val NEWER_COUNT = 2L
        const val TIMEOUT_SECONDS = 10L
        const val GRACE_MILLIS = 500L
        const val MILLIS = 1000L
    }
}
