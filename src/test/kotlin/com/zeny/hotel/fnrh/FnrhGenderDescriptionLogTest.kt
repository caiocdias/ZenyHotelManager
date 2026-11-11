package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.util.UUID

/**
 * A descrição de gênero ecoada num `400` não chega à tabela `integration_call` nem ao log (M5 E15, Task 5), com o
 * gateway real ligado ao registro das chamadas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class FnrhGenderDescriptionLogTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val gateway: FnrhGateway,
) {
    private val description = "Pessoa não binária"
    private val person =
        FnrhPersonData(
            name = "ZULEICA FERNANDES DA ROCHA",
            gender = "OUTRO",
            genderDescription = description,
            birthDate = LocalDate.parse("1985-03-02"),
        )

    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
        iam.enableFnrh()
    }

    @AfterEach
    fun tearDown() {
        jdbc.resetFnrhSettings()
        FnrhTestServer.reset()
    }

    @Test
    fun `a 400 echoing the description leaves a VALIDATION row and a log line without it`(output: CapturedOutput) {
        val correlation = "gd-${UUID.randomUUID()}"
        val already = output.all.length
        FnrhTestServer.stubAny(400, """{"mensagem":"GeneroDescricao PESSOA NAO BINARIA invalida; binária"}""")

        val e =
            shouldThrow<FnrhGatewayException> {
                gateway.registerPerson(person, Cpf.of("52998224725"), correlation)
            }

        val row =
            jdbc
                .sql("SELECT t::text FROM fnrh.integration_call t WHERE correlation_ref = :ref")
                .param("ref", correlation)
                .query(String::class.java)
                .single()
        val outcome =
            jdbc
                .sql("SELECT outcome FROM fnrh.integration_call WHERE correlation_ref = :ref")
                .param("ref", correlation)
                .query(String::class.java)
                .single()
        outcome shouldBe "VALIDATION"
        val log = output.all.substring(already)
        log shouldContain correlation
        for ((place, text) in mapOf("row" to row, "log" to log, "message" to e.message.orEmpty())) {
            for (leak in listOf("binária", "binaria", "BINARIA", "PESSOA")) {
                withClue("$place leaked $leak") { text shouldNotContain leak }
            }
        }
    }
}
