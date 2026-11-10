package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.URI
import java.time.Duration

/**
 * Ataques às consultas de conciliação (E08 Task 4): privacidade da `mensagem` de erro, falha fechada, classificação
 * das falhas de `GET`, escape do número do documento no caminho e o registro de cada chamada.
 */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayReconciliationAttackTest {
    private val requesterCpf = Cpf.of("52998224725")
    private val cpfDocument = FnrhDocument(FnrhDocumentType.CPF, "11144477735")
    private val calls = mutableListOf<FnrhCall>()

    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(10)),
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

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    private fun guests(id: String = "r-1") = gateway.listReservationGuests(FnrhRemoteId(id), "corr")

    private fun find() = gateway.findPersonByDocument(cpfDocument, requesterCpf, "corr")

    private fun failureOf(block: () -> Unit): FnrhGatewayException = shouldThrow<FnrhGatewayException> { block() }

    private fun stubGuestsBody(
        body: String,
        status: Int = 200,
    ) = FnrhTestServer.stub("/reservas/r-1/hospedes", status, body)

    private fun stubPersonBody(
        body: String,
        status: Int = 200,
    ) = FnrhTestServer.stub("/pessoas/documento/CPF/11144477735", status, body)

    // ---- privacidade: o que o Serpro ecoa na mensagem de erro e não foi enviado por nós

    private val name = "Joaquim Teixeira"
    private val email = "joaquim.teixeira@example.org"

    private fun leakedText(
        output: CapturedOutput,
        failure: FnrhGatewayException,
    ) = output.all + failure.message + failure.sanitizedMessage + calls.joinToString { it.toString() }

    @ParameterizedTest
    @ValueSource(ints = [400, 404, 500])
    fun `findPersonByDocument does not leak a name echoed in the error message`(
        status: Int,
        output: CapturedOutput,
    ) {
        stubPersonBody("""{"mensagem":"Pessoa $name não localizada"}""", status)

        val text =
            try {
                find()
                output.all + calls.joinToString { it.toString() }
            } catch (e: FnrhGatewayException) {
                leakedText(output, e)
            }

        text shouldNotContain "Joaquim"
        text shouldNotContain "Teixeira"
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 500])
    fun `listReservationGuests does not leak a name echoed in the error message`(
        status: Int,
        output: CapturedOutput,
    ) {
        stubGuestsBody("""{"mensagem":"Hóspede $name inválido"}""", status)

        val text = leakedText(output, failureOf { guests() })

        text shouldNotContain "Joaquim"
        text shouldNotContain "Teixeira"
    }

    @Test
    fun `findPersonByDocument does not leak an email, a formatted CPF nor the document echoed in an error`(
        output: CapturedOutput,
    ) {
        stubPersonBody(
            """{"mensagem":"Falha para $email, CPF 111.444.777-35, doc 11144477735, solicitante 52998224725"}""",
            500,
        )

        val text = leakedText(output, failureOf { find() })

        listOf(email, "joaquim.teixeira", "111.444.777-35", "11144477735", "52998224725").forEach {
            text shouldNotContain it
        }
    }

    @Test
    fun `listReservationGuests does not leak an email echoed in an error`(output: CapturedOutput) {
        stubGuestsBody("""{"mensagem":"Falha para $email"}""", 500)

        val text = leakedText(output, failureOf { guests() })

        text shouldNotContain email
        text shouldNotContain "joaquim.teixeira"
    }

    @Test
    fun `a successful reply with personal data leaves no personal data in log, record or return`(
        output: CapturedOutput,
    ) {
        stubPersonBody(
            """{"dados":{"id":"p-9","dado_pessoal":{"nome":"$name","data_nascimento":"1990-01-02"},""" +
                """"contato":{"Email":"$email","Telefone":"+5511988887777"}}}""",
        )
        val id = find()
        stubGuestsBody(
            """{"dados":[{"pessoa":{"pessoa_id":"p-9","nome":"$name","email":"$email"},""" +
                """"hospede":{"hospede_id":"h-1","pessoa_id":"p-9"}}]}""",
        )
        val refs = guests()

        val text = output.all + id + refs.joinToString { it.toString() } + calls.joinToString { it.toString() }
        listOf("Joaquim", "Teixeira", email, "1990-01-02", "988887777", "11144477735", "52998224725").forEach {
            text shouldNotContain it
        }
    }

    // ---- falha fechada: listReservationGuests

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{}""",
            """{"dados":null}""",
            """{"dados":{}}""",
            """{"dados":"x"}""",
            """{"dados":7}""",
            """{"dados":[null]}""",
            """{"dados":[{}]}""",
            """{"dados":[{"hospede":null}]}""",
            """{"dados":[{"hospede":"x"}]}""",
            """{"dados":[{"hospede":{"hospede_id":"","pessoa_id":"p-1"}}]}""",
            """{"dados":[{"hospede":{"hospede_id":"h-1","pessoa_id":""}}]}""",
            """{"dados":[{"hospede":{"hospede_id":"  ","pessoa_id":"p-1"}}]}""",
            """{"dados":[{"hospede":{"hospede_id":12,"pessoa_id":"p-1"}}]}""",
            """{"dados":[{"hospede":{"hospede_id":"h-1","pessoa_id":{"id":"p-1"}}}]}""",
            """{"dados":[{"hospede":{"hospede_id":null,"pessoa_id":"p-1"}}]}""",
            """{"dados":[{"pessoa":{"pessoa_id":"p-1"},"hospede_id":"h-1"}]}""",
        ],
    )
    fun `listReservationGuests fails closed on a malformed 200`(body: String) {
        stubGuestsBody(body)

        val failure = failureOf { guests() }

        failure.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        failure.httpStatus shouldBe 200
        calls.single().outcome shouldBe "UNKNOWN_OUTCOME"
    }

    @Test
    fun `listReservationGuests does not return the good items when another item of the page is malformed`() {
        stubGuestsBody(
            """{"dados":[{"hospede":{"hospede_id":"h-1","pessoa_id":"p-1"}},{"hospede":{"hospede_id":"h-2"}}]}""",
        )

        failureOf { guests() }.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "{\"dados\":[", "[]", "null"])
    fun `listReservationGuests with an empty or invalid body throws and never returns a list`(body: String) {
        stubGuestsBody(body)

        val failure = failureOf { guests() }

        (failure.failure in setOf(FnrhFailure.UNKNOWN_OUTCOME, FnrhFailure.SERVER_ERROR)) shouldBe true
    }

    @Test
    fun `listReservationGuests on a 404 throws NOT_FOUND instead of returning an empty list`() {
        stubGuestsBody("""{"mensagem":"x"}""", 404)

        failureOf { guests() }.failure shouldBe FnrhFailure.NOT_FOUND
    }

    // ---- falha fechada: findPersonByDocument

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{}""",
            """{"dados":null}""",
            """{"dados":[]}""",
            """{"dados":[{"id":"p-1"}]}""",
            """{"dados":"p-1"}""",
            """{"dados":5}""",
        ],
    )
    fun `findPersonByDocument fails closed when dados is not an object`(body: String) {
        stubPersonBody(body)

        failureOf { find() }.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "{\"dados\":{", "null"])
    fun `findPersonByDocument with an empty or invalid body throws and never says the person does not exist`(
        body: String,
    ) {
        stubPersonBody(body)

        val failure = failureOf { find() }

        (failure.failure in setOf(FnrhFailure.UNKNOWN_OUTCOME, FnrhFailure.SERVER_ERROR)) shouldBe true
    }

    @Test
    fun `findPersonByDocument ignores a pessoa_id or an id outside dados`() {
        stubPersonBody("""{"id":"p-x","pessoa_id":"p-y","dados":{"documento":{"id":"d-1"}}}""")

        find().shouldBeNull()
    }

    @Test
    fun `findPersonByDocument 404 is null even with an empty body, and is recorded as NOT_FOUND`() {
        stubPersonBody("", 404)

        find().shouldBeNull()
        calls.single().outcome shouldBe "NOT_FOUND"
        calls.single().httpStatus shouldBe 404
    }

    // ---- classificação das falhas de GET

    @ParameterizedTest
    @CsvSource(
        "400,VALIDATION",
        "401,UNAUTHORIZED",
        "403,FORBIDDEN",
        "422,VALIDATION",
        "429,SERVER_ERROR",
        "500,SERVER_ERROR",
        "502,SERVER_ERROR",
        "503,SERVER_ERROR",
    )
    fun `GET failures map to the port failure in both queries and are recorded with the same outcome`(
        status: Int,
        expected: FnrhFailure,
    ) {
        stubGuestsBody("""{"mensagem":"x"}""", status)
        stubPersonBody("""{"mensagem":"x"}""", status)

        failureOf { guests() }.failure shouldBe expected
        failureOf { find() }.failure shouldBe expected
        calls.map { it.outcome } shouldBe listOf(expected.name, expected.name)
        calls.map { it.httpStatus } shouldBe listOf(status, status)
    }

    @Test
    fun `an unreachable server is UNAVAILABLE and never UNKNOWN_OUTCOME for the GET queries`() {
        val down =
            SerproFnrhGateway(
                FnrhProperties(connectTimeout = Duration.ofMillis(500), readTimeout = Duration.ofSeconds(1)),
                {
                    FnrhConnection(
                        FnrhEnvironment.HOMOLOGATION,
                        URI.create("http://127.0.0.1:1/FNRH_API/rest/v2"),
                        "u",
                        "p",
                        Cpf.of("11144477735"),
                    )
                },
                { calls += it },
            )

        failureOf { down.listReservationGuests(FnrhRemoteId("r-1"), "c") }.failure shouldBe FnrhFailure.UNAVAILABLE
        failureOf { down.findPersonByDocument(cpfDocument, requesterCpf, "c") }.failure shouldBe
            FnrhFailure.UNAVAILABLE
        calls.map { it.outcome } shouldBe listOf("UNAVAILABLE", "UNAVAILABLE")
    }

    // ---- cabeçalho, tipo e escape do caminho

    @Test
    fun `cpf_solicitante is sent only by findPersonByDocument`() {
        FnrhTestServer.stubListReservationGuests_v2_4("r-1")
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725")
        guests()
        find()

        val byUrl = FnrhTestServer.requests().associateBy { it.url.substringAfter("/v2") }
        byUrl.getValue("/reservas/r-1/hospedes").getHeader("cpf_solicitante").shouldBeNull()
        byUrl.getValue("/pessoas/documento/CPF/11144477735").getHeader("cpf_solicitante") shouldBe "52998224725"
    }

    @Test
    fun `the requester header carries only digits and never the document`() {
        FnrhTestServer.stubAny(200, """{"dados":{}}""")
        gateway.findPersonByDocument(cpfDocument, Cpf.of("529.982.247-25"), "c")

        FnrhTestServer.onlyRequest().getHeader("cpf_solicitante") shouldBe "52998224725"
    }

    @Test
    fun `a passport number with letters is sent under PASSAPORTE as is`() {
        FnrhTestServer.stubAny(200, """{"dados":{"id":"p-1"}}""")

        gateway.findPersonByDocument(FnrhDocument(FnrhDocumentType.PASSPORT, "Xy98ab7Z"), requesterCpf, "c") shouldBe
            FnrhRemoteId("p-1")
        FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/pessoas/documento/PASSAPORTE/Xy98ab7Z"
    }

    @Test
    fun `characters that need escaping stay inside the path segment of the document`() {
        FnrhTestServer.stubAny(200, """{"dados":{}}""")

        gateway.findPersonByDocument(FnrhDocument(FnrhDocumentType.PASSPORT, "A/B 1?x#y"), requesterCpf, "c")

        FnrhTestServer.onlyRequest().url shouldBe
            "${FnrhTestServer.BASE_PATH}/pessoas/documento/PASSAPORTE/A%2FB%201%3Fx%23y"
    }

    @Test
    fun `a document number made of dots cannot climb the path`() {
        FnrhTestServer.stubAny(200, """{"dados":{}}""")

        gateway.findPersonByDocument(FnrhDocument(FnrhDocumentType.PASSPORT, ".."), requesterCpf, "c")

        FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/pessoas/documento/PASSAPORTE/%2E%2E"
    }

    @Test
    fun `a reservation id with slashes cannot leave its segment`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""")

        guests("a/../b")

        FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/reservas/a%2F..%2Fb/hospedes"
    }

    // ---- registro de cada chamada

    @Test
    fun `each query records exactly one call with the outcome, status and correlation, and no free text`() {
        FnrhTestServer.stubListReservationGuests_v2_4("r-1", "h-1" to "p-1")
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725", personId = "p-9")
        guests()
        find()

        calls.map { it.operation } shouldBe listOf("listReservationGuests", "findPersonByDocument")
        calls.map { it.outcome } shouldBe listOf("SUCCESS", "SUCCESS")
        calls.map { it.httpStatus } shouldBe listOf(200, 200)
        calls.map { it.correlationRef } shouldBe listOf("corr", "corr")
        calls.map { it.remoteId } shouldBe listOf("r-1", "p-9")
        calls.map { it.environment } shouldBe listOf(FnrhEnvironment.HOMOLOGATION, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `findPersonByDocument without id records no remote id`() {
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725")

        find().shouldBeNull()

        calls.single().outcome shouldBe "SUCCESS"
        calls.single().remoteId.shouldBeNull()
    }

    @Test
    fun `a failing record does not change the result of the query`() {
        val broken =
            SerproFnrhGateway(
                FnrhProperties(),
                {
                    FnrhConnection(
                        FnrhEnvironment.HOMOLOGATION,
                        URI.create(FnrhTestServer.baseUrl),
                        FnrhTestServer.USERNAME,
                        FnrhTestServer.PASSWORD,
                        Cpf.of("11144477735"),
                    )
                },
                { error("banco fora") },
            )
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", "11144477735", "52998224725", personId = "p-9")

        broken.findPersonByDocument(cpfDocument, requesterCpf, "c") shouldBe FnrhRemoteId("p-9")
    }
}
