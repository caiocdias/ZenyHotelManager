package com.zeny.hotel.fnrh.infrastructure

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
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.LocalDate

/**
 * Ataque ao envio da descrição de gênero ao Serpro (M5 E15, Task 5): eco em maiúsculas e sem acento, em parte, com
 * controle e com 3 caracteres (exceção e log sem o valor); JSON com caracteres especiais; e o limite D-E15-6.
 */
@ExtendWith(OutputCaptureExtension::class)
class SerproFnrhGatewayGenderDescriptionAttackTest {
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
    private val mapper = JsonMapper.builder().build()

    private fun person(description: String?) =
        FnrhPersonData(
            name = "MARIA DA LUZ",
            gender = "OUTRO",
            genderDescription = description,
            birthDate = LocalDate.parse("1990-05-17"),
        )

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    private fun failWith(
        description: String,
        serproMessage: String,
    ): FnrhGatewayException {
        FnrhTestServer.reset()
        val body = mapper.writeValueAsString(mapOf("mensagem" to serproMessage))
        FnrhTestServer.stubAny(400, body)
        return shouldThrow<FnrhGatewayException> {
            gateway.registerPerson(person(description), Cpf.of("52998224725"), "corr-ge")
        }
    }

    private fun assertHidden(
        e: FnrhGatewayException,
        output: CapturedOutput,
        already: Int,
        vararg leaks: String,
    ) {
        val places =
            mapOf(
                "sanitizedMessage" to e.sanitizedMessage.orEmpty(),
                "message" to e.message.orEmpty(),
                "log" to output.all.substring(already),
            )
        for ((place, text) in places) {
            for (leak in leaks) withClue("$place leaked [$leak]: $text") { text shouldNotContain leak }
        }
    }

    @Test
    fun `an echo in upper case and without accents is masked in the exception and the log`(output: CapturedOutput) {
        val already = output.all.length

        val e = failWith("Pessoa não binária", "Campo invalido: PESSOA NAO BINARIA nao aceita")

        assertHidden(e, output, already, "PESSOA", "BINARIA", "NAO BIN")
        output.all.substring(already) shouldContain "corr-ge"
    }

    @Test
    fun `an echo of only part of the description is masked, word by word`(output: CapturedOutput) {
        val already = output.all.length

        val e = failWith("Pessoa não binária", "Valor 'binária' recusado; tente Pessoa")

        assertHidden(e, output, already, "binária", "binaria", "Pessoa")
    }

    @Test
    fun `an echo of a description of exactly 3 characters is masked`(output: CapturedOutput) {
        val already = output.all.length

        val e = failWith("Xyz", "GeneroDescricao Xyz invalida; xyz tambem; XYZ")

        assertHidden(e, output, already, "Xyz", "xyz", "XYZ")
    }

    @Test
    fun `an echo of a description with a control character is masked`(output: CapturedOutput) {
        val already = output.all.length

        val e = failWith("Pessoa\u0001binária", "Recusado: Pessoa\u0001binária fim")

        assertHidden(e, output, already, "Pessoa", "binária", "binaria")
    }

    @Test
    fun `an echo of a short description split by a NUL is masked as a whole`(output: CapturedOutput) {
        val already = output.all.length

        val e = failWith("Ab\u0000cd", "Recusado: Ab\u0000cd fim")

        assertHidden(e, output, already, "Ab", "cd")
    }

    @Test
    fun `D-E15-6 an echo of a description of 1 or 2 characters is not masked, an accepted risk`(
        output: CapturedOutput,
    ) {
        for (description in listOf("X", "Ab")) {
            val already = output.all.length

            val e = failWith(description, "descricao $description recusada")

            e.sanitizedMessage shouldContain description
            output.all.substring(already) shouldContain "descricao $description recusada"
        }
    }

    @Test
    fun `quotes, backslash, control characters, NUL and newline go to the Serpro escaped and intact`() {
        val description = "Ele \"dito\" C:\\x\u0001y\u0000z\nlinha {\"a\":1} \u2028 ç"
        FnrhTestServer.stubRegisterPerson_v2_4("p-1", "52998224725")

        gateway.registerPerson(person(description), Cpf.of("52998224725"), "corr-js")

        val raw = FnrhTestServer.onlyRequest().bodyAsString
        raw shouldNotContain "\u0000"
        raw shouldNotContain "\u0001"
        raw shouldNotContain "\n"
        val parsed = mapper.readTree(raw)
        parsed["pessoa"]["GeneroDescricao"].asString() shouldBe description
        parsed["pessoa"]["genero_id"].asString() shouldBe "OUTRO"
    }

    @Test
    fun `a person without description sends no GeneroDescricao`() {
        FnrhTestServer.stubRegisterPerson_v2_4("p-1", "52998224725")

        gateway.registerPerson(person(null), Cpf.of("52998224725"), "corr-nd")

        val raw = FnrhTestServer.onlyRequest().bodyAsString
        mapper.readTree(raw)["pessoa"].has("GeneroDescricao") shouldBe false
    }
}
