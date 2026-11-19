package com.zeny.hotel.shared

import com.zeny.hotel.shared.infrastructure.scheduling.ShedLockLogFilter
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * Filtro do log do ShedLock (M6 E21, ARCHITECTURE §13 e §16): o logger do `JdbcTemplateStorageAccessor` loga a
 * `DataAccessException` da trava com a mensagem do JDBC, que pode trazer valores do banco. O filtro descarta só os
 * eventos desse logger que trazem exceção (como `Throwable` do evento ou como parâmetro) e deixa passar as mensagens
 * sem exceção, como o "Unlock failed after 10 attempts".
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class ShedLockLogFilterTest(
    @Autowired private val dataSource: DataSource,
) {
    private val shedLockLogger = LoggerFactory.getLogger(SHEDLOCK_LOGGER)

    @Test
    fun `an event with the exception as the Throwable of the event is dropped whole`(output: CapturedOutput) {
        shedLockLogger.error("Unexpected exception", sentinelFailure())

        output.all shouldNotContain SENTINEL
        output.all shouldNotContain "Unexpected exception"
    }

    @Test
    fun `an event with the exception as the last parameter is dropped whole`(output: CapturedOutput) {
        shedLockLogger.error("Lock {} failed: {}", "job-x", sentinelFailure())

        output.all shouldNotContain SENTINEL
        output.all shouldNotContain "Lock job-x failed"
    }

    @Test
    fun `a message without an exception still shows up`(output: CapturedOutput) {
        shedLockLogger.error("Unlock failed after 10 attempts")

        output.all shouldContain "Unlock failed after 10 attempts"
    }

    @Test
    fun `an event with an exception from another logger still shows up`(output: CapturedOutput) {
        LoggerFactory.getLogger("com.zeny.hotel.e21.OtherLogger").error("other failure visible", sentinelFailure())

        output.all shouldContain "other failure visible"
    }

    @Test
    fun `a message with a non-throwable parameter from the shedlock logger still shows up`(output: CapturedOutput) {
        shedLockLogger.error("Lock {} released", "job-y")

        output.all shouldContain "Lock job-y released"
    }

    @Test
    fun `a real lock provider that fails in the command leaves the sentinel out of the log`(output: CapturedOutput) {
        val provider =
            JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration
                    .builder()
                    .withJdbcTemplate(JdbcTemplate(dataSource))
                    .withTableName(MISSING_TABLE)
                    .usingDbTime()
                    .build(),
            )
        val config = LockConfiguration(Instant.now(), "e21-shedlock-filter", Duration.ofMinutes(1), Duration.ZERO)

        runCatching { provider.lock(config) }

        output.all shouldNotContain SENTINEL
    }

    /** Falha com o sentinela na mensagem e na causa, como a do JDBC com um valor do banco. */
    private fun sentinelFailure() = IllegalStateException("banco: $SENTINEL", SQLException("causa $SENTINEL"))

    private companion object {
        const val SHEDLOCK_LOGGER = ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER

        // Minúsculo e sem hífen: o PostgreSQL ecoa o nome da tabela inexistente na mensagem do erro.
        const val SENTINEL = "sentinela_e21_shedlock"
        const val MISSING_TABLE = SENTINEL
    }
}
