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
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.time.Duration

/** `markNoShow` (`POST /reservas/{id}/noshow`, D-E14-1) contra a FNRH de mentira, sem contexto do Spring. */
class SerproFnrhGatewayNoShowTest {
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection = connectionTo(FnrhTestServer.baseUrl)

    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(10)),
            { connection },
            { calls += it },
        )

    private val shortTimeoutGateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofMillis(100)),
            { connection },
            { calls += it },
        )

    private fun connectionTo(baseUrl: String) =
        FnrhConnection(
            environment = FnrhEnvironment.HOMOLOGATION,
            baseUrl = URI.create(baseUrl),
            username = FnrhTestServer.USERNAME,
            password = FnrhTestServer.PASSWORD,
            defaultRequesterCpf = Cpf.of("11144477735"),
        )

    companion object {
        /** Aquece o cliente HTTP da JVM para o timeout de 100 ms só disparar onde o teste o provoca. */
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

    private fun failureOf(block: () -> Unit): FnrhGatewayException = shouldThrow<FnrhGatewayException> { block() }

    @Test
    fun `markNoShow posts to the reservation noshow path without body`() {
        FnrhTestServer.stubMarkNoShow_v2_4("r-1")

        gateway.markNoShow(FnrhRemoteId("r-1"), "corr-1")

        val request = FnrhTestServer.onlyRequest()
        request.method.value() shouldBe "POST"
        request.url shouldBe "${FnrhTestServer.BASE_PATH}/reservas/r-1/noshow"
        request.getHeader("Authorization") shouldBe FnrhTestServer.basicAuthorization
        request.getHeader("cpf_solicitante").shouldBeNull()
        request.bodyAsString shouldBe ""
    }

    @Test
    fun `markNoShow accepts any 2xx body`() {
        listOf("""{"dados": []}""", "", "<html>nao json</html>").forEach { body ->
            FnrhTestServer.reset()
            FnrhTestServer.stubAny(200, body)

            gateway.markNoShow(FnrhRemoteId("r-1"), "corr-1")
        }
    }

    @Test
    fun `markNoShow maps failures like the other writes`() {
        mapOf(
            422 to FnrhFailure.VALIDATION,
            404 to FnrhFailure.NOT_FOUND,
            503 to FnrhFailure.SERVER_ERROR,
        ).forEach { (status, expected) ->
            FnrhTestServer.reset()
            FnrhTestServer.stubAny(status, """{"erro":"x","mensagem":"y","codigo":$status}""")

            val e = failureOf { gateway.markNoShow(FnrhRemoteId("r-1"), "corr-1") }

            e.failure shouldBe expected
            e.httpStatus shouldBe status
            e.cause.shouldBeNull()
        }
    }

    @Test
    fun `markNoShow on a closed port is UNAVAILABLE`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        connection = connectionTo("http://localhost:$closedPort/FNRH_API/rest/v2")

        failureOf { gateway.markNoShow(FnrhRemoteId("r-1"), "corr-1") }.failure shouldBe FnrhFailure.UNAVAILABLE
    }

    @Test
    fun `markNoShow read timeout is UNKNOWN_OUTCOME, since the server may have written`() {
        FnrhTestServer.stubAny(200, "", delayMs = 300)

        val e = failureOf { shortTimeoutGateway.markNoShow(FnrhRemoteId("r-1"), "corr-1") }

        e.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        e.httpStatus.shouldBeNull()
    }

    @Test
    fun `markNoShow logs the call without personal data`() {
        FnrhTestServer.stubMarkNoShow_v2_4("r-1")

        gateway.markNoShow(FnrhRemoteId("r-1"), "corr-1")

        val call = calls.single()
        call.operation shouldBe "markNoShow"
        call.correlationRef shouldBe "corr-1"
        call.remoteId shouldBe "r-1"
        call.outcome shouldBe "SUCCESS"
        call.toString() shouldNotContain "/noshow"
        call.toString() shouldNotContain FnrhTestServer.PASSWORD
    }
}
