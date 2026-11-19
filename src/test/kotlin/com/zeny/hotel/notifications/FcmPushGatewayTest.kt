package com.zeny.hotel.notifications

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSSigner
import com.zeny.hotel.notifications.application.PushMessage
import com.zeny.hotel.notifications.application.PushOutcome
import com.zeny.hotel.notifications.infrastructure.FcmProperties
import com.zeny.hotel.notifications.infrastructure.FcmPushGateway
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A falha na assinatura do JWT do token OAuth (M6 E21, ARCHITECTURE §16): a `JOSEException` de `SignedJWT.sign` (e
 * qualquer `RuntimeException` da montagem da asserção) não escapa do gateway nem leva a mensagem ou a causa ao log, que
 * podem citar material da chave; o envio segue o caminho de falha do token indisponível (temporária, o outbox tenta de
 * novo). Sem contexto Spring: o assinador é injetado.
 */
class FcmPushGatewayTest {
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val message = PushMessage("device-token", "Título", "Texto", mapOf("k" to "v"))

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
    fun `a signing failure becomes an unavailable token, logged by class only`() {
        val gateway = gatewayWith { throw JOSEException("chave $SENTINEL", IllegalStateException("causa $SENTINEL")) }

        val outcome = gateway.send(message)

        outcome shouldBe PushOutcome.TemporaryFailure("token OAuth indisponível")
        logged() shouldContain JOSEException::class.java.name
        logged() shouldContain IllegalStateException::class.java.name
        logged() shouldNotContain SENTINEL
    }

    @Test
    fun `a runtime failure while building the assertion becomes an unavailable token, logged by class only`() {
        val gateway =
            gatewayWith { throw IllegalArgumentException("chave $SENTINEL", RuntimeException("causa $SENTINEL")) }

        val outcome = gateway.send(message)

        outcome shouldBe PushOutcome.TemporaryFailure("token OAuth indisponível")
        logged() shouldContain IllegalArgumentException::class.java.name
        logged() shouldContain RuntimeException::class.java.name
        logged() shouldNotContain SENTINEL
    }

    private fun gatewayWith(signer: (RSAPrivateKey) -> JWSSigner) =
        FcmPushGateway(
            FcmProperties(credentials = FcmTestServer.credentials, endpoint = URI.create(FcmTestServer.baseUrl)),
            Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC),
            JsonMapper.builder().build(),
            signer,
        )

    /** Tudo o que os eventos capturados mostram: mensagem, argumentos e pilha com causas e suprimidas. */
    private fun logged(): String =
        appender.list.joinToString("\n") { event ->
            listOfNotNull(
                event.formattedMessage,
                event.argumentArray?.joinToString { it.toString() },
                event.throwableProxy?.let(ThrowableProxyUtil::asString),
            ).joinToString("\n")
        }

    private companion object {
        const val SENTINEL = "SENTINELA-e21-fcm-sign"
    }
}
