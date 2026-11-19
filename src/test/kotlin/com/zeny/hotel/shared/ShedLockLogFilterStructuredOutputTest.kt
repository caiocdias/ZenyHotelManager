package com.zeny.hotel.shared

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import com.zeny.hotel.shared.infrastructure.scheduling.ShedLockLogFilter
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment
import java.io.ByteArrayOutputStream
import java.sql.SQLException

/**
 * O filtro do ShedLock com a saída estruturada (ECS) do profile `prod` (M6 E21). Um [LoggerContext] próprio do teste,
 * com o [ShedLockLogFilter] e um appender com o `StructuredLogEncoder` do Boot no formato `ecs` (o mesmo do `prod`),
 * não depende do estado do logging da JVM nem o altera: subir uma aplicação com o profile `prod` dependia da ordem das
 * classes de teste (o Boot não reinicializa o logging se outra aplicação já o configurou) e deixava a saída em ECS
 * para os testes seguintes. O que prova: com saída ECS, o evento do ShedLock com exceção some, o de outro logger com
 * exceção e o do ShedLock sem exceção saem em JSON.
 */
class ShedLockLogFilterStructuredOutputTest {
    private val buffer = ByteArrayOutputStream()
    private val context = LoggerContext()

    @BeforeEach
    fun setUp() {
        val loggerContext = context
        loggerContext.mdcAdapter = LogbackMDCAdapter()
        loggerContext.putObject(Environment::class.java.name, MockEnvironment())
        val encoder =
            StructuredLogEncoder().apply {
                setContext(loggerContext)
                setFormat("ecs")
                start()
            }
        val appender =
            OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
                setContext(loggerContext)
                setEncoder(encoder)
                outputStream = buffer
                start()
            }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
        context.addTurboFilter(ShedLockLogFilter().apply { start() })
    }

    @AfterEach
    fun tearDown() = context.stop()

    private fun output() = buffer.toString(Charsets.UTF_8)

    @Test
    fun `the filter drops the shedlock exception event and keeps other loggers under the ECS output`() {
        val sentinel = "sentinela_ecs_e21"
        val failure = IllegalStateException("banco: $sentinel", SQLException("causa $sentinel"))

        context.getLogger(ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER).error("Unexpected exception", failure)
        context.getLogger("com.zeny.hotel.e21.OtherEcs").error("other ecs failure", failure)

        // A saída é mesmo a estruturada: o evento do outro logger sai em JSON ECS.
        output() shouldContain "\"logger\":\"com.zeny.hotel.e21.OtherEcs\""
        output() shouldContain "other ecs failure"
        output() shouldNotContain "Unexpected exception"
        output() shouldNotContain ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER
    }

    @Test
    fun `an event without exception from the shedlock logger still shows up under the ECS output`() {
        context.getLogger(ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER).error("Unlock failed after 10 attempts")

        output() shouldContain "Unlock failed after 10 attempts"
        output() shouldContain "\"logger\":\"${ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER}\""
    }

    private object Logger {
        const val ROOT_LOGGER_NAME = org.slf4j.Logger.ROOT_LOGGER_NAME
    }
}
