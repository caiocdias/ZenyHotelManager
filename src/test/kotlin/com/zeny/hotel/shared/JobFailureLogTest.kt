package com.zeny.hotel.shared

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.application.FileOrphanPurge
import com.zeny.hotel.files.infrastructure.FileOrphanPurgeJob
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.application.BookingStore
import com.zeny.hotel.reservations.application.InventoryRecalculation
import com.zeny.hotel.reservations.application.ProvisionalExpiry
import com.zeny.hotel.reservations.application.ProvisionalExpiryService
import com.zeny.hotel.reservations.infrastructure.InventoryHorizonJob
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.infrastructure.events.OutboxMetrics
import com.zeny.hotel.shared.infrastructure.events.OutboxProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Log da falha dos quatro jobs (M6 E21, ARCHITECTURE §13 e §16): o item ou a leitura que falha sai no log com o nome
 * do job (ou o id do item) e só as classes da exceção e das causas, sem a mensagem, sem o *stack trace* e sem as
 * suprimidas, que numa falha do banco trazem nomes e valores. A falha de um item não interrompe os outros. Sem
 * contexto Spring: os jobs são montados com dublês.
 */
class JobFailureLogTest {
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun captureLogs() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun releaseLogs() {
        root.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `the outbox metrics refresh logs only the classes when the read fails`() {
        val jdbc = mockk<JdbcClient>()
        every { jdbc.sql(any()) } throws CannotGetJdbcConnectionException("banco fora $SENTINEL", sentinelCause())
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

        OutboxMetrics(jdbc, Clock.systemUTC(), properties).refresh()

        logged() shouldContain CannotGetJdbcConnectionException::class.java.name
        logged() shouldContain SQLException::class.java.name
        logged() shouldNotContain SENTINEL
    }

    @Test
    fun `the orphan purge logs the failed file by id and classes, and goes on with the others`() {
        val failing = FileId(UUID.randomUUID())
        val healthy = FileId(UUID.randomUUID())
        val purge = mockk<FileOrphanPurge>()
        every { purge.candidates(any()) } returns listOf(failing, healthy)
        every { purge.purge(failing) } throws sentinelFailure()
        every { purge.purge(healthy) } returns true

        FileOrphanPurgeJob(purge).purgeOrphans()

        verify { purge.purge(healthy) }
        logged() shouldContain failing.toString()
        logged() shouldContain IllegalStateException::class.java.name
        logged() shouldContain SQLException::class.java.name
        logged() shouldContain "1 apagados"
        logged() shouldNotContain SENTINEL
    }

    @Test
    fun `the provisional expiry logs the failed reservation by id and classes, and goes on with the others`() {
        val failing = ReservationId(UUID.randomUUID())
        val healthy = ReservationId(UUID.randomUUID())
        val store = mockk<BookingStore>()
        val expiry = mockk<ProvisionalExpiryService>()
        val now = Instant.parse("2026-11-19T12:00:00Z")
        every { store.dueProvisionals(any(), any(), any()) } returns listOf(now to failing, now to healthy)
        every { expiry.expire(failing) } throws sentinelFailure()
        every { expiry.expire(healthy) } returns true

        val run = ProvisionalExpiry(store, expiry, Clock.fixed(now, java.time.ZoneOffset.UTC)).expireDue()

        run shouldBe ProvisionalExpiry.Run(expired = 1, failed = 1)
        logged() shouldContain failing.toString()
        logged() shouldContain IllegalStateException::class.java.name
        logged() shouldContain SQLException::class.java.name
        logged() shouldNotContain SENTINEL
    }

    @Test
    fun `the inventory horizon logs the failed room type by id and classes, and goes on with the others`() {
        val failing = RoomTypeId(UUID.randomUUID())
        val healthy = RoomTypeId(UUID.randomUUID())
        val recalculation = mockk<InventoryRecalculation>()
        every { recalculation.horizonRoomTypes() } returns listOf(failing, healthy)
        every { recalculation.extendHorizon(failing) } throws sentinelFailure()
        every { recalculation.extendHorizon(healthy) } returns 3

        InventoryHorizonJob(recalculation).extendHorizon()

        verify { recalculation.extendHorizon(healthy) }
        logged() shouldContain failing.toString()
        logged() shouldContain IllegalStateException::class.java.name
        logged() shouldContain SQLException::class.java.name
        logged() shouldContain "3 linhas"
        logged() shouldNotContain SENTINEL
    }

    /** Tudo o que os eventos capturados mostram: mensagem, argumentos e pilha com causas e suprimidas. */
    private fun logged(): String =
        appender.list.joinToString("\n") { event ->
            listOfNotNull(
                event.formattedMessage,
                event.argumentArray?.joinToString { it.toString() },
                event.throwableProxy?.let(ThrowableProxyUtil::asString),
            ).joinToString("\n")
        }

    private fun sentinelCause() = SQLException("causa $SENTINEL")

    private fun sentinelFailure() =
        IllegalStateException("falha $SENTINEL", sentinelCause()).apply {
            addSuppressed(RuntimeException("suprimida $SENTINEL"))
        }

    private companion object {
        const val SENTINEL = "SENTINELA-e21-job-log"
    }
}
