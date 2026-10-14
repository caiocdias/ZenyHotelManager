package com.zeny.hotel.iam

import com.zeny.hotel.iam.application.PasswordHasher
import com.zeny.hotel.iam.application.Users
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** Um usuário criado pelo teste, com a senha em claro só na memória do teste. */
data class TestUser(
    val id: UserId,
    val login: String,
    val password: String,
)

/**
 * Apoio dos testes do `iam`: cria usuários direto pelo repositório (a gestão de usuários chega
 * na E03) e chama a API de autenticação pelo `MockMvc`. Entra no contexto pelo
 * [IamTestConfiguration] (fora da varredura de componentes).
 */
@Suppress("LongParameterList") // os beans de que o apoio precisa, injetados pela configuração de teste
class IamTestSupport(
    private val users: Users,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val mockMvc: MockMvc,
    private val jsonMapper: JsonMapper,
) {
    /** Usuário ativo novo, com login e senha únicos. */
    fun createUser(passwordChangeRequired: Boolean = false): TestUser {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val login = "user.$suffix"
        val password = "Senha-$suffix-de-teste"
        val user = User.create("Usuária $suffix", login, hasher.hash(password), passwordChangeRequired, clock.instant())
        transactions.executeWithoutResult { users.add(user) }
        return TestUser(user.id, login, password)
    }

    /** Desativa o usuário direto no banco (a desativação pela API chega na E03). */
    fun deactivate(user: TestUser) {
        jdbc
            .sql("UPDATE iam.user_account SET active = false WHERE id = :id")
            .param("id", user.id.value)
            .update() shouldBe 1
    }

    fun login(
        login: String,
        password: String,
        deviceId: String = "device-1",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    jsonMapper.writeValueAsString(
                        mapOf(
                            "login" to login,
                            "password" to password,
                            "device" to mapOf("id" to deviceId, "name" to "Celular"),
                        ),
                    )
            }.andReturn()
            .response

    /** Login que precisa dar certo; devolve o corpo com os tokens. */
    fun loginOk(
        user: TestUser,
        deviceId: String = "device-1",
    ): JsonNode = login(user.login, user.password, deviceId).ok()

    fun refresh(
        refreshToken: String,
        bearer: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
                if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
            }.andReturn()
            .response

    fun sessions(accessToken: String): MockHttpServletResponse = getWithToken("/api/v1/auth/sessions", accessToken)

    fun getWithToken(
        path: String,
        accessToken: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
            .andReturn()
            .response

    fun postWithToken(
        path: String,
        accessToken: String,
        body: Map<String, Any>? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(body)
                }
            }.andReturn()
            .response

    fun json(response: MockHttpServletResponse): JsonNode =
        jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))

    fun MockHttpServletResponse.ok(): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { status shouldBe 200 }
        return json(this)
    }

    /** Confere um problema RFC 9457 com o status e o código. */
    fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) {
            this.status shouldBe status
            MediaType.parseMediaType(contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
        }
        val problem = json(this)
        problem["code"].asString() shouldBe code
        return problem
    }

    private companion object {
        const val SUFFIX_LENGTH = 12
    }
}

@TestConfiguration(proxyBeanMethods = false)
class IamTestConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun iamTestSupport(
        users: Users,
        hasher: PasswordHasher,
        clock: Clock,
        transactions: TransactionTemplate,
        jdbc: JdbcClient,
        mockMvc: MockMvc,
        jsonMapper: JsonMapper,
    ) = IamTestSupport(users, hasher, clock, transactions, jdbc, mockMvc, jsonMapper)
}
