package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
import java.time.LocalDate

/** Correção 3 da Task 4 (revisão de segurança): eco de dados sensíveis, `findReservationByCode` e log que lança. */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayRound3Test {
    private val calls = mutableListOf<FnrhCall>()
    private var logThrows = false
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofMillis(300)),
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
                if (logThrows) error("o registro falhou: segredo-do-registro")
                calls += it
            },
        )
    private val reservation =
        FnrhReservationData("RES-1", null, LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-15"), 2, 0)
    private val person =
        FnrhPersonData(
            name = "MARIA DA LUZ",
            gender = "MULHER",
            genderDescription = "Mulher trans",
            birthDate = LocalDate.parse("1990-05-17"),
            race = "PARDA",
            disabilityOption = "SIM",
            disabilityType = "VISUAL",
            document = FnrhDocument(FnrhDocumentType.CPF, "12345678901"),
            postalCode = "01310-100",
            street = "Avenida Paulista",
            number = "1578",
            complement = "Conjunto 42",
            neighborhood = "Bela Vista",
            cityIbge = 3550308,
            state = "SP",
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
            repeat(2) { warm.findReservationByCode("warm", "warm") }
        }
    }

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    @Test
    fun `a 400 echoing race, disability, birth date and address is masked in the exception and the log`(
        output: CapturedOutput,
    ) {
        FnrhTestServer.stubAny(
            400,
            "{\"mensagem\":\"Valor PARDA invalido para raca; VISUAL invalido para tipo_deficiencia_id; " +
                "nascimento 1990-05-17 e 17/05/1990 invalido; logradouro Avenida Paulista, 1578, " +
                "Conjunto 42, Bela Vista, cep 01310-100 e tail 100; genero Mulher trans\"}",
        )

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, Cpf.of("52998224725"), "corr-3") }

        val sensitive =
            listOf(
                "PARDA",
                "VISUAL",
                "1990",
                "17/05",
                "Paulista",
                "1578",
                "Conjunto",
                "Bela Vista",
                "100",
                "Mulher trans",
            )
        for (text in listOf(e.sanitizedMessage.orEmpty(), e.message.orEmpty(), output.all)) {
            for (word in sensitive) withClue(word) { text shouldNotContain word }
        }
        output.all shouldContain "corr-3"
    }

    @Test
    fun `findReservationByCode ignores list items with another reservation number`() {
        FnrhTestServer.stubAny(
            200,
            """{"dados":[{"reserva_id":"id-10","numero_reserva":"RES-10"},""" +
                """{"reserva_id":"id-1","numero_reserva":"RES-1"}]}""",
        )

        gateway.findReservationByCode("RES-1", "c") shouldBe FnrhRemoteId("id-1")
    }

    @Test
    fun `findReservationByCode is null when no item has the requested number`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"reserva_id":"id-10","numero_reserva":"RES-10"}]}""")

        gateway.findReservationByCode("RES-1", "c").shouldBeNull()
    }

    @Test
    fun `findReservationByCode still accepts an item without numero_reserva`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"reserva_id":"id-x"}]}""")

        gateway.findReservationByCode("RES-1", "c") shouldBe FnrhRemoteId("id-x")
    }

    @Test
    fun `an integration log that throws does not change a success into an exception`(output: CapturedOutput) {
        FnrhTestServer.stubAny(200, """{"reserva":{"reserva_id":"r-1"}}""")
        logThrows = true

        gateway.registerReservation(reservation, "c").reservationId shouldBe FnrhRemoteId("r-1")

        output.all shouldContain "IllegalStateException"
        output.all shouldNotContain "segredo-do-registro"
    }

    @Test
    fun `an integration log that throws does not hide the real failure`() {
        FnrhTestServer.stubAny(503, "{}")
        logThrows = true

        shouldThrow<FnrhGatewayException> { gateway.registerReservation(reservation, "c") }.httpStatus shouldBe 503
    }
}
