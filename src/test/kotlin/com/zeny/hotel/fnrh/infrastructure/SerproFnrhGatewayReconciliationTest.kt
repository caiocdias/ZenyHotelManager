package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhRemoteGuestRef
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.URI
import java.time.Duration

/** As consultas de conciliação do adaptador (E08 Task 4): hóspedes da reserva e pessoa por documento. */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayReconciliationTest {
    private val requesterCpf = Cpf.of("52998224725")
    private val cpfDocument = FnrhDocument(FnrhDocumentType.CPF, "11144477735")
    private val passport = FnrhDocument(FnrhDocumentType.PASSPORT, "AB123456")
    private val calls = mutableListOf<FnrhCall>()

    private fun gatewayWith(readTimeout: Duration) =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = readTimeout),
            {
                FnrhConnection(
                    FnrhEnvironment.HOMOLOGATION,
                    URI.create(FnrhTestServer.baseUrl),
                    FnrhTestServer.USERNAME,
                    FnrhTestServer.PASSWORD,
                    Cpf.of("11144477735"),
                )
            },
            { calls += it },
        )

    private val gateway = gatewayWith(Duration.ofSeconds(10))
    private val shortTimeoutGateway = gatewayWith(Duration.ofMillis(100))

    companion object {
        /** A primeira chamada da JVM passa dos 100 ms do timeout de teste: aquece o cliente HTTP. */
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

    // ---- listReservationGuests

    @Test
    fun `listReservationGuests reads only the guest and person ids`() {
        FnrhTestServer.stubListReservationGuests_v2_4("r-1", "h-1" to "p-1", "h-2" to "p-2")

        gateway.listReservationGuests(FnrhRemoteId("r-1"), "corr-1") shouldContainExactly
            listOf(
                FnrhRemoteGuestRef(FnrhRemoteId("h-1"), FnrhRemoteId("p-1")),
                FnrhRemoteGuestRef(FnrhRemoteId("h-2"), FnrhRemoteId("p-2")),
            )

        val request = FnrhTestServer.onlyRequest()
        request.method.value() shouldBe "GET"
        request.url shouldBe "${FnrhTestServer.BASE_PATH}/reservas/r-1/hospedes"
        request.getHeader("Authorization") shouldBe FnrhTestServer.basicAuthorization
        request.getHeader("cpf_solicitante").shouldBeNull()
        calls.single().operation shouldBe "listReservationGuests"
        calls.single().remoteId shouldBe "r-1"
        calls.single().correlationRef shouldBe "corr-1"
    }

    @Test
    fun `listReservationGuests is empty when the reservation has no guest`() {
        FnrhTestServer.stubListReservationGuests_v2_4("r-1")

        gateway.listReservationGuests(FnrhRemoteId("r-1"), "c").shouldBeEmpty()
    }

    @Test
    fun `listReservationGuests fails closed on a 200 without dados`() {
        FnrhTestServer.stubListReservationGuests_v2_4("r-1", body = """{"mensagem":"ok"}""")

        failureOf { gateway.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `listReservationGuests fails closed on an item without hospede_id or pessoa_id`() {
        FnrhTestServer.stubListReservationGuests_v2_4(
            "r-1",
            body = """{"dados":[{"hospede":{"hospede_id":"h-1","pessoa_id":"p-1"}},{"hospede":{"pessoa_id":"p-2"}}]}""",
        )
        failureOf { gateway.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME

        FnrhTestServer.stubListReservationGuests_v2_4("r-1", body = """{"dados":[{"hospede":{"hospede_id":"h-1"}}]}""")
        failureOf { gateway.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `listReservationGuests maps a 500 to SERVER_ERROR`() {
        FnrhTestServer.stub("/reservas/r-1/hospedes", 500, """{"mensagem":"falha"}""")

        failureOf { gateway.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe FnrhFailure.SERVER_ERROR
    }

    @Test
    fun `listReservationGuests read timeout is UNAVAILABLE`() {
        FnrhTestServer.stub("/reservas/r-1/hospedes", 200, """{"dados":[]}""", delayMs = 300)

        failureOf { shortTimeoutGateway.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe
            FnrhFailure.UNAVAILABLE
    }

    // ---- findPersonByDocument

    @Test
    fun `findPersonByDocument returns dados id and sends the requester header`() {
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725", personId = "p-9")

        gateway.findPersonByDocument(cpfDocument, requesterCpf, "corr-2") shouldBe FnrhRemoteId("p-9")

        val request = FnrhTestServer.onlyRequest()
        request.method.value() shouldBe "GET"
        request.url shouldBe "${FnrhTestServer.BASE_PATH}/pessoas/documento/CPF/11144477735"
        request.getHeader("Authorization") shouldBe FnrhTestServer.basicAuthorization
        request.getHeader("cpf_solicitante") shouldBe "52998224725"
        calls.single().operation shouldBe "findPersonByDocument"
        calls.single().remoteId shouldBe "p-9"
        calls.single().correlationRef shouldBe "corr-2"
    }

    @Test
    fun `findPersonByDocument uses PASSAPORTE as the type for a passport`() {
        FnrhTestServer.stubFindPersonByDocument_v2_4("PASSAPORTE", "AB123456", "52998224725", personId = "p-1")

        gateway.findPersonByDocument(passport, requesterCpf, "c") shouldBe FnrhRemoteId("p-1")
        FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/pessoas/documento/PASSAPORTE/AB123456"
    }

    @Test
    fun `findPersonByDocument is null when dados has no id`() {
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725")

        gateway.findPersonByDocument(cpfDocument, requesterCpf, "c").shouldBeNull()
    }

    @Test
    fun `findPersonByDocument is null on a 404`() {
        FnrhTestServer.stub("/pessoas/documento/CPF/11144477735", 404, """{"mensagem":"não encontrada"}""")

        gateway.findPersonByDocument(cpfDocument, requesterCpf, "c").shouldBeNull()
    }

    @Test
    fun `findPersonByDocument fails closed on a 200 without dados`() {
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725", body = """{"x":1}""")

        failureOf { gateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `findPersonByDocument maps a 500 to SERVER_ERROR and a read timeout to UNAVAILABLE`() {
        FnrhTestServer.stub("/pessoas/documento/CPF/11144477735", 500, """{"mensagem":"falha"}""")
        failureOf { gateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }.failure shouldBe
            FnrhFailure.SERVER_ERROR

        FnrhTestServer.reset()
        FnrhTestServer.stub("/pessoas/documento/CPF/11144477735", 200, """{"dados":{}}""", delayMs = 300)
        failureOf { shortTimeoutGateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }.failure shouldBe
            FnrhFailure.UNAVAILABLE
    }

    // ---- nenhum dado pessoal em log, mensagem nem registro

    @Test
    fun `neither log nor exception message carries the document, the url or response personal data`(
        output: CapturedOutput,
    ) {
        val leaks =
            listOf("11144477735", "52998224725", "/pessoas/documento", "Maria", "maria@example.com", "988887777")
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725", personId = "p-9")
        gateway.findPersonByDocument(cpfDocument, requesterCpf, "c")
        FnrhTestServer.stubListReservationGuests_v2_4("r-1", "h-1" to "p-1")
        gateway.listReservationGuests(FnrhRemoteId("r-1"), "c")

        FnrhTestServer.stub(
            "/pessoas/documento/CPF/11144477735",
            500,
            """{"mensagem":"Documento 11144477735 de maria@example.com"}""",
        )
        val failure = failureOf { gateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }
        failure.cause.shouldBeNull()
        val text = output.all + failure.message + calls.joinToString { it.toString() }
        leaks.forEach { text shouldNotContain it }
    }

    @Test
    fun `findPersonByDocument fails closed when dados id is present but empty, null or not text`() {
        listOf("\"\"", "null", "123", "{}").forEach { raw ->
            FnrhTestServer.reset()
            FnrhTestServer.stubFindPersonByDocument_v2_4(
                "CPF",
                "11144477735",
                "52998224725",
                body = """{"dados":{"id":$raw,"documento":{}}}""",
            )
            failureOf { gateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }.failure shouldBe
                FnrhFailure.UNKNOWN_OUTCOME
        }
    }

    @Test
    fun `error messages of the lookups are never echoed`(output: CapturedOutput) {
        FnrhTestServer.stub("/pessoas/documento/CPF/11144477735", 400, """{"mensagem":"Pessoa Joaquim Teixeira"}""")
        val e = failureOf { gateway.findPersonByDocument(cpfDocument, requesterCpf, "c") }
        e.sanitizedMessage.shouldBeNull()
        (output.all + e.message) shouldNotContain "Joaquim"
    }
}
