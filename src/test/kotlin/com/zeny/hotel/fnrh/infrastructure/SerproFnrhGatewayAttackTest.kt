package com.zeny.hotel.fnrh.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/**
 * Ataques do testador ao adaptador do Serpro (E07, Task 4): contrato campo a campo, Basic Auth, instantes, caminhos
 * com id remoto, cada linha da tabela de erros em cada operação, e vazamento de dado pessoal em exceção, `toString`
 * e log.
 */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayAttackTest {
    private val mapper = JsonMapper.builder().build()
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection? = connectionTo(FnrhTestServer.baseUrl)
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofMillis(100)),
            { connection },
            { calls += it },
        )

    private fun connectionTo(
        baseUrl: String,
        username: String = FnrhTestServer.USERNAME,
        password: String = FnrhTestServer.PASSWORD,
        environment: FnrhEnvironment = FnrhEnvironment.HOMOLOGATION,
    ) = FnrhConnection(environment, URI.create(baseUrl), username, password, Cpf.of("11144477735"))

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

    private val reservation =
        FnrhReservationData("RES-1", "OTA-9", LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-15"), 2, 0)

    private val person =
        FnrhPersonData(
            name = "Maria da Luz",
            socialName = "Mari",
            document = FnrhDocument(FnrhDocumentType.CPF, "12345678901"),
            email = "maria@x.com",
            phone = "11999998888",
            street = "Rua Zeta",
        )

    private fun LoggedRequest.json() = mapper.readTree(bodyAsString)

    private val rid = FnrhRemoteId("remote-1")
    private val at = Instant.parse("2026-02-15T11:00:00Z")
    private val requester = Cpf.of("52998224725")
    private val plainGuest = FnrhGuest(true, at, null, null, null)

    /** Cada operação da porta com a sua chamada, para atacar todas do mesmo jeito. */
    private val operations: Map<String, () -> Unit> =
        mapOf(
            "registerReservation" to { gateway.registerReservation(reservation, "corr-x").let { } },
            "updateReservation" to { gateway.updateReservation(rid, reservation, "corr-x") },
            "cancelReservation" to { gateway.cancelReservation(rid, "corr-x") },
            "findReservationByCode" to { gateway.findReservationByCode("RES-1", "corr-x").let { } },
            "registerPerson" to { gateway.registerPerson(person, requester, "corr-x").let { } },
            "addGuestToReservation" to
                { gateway.addGuestToReservation(rid, FnrhRemoteId("p"), plainGuest, "corr-x").let { } },
            "checkOut" to { gateway.checkOut(rid, at, "corr-x") },
            "fetchDomain" to { gateway.fetchDomain(FnrhDomain.RACES).let { } },
        )

    private val writes = operations.keys - setOf("findReservationByCode", "fetchDomain")

    private fun FnrhGatewayException.shouldBeClean() {
        cause.shouldBeNull()
        suppressed.size shouldBe 0
    }

    // ---------- erros em cada operação

    @Test
    fun `every operation maps every status of the brief table, with one call logged and the correlation ref`() {
        val table =
            mapOf(
                400 to FnrhFailure.VALIDATION,
                409 to FnrhFailure.VALIDATION,
                415 to FnrhFailure.VALIDATION,
                422 to FnrhFailure.VALIDATION,
                418 to FnrhFailure.VALIDATION,
                451 to FnrhFailure.VALIDATION,
                401 to FnrhFailure.UNAUTHORIZED,
                403 to FnrhFailure.FORBIDDEN,
                404 to FnrhFailure.NOT_FOUND,
                429 to FnrhFailure.SERVER_ERROR,
                301 to FnrhFailure.SERVER_ERROR,
                302 to FnrhFailure.SERVER_ERROR,
                307 to FnrhFailure.SERVER_ERROR,
                308 to FnrhFailure.SERVER_ERROR,
                500 to FnrhFailure.SERVER_ERROR,
                502 to FnrhFailure.SERVER_ERROR,
                504 to FnrhFailure.SERVER_ERROR,
            )
        for ((op, run) in operations) {
            for ((status, expected) in table) {
                FnrhTestServer.reset()
                calls.clear()
                FnrhTestServer.stubAny(status, """{"mensagem":"nada"}""")

                val e = shouldThrow<FnrhGatewayException> { run() }

                withClue("$op $status") {
                    e.failure shouldBe expected
                    e.httpStatus shouldBe status
                    e.shouldBeClean()
                    FnrhTestServer.requests().size shouldBe 1
                    calls.size shouldBe 1
                    calls.single().outcome shouldBe expected.name
                    calls.single().httpStatus shouldBe status
                    if (op != "fetchDomain") calls.single().correlationRef shouldBe "corr-x"
                    calls.single().operation shouldBe op
                }
            }
        }
    }

    @Test
    fun `a read timeout is UNKNOWN_OUTCOME on every write and UNAVAILABLE on every read`() {
        for ((op, run) in operations) {
            FnrhTestServer.reset()
            calls.clear()
            FnrhTestServer.stubAny(
                200,
                """{"dados":[],"reserva":{"reserva_id":"r"},"pessoa_id":"p","hospede_id":"h"}""",
                400,
            )

            val e = shouldThrow<FnrhGatewayException> { run() }

            withClue(op) {
                e.failure shouldBe (if (op in writes) FnrhFailure.UNKNOWN_OUTCOME else FnrhFailure.UNAVAILABLE)
                e.httpStatus.shouldBeNull()
                e.shouldBeClean()
                calls.size shouldBe 1
                calls.single().outcome shouldBe e.failure.name
                calls.single().httpStatus.shouldBeNull()
            }
        }
    }

    @Test
    fun `a refused connection is UNAVAILABLE on every operation, one call logged each`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        connection = connectionTo("http://localhost:$closedPort/FNRH_API/rest/v2")
        for ((op, run) in operations) {
            calls.clear()

            val e = shouldThrow<FnrhGatewayException> { run() }

            withClue(op) {
                e.failure shouldBe FnrhFailure.UNAVAILABLE
                e.shouldBeClean()
                calls.size shouldBe 1
                calls.single().outcome shouldBe "UNAVAILABLE"
            }
        }
    }

    @Test
    fun `an unresolvable host is UNAVAILABLE even on a write`() {
        connection = connectionTo("http://host-que-nao-existe.invalid/FNRH_API/rest/v2")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerReservation(reservation, "c") }

        e.failure shouldBe FnrhFailure.UNAVAILABLE
        e.shouldBeClean()
    }

    @Test
    fun `without configuration every operation is NOT_CONFIGURED, sends nothing and has no cause`() {
        connection = null
        for ((op, run) in operations) {
            val e = shouldThrow<FnrhGatewayException> { run() }
            withClue(op) {
                e.failure shouldBe FnrhFailure.NOT_CONFIGURED
                e.httpStatus.shouldBeNull()
                e.shouldBeClean()
            }
        }
        FnrhTestServer.requests().shouldBeEmpty()
        calls.shouldBeEmpty()
    }

    @Test
    fun `a 2xx with a non-JSON body on a write that must return an id is UNKNOWN_OUTCOME`() {
        FnrhTestServer.stubAny(200, "<html>ok</html>")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        e.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        e.httpStatus shouldBe 200
        e.shouldBeClean()
    }

    @Test
    fun `a 2xx with a non-JSON body on a GET is SERVER_ERROR`() {
        FnrhTestServer.stubAny(200, "not json")

        shouldThrow<FnrhGatewayException> { gateway.fetchDomain(FnrhDomain.GENDERS) }.failure shouldBe
            FnrhFailure.SERVER_ERROR
    }

    @Test
    fun `a JSON message that is not a string is ignored instead of crashing`() {
        FnrhTestServer.stubAny(400, """{"mensagem":{"nome":"Maria da Luz"}}""")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        e.failure shouldBe FnrhFailure.VALIDATION
        e.sanitizedMessage.shouldBeNull()
    }

    @Test
    fun `a body that stalls after the headers does not hang the call past the read timeout`() {
        FnrhTestServer.server.stubFor(
            WireMock
                .any(WireMock.anyUrl())
                .willReturn(
                    WireMock
                        .aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"dados":[{"id":"A","label":"B"}]}""")
                        .withChunkedDribbleDelay(5, 3_000),
                ),
        )

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runCatching { gateway.fetchDomain(FnrhDomain.RACES) }
        }
    }

    // ---------- Basic Auth

    @Test
    fun `Basic Auth encodes a password with colon, accents and specials in UTF-8`() {
        val password = "pä:ss wörd/ç%é€:"
        connection = connectionTo(FnrhTestServer.baseUrl, username = "usér.ñ", password = password)
        FnrhTestServer.stubAny(200, """{"dados":[]}""")

        gateway.fetchDomain(FnrhDomain.RACES)

        val header = FnrhTestServer.onlyRequest().getHeader("Authorization")
        header shouldBe "Basic " + Base64.getEncoder().encodeToString("usér.ñ:$password".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `a username with a colon is reported as a gateway failure, not as a raw IllegalArgumentException`() {
        connection = connectionTo(FnrhTestServer.baseUrl, username = "dom:user")
        FnrhTestServer.stubAny(200, """{"dados":[]}""")

        val e = shouldThrow<Throwable> { gateway.fetchDomain(FnrhDomain.RACES) }

        e::class shouldBe FnrhGatewayException::class
    }

    // ---------- cpf_solicitante

    @Test
    fun `the requester CPF goes out with digits only, even if the Cpf was typed formatted`() {
        FnrhTestServer.stubAny(200, """{"pessoa_id":"p"}""")

        gateway.registerPerson(person, Cpf.of("529.982.247-25"), "c")

        FnrhTestServer.onlyRequest().getHeader("cpf_solicitante") shouldBe "52998224725"
    }

    @Test
    fun `only registerPerson carries cpf_solicitante, in header and body, across all operations`() {
        FnrhTestServer.stubAny(
            200,
            """{"dados":[],"reserva":{"reserva_id":"r"},"pessoa_id":"p","hospede_id":"h"}""",
        )
        for ((op, run) in operations) {
            FnrhTestServer.server.resetRequests()
            run()
            val request = FnrhTestServer.onlyRequest()
            withClue(op) {
                if (op == "registerPerson") {
                    request.getHeader("cpf_solicitante") shouldBe "52998224725"
                } else {
                    request.containsHeader("cpf_solicitante") shouldBe false
                    request.bodyAsString shouldNotContain "cpf_solicitante"
                    request.bodyAsString shouldNotContain "52998224725"
                    request.url shouldNotContain "52998224725"
                }
                request.bodyAsString shouldNotContain "11144477735"
            }
        }
    }

    // ---------- instantes

    @Test
    fun `instants leave with milliseconds and Z, truncated and not rounded, with and without nanos`() {
        FnrhTestServer.stubAny(200, """{"hospede_id":"h"}""")
        val cases =
            mapOf(
                Instant.parse("2026-02-10T15:00:00Z") to "2026-02-10T15:00:00.000Z",
                Instant.parse("2026-02-10T15:00:00.999999999Z") to "2026-02-10T15:00:00.999Z",
                Instant.parse("2026-02-10T15:00:00.000000001Z") to "2026-02-10T15:00:00.000Z",
                Instant.parse("2026-12-31T23:59:59.5Z") to "2026-12-31T23:59:59.500Z",
                Instant.parse("1969-12-31T23:59:59.9995Z") to "1969-12-31T23:59:59.999Z",
                Instant.EPOCH to "1970-01-01T00:00:00.000Z",
            )
        for ((instant, text) in cases) {
            FnrhTestServer.server.resetRequests()
            gateway.checkOut(rid, instant, "c")
            val plain = FnrhTestServer.onlyRequest()
            withClue(instant.toString()) { plain.bodyAsString shouldBe text }

            FnrhTestServer.server.resetRequests()
            gateway.addGuestToReservation(rid, FnrhRemoteId("p"), FnrhGuest(true, instant, null, null, null), "c")
            FnrhTestServer.onlyRequest().json()["checkin_em"].asString() shouldBe text
        }
    }

    @Test
    fun `the plain text checkout body has no quotes, no newline and a text slash plain content type`() {
        FnrhTestServer.stubAny(200, "")

        gateway.checkOut(rid, Instant.parse("2026-02-15T11:00:00.5Z"), "c")

        val r = FnrhTestServer.onlyRequest()
        r.bodyAsString shouldNotContain "\""
        r.bodyAsString shouldNotContain "\n"
        r.getHeader("Content-Type").startsWith("text/plain") shouldBe true
    }

    // ---------- contrato de campos

    @Test
    fun `a reservation with an OTA code sends the exact Serpro field names and nothing else`() {
        FnrhTestServer.stubAny(200, """{"reserva":{"reserva_id":"r"}}""")

        gateway.registerReservation(reservation, "c")

        FnrhTestServer.onlyRequest().json() shouldBe
            mapper.readTree(
                """{"numero_reserva":"RES-1","numero_reserva_ota":"OTA-9","data_entrada":"2026-02-10",
                   "data_saida":"2026-02-15","quantidade_hospede_adulto":2,"quantidade_hospede_menor":0,
                   "origem_reserva_id":"OTA"}""",
            )
    }

    @Test
    fun `a minimal person sends only the name and no empty contact block`() {
        FnrhTestServer.stubAny(200, """{"pessoa_id":"p"}""")

        gateway.registerPerson(FnrhPersonData(name = "Ana Souza"), requester, "c")

        FnrhTestServer.onlyRequest().json() shouldBe mapper.readTree("""{"pessoa":{"nome":"Ana Souza"}}""")
    }

    @Test
    fun `a person with social name, gender description and a foreign residence sends the Serpro names`() {
        FnrhTestServer.stubAny(200, """{"pessoa_id":"p"}""")

        gateway.registerPerson(
            FnrhPersonData(
                name = "John Doe",
                socialName = "Jo",
                nationality = "US",
                gender = "OUTRO",
                genderDescription = "agênero",
                residenceCountry = "US",
                complement = "apto 2",
            ),
            requester,
            "c",
        )

        FnrhTestServer.onlyRequest().json() shouldBe
            mapper.readTree(
                """{"pessoa":{"nome":"John Doe","nome_social":"Jo","PaisNacionalidade_id":"US","genero_id":"OUTRO",
                   "GeneroDescricao":"agênero","contato":{"PaisResidencia_id":"US","complemento":"apto 2"}}}""",
            )
    }

    @Test
    fun `the guest status is always CHECKIN_REALIZADO and the principal flag follows the guest`() {
        FnrhTestServer.stubAny(200, """{"hospede_id":"h"}""")

        gateway.addGuestToReservation(rid, FnrhRemoteId("p"), FnrhGuest(false, at, "NEGOCIOS", null, null), "c")

        val body = FnrhTestServer.onlyRequest().json()
        body["situacao_hospede_id"].asString() shouldBe "CHECKIN_REALIZADO"
        body["is_principal"].asBoolean() shouldBe false
        body["fnrh"] shouldBe mapper.readTree("""{"motivo_viagem_id":"NEGOCIOS"}""")
    }

    @Test
    fun `the id read from each response is the one the Serpro names, and a wrong shape is not accepted`() {
        FnrhTestServer.stubAny(
            200,
            """{"pessoa_id":"p-1","id":"wrong","reserva":{"reserva_id":"r-1"},"hospede_id":"h-1"}""",
        )

        gateway.registerPerson(person, requester, "c") shouldBe FnrhRemoteId("p-1")
        gateway.addGuestToReservation(rid, FnrhRemoteId("p"), plainGuest, "c") shouldBe FnrhRemoteId("h-1")
        gateway.registerReservation(reservation, "c").reservationId shouldBe FnrhRemoteId("r-1")

        FnrhTestServer.reset()
        FnrhTestServer.stubAny(200, """{"id":"wrong","reserva_id":"top-level","hospede":{"hospede_id":"nested"}}""")
        shouldThrow<FnrhGatewayException> { gateway.registerReservation(reservation, "c") }
        shouldThrow<FnrhGatewayException> {
            gateway.addGuestToReservation(rid, FnrhRemoteId("p"), plainGuest, "c")
        }
        shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }
    }

    @Test
    fun `a domain item without a label keeps the id, and an item without an id is dropped`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"id":"A"},{"label":"sem id"},{"id":"B","label":"Bê"}]}""")

        gateway.fetchDomain(FnrhDomain.RACES).map { it.code to it.label } shouldBe listOf("A" to "", "B" to "Bê")
    }

    @Test
    fun `every domain hits its own path`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""")
        for (domain in FnrhDomain.entries) {
            FnrhTestServer.server.resetRequests()
            gateway.fetchDomain(domain)
            FnrhTestServer.onlyRequest().url shouldBe FnrhTestServer.BASE_PATH + domain.path
        }
        FnrhDomain.entries.size shouldBe 10
    }

    // ---------- caminhos

    @Test
    fun `a remote id with slash, question mark, hash, dots or spaces cannot change the path`() {
        FnrhTestServer.stubAny(200, "")
        val nasty = listOf("a/b", "a?x=1", "a#f", "../cancelar", "..", "a b", "a%2Fb", "a;b", "ção/é")
        for (id in nasty) {
            FnrhTestServer.server.resetRequests()
            gateway.updateReservation(FnrhRemoteId(id), reservation, "c")
            val url = FnrhTestServer.onlyRequest().url
            withClue(id) {
                url.removePrefix("${FnrhTestServer.BASE_PATH}/reservas/").contains("/") shouldBe false
                url.contains("?") shouldBe false
                url.startsWith("${FnrhTestServer.BASE_PATH}/reservas/") shouldBe true
                url.substringAfterLast("/") shouldNotBe "."
                url.substringAfterLast("/") shouldNotBe ".."
            }
        }
    }

    @Test
    fun `a reservation code with ampersand, equals, plus and space stays a single query value`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""")

        gateway.findReservationByCode("A&b=c+d e", "c")

        val request = FnrhTestServer.onlyRequest()
        request.queryParameter("codigo_reserva").values() shouldBe listOf("A&b=c+d e")
        request.queryParameter("page_number").values() shouldBe listOf("1")
        request.queryParams.size shouldBe 2
    }

    // ---------- vazamento

    @Test
    fun `a message echoing lowercase unaccented parts of every sent value is fully masked`() {
        FnrhTestServer.stubAny(
            400,
            "{\"mensagem\":\"nome maria invalido; mari ja existe; fone 11999998888; " +
                "doc 12345678901; maria@x.com; luz\"}",
        )

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        val message = e.sanitizedMessage.shouldNotBeNull()
        listOf("maria", "luz", "mari ", "11999998888", "12345678901", "x.com").forEach {
            message shouldNotContain it
        }
        e.message.orEmpty() shouldNotContain "maria"
    }

    @Test
    fun `a huge message is cut to 200 characters in the exception`() {
        FnrhTestServer.stubAny(400, """{"mensagem":"${"erro grande ".repeat(500)}"}""")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        e.sanitizedMessage.shouldNotBeNull().length shouldBe 200
    }

    @Test
    fun `the requester CPF echoed by the Serpro is masked as well`() {
        FnrhTestServer.stubAny(400, """{"mensagem":"solicitante 529.982.247-25 / 52998224725 sem permissão"}""")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        e.sanitizedMessage.shouldNotBeNull() shouldNotContain "529"
        e.sanitizedMessage.shouldNotBeNull() shouldNotContain "247"
    }

    @Test
    fun `a non-JSON error body never reaches the exception, the log or the recorded call`(output: CapturedOutput) {
        FnrhTestServer.stubAny(400, "Maria da Luz 12345678901 maria@x.com RAWBODYMARK")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }

        e.sanitizedMessage.shouldBeNull()
        listOf(e.toString(), calls.toString(), output.all).forEach {
            it shouldNotContain "RAWBODYMARK"
            it shouldNotContain "Maria"
            it shouldNotContain "12345678901"
        }
    }

    @Test
    fun `the production log never shows personal data, credentials nor the URL, even when the Serpro echoes them`(
        output: CapturedOutput,
    ) {
        FnrhTestServer.stubAny(
            400,
            """{"mensagem":"Pessoa Maria da Luz, CPF 12345678901, maria@x.com, 11999998888 ja existe"}""",
        )
        shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "corr-log") }
        FnrhTestServer.stubAny(200, """{"dados":[]}""")
        gateway.findReservationByCode("RES-SECRET-CODE", "corr-log-2")

        val provisional = ProvisionalFnrhIntegrationLogConfiguration().provisionalFnrhIntegrationLog()
        calls.forEach(provisional::record)

        val log = output.all
        listOf(
            "Maria",
            "Luz",
            "12345678901",
            "maria@x.com",
            "11999998888",
            "52998224725",
            FnrhTestServer.PASSWORD,
            FnrhTestServer.USERNAME,
            "RES-SECRET-CODE",
            "/pessoas",
            "/reservas",
            "localhost",
        ).forEach { log shouldNotContain it }
        log.contains("corr-log") shouldBe true
    }

    @Test
    fun `toString of the person, document, guest, reservation and exception never shows personal data`() {
        FnrhTestServer.stubAny(400, """{"mensagem":"Maria da Luz 12345678901"}""")
        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }
        val texts =
            listOf(
                person.toString(),
                person.document.toString(),
                FnrhGuest(true, at, "LAZER_FERIAS", "AVIAO", FnrhRemoteId("resp-guest")).toString(),
                reservation.toString(),
                e.toString(),
                e.message.orEmpty(),
                calls.toString(),
                connection.toString(),
            )
        val secrets =
            listOf(
                "Maria",
                "Luz",
                "Mari",
                "12345678901",
                "maria@x.com",
                "11999998888",
                "52998224725",
                "Rua Zeta",
                FnrhTestServer.PASSWORD,
            )
        for (text in texts) secrets.forEach { text shouldNotContain it }
    }

    @Test
    fun `the environment of the recorded call follows the connection`() {
        connection = connectionTo(FnrhTestServer.baseUrl, environment = FnrhEnvironment.PRODUCTION)
        FnrhTestServer.stubAny(200, """{"dados":[]}""")

        gateway.fetchDomain(FnrhDomain.RACES)

        calls.single().environment shouldBe FnrhEnvironment.PRODUCTION
    }

    @Test
    fun `the base URL of each call comes from the current connection, not from a cached one`() {
        FnrhTestServer.stubAny(200, """{"dados":[]}""")
        gateway.fetchDomain(FnrhDomain.RACES)
        val closedPort = ServerSocket(0).use { it.localPort }
        connection = connectionTo("http://localhost:$closedPort/FNRH_API/rest/v2")

        shouldThrow<FnrhGatewayException> { gateway.fetchDomain(FnrhDomain.RACES) }.failure shouldBe
            FnrhFailure.UNAVAILABLE
    }
}
