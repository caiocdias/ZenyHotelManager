package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentStore
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.json.JsonMapper

/**
 * Falha de infraestrutura no realm do agente (KDoc de `ServiceCredentialRealm.authenticate`): o cliente não pode tomar
 * a indisponibilidade por token recusado (401) nem ver detalhe da falha; e a recuperação é imediata.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PrintAgentRealmInfraAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    @Suppress("VarCouldBeVal") // o Spring injeta o campo lateinit
    @MockitoSpyBean
    private lateinit var store: PrintAgentStore

    @AfterEach
    fun restoreStore() {
        reset(store)
    }

    @Test
    fun `a store failure is never a 401 and never a 200, shows no detail and the agent recovers`(
        output: CapturedOutput,
    ) {
        val created =
            jsonMapper.readTree(
                iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Infra")).contentAsString,
            )
        val token = created["token"].asString()
        val secret = "jdbc:postgresql://segredo-host:5432/zeny?password=hunter2"
        doThrow(
            DataAccessResourceFailureException(secret),
        ).`when`(store).byTokenHash(org.mockito.ArgumentMatchers.anyString())

        val outcome =
            runCatching {
                mockMvc
                    .request(GET, "/api/v1/print-agents/me/identity") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    }.andReturn()
                    .response
            }
        val response = outcome.getOrNull()
        if (response != null) {
            withClue("status ${response.status} body ${response.contentAsString}") {
                response.status shouldBe 500
                response.contentAsString shouldNotContain "hunter2"
                response.contentAsString shouldNotContain "segredo-host"
                response.contentAsString shouldNotContain token
            }
        } else {
            // MockMvc repassa a exceção do filtro; no contêiner ela vira o 500 genérico do ProblemErrorController.
            outcome.exceptionOrNull() shouldNotBe null
            outcome.exceptionOrNull()!!.message.orEmpty() shouldNotContain token
        }
        output.all shouldNotContain token

        reset(store)
        mockMvc
            .request(GET, "/api/v1/print-agents/me/identity") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .andReturn()
            .response.status shouldBe 200
    }
}
