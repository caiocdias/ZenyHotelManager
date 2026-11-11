package com.zeny.hotel.shared.web

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.ServiceCredentialRealm
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A cadeia de segurança das credenciais de serviço (D-E10-1) com um realm só de teste em
 * `/api/v1/test-service/me/`: o token do realm vale ali e só ali; JWT de usuário não vale no
 * realm; o token nunca aparece no log nem na resposta.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(ServiceCredentialChainTest.TestRealmConfiguration::class, IamTestConfiguration::class)
class ServiceCredentialChainTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val iam: IamTestSupport,
) {
    @Test
    fun `a realm token reaches the realm path with the right principal`() {
        val response = get(PING, "Bearer $GOOD_TOKEN")

        response.status shouldBe 200
        val body = jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))
        body["realm"].asString() shouldBe REALM
        body["id"].asString() shouldBe PRINCIPAL_ID.toString()
        body["stamp"].asString() shouldBe STAMP
    }

    @Test
    fun `the prefix without the trailing slash belongs to the service chain`() {
        get("/api/v1/test-service/me", "Bearer $GOOD_TOKEN").status shouldBe 200
        expectUnauthenticated(get("/api/v1/test-service/me", null))
        expectUnauthenticated(get("/api/v1/test-service/me", "Bearer $BAD_TOKEN"))
    }

    @Test
    fun `no token is a 401 problem with a bearer challenge`() {
        expectUnauthenticated(get(PING, null))
    }

    @Test
    fun `a rejected token is a 401 problem`() {
        expectUnauthenticated(get(PING, "Bearer $BAD_TOKEN"))
    }

    @Test
    fun `a valid user jwt on the realm path is a 401`() {
        val user = iam.createUser()
        val accessToken = iam.loginOk(user)["accessToken"].asString()

        expectUnauthenticated(get(PING, "Bearer $accessToken"))
        // O mesmo JWT segue valendo na cadeia de usuário.
        get("/api/v1/auth/sessions", "Bearer $accessToken").status shouldBe 200
    }

    @Test
    fun `a realm token on a user endpoint is a 401`() {
        listOf("/api/v1/fnrh/status", "/api/v1/auth/sessions", "/api/v1/test-service/other").forEach { path ->
            withClue(path) { expectUnauthenticated(get(path, "Bearer $GOOD_TOKEN")) }
        }
    }

    @Test
    fun `malformed authorization headers are a 401 and never a 500`() {
        val headers =
            listOf(
                "Bearer $GOOD_TOKEN extra",
                "Bearer Bearer $GOOD_TOKEN",
                "Bearer  $GOOD_TOKEN",
                "Bearer\t$GOOD_TOKEN",
                "Bearer ",
                "Bearer",
                "Basic dXNlcjpwYXNz",
                "Token $GOOD_TOKEN",
                GOOD_TOKEN,
                "Bearer $GOOD_TOKEN,Bearer $GOOD_TOKEN",
            )

        assertSoftly {
            headers.forEach { header ->
                withClue("header ${header.replace("\n", "\\n")}") { expectUnauthenticated(get(PING, header)) }
            }
        }
    }

    @Test
    fun `a header with control characters is refused and never a 500`() {
        listOf("Bearer $GOOD_TOKEN\nX-Injected: 1", "Bearer \u0000$GOOD_TOKEN").forEach { header ->
            val response = get(PING, header)

            // O firewall do Spring Security pode recusar antes da cadeia (400); nunca 200 nem 500.
            withClue(header) { (response.status in listOf(400, 401)) shouldBe true }
            response.getContentAsString(Charsets.UTF_8) shouldNotContain GOOD_TOKEN
        }
    }

    @Test
    fun `two authorization headers are a 401`() {
        val response =
            mockMvc
                .get(PING) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $GOOD_TOKEN")
                    header(HttpHeaders.AUTHORIZATION, "Bearer $GOOD_TOKEN")
                }.andReturn()
                .response

        expectUnauthenticated(response)
    }

    @Test
    fun `the token is not accepted from the query string`() {
        expectUnauthenticated(
            mockMvc
                .get(PING) { param("access_token", GOOD_TOKEN) }
                .andReturn()
                .response,
        )
    }

    @Test
    fun `the token never shows up in the log or in the problem`(output: CapturedOutput) {
        val responses =
            listOf(
                get(PING, "Bearer $BAD_TOKEN"),
                get(PING, "Bearer $GOOD_TOKEN extra"),
                get(PING, "Bearer $GOOD_TOKEN"),
                get("/api/v1/auth/sessions", "Bearer $GOOD_TOKEN"),
            )

        responses.forEach {
            it.getContentAsString(Charsets.UTF_8) shouldNotContain BAD_TOKEN
        }
        responses[1].getContentAsString(Charsets.UTF_8) shouldNotContain GOOD_TOKEN
        responses[3].getContentAsString(Charsets.UTF_8) shouldNotContain GOOD_TOKEN
        // O dump do MockMvc (do harness, não da aplicação) mostra os cabeçalhos da requisição: fica de fora.
        val appLog = output.all.lines().filterNot { it.trim().startsWith("Headers = [") }
        appLog.filter { it.contains(GOOD_TOKEN) || it.contains(BAD_TOKEN) }.shouldBeEmpty()
    }

    @Test
    fun `the contract applies the realm scheme to the realm paths only`() {
        val doc =
            jsonMapper.readTree(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )

        val scheme = doc["components"]["securitySchemes"][OPEN_API_SCHEME]
        scheme["type"].asString() shouldBe "http"
        scheme["scheme"].asString() shouldBe "bearer"
        scheme["bearerFormat"].asString() shouldBe "opaque"
        val ping = doc["paths"]["/api/v1/test-service/me/ping"]["get"]
        ping["security"].values().map { it.propertyNames().toList() } shouldBe listOf(listOf(OPEN_API_SCHEME))
        val sessions = doc["paths"]["/api/v1/auth/sessions"]["get"]
        sessions["security"] shouldBe null
        doc["security"].values().map { it.propertyNames().toList() } shouldBe listOf(listOf("bearerAuth"))
        doc["info"]["description"].asString().let {
            it.contains("bearerAuth") shouldBe true
            it.contains(OPEN_API_SCHEME) shouldBe true
        }
    }

    private fun get(
        path: String,
        authorization: String?,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { if (authorization != null) header(HttpHeaders.AUTHORIZATION, authorization) }
            .andReturn()
            .response

    private fun expectUnauthenticated(response: MockHttpServletResponse) {
        response.status shouldBe 401
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
        response.contentType?.let {
            MediaType
                .parseMediaType(
                    it,
                ).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        } shouldBe
            true
        jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe "UNAUTHENTICATED"
    }

    /** O realm e o controller só de teste (D-E10-1): nada disto existe em produção. */
    @TestConfiguration(proxyBeanMethods = false)
    class TestRealmConfiguration {
        @Bean
        fun testServiceRealm(): ServiceCredentialRealm =
            object : ServiceCredentialRealm {
                override val name = REALM
                override val pathPrefix = "/api/v1/test-service/me/"
                override val openApiScheme = OPEN_API_SCHEME

                override fun authenticate(token: String): ServicePrincipal? =
                    if (token == GOOD_TOKEN) ServicePrincipal(REALM, PRINCIPAL_ID, STAMP) else null
            }

        @Bean
        fun testServiceController() = TestServiceController()
    }

    @RestController
    class TestServiceController {
        @GetMapping("/api/v1/test-service/me", "/api/v1/test-service/me/ping")
        fun me(
            @AuthenticationPrincipal principal: ServicePrincipal,
        ) = mapOf("realm" to principal.realm, "id" to principal.id.toString(), "stamp" to principal.credentialStamp)

        @GetMapping("/api/v1/test-service/other")
        fun other() = mapOf("ok" to true)
    }

    private companion object {
        const val REALM = "test-service"
        const val OPEN_API_SCHEME = "testServiceToken"
        const val GOOD_TOKEN = "good-token-0123456789abcdef"
        const val BAD_TOKEN = "bad-token-fedcba9876543210"
        const val STAMP = "stamp-1"
        const val PING = "/api/v1/test-service/me/ping"
        val PRINCIPAL_ID: UUID = UUID.fromString("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d")
    }
}
