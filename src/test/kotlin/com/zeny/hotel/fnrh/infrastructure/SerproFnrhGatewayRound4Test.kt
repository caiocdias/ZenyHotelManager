package com.zeny.hotel.fnrh.infrastructure

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
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Quarta rodada do testador (E07, Task 4): ataca a correção 3 (eco de dados sensíveis, registro que lança,
 * `findReservationByCode`, saneador) e prova que os stubs `*_v2_4` por operação do `FnrhTestServer` são estritos.
 */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayRound4Test {
    private val calls = mutableListOf<FnrhCall>()
    private var logThrows = false
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
            {
                if (logThrows) error("falha-do-registro-SEGREDO")
                calls += it
            },
        )
    private val requester = Cpf.of("52998224725")
    private val at = Instant.parse("2026-02-15T11:00:00.5Z")
    private val rid = FnrhRemoteId("res-1")
    private val reservation =
        FnrhReservationData("RES-1", null, LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-15"), 2, 0)
    private val person =
        FnrhPersonData(
            name = "Jean-Pierre D'Avila",
            birthDate = LocalDate.parse("1990-05-17"),
            race = "PARDA",
            disabilityOption = "SIM",
            disabilityType = "VISUAL",
            postalCode = "01310-100",
            street = "Avenida Paulista",
            number = "1578",
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

    private fun echo(message: String) {
        FnrhTestServer.stubAny(400, "{\"mensagem\":\"$message\"}")
    }

    private fun sanitizedAfterEcho(message: String): String {
        echo(message)
        return shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }
            .sanitizedMessage
            .orEmpty()
    }

    // ---------- correção 3: eco

    @Test
    fun `lowercase and unaccented echoes of the domain values and the name parts are masked`() {
        val out = sanitizedAfterEcho("parda e visual; jean; pierre; davila; avenida paulista; sim")

        listOf("parda", "visual", "jean", "pierre", "davila", "paulista", "avenida").forEach { out shouldNotContain it }
    }

    @Test
    fun `the birth date in other common formats is masked through the year or the compact digits`() {
        val out = sanitizedAfterEcho("nasc 17-05-1990; 05/17/1990; 17.05.1990; 19900517; 1990")

        listOf("1990", "19900517").forEach { out shouldNotContain it }
    }

    @Test
    fun `the postal code is masked with and without hyphen, and by each part`() {
        val out = sanitizedAfterEcho("cep 01310-100; 01310100; 01310; 100; zip 13101")

        listOf("01310", "100", "13101").forEach { out shouldNotContain it }
    }

    @Test
    fun `an unrelated numeric code and the field names stay readable`() {
        val out = sanitizedAfterEcho("codigo 4021 campo data_nascimento invalido")

        out shouldBe "codigo 4021 campo data_nascimento invalido"
    }

    @Test
    fun `a hyphenated or apostrophed name has every part masked on its own`() {
        val out = sanitizedAfterEcho("JEAN e Pierre e d'avila e D’Avila")

        listOf("JEAN", "Pierre", "avila", "Avila").forEach { out shouldNotContain it }
    }

    @Test
    fun `a message with a name partly before and after the 1000 character input limit never leaks a fragment`() {
        val out = FnrhMessageSanitizer.sanitize("x".repeat(997) + "Maria Maria", listOf("Maria"))!!

        out shouldNotContain "Mar"
        FnrhMessageSanitizer.sanitize("Maria ".repeat(400), listOf("Maria"))!!.length shouldBe 200
    }

    // ---------- registro que lança

    @Test
    fun `an integration log that throws leaves every outcome as it was, calling record once`(output: CapturedOutput) {
        logThrows = true
        FnrhTestServer.stubAny(503, """{"mensagem":"x"}""")
        shouldThrow<FnrhGatewayException> { gateway.cancelReservation(rid, "c") }.failure shouldBe
            FnrhFailure.SERVER_ERROR

        FnrhTestServer.reset()
        echo("Pessoa Jean ja existe")
        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, "c") }
        e.failure shouldBe FnrhFailure.VALIDATION
        e.sanitizedMessage.orEmpty() shouldNotContain "Jean"
        e.cause.shouldBeNull()

        FnrhTestServer.reset()
        FnrhTestServer.stubAny(200, """{"pessoa_id":"p-9"}""")
        gateway.registerPerson(person, requester, "c") shouldBe FnrhRemoteId("p-9")

        output.all shouldContain "IllegalStateException"
        output.all shouldNotContain "SEGREDO"
    }

    // ---------- findReservationByCode

    @Test
    fun `findReservationByCode with RES-1 never returns the id of RES-10 even when it comes first`() {
        FnrhTestServer.stubAny(
            200,
            """{"dados":[{"reserva_id":"a-10","numero_reserva":"RES-10"},""" +
                """{"reserva_id":"a-1","numero_reserva":"RES-1"}]}""",
        )

        gateway.findReservationByCode("RES-1", "c") shouldBe FnrhRemoteId("a-1")
        gateway.findReservationByCode("RES-10", "c") shouldBe FnrhRemoteId("a-10")
    }

    @Test
    fun `findReservationByCode does not accept an item whose numero_reserva is present but not a string`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"reserva_id":"a-100","numero_reserva":100}]}""")

        gateway.findReservationByCode("10", "c").shouldBeNull()
    }

    // ---------- stubs *_v2_4 estritos

    @Test
    fun `the per-operation v2_4 stubs serve the exact calls of the adapter`() {
        FnrhTestServer.stubRegisterReservation_v2_4("res-1")
        FnrhTestServer.stubUpdateReservation_v2_4("res-1")
        FnrhTestServer.stubCancelReservation_v2_4("res-1")
        FnrhTestServer.stubFindReservationByCode_v2_4("RES-1", "res-1")
        FnrhTestServer.stubRegisterPerson_v2_4("pes-1", "52998224725")
        FnrhTestServer.stubAddGuest_v2_4("res-1", "hos-1")
        FnrhTestServer.stubCheckOut_v2_4("hos-1")
        FnrhTestServer.stubDomain_v2_4("/dominios/pessoas/racas", "PARDA" to "Pardo")

        gateway.registerReservation(reservation, "c").reservationId shouldBe rid
        gateway.updateReservation(rid, reservation, "c")
        gateway.cancelReservation(rid, "c")
        gateway.findReservationByCode("RES-1", "c") shouldBe rid
        gateway.registerPerson(person, requester, "c") shouldBe FnrhRemoteId("pes-1")
        gateway.addGuestToReservation(rid, FnrhRemoteId("pes-1"), FnrhGuest(true, at, null, null, null), "c") shouldBe
            FnrhRemoteId("hos-1")
        gateway.checkOut(FnrhRemoteId("hos-1"), at, "c")
        gateway.fetchDomain(FnrhDomain.RACES) shouldBe listOf(FnrhDomainValue("PARDA", "Pardo"))
        calls.map { it.outcome }.toSet() shouldBe setOf("SUCCESS")
        calls.size shouldBe 8
    }

    @Test
    fun `a v2_4 stub does not answer the wrong method, wrong credentials nor a stray cpf_solicitante`() {
        FnrhTestServer.stubCancelReservation_v2_4("res-1")
        FnrhTestServer.stubRegisterPerson_v2_4("pes-1", "52998224725")

        // método errado: a operação de pessoa chama POST /pessoas, mas o stub de cancelar é para outro caminho
        shouldThrow<FnrhGatewayException> { gateway.updateReservation(rid, reservation, "c") }.failure shouldBe
            FnrhFailure.NOT_FOUND
        // CPF solicitante diferente do esperado
        shouldThrow<FnrhGatewayException> {
            gateway.registerPerson(person, Cpf.of("11144477735"), "c")
        }.failure shouldBe FnrhFailure.NOT_FOUND
        // credencial errada
        val wrong = wrongCredentialsGateway()
        shouldThrow<FnrhGatewayException> { wrong.cancelReservation(rid, "c") }.failure shouldBe FnrhFailure.NOT_FOUND
    }

    @Test
    fun `the checkOut v2_4 stub refuses a JSON body with quotes`() {
        FnrhTestServer.stubCheckOut_v2_4("hos-1")
        FnrhTestServer.server.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock
                .patch(
                    com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo("${FnrhTestServer.BASE_PATH}/x"),
                ).willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock
                        .ok(),
                ),
        )

        gateway.checkOut(FnrhRemoteId("hos-1"), at, "c")

        FnrhTestServer.onlyRequest().bodyAsString shouldBe "2026-02-15T11:00:00.500Z"
    }

    @Test
    fun `a person document passport is also served by the stub and carries the requester only there`() {
        FnrhTestServer.stubRegisterPerson_v2_4("pes-2", "52998224725")

        gateway.registerPerson(
            person.copy(document = FnrhDocument(FnrhDocumentType.PASSPORT, "AB123456")),
            requester,
            "c",
        ) shouldBe FnrhRemoteId("pes-2")
    }

    private fun wrongCredentialsGateway() =
        SerproFnrhGateway(
            FnrhProperties(readTimeout = Duration.ofSeconds(10)),
            {
                FnrhConnection(
                    FnrhEnvironment.HOMOLOGATION,
                    URI.create(FnrhTestServer.baseUrl),
                    "outro-usuario",
                    "outra-senha",
                    Cpf.of("11144477735"),
                )
            },
            { },
        )
}
