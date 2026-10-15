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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper

/**
 * Segurança e roteamento, no contexto de produção (sem o controller de exemplo): `/api/...`
 * exige access token, exceto login, refresh e `GET /api/v1/meta/version`; o resto da aplicação
 * é negado; a documentação fica aberta fora de `prod`; não há usuário em memória, HTTP Basic nem
 * login por formulário. A validação do token em si (assinatura, `kid`, `alg`, validade) fica em
 * `iam.AccessTokenValidationTest`.
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
                .filterNot { (method, pattern, _) -> (method to pattern) in PUBLIC_API_ENDPOINTS }
                .map { (method, _, path) -> method to path }
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
    fun `only a bearer access token authenticates`() {
        context.getBeanNamesForType(UserDetailsService::class.java).toList().shouldBeEmpty()

        // Sem HTTP Basic nem login por formulário: credenciais são ignoradas e a resposta é 401.
        expectUnauthenticated(HttpMethod.GET, "/api/v1/auth/sessions") {
            header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==")
        }
        expectUnauthenticated(HttpMethod.POST, "/login")
        // Token que não é um JWT válido: 401, com o desafio Bearer e sem detalhe do motivo.
        val response =
            expectUnauthenticated(HttpMethod.GET, "/api/v1/auth/sessions") {
                header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
            }
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
        // Token só na query ou no formulário não vale: só o cabeçalho Authorization.
        expectUnauthenticated(HttpMethod.GET, "/api/v1/auth/sessions?access_token=x")
    }

    @Test
    fun `public endpoints ignore a stale authorization header`() {
        // Um token inválido mandado por hábito não vira 401 UNAUTHENTICATED no login nem no refresh.
        mockMvc
            .request(HttpMethod.POST, "/api/v1/auth/refresh") {
                header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken": "unknown"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("REFRESH_TOKEN_INVALID") }
            }
        mockMvc
            .get("/api/v1/meta/version") { header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt") }
            .andExpect { status { isOk() } }
        SecurityConfiguration.PUBLIC_API_ENDPOINTS.toSet() shouldBe PUBLIC_API_ENDPOINTS
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
        // O cabeçalho Idempotency-Key fica documentado mesmo antes de haver operação de produção marcada.
        doc["components"]["parameters"]["IdempotencyKey"]["name"].asString() shouldBe "Idempotency-Key"
        doc["servers"].values().map { it["url"].asString() } shouldBe listOf("/")
        // Toda operação exige o access token, menos as públicas, que declaram `security: []`.
        doc["components"]["securitySchemes"]["bearerAuth"]["scheme"].asString() shouldBe "bearer"
        doc["security"].values().map { it.propertyNames().toList() } shouldBe listOf(listOf("bearerAuth"))
        val publicOperations =
            doc["paths"].properties().flatMap { (path, item) ->
                item
                    .properties()
                    .filter { (_, operation) ->
                        operation["security"]?.isEmpty == true
                    }.map { (method, _) ->
                        HttpMethod.valueOf(method.uppercase()) to path
                    }
            }
        publicOperations.toSet() shouldBe PUBLIC_API_ENDPOINTS
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
    ): MockHttpServletResponse {
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
        return response
    }

    /** Mapeamentos dos controllers da aplicação (pacote base), sem os do springdoc e do Spring. */
    private fun applicationMappings(): List<Pair<RequestMappingInfo, HandlerMethod>> =
        handlerMapping.handlerMethods
            .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.zeny.hotel") }
            .toList()

    private fun RequestMappingInfo.patterns(): Set<String> = pathPatternsCondition?.patternValues.orEmpty()

    /** Cada combinação de método e padrão de caminho, com o caminho de exemplo (variáveis preenchidas). */
    private fun RequestMappingInfo.endpoints(): List<Triple<HttpMethod, String, String>> {
        val methods = methodsCondition.methods.map { HttpMethod.valueOf(it.name) }.ifEmpty { listOf(HttpMethod.GET) }
        return patterns().flatMap { pattern ->
            val path = pattern.replace(Regex("\\{[^}]+}"), "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d")
            methods.map { Triple(it, pattern, path) }
        }
    }

    private companion object {
        /**
         * Os únicos endpoints de `/api/...` abertos sem access token (M1 E01): login, refresh, versão e o
         * download de anexo sensível, que exige o token da URL assinada pelo servidor (M1 E08).
         */
        val PUBLIC_API_ENDPOINTS =
            setOf(
                HttpMethod.POST to "/api/v1/auth/login",
                HttpMethod.POST to "/api/v1/auth/refresh",
                HttpMethod.GET to "/api/v1/meta/version",
                HttpMethod.GET to "/api/v1/files/{id}/content",
            )
    }
}
