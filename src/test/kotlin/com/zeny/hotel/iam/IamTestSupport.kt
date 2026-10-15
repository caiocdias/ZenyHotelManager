package com.zeny.hotel.iam

import com.zeny.hotel.iam.application.PasswordHasher
import com.zeny.hotel.iam.application.Roles
import com.zeny.hotel.iam.application.Users
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.TestAdmin
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/** Um usuário criado pelo teste, com a senha em claro só na memória do teste. */
data class TestUser(
    val id: UserId,
    val login: String,
    val password: String,
)

/** Uma concessão para [IamTestSupport.createRole]. */
data class TestGrant(
    val definition: PermissionDefinition,
    val scope: PermissionScope,
    val limits: Map<String, LimitValue> = emptyMap(),
)

/**
 * Apoio dos testes do `iam`: cria usuários e roles direto pelos repositórios (para preparar o
 * cenário sem depender da API de gestão da E03) e chama a API pelo `MockMvc`. Entra no contexto pelo
 * [IamTestConfiguration] (fora da varredura de componentes).
 */
@Suppress("LongParameterList") // os beans de que o apoio precisa, injetados pela configuração de teste
class IamTestSupport(
    private val users: Users,
    private val roles: Roles,
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

    /** Desativa o usuário direto no banco (sem passar pela API de gestão). */
    fun deactivate(user: TestUser) {
        jdbc
            .sql("UPDATE iam.user_account SET active = false WHERE id = :id")
            .param("id", user.id.value)
            .update() shouldBe 1
    }

    /** Define o PIN de aprovação do usuário direto pelo repositório. */
    fun setPin(
        user: TestUser,
        pin: String,
    ) = transactions.executeWithoutResult {
        users.findByIdForUpdate(user.id)!!.resetPin(hasher.hash(pin), clock.instant())
    }

    /** Role nova, ativa, com as concessões dadas. */
    fun createRole(vararg grants: TestGrant): RoleId {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val role = Role.create("Role $suffix", "Role de teste", clock.instant())
        grants.forEach { role.grant(it.definition, it.scope, it.limits, clock.instant()) }
        transactions.executeWithoutResult { roles.add(role) }
        return role.id
    }

    fun assignRole(
        user: TestUser,
        roleId: RoleId,
    ) = transactions.executeWithoutResult {
        users.findByIdForUpdate(user.id)!!.assignRole(roleId, clock.instant()) shouldBe true
    }

    fun unassignRole(
        user: TestUser,
        roleId: RoleId,
    ) = transactions.executeWithoutResult {
        users.findByIdForUpdate(user.id)!!.unassignRole(roleId, clock.instant()) shouldBe true
    }

    /** Retira uma concessão da role. */
    fun revokeGrant(
        roleId: RoleId,
        permission: PermissionCode,
    ) = transactions.executeWithoutResult { roles.findById(roleId)!!.revoke(permission, clock.instant()) }

    fun deactivateRole(roleId: RoleId) =
        transactions.executeWithoutResult { roles.findById(roleId)!!.deactivate(clock.instant()) }

    /** Usuário novo com uma role nova com as concessões dadas. */
    fun createUserWith(vararg grants: TestGrant): TestUser = createUser().also { assignRole(it, createRole(*grants)) }

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

    /** O id do administrador inicial dos testes ([TestAdmin]). */
    fun adminId(): UUID =
        jdbc
            .sql("SELECT id FROM iam.user_account WHERE login = :login")
            .param("login", TestAdmin.LOGIN)
            .query(UUID::class.java)
            .single()

    /** Autentica a requisição como o administrador inicial (as permissões vêm do banco). */
    fun asAdmin(): RequestPostProcessor = asUser(adminId())

    fun asTestUser(user: TestUser): RequestPostProcessor = asUser(user.id.value)

    /** Uma requisição à API como [auth], com corpo JSON, `If-Match` e token de aprovação opcionais. */
    fun call(
        method: HttpMethod,
        path: String,
        auth: RequestPostProcessor,
        body: Any? = null,
        ifMatch: String? = null,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                with(auth)
                if (ifMatch != null) header(HttpHeaders.IF_MATCH, ifMatch)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
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

    /** Confere o status (com o corpo na mensagem de falha) e devolve o corpo, se houver. */
    fun MockHttpServletResponse.expectStatus(expected: Int): JsonNode? {
        withClue(getContentAsString(Charsets.UTF_8)) { status shouldBe expected }
        return getContentAsString(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let { jsonMapper.readTree(it) }
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
        roles: Roles,
        hasher: PasswordHasher,
        clock: Clock,
        transactions: TransactionTemplate,
        jdbc: JdbcClient,
        mockMvc: MockMvc,
        jsonMapper: JsonMapper,
    ) = IamTestSupport(users, roles, hasher, clock, transactions, jdbc, mockMvc, jsonMapper)
}
