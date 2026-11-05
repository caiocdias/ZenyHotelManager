package com.zeny.hotel.fnrh.infrastructure

import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * O adaptador do Serpro contra a FNRH de mentira (API v2.4), sem contexto do Spring: o caminho, o método, o
 * `Authorization`, o corpo, o `cpf_solicitante` (só em `registerPerson`), o mapeamento dos erros e o registro de
 * cada chamada.
 */
class SerproFnrhGatewayTest {
    private val mapper = JsonMapper.builder().build()
    private val requesterCpf = Cpf.of("52998224725")
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection? = connectionTo(FnrhTestServer.baseUrl)

    /** Prazo largo para os casos felizes e de erro de status: um prazo curto daria falha intermitente em CI. */
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(10)),
            { connection },
            { calls += it },
        )

    /** Só para os testes de timeout de leitura (100 ms contra um atraso de 300 ms). */
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
        /**
         * A primeira chamada da JVM carrega as classes do cliente HTTP e passa dos 100 ms do `read-timeout` de
         * teste: aquece com um adaptador de prazo largo para o timeout só disparar onde o teste o provoca.
         */
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
            repeat(2) {
                warm.fetchDomain(FnrhDomain.RACES)
                warm.cancelReservation(FnrhRemoteId("warm"), "warm")
            }
        }
    }

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    private fun LoggedRequest.json() = mapper.readTree(bodyAsString)

    private fun LoggedRequest.expectCall(
        method: String,
        path: String,
    ) {
        this.method.value() shouldBe method
        url shouldBe "${FnrhTestServer.BASE_PATH}$path"
        getHeader("Authorization") shouldBe FnrhTestServer.basicAuthorization
    }

    private fun LoggedRequest.requesterHeader(): String? = getHeader("cpf_solicitante")

    private val reservation =
        FnrhReservationData(
            reservationCode = "RES-2026-0001",
            otaCode = null,
            arrival = LocalDate.parse("2026-02-10"),
            departure = LocalDate.parse("2026-02-15"),
            adults = 2,
            minors = 1,
        )

    private val person =
        FnrhPersonData(
            name = "MARIA DA LUZ",
            socialName = null,
            nationality = "BR",
            gender = "MULHER",
            birthDate = LocalDate.parse("1990-05-17"),
            race = "PARDA",
            disabilityOption = "SIM",
            disabilityType = "VISUAL",
            document = FnrhDocument(FnrhDocumentType.CPF, "12345678901"),
            email = "maria@x.com",
            phone = "11999998888",
            residenceCountry = "BR",
            postalCode = "01310100",
            street = "Avenida Paulista",
            number = "1000",
            neighborhood = "Bela Vista",
            cityIbge = 3550308,
            state = "SP",
        )

    @Test
    fun `registerReservation posts the reservation without personal data nor the requester CPF`() {
        FnrhTestServer.stubRegisterReservation_v2_4("res-uuid-1", "https://precheckin.example/r/1")

        val result = gateway.registerReservation(reservation, "corr-1")

        result.reservationId shouldBe FnrhRemoteId("res-uuid-1")
        result.precheckinLink shouldBe "https://precheckin.example/r/1"
        val request = FnrhTestServer.onlyRequest()
        request.expectCall("POST", "/reservas")
        request.requesterHeader().shouldBeNull()
        request.getHeader("Content-Type") shouldStartWith "application/json"
        request.json() shouldBe
            mapper.readTree(
                """
                {"numero_reserva":"RES-2026-0001","data_entrada":"2026-02-10","data_saida":"2026-02-15",
                 "quantidade_hospede_adulto":2,"quantidade_hospede_menor":1,"origem_reserva_id":"MEIOHOSPEDAGEM"}
                """,
            )
    }

    @Test
    fun `registerReservation with an OTA code sends it and the OTA origin`() {
        FnrhTestServer.stubRegisterReservation_v2_4("res-uuid-2", precheckinLink = null)

        val result = gateway.registerReservation(reservation.copy(otaCode = "HB-777"), "corr-1")

        result.precheckinLink.shouldBeNull()
        val body = FnrhTestServer.onlyRequest().json()
        body["numero_reserva_ota"].asString() shouldBe "HB-777"
        body["origem_reserva_id"].asString() shouldBe "OTA"
        body["numero_reserva"].asString() shouldBe "RES-2026-0001"
    }

    @Test
    fun `updateReservation puts the data on the remote id`() {
        FnrhTestServer.stub("/reservas/res-uuid-1")

        gateway.updateReservation(FnrhRemoteId("res-uuid-1"), reservation, "corr-1")

        val request = FnrhTestServer.onlyRequest()
        request.expectCall("PUT", "/reservas/res-uuid-1")
        request.requesterHeader().shouldBeNull()
        request.json() shouldBe
            mapper.readTree(
                """
                {"numero_reserva":"RES-2026-0001","data_entrada":"2026-02-10","data_saida":"2026-02-15",
                 "quantidade_hospede_adulto":2,"quantidade_hospede_menor":1}
                """,
            )
        calls.single().outcome shouldBe "SUCCESS"
        calls.single().remoteId shouldBe "res-uuid-1"
    }

    @Test
    fun `cancelReservation posts to cancelar without a body nor the requester CPF`() {
        FnrhTestServer.stub("/reservas/res-uuid-1/cancelar")

        gateway.cancelReservation(FnrhRemoteId("res-uuid-1"), "corr-1")

        val request = FnrhTestServer.onlyRequest()
        request.expectCall("POST", "/reservas/res-uuid-1/cancelar")
        request.requesterHeader().shouldBeNull()
    }

    @Test
    fun `findReservationByCode maps reserva_id of the item whose numero_reserva is the code`() {
        FnrhTestServer.stub(
            "/reservas?page_number=1&codigo_reserva=RES-2026-0001",
            body =
                """{"dados":[{"reserva_id":"res-a","numero_reserva":"RES-2026-0010"},""" +
                    """{"reserva_id":"res-b","numero_reserva":"RES-2026-0001"}],"pagination":{"TotalRegistros":2}}""",
        )

        gateway.findReservationByCode("RES-2026-0001", "corr-1") shouldBe FnrhRemoteId("res-b")

        val request = FnrhTestServer.onlyRequest()
        request.expectCall("GET", "/reservas?page_number=1&codigo_reserva=RES-2026-0001")
        request.requesterHeader().shouldBeNull()
    }

    @Test
    fun `findReservationByCode with two items without numero_reserva is an unknown outcome, none is picked`() {
        FnrhTestServer.stub(
            "/reservas?page_number=1&codigo_reserva=RES-2026-0001",
            body = """{"dados":[{"reserva_id":"res-a"},{"reserva_id":"res-b"}],"pagination":{"TotalRegistros":2}}""",
        )

        shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-2026-0001", "corr-1") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `findReservationByCode returns null with an empty list`() {
        FnrhTestServer.stub(
            "/reservas?page_number=1&codigo_reserva=NOPE",
            body = """{"dados":[],"pagination":{"TotalRegistros":0}}""",
        )

        gateway.findReservationByCode("NOPE", "corr-1").shouldBeNull()
    }

    @Test
    fun `registerPerson sends the requester CPF and the person fields, and maps pessoa_id`() {
        FnrhTestServer.stub("/pessoas", body = """{"pessoa_id":"pes-uuid-1"}""")

        val id = gateway.registerPerson(person, requesterCpf, "corr-1")

        id shouldBe FnrhRemoteId("pes-uuid-1")
        val request = FnrhTestServer.onlyRequest()
        request.expectCall("POST", "/pessoas")
        request.requesterHeader() shouldBe "52998224725"
        request.json() shouldBe
            mapper.readTree(
                """
                {"pessoa":{"nome":"MARIA DA LUZ","PaisNacionalidade_id":"BR","genero_id":"MULHER",
                 "data_nascimento":"1990-05-17","raca_id":"PARDA","deficiencia_id":"SIM","tipo_deficiencia_id":"VISUAL",
                 "documento_id":{"numero_documento":"12345678901","tipo_documento_id":"CPF"},
                 "contato":{"email":"maria@x.com","telefone":"11999998888","PaisResidencia_id":"BR","cep":"01310100",
                  "logradouro":"Avenida Paulista","numero":"1000","bairro":"Bela Vista","cidade_id":3550308,
                  "estado_id":"SP"}}}
                """,
            )
    }

    @Test
    fun `registerPerson sends a passport as PASSAPORTE`() {
        FnrhTestServer.stub("/pessoas", body = """{"pessoa_id":"pes-uuid-2"}""")

        gateway.registerPerson(
            person.copy(document = FnrhDocument(FnrhDocumentType.PASSPORT, "AB123456")),
            requesterCpf,
            "corr-1",
        )

        FnrhTestServer.onlyRequest().json()["pessoa"]["documento_id"]["tipo_documento_id"].asString() shouldBe
            "PASSAPORTE"
    }

    @Test
    fun `addGuestToReservation sends the check-in with milliseconds, the form data and the responsible`() {
        FnrhTestServer.stub("/reservas/res-uuid-1/hospedes", body = """{"hospede_id":"hos-uuid-1"}""")
        val guest =
            FnrhGuest(
                principal = true,
                checkedInAt = Instant.parse("2026-02-10T15:00:00Z"),
                travelReason = "LAZER_FERIAS",
                transportMode = "AVIAO",
                responsibleGuestId = FnrhRemoteId("hos-resp"),
            )

        val id = gateway.addGuestToReservation(FnrhRemoteId("res-uuid-1"), FnrhRemoteId("pes-uuid-1"), guest, "corr-1")

        id shouldBe FnrhRemoteId("hos-uuid-1")
        val request = FnrhTestServer.onlyRequest()
        request.expectCall("POST", "/reservas/res-uuid-1/hospedes")
        request.requesterHeader().shouldBeNull()
        request.json() shouldBe
            mapper.readTree(
                """
                {"pessoa_id":"pes-uuid-1","is_principal":true,"situacao_hospede_id":"CHECKIN_REALIZADO",
                 "checkin_em":"2026-02-10T15:00:00.000Z",
                 "fnrh":{"motivo_viagem_id":"LAZER_FERIAS","meio_transporte_id":"AVIAO"},
                 "responsavel_id":"hos-resp"}
                """,
            )
    }

    @Test
    fun `addGuestToReservation truncates the instant to milliseconds`() {
        FnrhTestServer.stub("/reservas/res-uuid-1/hospedes", body = """{"hospede_id":"hos-uuid-1"}""")
        val guest = FnrhGuest(false, Instant.parse("2026-02-10T15:00:00.123987Z"), null, null, null)

        gateway.addGuestToReservation(FnrhRemoteId("res-uuid-1"), FnrhRemoteId("pes-uuid-1"), guest, "corr-1")

        val body = FnrhTestServer.onlyRequest().json()
        body["checkin_em"].asString() shouldBe "2026-02-10T15:00:00.123Z"
        body.has("fnrh") shouldBe false
        body.has("responsavel_id") shouldBe false
    }

    @Test
    fun `checkOut patches the guest with a plain text instant, without quotes`() {
        FnrhTestServer.stub(
            "/hospedes/hos-uuid-1/checkout",
            body = """{"dados":{"hospede_id":"hos-uuid-1","situacao_id":"CHECKOUT_REALIZADO"}}""",
        )

        gateway.checkOut(FnrhRemoteId("hos-uuid-1"), Instant.parse("2026-02-15T11:00:00Z"), "corr-1")

        val request = FnrhTestServer.onlyRequest()
        request.expectCall("PATCH", "/hospedes/hos-uuid-1/checkout")
        request.requesterHeader().shouldBeNull()
        request.getHeader("Content-Type") shouldStartWith "text/plain"
        request.bodyAsString shouldBe "2026-02-15T11:00:00.000Z"
    }

    @Test
    fun `fetchDomain gets the domain path and maps id and label`() {
        FnrhTestServer.stub(
            "/dominios/fnrh/meios_transporte",
            body = """{"dados":[{"id":"ONIBUS","label":"Ônibus"},{"id":"AVIAO","label":"Avião"}]}""",
        )

        val values = gateway.fetchDomain(FnrhDomain.TRANSPORT_MODES)

        values shouldBe listOf(FnrhDomainValue("ONIBUS", "Ônibus"), FnrhDomainValue("AVIAO", "Avião"))
        val request = FnrhTestServer.onlyRequest()
        request.expectCall("GET", "/dominios/fnrh/meios_transporte")
        request.requesterHeader().shouldBeNull()
    }

    @Test
    fun `every call is logged once with the environment, outcome and opaque references`() {
        FnrhTestServer.stubRegisterReservation_v2_4("res-uuid-1")

        gateway.registerReservation(reservation, "corr-9")

        val call = calls.single()
        call.operation shouldBe "registerReservation"
        call.environment shouldBe FnrhEnvironment.HOMOLOGATION
        call.outcome shouldBe "SUCCESS"
        call.httpStatus shouldBe 200
        call.correlationRef shouldBe "corr-9"
        call.remoteId shouldBe "res-uuid-1"
        (call.durationMs >= 0) shouldBe true
    }

    private fun failureOf(block: () -> Unit): FnrhGatewayException = shouldThrow<FnrhGatewayException> { block() }

    private fun FnrhGatewayException.shouldHaveNoCause() = cause.shouldBeNull()

    @Test
    fun `a 400 that echoes the sent data is a VALIDATION without the name, the number nor the e-mail`() {
        FnrhTestServer.stubAny(
            400,
            """{"mensagem":"Pessoa MARIA DA LUZ, CPF 12345678901, maria@x.com já existe"}""",
        )

        val e = failureOf { gateway.registerPerson(person, requesterCpf, "corr-1") }

        e.failure shouldBe FnrhFailure.VALIDATION
        e.httpStatus shouldBe 400
        val message = e.sanitizedMessage.shouldNotBeNull()
        message shouldNotContain "MARIA"
        message shouldNotContain "LUZ"
        message shouldNotContain "12345678901"
        message shouldNotContain "maria@x.com"
        e.message.orEmpty() shouldNotContain "MARIA"
        e.shouldHaveNoCause()
        calls.single().outcome shouldBe "VALIDATION"
        calls.single().httpStatus shouldBe 400
    }

    @Test
    fun `a 400 without a JSON message has no message, and the raw body is never used`() {
        FnrhTestServer.stubAny(400, "<html>MARIA DA LUZ 12345678901</html>")

        val e = failureOf { gateway.registerPerson(person, requesterCpf, "corr-1") }

        e.failure shouldBe FnrhFailure.VALIDATION
        e.sanitizedMessage.shouldBeNull()
    }

    @Test
    fun `the other 4xx statuses are mapped`() {
        mapOf(
            401 to FnrhFailure.UNAUTHORIZED,
            403 to FnrhFailure.FORBIDDEN,
            404 to FnrhFailure.NOT_FOUND,
            409 to FnrhFailure.VALIDATION,
            415 to FnrhFailure.VALIDATION,
            422 to FnrhFailure.VALIDATION,
            429 to FnrhFailure.SERVER_ERROR,
            500 to FnrhFailure.SERVER_ERROR,
            503 to FnrhFailure.SERVER_ERROR,
        ).forEach { (status, expected) ->
            FnrhTestServer.reset()
            calls.clear()
            FnrhTestServer.stubAny(status, """{"erro":"x","mensagem":"y","codigo":$status}""")

            val e = failureOf { gateway.cancelReservation(FnrhRemoteId("res-uuid-1"), "corr-1") }

            e.failure shouldBe expected
            e.httpStatus shouldBe status
            e.shouldHaveNoCause()
            calls.single().outcome shouldBe expected.name
        }
    }

    @Test
    fun `a redirect is a SERVER_ERROR`() {
        FnrhTestServer.stubAny(302)

        failureOf { gateway.cancelReservation(FnrhRemoteId("res-uuid-1"), "corr-1") }.failure shouldBe
            FnrhFailure.SERVER_ERROR
    }

    @Test
    fun `a 200 without the reservation id on a POST is not trusted`() {
        FnrhTestServer.stubAny(200, "{}")

        val e = failureOf { gateway.registerReservation(reservation, "corr-1") }

        e.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        calls.single().outcome shouldBe "UNKNOWN_OUTCOME"
    }

    @Test
    fun `a read timeout on a POST is UNKNOWN_OUTCOME, since the server may have written`() {
        FnrhTestServer.stubAny(200, """{"reserva":{"reserva_id":"r"}}""", delayMs = 300)

        val e = failureOf { shortTimeoutGateway.registerReservation(reservation, "corr-1") }

        e.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        e.httpStatus.shouldBeNull()
        e.shouldHaveNoCause()
        calls.single().outcome shouldBe "UNKNOWN_OUTCOME"
        calls.single().httpStatus.shouldBeNull()
    }

    @Test
    fun `a read timeout on a PATCH is UNKNOWN_OUTCOME`() {
        FnrhTestServer.stubAny(200, "{}", delayMs = 300)

        failureOf {
            shortTimeoutGateway.checkOut(
                FnrhRemoteId("hos-uuid-1"),
                Instant.parse("2026-02-15T11:00:00Z"),
                "corr-1",
            )
        }.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `a read timeout on a GET is UNAVAILABLE`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""", delayMs = 300)

        val e = failureOf { shortTimeoutGateway.findReservationByCode("RES-2026-0001", "corr-1") }

        e.failure shouldBe FnrhFailure.UNAVAILABLE
        e.shouldHaveNoCause()
        calls.single().outcome shouldBe "UNAVAILABLE"
    }

    @Test
    fun `a closed port is UNAVAILABLE on a POST too, since nothing was sent`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        connection = connectionTo("http://localhost:$closedPort/FNRH_API/rest/v2")

        val e = failureOf { gateway.registerReservation(reservation, "corr-1") }

        e.failure shouldBe FnrhFailure.UNAVAILABLE
        e.shouldHaveNoCause()
        calls.single().outcome shouldBe "UNAVAILABLE"
    }

    @Test
    fun `without a configuration it is NOT_CONFIGURED and nothing is sent nor logged`() {
        connection = null

        val e = failureOf { gateway.registerReservation(reservation, "corr-1") }

        e.failure shouldBe FnrhFailure.NOT_CONFIGURED
        e.httpStatus.shouldBeNull()
        e.shouldHaveNoCause()
        FnrhTestServer.requests().shouldBeEmpty()
        calls.shouldBeEmpty()
    }

    @Test
    fun `the exception and the logged call never carry the credentials nor the URL`() {
        FnrhTestServer.stubAny(401, """{"erro":"Unauthorized","mensagem":"Credenciais inválidas ou ausentes"}""")

        val e = failureOf { gateway.fetchDomain(FnrhDomain.RACES) }

        e.failure shouldBe FnrhFailure.UNAUTHORIZED
        listOf(e.toString(), calls.toString()).forEach {
            it shouldNotContain FnrhTestServer.PASSWORD
            it shouldNotContain "/dominios"
        }
        calls shouldHaveSize 1
    }
}
