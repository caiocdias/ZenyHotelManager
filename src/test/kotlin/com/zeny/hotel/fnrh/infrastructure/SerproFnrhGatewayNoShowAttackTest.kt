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
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.URI
import java.time.Duration

/** Ataques do testador a `markNoShow` (E14, Task 3): caminho com id hostil, tabela de falhas, mensagem e registro. */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayNoShowAttackTest {
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection? = connectionTo(FnrhTestServer.baseUrl)

    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(10)),
            { connection },
            { calls += it },
        )

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

    private fun noShow(id: String = "r-1") = gateway.markNoShow(FnrhRemoteId(id), "corr-ns")

    @Test
    fun `a remote id with slash, dots, space, percent, question mark or hash stays one segment before noshow`() {
        FnrhTestServer.stubAny(200, "")
        val nasty =
            listOf("a/b", "a?x=1", "a#f", "../cancelar", "..", ".", "a b", "a%2Fb", "100%", "a;b", "ção/é", "a\\b")
        for (id in nasty) {
            FnrhTestServer.server.resetRequests()
            noShow(id)
            val url = FnrhTestServer.onlyRequest().url
            withClue("id=$id url=$url") {
                url.startsWith("${FnrhTestServer.BASE_PATH}/reservas/") shouldBe true
                url.endsWith("/noshow") shouldBe true
                url.contains("?") shouldBe false
                url.contains("#") shouldBe false
                val segments = url.removePrefix("${FnrhTestServer.BASE_PATH}/reservas/").split("/")
                segments.size shouldBe 2
                segments[1] shouldBe "noshow"
                segments[0] shouldNotBe "."
                segments[0] shouldNotBe ".."
                segments[0].isEmpty() shouldBe false
            }
        }
    }

    @Test
    fun `a blank or empty remote id never collapses the path to the reservations noshow collection`() {
        FnrhTestServer.stubAny(200, "")
        for (id in listOf("", " ")) {
            FnrhTestServer.server.resetRequests()
            runCatching { noShow(id) }
            FnrhTestServer.requests().forEach {
                withClue("id='$id' url=${it.url}") {
                    it.url.endsWith("/reservas/noshow") shouldBe false
                }
            }
        }
    }

    @Test
    fun `every status of the table is classified as in E07 and records one call with the failure name`() {
        val table =
            mapOf(
                400 to FnrhFailure.VALIDATION,
                401 to FnrhFailure.UNAUTHORIZED,
                403 to FnrhFailure.FORBIDDEN,
                404 to FnrhFailure.NOT_FOUND,
                409 to FnrhFailure.VALIDATION,
                415 to FnrhFailure.VALIDATION,
                422 to FnrhFailure.VALIDATION,
                429 to FnrhFailure.SERVER_ERROR,
                301 to FnrhFailure.SERVER_ERROR,
                302 to FnrhFailure.SERVER_ERROR,
                307 to FnrhFailure.SERVER_ERROR,
                500 to FnrhFailure.SERVER_ERROR,
                502 to FnrhFailure.SERVER_ERROR,
                503 to FnrhFailure.SERVER_ERROR,
            )
        for ((status, expected) in table) {
            FnrhTestServer.reset()
            calls.clear()
            FnrhTestServer.stubAny(status, """{"mensagem":"x"}""")

            val e = shouldThrow<FnrhGatewayException> { noShow() }

            withClue("status=$status") {
                e.failure shouldBe expected
                e.httpStatus shouldBe status
                e.cause.shouldBeNull()
                e.suppressed.size shouldBe 0
                FnrhTestServer.requests().size shouldBe 1
                calls.size shouldBe 1
                calls.single().operation shouldBe "markNoShow"
                calls.single().outcome shouldBe expected.name
                calls.single().httpStatus shouldBe status
                calls.single().correlationRef shouldBe "corr-ns"
                calls.single().remoteId shouldBe "r-1"
            }
        }
    }

    @Test
    fun `a redirect is not followed and the Location target is never requested`() {
        FnrhTestServer.stubAny(302, "")

        shouldThrow<FnrhGatewayException> { noShow() }.failure shouldBe FnrhFailure.SERVER_ERROR

        FnrhTestServer.requests().size shouldBe 1
    }

    @Test
    fun `the Serpro message is masked and cut in the exception, and the body never reaches the log`(
        output: CapturedOutput,
    ) {
        val long = "x".repeat(500)
        FnrhTestServer.stubAny(
            422,
            """{"mensagem":"Reserva 98765432101 de joao@x.com ja marcada $long"}""",
        )

        val e = shouldThrow<FnrhGatewayException> { noShow("98765432101") }

        val message = e.sanitizedMessage!!
        message shouldNotContain "98765432101"
        message shouldNotContain "joao@x.com"
        (message.length <= 200) shouldBe true
        listOf(e.toString(), calls.toString(), output.all).forEach {
            it shouldNotContain "joao@x.com"
            it shouldNotContain "/noshow"
            it shouldNotContain FnrhTestServer.PASSWORD
        }
    }

    @Test
    fun `a non-JSON error body never reaches the exception, the log or the recorded call`(output: CapturedOutput) {
        FnrhTestServer.stubAny(422, "RAWBODYMARK Maria 12345678901")

        val e = shouldThrow<FnrhGatewayException> { noShow() }

        e.sanitizedMessage.shouldBeNull()
        listOf(e.toString(), calls.toString(), output.all).forEach {
            it shouldNotContain "RAWBODYMARK"
            it shouldNotContain "12345678901"
        }
    }

    @Test
    fun `without configuration it is NOT_CONFIGURED, sends nothing and records no call`() {
        connection = null
        FnrhTestServer.stubAny(200, "")

        val e = shouldThrow<FnrhGatewayException> { noShow() }

        e.failure shouldBe FnrhFailure.NOT_CONFIGURED
        e.httpStatus.shouldBeNull()
        e.cause.shouldBeNull()
        FnrhTestServer.requests().size shouldBe 0
        calls.size shouldBe 0
    }

    @Test
    fun `a success records exactly one call with the status and no failure`() {
        FnrhTestServer.stubMarkNoShow_v2_4("r-1")

        noShow()

        calls.size shouldBe 1
        calls.single().httpStatus shouldBe 200
        calls.single().environment shouldBe FnrhEnvironment.HOMOLOGATION
    }

    @Test
    fun `the failed calls of a refused connection and a timeout record one markNoShow call each`() {
        connection = connectionTo("http://localhost:${java.net.ServerSocket(0).use { it.localPort }}/FNRH_API/rest/v2")
        shouldThrow<FnrhGatewayException> { noShow() }.failure shouldBe FnrhFailure.UNAVAILABLE

        calls.size shouldBe 1
        calls.single().operation shouldBe "markNoShow"
        calls.single().outcome shouldBe "UNAVAILABLE"
        calls.single().httpStatus.shouldBeNull()
    }
}
