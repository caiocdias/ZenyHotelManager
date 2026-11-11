package com.zeny.hotel.fnrh.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.URI
import java.time.Duration
import java.time.LocalDate

/** Descrição de gênero no envio ao Serpro (M5 E15, Task 5): vai no corpo e não volta na exceção nem no log. */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayGenderDescriptionTest {
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(5)),
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
    private val person =
        FnrhPersonData(
            name = "MARIA DA LUZ",
            gender = "OUTRO",
            genderDescription = "Pessoa não binária",
            birthDate = LocalDate.parse("1990-05-17"),
        )

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    @Test
    fun `registerPerson sends the description as pessoa GeneroDescricao`() {
        FnrhTestServer.stubRegisterPerson_v2_4("p-1", "52998224725")

        gateway.registerPerson(person, Cpf.of("52998224725"), "corr-5")

        FnrhTestServer.server.verify(
            1,
            postRequestedFor(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withRequestBody(matchingJsonPath("$.pessoa[?(@.GeneroDescricao == 'Pessoa não binária')]")),
        )
    }

    @Test
    fun `a 400 echoing the description is masked in the exception and the log`(output: CapturedOutput) {
        FnrhTestServer.stubAny(400, "{\"mensagem\":\"GeneroDescricao Pessoa não binária invalida\"}")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, Cpf.of("52998224725"), "corr-5") }

        for (text in listOf(e.sanitizedMessage.orEmpty(), e.message.orEmpty(), output.all)) {
            withClue(text) { text shouldNotContain "binária" }
        }
        output.all shouldContain "corr-5"
        e.httpStatus shouldBe 400
    }
}
