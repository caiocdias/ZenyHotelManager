package com.zeny.hotel.iam

import authzexample.AuthzExampleConfiguration
import authzexample.ExamplePermissions
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestAdmin
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Toda escrita do `iam` vai para a trilha de auditoria (M1 E04, RF-ACS-11): login (com sucesso e
 * com falha), refresh (inclusive o reuso), logout e revogação de sessões, troca de senha, gestão
 * de usuários e roles, sincronização do catálogo e administrador inicial. Os valores antes e
 * depois nunca trazem senha, PIN, token nem hash.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, AuthzExampleConfiguration::class)
class IamAuditTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
) {
    /** O banco é compartilhado: outro contexto pode ter marcado as permissões de exemplo como obsoletas. */
    @BeforeEach
    fun synchronizeCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `login, refresh, logout and session revocation are audited with the user and the session`() {
        val user = iam.createUser()
        val first = with(iam) { iam.loginOk(user, deviceId = "audit-1") }
        val firstSession = first["sessionId"].asString()

        val login = entriesOf(firstSession).single()
        login.action shouldBe "iam.session.login"
        login.outcome shouldBe "SUCCEEDED"
        login.userId shouldBe user.id.value
        login.deviceSessionId shouldBe UUID.fromString(firstSession)
        login.entityType shouldBe "iam.session"
        login.before.shouldBeNull()
        login.after!!["deviceId"].asString() shouldBe "audit-1"
        login.after["revokedAt"].isNull shouldBe true

        val refreshed = with(iam) { iam.refresh(first["refreshToken"].asString()).ok() }
        val refresh = entriesOf(firstSession).single { it.action == "iam.session.refresh" }
        refresh.userId shouldBe user.id.value
        refresh.deviceSessionId shouldBe UUID.fromString(firstSession)
        refresh.before!!["expiresAt"] shouldNotBe refresh.after!!["expiresAt"]

        val second = with(iam) { iam.loginOk(user, deviceId = "audit-2") }
        val secondSession = second["sessionId"].asString()
        with(iam) {
            iam
                .postWithToken("/api/v1/auth/sessions/$secondSession/revoke", refreshed["accessToken"].asString())
                .expectStatus(204)
        }
        val revoke = entriesOf(secondSession).single { it.action == "iam.session.revoke" }
        revoke.userId shouldBe user.id.value
        revoke.deviceSessionId shouldBe UUID.fromString(firstSession)
        revoke.before!!["revokedAt"].isNull shouldBe true
        revoke.after!!["revocationReason"].asString() shouldBe "REVOKED_BY_USER"

        with(iam) { iam.postWithToken("/api/v1/auth/logout", refreshed["accessToken"].asString()).expectStatus(204) }
        val logout = entriesOf(firstSession).single { it.action == "iam.session.logout" }
        logout.after!!["revocationReason"].asString() shouldBe "LOGOUT"
        logout.userId shouldBe user.id.value
    }

    @Test
    fun `failed logins and the reuse of a refresh token are audited as rejections`() {
        val user = iam.createUser()
        with(iam) { iam.login(user.login, "senha-errada-de-teste").problem(401, "INVALID_CREDENTIALS") }

        val failure = entriesOf(user.id.value.toString()).single { it.action == "iam.session.login" }
        failure.outcome shouldBe "REJECTED"
        failure.errorCode shouldBe "INVALID_CREDENTIALS"
        failure.userId shouldBe user.id.value
        failure.deviceSessionId.shouldBeNull()
        failure.before!!["failedLoginCount"].asInt() shouldBe 0
        failure.after!!["failedLoginCount"].asInt() shouldBe 1

        // Login inexistente não escreve nada e não gera entrada.
        val anonymousBefore = anonymousLoginRejections()
        with(
            iam,
        ) { iam.login("ninguem.${UuidV7.generate()}", "senha-qualquer-123").problem(401, "INVALID_CREDENTIALS") }
        anonymousLoginRejections() shouldBe anonymousBefore

        val tokens = with(iam) { iam.loginOk(user) }
        val session = tokens["sessionId"].asString()
        with(iam) { iam.refresh(tokens["refreshToken"].asString()).ok() }
        with(iam) { iam.refresh(tokens["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_REUSED") }

        val reuse = entriesOf(session).single { it.outcome == "REJECTED" }
        reuse.action shouldBe "iam.session.refresh"
        reuse.errorCode shouldBe "REFRESH_TOKEN_REUSED"
        reuse.userId shouldBe user.id.value
        reuse.after!!["revocationReason"].asString() shouldBe "REFRESH_TOKEN_REUSED"
    }

    @Test
    fun `changing the own password is audited, and so is the revocation of the other sessions`() {
        val user = iam.createUser()
        val current = with(iam) { iam.loginOk(user, deviceId = "pwd-1") }
        val other = with(iam) { iam.loginOk(user, deviceId = "pwd-2") }
        val newPassword = "Nova-Senha-${UuidV7.generate()}"

        with(iam) {
            iam
                .postWithToken(
                    "/api/v1/auth/password",
                    current["accessToken"].asString(),
                    mapOf("currentPassword" to user.password, "newPassword" to newPassword),
                ).expectStatus(204)
        }

        val change = entriesOf(user.id.value.toString()).single { it.action == "iam.user.change-password" }
        change.userId shouldBe user.id.value
        change.deviceSessionId shouldBe UUID.fromString(current["sessionId"].asString())
        val revoked = entriesOf(other["sessionId"].asString()).single { it.action == "iam.user.change-password" }
        revoked.after!!["revocationReason"].asString() shouldBe "PASSWORD_CHANGED"
        rawTrailOf(user.id.value.toString()).let {
            it shouldNotContain newPassword
            it shouldNotContain user.password
        }
    }

    @Test
    @Suppress("LongMethod") // o ciclo inteiro de um usuário na gestão, de ponta a ponta
    fun `every user management write is audited, without passwords, PINs or hashes`() {
        val password = "Senha-Provisoria-${UuidV7.generate()}"
        val pin = "90817263"
        val role = iam.createRole(TestGrant(definition(ExamplePermissions.TASKS_READ), ALL))
        val response =
            iam.call(
                POST,
                "/api/v1/users",
                iam.asAdmin(),
                mapOf("name" to "Auditada", "login" to "auditada.${suffix()}", "password" to password, "pin" to pin),
            )
        val created = with(iam) { response.expectStatus(201) }.shouldNotBeNull()
        val id = created["id"].asString()
        var etag = response.getHeader("ETag").shouldNotBeNull()
        with(iam) {
            etag = iam.call(PUT, "/api/v1/users/$id", iam.asAdmin(), mapOf("name" to "Auditada Silva"), etag).etag()
            iam.call(PUT, "/api/v1/users/$id/roles", iam.asAdmin(), mapOf("roleIds" to listOf(role.value)), etag).ok()
            val session = iam.login(created["login"].asString(), password).ok()["sessionId"].asString()
            iam.call(POST, "/api/v1/users/$id/sessions/$session/revoke", iam.asAdmin()).expectStatus(204)
            iam.call(POST, "/api/v1/users/$id/deactivate", iam.asAdmin()).ok()
            iam.call(POST, "/api/v1/users/$id/reactivate", iam.asAdmin()).ok()
            iam
                .call(POST, "/api/v1/users/$id/reset-password", iam.asAdmin(), mapOf("newPassword" to password))
                .expectStatus(204)
            iam.call(POST, "/api/v1/users/$id/reset-pin", iam.asAdmin(), mapOf("newPin" to NEW_PIN)).expectStatus(204)

            val revoke = entriesOf(session).single { it.action == "iam.session.revoke-as-administrator" }
            revoke.userId shouldBe iam.adminId()
            revoke.after!!["revocationReason"].asString() shouldBe "REVOKED_BY_ADMINISTRATOR"
        }

        val entries = entriesOf(id)
        entries.map { it.action } shouldContainAll
            listOf(
                "iam.user.create",
                "iam.user.rename",
                "iam.user.set-roles",
                "iam.user.deactivate",
                "iam.user.reactivate",
                "iam.user.reset-password",
                "iam.user.reset-pin",
            )
        entries.forEach { withClue(it.action) { it.userId shouldBe iam.adminId() } }
        entries.single { it.action == "iam.user.create" }.let {
            it.before.shouldBeNull()
            it.after!!["hasPin"].asBoolean() shouldBe true
            it.after["login"].asString() shouldBe created["login"].asString()
        }
        entries.single { it.action == "iam.user.rename" }.let {
            it.before!!["name"].asString() shouldBe "Auditada"
            it.after!!["name"].asString() shouldBe "Auditada Silva"
        }
        entries
            .single {
                it.action == "iam.user.set-roles"
            }.after!!["roleIds"]
            .values()
            .map { it.asString() } shouldContainExactly
            listOf(role.value.toString())
        entries.single { it.action == "iam.user.deactivate" }.after!!["active"].asBoolean() shouldBe false

        val (passwordHash, pinHash) =
            jdbc
                .sql("SELECT password_hash, pin_hash FROM iam.user_account WHERE id = :id")
                .param("id", UUID.fromString(id))
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
        rawTrailOf(id).let { raw ->
            listOf(password, pin, NEW_PIN, passwordHash, pinHash, "argon2").forEach { raw shouldNotContain it }
        }
    }

    @Test
    fun `every role management write is audited`() {
        val grants = listOf(mapOf("permission" to ExamplePermissions.TASKS_READ.value, "scope" to "ALL"))
        val response =
            iam.call(
                POST,
                "/api/v1/roles",
                iam.asAdmin(),
                mapOf("name" to "Auditada ${suffix()}", "description" to "Role auditada", "grants" to grants),
            )
        val id = with(iam) { response.expectStatus(201) }.shouldNotBeNull()["id"].asString()
        with(iam) {
            iam
                .call(
                    PUT,
                    "/api/v1/roles/$id",
                    iam.asAdmin(),
                    mapOf("name" to "Auditada ${suffix()}", "description" to "Editada", "grants" to emptyList<Any>()),
                    response.getHeader("ETag"),
                ).ok()
            iam.call(POST, "/api/v1/roles/$id/deactivate", iam.asAdmin()).ok()
            iam.call(POST, "/api/v1/roles/$id/reactivate", iam.asAdmin()).ok()
            val copy =
                iam.call(
                    POST,
                    "/api/v1/roles/$id/duplicate",
                    iam.asAdmin(),
                    mapOf("name" to "Cópia ${suffix()}"),
                )
            val copyId = copy.expectStatus(201)!!["id"].asString()
            entriesOf(copyId).single().let {
                it.action shouldBe "iam.role.duplicate"
                it.before.shouldBeNull()
            }
        }

        val entries = entriesOf(id)
        entries.map { it.action } shouldContainExactly
            listOf("iam.role.create", "iam.role.update", "iam.role.deactivate", "iam.role.reactivate")
        entries.forEach { withClue(it.action) { it.userId shouldBe iam.adminId() } }
        entries.single { it.action == "iam.role.update" }.let {
            it.before!!["grants"].values().map { grant -> grant["permission"].asString() } shouldContain
                ExamplePermissions.TASKS_READ.value
            it.after!!["grants"].size() shouldBe 0
            it.after["description"].asString() shouldBe "Editada"
        }
    }

    @Test
    fun `the catalog synchronization and the initial administrator are audited as the system`() {
        val code = PermissionCode("auditsync.items.read")
        val module =
            object : ModulePermissions {
                override val module = "auditsync"
                override val permissions = listOf(PermissionDefinition(code, "Ler itens", setOf(ALL)))
            }

        synchronizer.synchronize(listOf(module) + modules)
        synchronizer.synchronize()

        val entries = entriesOf(code.value)
        entries shouldHaveSize 2
        entries.forEach {
            it.action shouldBe "iam.permission-catalog.synchronize"
            it.entityType shouldBe "iam.permission"
            it.userId.shouldBeNull()
        }
        entries[0].before.shouldBeNull()
        entries[1].after!!["obsolete"].asBoolean() shouldBe true
        // Sem mudança, a sincronização não grava nada.
        val count = entriesOf(code.value).size
        synchronizer.synchronize()
        entriesOf(code.value).size shouldBe count

        val admin = entriesOf(iam.adminId().toString()).first()
        admin.action shouldBe "iam.user.create-initial-admin"
        admin.userId.shouldBeNull()
        admin.after!!["login"].asString() shouldBe TestAdmin.LOGIN
        admin.after["passwordChangeRequired"].asBoolean() shouldBe true
    }

    private fun definition(code: PermissionCode): PermissionDefinition =
        (modules.flatMap { it.permissions }).single { it.code == code }

    private fun org.springframework.mock.web.MockHttpServletResponse.etag(): String {
        withClue(contentAsString) { status shouldBe 200 }
        return getHeader("ETag").shouldNotBeNull()
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun anonymousLoginRejections(): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'iam.session.login' AND entity_id IS NULL")
            .query(Int::class.java)
            .single()

    private fun rawTrailOf(entityId: String): String =
        jdbc
            .sql(
                "SELECT string_agg(coalesce(CAST(before_state AS text), '') || " +
                    "coalesce(CAST(after_state AS text), ''), '') FROM audit.audit_entry WHERE entity_id = :id",
            ).param("id", entityId)
            .query(String::class.java)
            .single()

    private fun entriesOf(entityId: String): List<Entry> =
        jdbc
            .sql(
                """
                SELECT action, outcome, error_code, user_id, device_session_id, entity_type,
                    CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state
                FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id
                """.trimIndent(),
            ).param("id", entityId)
            .query { rs, _ ->
                Entry(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    errorCode = rs.getString("error_code"),
                    userId = rs.getObject("user_id", UUID::class.java),
                    deviceSessionId = rs.getObject("device_session_id", UUID::class.java),
                    entityType = rs.getString("entity_type"),
                    before = rs.getString("before_state")?.let(jsonMapper::readTree),
                    after = rs.getString("after_state")?.let(jsonMapper::readTree),
                )
            }.list()

    private data class Entry(
        val action: String,
        val outcome: String,
        val errorCode: String?,
        val userId: UUID?,
        val deviceSessionId: UUID?,
        val entityType: String?,
        val before: JsonNode?,
        val after: JsonNode?,
    )

    private companion object {
        const val SUFFIX_LENGTH = 10
        const val NEW_PIN = "11223344"
    }
}
