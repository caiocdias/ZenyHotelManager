package com.zeny.hotel.fnrh

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse

/**
 * A configuração da FNRH pela API (M5 E07): leitura e escrita só com `fnrh.settings.manage`, `If-Match`, senha que
 * nunca volta, CPF mascarado e a trilha sem segredo. O banco é compartilhado: cada teste devolve a linha semente ao
 * estado inicial.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class FnrhSettingsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val path = "/api/v1/fnrh/settings"
    private val password = "s3cr3t-Pass-do-Serpro"
    private val cpf = "529.982.247-25"

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
    }

    @Test
    fun `the seed reads as homologation, off, with no password and no CPF`(): Unit =
        with(iam) {
            val response = call(GET, path, asAdmin())

            val body = response.ok()
            body["environment"].asString() shouldBe "HOMOLOGATION"
            body["username"].isNull shouldBe true
            body["passwordSet"].asBoolean() shouldBe false
            body["defaultRequesterCpf"].isNull shouldBe true
            body["enabled"].asBoolean() shouldBe false
            response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        }

    @Test
    fun `a full PUT is read back with the password flag and the masked CPF, never the secrets`(): Unit =
        with(iam) {
            val etag = etagOf()

            val put = call(PUT, path, asAdmin(), fullBody, ifMatch = etag)

            val written = put.ok()
            val newEtag = put.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            newEtag shouldNotBe etag
            val read = call(GET, path, asAdmin())
            val body = read.ok()
            read.getHeader(HttpHeaders.ETAG) shouldBe newEtag
            body["environment"].asString() shouldBe "HOMOLOGATION"
            body["username"].asString() shouldBe "hotel-user"
            body["passwordSet"].asBoolean() shouldBe true
            body["defaultRequesterCpf"].asString() shouldBe "***.***.***-25"
            body["enabled"].asBoolean() shouldBe true
            written["defaultRequesterCpf"].asString() shouldBe "***.***.***-25"
            for (text in listOf(put, read).map { it.getContentAsString(Charsets.UTF_8) }) {
                text shouldNotContain password
                text shouldNotContain "52998224725"
                text shouldNotContain cpf
                text shouldNotContain "password\""
            }
        }

    @Test
    fun `a PUT without password in the same environment keeps the password`(): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()

            val second =
                call(
                    PUT,
                    path,
                    asAdmin(),
                    (fullBody - "password") + ("username" to "outro-user"),
                    ifMatch = first.getHeader(HttpHeaders.ETAG),
                )

            val body = second.ok()
            body["passwordSet"].asBoolean() shouldBe true
            body["username"].asString() shouldBe "outro-user"
            body["enabled"].asBoolean() shouldBe true
        }

    @Test
    fun `changing the environment without a new password is refused with 422`(): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()
            val etag = first.getHeader(HttpHeaders.ETAG)

            call(
                PUT,
                path,
                asAdmin(),
                (fullBody - "password") + ("environment" to "PRODUCTION"),
                ifMatch = etag,
            ).problem(422, "FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE")
            // Com a senha de novo, a troca passa.
            call(
                PUT,
                path,
                asAdmin(),
                fullBody + ("environment" to "PRODUCTION") + ("password" to "nova-senha-de-producao"),
                ifMatch = etag,
            ).ok()["environment"]
                .asString() shouldBe "PRODUCTION"
        }

    @Test
    fun `enabling without user, password or CPF is refused with 422 and nothing changes`(): Unit =
        with(iam) {
            val etag = etagOf()

            call(
                PUT,
                path,
                asAdmin(),
                mapOf("environment" to "HOMOLOGATION", "username" to "hotel-user", "enabled" to true),
                ifMatch = etag,
            ).problem(422, "FNRH_SETTINGS_INCOMPLETE")

            etagOf() shouldBe etag
        }

    @Test
    fun `a blank password is a 400 on the field, a missing one is not`(): Unit =
        with(iam) {
            val etag = etagOf()

            for (blank in listOf("", "   ")) {
                val problem =
                    call(PUT, path, asAdmin(), fullBody + ("password" to blank), ifMatch = etag)
                        .problem(400, "VALIDATION_FAILED")
                problem["errors"].values().map { it["field"].asString() } shouldContainExactly listOf("password")
            }
            etagOf() shouldBe etag
        }

    @Test
    fun `an invalid CPF is a 400 on the field without echoing the value`(): Unit =
        with(iam) {
            val etag = etagOf()

            val invalid = fullBody + ("defaultRequesterCpf" to "111.111.111-11")
            val response = call(PUT, path, asAdmin(), invalid, ifMatch = etag)

            val problem = response.problem(400, "VALIDATION_FAILED")
            problem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContainExactly
                listOf("defaultRequesterCpf" to "INVALID_FORMAT")
            val text = response.getContentAsString(Charsets.UTF_8)
            text shouldNotContain "111.111.111-11"
            text shouldNotContain "11111111111"
            text shouldNotContain password
        }

    @Test
    fun `a PUT without If-Match is 428 and with a stale one is 412`(): Unit =
        with(iam) {
            call(PUT, path, asAdmin(), fullBody).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), fullBody, ifMatch = "\"999999\"").problem(412, "PRECONDITION_FAILED")
            val etag = etagOf()
            call(PUT, path, asAdmin(), fullBody, ifMatch = etag).ok()

            call(PUT, path, asAdmin(), fullBody + ("username" to "outro"), ifMatch = etag)
                .problem(412, "PRECONDITION_FAILED")
        }

    @Test
    fun `a receptionist and an anonymous caller cannot read or write`(): Unit =
        with(iam) {
            val receptionist =
                iam.createUserWith(
                    TestGrant(definition(FnrhPermissions.DOMAINS_READ), ALL),
                    TestGrant(definition(FnrhPermissions.SUBMISSIONS_READ), ALL),
                )
            val etag = etagOf()

            call(GET, path, asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asTestUser(receptionist), fullBody, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            etagOf() shouldBe etag
        }

    @Test
    fun `a user with fnrh settings manage can read and write`(): Unit =
        with(iam) {
            val manager = iam.createUserWith(TestGrant(definition(FnrhPermissions.SETTINGS_MANAGE), ALL))

            val put = call(PUT, path, asTestUser(manager), fullBody, ifMatch = etagOf())

            put.ok()["passwordSet"].asBoolean() shouldBe true
            call(GET, path, asTestUser(manager)).ok()["enabled"].asBoolean() shouldBe true
        }

    @Test
    fun `the audit trail records the change, including a password-only one, with no password nor CPF`(): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()
            // Só a senha muda: ainda assim há uma entrada.
            call(
                PUT,
                path,
                asAdmin(),
                fullBody + ("password" to "outra-senha-secreta-123"),
                ifMatch = first.getHeader(HttpHeaders.ETAG),
            ).ok()

            val entries = auditEntries()

            entries.size shouldBe 2
            for (entry in entries) {
                (entry.before.orEmpty() + entry.after.shouldNotBeNull()).let { text ->
                    text shouldNotContain password
                    text shouldNotContain "outra-senha-secreta-123"
                    text shouldNotContain "52998224725"
                    text shouldNotContain cpf
                }
            }
            entries.last().before.shouldNotBeNull()
            entries.last().before shouldNotBe entries.last().after
        }

    private data class AuditRow(
        val before: String?,
        val after: String?,
    )

    private fun auditEntries(): List<AuditRow> =
        jdbc
            .sql(
                "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a FROM audit.audit_entry " +
                    "WHERE action = 'fnrh.settings.update' ORDER BY occurred_at DESC, id DESC LIMIT 2",
            ).query { rs, _ -> AuditRow(rs.getString("b"), rs.getString("a")) }
            .list()
            .reversed()

    private fun IamTestSupport.etagOf(): String {
        val response: MockHttpServletResponse = call(GET, path, asAdmin())
        response.status shouldBe 200
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun definition(code: PermissionCode) = FnrhPermissions.permissions.single { it.code == code }
}
