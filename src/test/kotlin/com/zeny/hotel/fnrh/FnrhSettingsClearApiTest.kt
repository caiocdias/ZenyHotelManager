package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * A ação de limpar a credencial da FNRH (M5 E16, Task 2): apaga usuário, senha e CPF padrão e desliga a integração,
 * com `If-Match`, repetição inócua, trilha sem segredo e sem rastro em log. O banco é compartilhado: cada teste devolve
 * a configuração e o CPF por usuário ao estado inicial.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
class FnrhSettingsClearApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val integrationLog: FnrhIntegrationLog,
) {
    private val path = "/api/v1/fnrh/settings"
    private val clearPath = "$path/clear-credentials"
    private val password = "s3cr3t-Pass-do-Serpro"
    private val cpf = "529.982.247-25"
    private val requesterId = UUID.randomUUID()

    private val fullBody =
        mapOf(
            "environment" to "HOMOLOGATION",
            "username" to "hotel-user",
            "password" to password,
            "defaultRequesterCpf" to cpf,
            "enabled" to true,
        )

    @BeforeEach
    @AfterEach
    fun resetSeed() {
        jdbc
            .sql(
                "UPDATE fnrh.settings SET environment = 'HOMOLOGATION', username = NULL, password = NULL, " +
                    "default_cpf = NULL, enabled = FALSE",
            ).update()
        jdbc.sql("DELETE FROM fnrh.requester WHERE user_id = :id").param("id", requesterId).update()
    }

    @Test
    fun `clearing empties the columns, turns the integration off and the status reads not configured`(): Unit =
        with(iam) {
            val etag = configure()

            val response = call(POST, clearPath, asAdmin(), ifMatch = etag)

            val body = response.ok()
            body["username"].isNull shouldBe true
            body["passwordSet"].asBoolean() shouldBe false
            body["defaultRequesterCpf"].isNull shouldBe true
            body["enabled"].asBoolean() shouldBe false
            body["environment"].asString() shouldBe "HOMOLOGATION"
            response.getHeader(HttpHeaders.ETAG).shouldNotBeNull() shouldNotBe etag
            row() shouldBe Row(null, null, null, false)
            // O registro append-only precisa de um sucesso: sem nenhum, a leitura do status falha.
            integrationLog.record(successCall())
            val status = call(GET, "/api/v1/fnrh/status", asAdmin()).ok()
            status["configured"].asBoolean() shouldBe false
            status["enabled"].asBoolean() shouldBe false
        }

    @Test
    fun `403 without permission, 401 anonymous, 428 without If-Match, 412 when stale, and nothing changes`(): Unit =
        with(iam) {
            val etag = configure()
            val staleEtag = "\"999999\""

            for (roleName in listOf("Gerente", "Recepcionista")) {
                val user = userWithTemplateRole(roleName)
                call(POST, clearPath, asTestUser(user), ifMatch = etag).problem(403, "PERMISSION_DENIED")
                row().isConfigured() shouldBe true
            }
            mockMvc
                .post(clearPath)
                .andReturn()
                .response.status shouldBe 401
            row().isConfigured() shouldBe true
            call(POST, clearPath, asAdmin()).problem(428, "PRECONDITION_REQUIRED")
            row().isConfigured() shouldBe true
            call(POST, clearPath, asAdmin(), ifMatch = staleEtag).problem(412, "PRECONDITION_FAILED")
            row().isConfigured() shouldBe true
            call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe etag
        }

    @Test
    fun `the audit trail records the clear and who did it, without username, password or CPF`(): Unit =
        with(iam) {
            val etag = configure()
            val before = clearEntries()

            call(POST, clearPath, asAdmin(), ifMatch = etag).ok()

            val entries = clearEntries()
            entries.size shouldBe before.size + 1
            val entry = entries.last()
            entry.userId shouldBe adminId()
            entry.entity shouldBe "fnrh.settings"
            entry.before.shouldNotBeNull() shouldContain "\"passwordSet\": true"
            entry.after.shouldNotBeNull() shouldContain "\"passwordSet\": false"
            entry.after shouldContain "\"enabled\": false"
            for (text in listOf(entry.before, entry.after)) {
                text shouldNotContain password
                text shouldNotContain "hotel-user"
                text shouldNotContain "52998224725"
                text shouldNotContain cpf
            }
        }

    @Test
    fun `re-enabling without new credentials is 422 FNRH_SETTINGS_INCOMPLETE, with new ones it works`(): Unit =
        with(iam) {
            val cleared = call(POST, clearPath, asAdmin(), ifMatch = configure())
            cleared.ok()
            val etag = cleared.getHeader(HttpHeaders.ETAG)

            call(PUT, path, asAdmin(), mapOf("environment" to "HOMOLOGATION", "enabled" to true), ifMatch = etag)
                .problem(422, "FNRH_SETTINGS_INCOMPLETE")
            call(PUT, path, asAdmin(), fullBody, ifMatch = etag).ok()

            row().enabled shouldBe true
        }

    @Test
    fun `clearing twice keeps the ETag, and the ETag from before the first clear is 412`(): Unit =
        with(iam) {
            val first = configure()
            val before = clearEntries().size
            val cleared = call(POST, clearPath, asAdmin(), ifMatch = first)
            cleared.ok()
            val second = cleared.getHeader(HttpHeaders.ETAG).shouldNotBeNull()

            val again = call(POST, clearPath, asAdmin(), ifMatch = second)

            again.ok()["enabled"].asBoolean() shouldBe false
            again.getHeader(HttpHeaders.ETAG) shouldBe second
            call(POST, clearPath, asAdmin(), ifMatch = first).problem(412, "PRECONDITION_FAILED")
            clearEntries().size shouldBe before + 1
        }

    @Test
    fun `clearing a disabled configuration that still holds credentials empties it`(): Unit =
        with(iam) {
            val configured = call(PUT, path, asAdmin(), fullBody + ("enabled" to false), ifMatch = etagOf())
            configured.ok()
            row().password.shouldNotBeNull()

            val response = call(POST, clearPath, asAdmin(), ifMatch = configured.getHeader(HttpHeaders.ETAG))

            response.ok()["passwordSet"].asBoolean() shouldBe false
            row() shouldBe Row(null, null, null, false)
        }

    @Test
    fun `clearing keeps the environment and the per-user requester CPF`(): Unit =
        with(iam) {
            val configured = call(PUT, path, asAdmin(), fullBody + ("environment" to "PRODUCTION"), ifMatch = etagOf())
            configured.ok()
            call(PUT, "/api/v1/fnrh/requesters/$requesterId", asAdmin(), mapOf("cpf" to cpf)).ok()

            val response = call(POST, clearPath, asAdmin(), ifMatch = configured.getHeader(HttpHeaders.ETAG))

            response.ok()["environment"].asString() shouldBe "PRODUCTION"
            jdbc
                .sql("SELECT environment FROM fnrh.settings")
                .query(String::class.java)
                .single() shouldBe "PRODUCTION"
            call(GET, "/api/v1/fnrh/requesters", asAdmin())
                .ok()
                .values()
                .map { it["userId"].asString() to it["cpf"].asString() }
                .filter { it.first == requesterId.toString() } shouldContainExactly
                listOf(requesterId.toString() to "***.***.***-25")
        }

    @Test
    fun `no clear, accepted or refused, writes the username, the password or the CPF to the logs`(
        output: CapturedOutput,
    ): Unit =
        with(iam) {
            val logStart = output.all.length
            val etag = configure()

            call(POST, clearPath, asAdmin(), ifMatch = "\"999999\"").status shouldBe 412
            call(POST, clearPath, asAdmin()).status shouldBe 428
            val cleared = call(POST, clearPath, asAdmin(), ifMatch = etag)
            cleared.ok()
            call(POST, clearPath, asAdmin(), ifMatch = cleared.getHeader(HttpHeaders.ETAG)).ok()

            val logs = output.all.substring(logStart)
            logs shouldNotContain password
            logs shouldNotContain "hotel-user"
            logs shouldNotContain "52998224725"
            logs shouldNotContain cpf
        }

    private fun successCall() =
        FnrhCall("registerReservation", FnrhEnvironment.HOMOLOGATION, "SUCCESS", 200, 5, "clear-test", null)

    private data class Row(
        val username: String?,
        val password: String?,
        val defaultCpf: String?,
        val enabled: Boolean,
    ) {
        fun isConfigured() = username != null && password != null && defaultCpf != null && enabled
    }

    private fun row(): Row =
        jdbc
            .sql("SELECT username, password, default_cpf, enabled FROM fnrh.settings")
            .query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }
            .single()

    private data class AuditRow(
        val userId: UUID?,
        val entity: String?,
        val before: String?,
        val after: String?,
    )

    private fun clearEntries(): List<AuditRow> =
        jdbc
            .sql(
                "SELECT user_id, entity_type, CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                    "FROM audit.audit_entry WHERE action = 'fnrh.settings.clear-credentials' " +
                    "ORDER BY occurred_at, id",
            ).query { rs, _ ->
                AuditRow(
                    rs.getObject("user_id", UUID::class.java),
                    rs.getString("entity_type"),
                    rs.getString("b"),
                    rs.getString("a"),
                )
            }.list()

    /** Grava a configuração completa e ligada e devolve o `ETag` resultante. */
    private fun IamTestSupport.configure(): String {
        val response = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
        response.ok()
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun IamTestSupport.etagOf(): String {
        val response: MockHttpServletResponse = call(GET, path, asAdmin())
        response.status shouldBe 200
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun IamTestSupport.userWithTemplateRole(roleName: String): TestUser {
        val roleId =
            jdbc
                .sql("SELECT id FROM iam.role WHERE name = :name AND active")
                .param("name", roleName)
                .query(UUID::class.java)
                .single()
        return createUser().also { assignRole(it, RoleId(roleId)) }
    }
}
