package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper

/**
 * Segurança mínima até o M1 e roteamento, no contexto de produção (sem o controller de
 * exemplo): `/api/...` nega quem não está autenticado, exceto `GET /api/v1/meta/version`; o
 * resto da aplicação é negado; a documentação fica aberta fora de `prod`; ninguém consegue
 * autenticar (não há usuário, senha nem HTTP Basic).
 */
@IntegrationTest
class ApiSecurityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    @Test
    fun `the public example endpoint answers anonymously`() {
        mockMvc.get("/api/v1/meta/version").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.apiVersion") { value("v1") }
            jsonPath("$.application") { value("zeny-hotel-manager") }
        }
    }

    @Test
    fun `every other api endpoint rejects anonymous requests with a 401 problem`() {
        val endpoints =
            applicationMappings()
                .filter { (info, _) -> info.patterns().any { it.startsWith("/api/") } }
                .flatMap { (info, _) -> info.endpoints() }
                .filterNot { it in PUBLIC_API_ENDPOINTS }
        // Rotas que não existem também não se revelam a quem não está autenticado.
        val probes = endpoints + listOf(HttpMethod.GET to "/api/v1/does-not-exist", HttpMethod.POST to "/api/v2/x")

        assertSoftly {
            probes.forEach { (method, path) ->
                withClue("$method $path") { expectUnauthenticated(method, path) }
            }
        }
    }

    @Test
    fun `paths outside the api and the documentation are denied`() {
        listOf("/", "/index.html", "/actuator", "/error", "/anything").forEach { path ->
            withClue(path) { expectUnauthenticated(HttpMethod.GET, path) }
        }
    }

    @Test
    fun `there is no way to authenticate until M1`() {
        context.getBeanNamesForType(UserDetailsService::class.java).toList().shouldBeEmpty()

        // Sem HTTP Basic nem login por formulário: credenciais são ignoradas e a resposta é 401.
        expectUnauthenticated(HttpMethod.GET, "/api/v1/does-not-exist") {
            header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==")
        }
        expectUnauthenticated(HttpMethod.POST, "/login")
    }

    @Test
    fun `application controllers live under the api v1 prefix`() {
        val mappings =
            applicationMappings().filterNot { (_, handler) ->
                handler.beanType ==
                    ProblemErrorController::class.java
            }
        mappings.shouldNotBeEmpty()

        assertSoftly {
            mappings.forEach { (info, handler) ->
                info.patterns().forEach { pattern ->
                    withClue(
                        "${handler.beanType.simpleName}: $pattern",
                    ) { pattern.startsWith("$API_V1/") shouldBe true }
                }
            }
        }
    }

    @Test
    fun `swagger ui and the api docs are served outside prod`() {
        mockMvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
        mockMvc
            .get("/v3/api-docs")
            .andExpect { status { isOk() } }
            .andReturn()
            .response.contentAsString shouldContain "/api/v1/meta/version"
    }

    @Test
    fun `errors forwarded by the servlet container are problems with a stable code`() {
        val problem =
            mockMvc
                .get("/error") {
                    with { request ->
                        request.dispatcherType = DispatcherType.ERROR
                        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400)
                        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/whatever")
                        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, IllegalStateException("segredo"))
                        request
                    }
                }.andExpect {
                    status { isBadRequest() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                }.andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
                .let(jsonMapper::readTree)

        problem["code"].asString() shouldBe "MALFORMED_REQUEST"
        problem["instance"].asString() shouldBe "/api/v1/whatever"
        problem.toString().contains("segredo") shouldBe false
    }

    @Test
    fun `the production contract has unique operation ids and the problem schema`() {
        val doc =
            jsonMapper.readTree(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )
        val operationIds =
            doc["paths"].properties().flatMap { (_, item) ->
                item.properties().map { (_, operation) -> operation["operationId"].asString() }
            }

        operationIds.shouldNotBeEmpty()
        operationIds.toSet().size shouldBe operationIds.size
        operationIds.filter { Regex("_\\d+$").containsMatchIn(it) }.shouldBeEmpty()
        doc["components"]["schemas"].propertyNames().toList() shouldContain "Problem"
        doc["servers"].values().map { it["url"].asString() } shouldBe listOf("/")
        // Propriedades Kotlin não nulas são obrigatórias no contrato (SDKs dos clientes).
        doc["components"]["schemas"]["ServerVersionResponse"]["required"]
            .values()
            .map {
                it.asString()
            }.toSet() shouldBe
            setOf("application", "version", "apiVersion")
    }

    private fun expectUnauthenticated(
        method: HttpMethod,
        path: String,
        customize: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {},
    ) {
        val response =
            mockMvc
                .request(method, path) {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                    customize()
                }.andReturn()
                .response
        response.status shouldBe 401
        MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe
            true
        val problem = jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))
        problem["code"].asString() shouldBe "UNAUTHENTICATED"
        problem["status"].asInt() shouldBe 401
    }

    /** Mapeamentos dos controllers da aplicação (pacote base), sem os do springdoc e do Spring. */
    private fun applicationMappings(): List<Pair<RequestMappingInfo, HandlerMethod>> =
        handlerMapping.handlerMethods
            .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.zeny.hotel") }
            .toList()

    private fun RequestMappingInfo.patterns(): Set<String> = pathPatternsCondition?.patternValues.orEmpty()

    /** Cada combinação de método e caminho, com as variáveis de caminho preenchidas. */
    private fun RequestMappingInfo.endpoints(): List<Pair<HttpMethod, String>> {
        val methods = methodsCondition.methods.map { HttpMethod.valueOf(it.name) }.ifEmpty { listOf(HttpMethod.GET) }
        return patterns().flatMap { pattern ->
            val path = pattern.replace(Regex("\\{[^}]+}"), "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d")
            methods.map { it to path }
        }
    }

    private companion object {
        /** Os únicos endpoints de `/api/...` abertos sem autenticação até o M1. */
        val PUBLIC_API_ENDPOINTS = setOf(HttpMethod.GET to "/api/v1/meta/version")
    }
}
