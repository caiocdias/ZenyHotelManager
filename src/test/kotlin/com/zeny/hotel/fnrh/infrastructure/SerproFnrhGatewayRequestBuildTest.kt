package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Terceira rodada do testador (E07, Task 4): a correção 2 (montagem fora do envio e log só com a classe). */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayRequestBuildTest {
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection? = connectionTo(FnrhTestServer.baseUrl)
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofMillis(100)),
            { connection },
            { calls += it },
        )
    private val rid = FnrhRemoteId("remote-1")

    private fun connectionTo(baseUrl: String) =
        FnrhConnection(
            FnrhEnvironment.HOMOLOGATION,
            URI.create(baseUrl),
            FnrhTestServer.USERNAME,
            FnrhTestServer.PASSWORD,
            Cpf.of("11144477735"),
        )

    companion object {
        @JvmStatic
        @BeforeAll
        fun warmUp() {
            FnrhTestServer.reset()
            FnrhTestServer.stubAny(200, """{"dados":[]}""")
            val warm =
                SerproFnrhGateway(
                    FnrhProperties(readTimeout = Duration.ofSeconds(10)),
                    {
                        FnrhConnection(
                            FnrhEnvironment.HOMOLOGATION,
                            URI.create(FnrhTestServer.baseUrl),
                            FnrhTestServer.USERNAME,
                            FnrhTestServer.PASSWORD,
                            Cpf.of("11144477735"),
                        )
                    },
                    { },
                )
            repeat(2) { warm.fetchDomain(FnrhDomain.RACES) }
        }
    }

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    @Test
    fun `a build failure logs only the exception class, never the message, the scheme nor the host`(
        output: CapturedOutput,
    ) {
        connection = connectionTo("ftp://segredo.example/FNRH_API/rest/v2")

        shouldThrow<FnrhGatewayException> { gateway.cancelReservation(rid, "corr-r3") }

        val log = output.all
        log shouldContain "IllegalArgumentException"
        listOf("ftp", "segredo", "esquema", "/reservas", FnrhTestServer.PASSWORD).forEach { log shouldNotContain it }
    }

    @Test
    fun `an upper case HTTP scheme is still accepted`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""")
        connection = connectionTo(FnrhTestServer.baseUrl.replaceFirst("http", "HTTP"))

        gateway.fetchDomain(FnrhDomain.RACES)

        calls.single().outcome shouldBe "SUCCESS"
    }

    @Test
    fun `an opaque or relative base URL is UNAVAILABLE and logs one call, not a raw exception`() {
        for (base in listOf("mailto:a@b.c", "file:///etc/passwd", "/relative/path", "//host/path")) {
            calls.clear()
            connection = connectionTo(base)

            val e = shouldThrow<FnrhGatewayException> { gateway.fetchDomain(FnrhDomain.RACES) }

            e.failure shouldBe FnrhFailure.UNAVAILABLE
            e.cause.shouldBeNull()
            calls.size shouldBe 1
        }
    }

    @Test
    fun `a refused connection on a write stays UNAVAILABLE and a real read timeout on a write stays UNKNOWN_OUTCOME`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        connection = connectionTo("http://localhost:$closedPort/FNRH_API/rest/v2")
        shouldThrow<FnrhGatewayException> { gateway.checkOut(rid, Instant.EPOCH, "c") }.failure shouldBe
            FnrhFailure.UNAVAILABLE

        connection = connectionTo(FnrhTestServer.baseUrl)
        FnrhTestServer.stubAny(200, "{}", delayMs = 400)
        shouldThrow<FnrhGatewayException> { gateway.checkOut(rid, Instant.EPOCH, "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `the send failure log of a real timeout shows the class and no URL, id nor body`(output: CapturedOutput) {
        FnrhTestServer.stubAny(200, "{}", delayMs = 400)

        shouldThrow<FnrhGatewayException> { gateway.checkOut(FnrhRemoteId("hos-SECRETID"), Instant.EPOCH, "corr-t") }

        val log = output.all
        log shouldContain "ResourceAccessException"
        listOf("SECRETID", "localhost", "/checkout", "1970", FnrhTestServer.PASSWORD).forEach {
            log shouldNotContain it
        }
    }
}
