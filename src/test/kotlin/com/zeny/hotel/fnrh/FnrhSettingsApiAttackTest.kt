package com.zeny.hotel.fnrh

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Ataques do testador à API da configuração da FNRH (M5 E07, Task 2): autenticação e papéis reais, `If-Match`
 * malformado, validação de borda, idempotência, concorrência e vazamento de senha e CPF em resposta, trilha e log.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
class FnrhSettingsApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    private val path = "/api/v1/fnrh/settings"
    private val password = "Senha-Ultra-Secreta-9X"
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
    fun `an anonymous caller gets 401 on GET and PUT`() {
        mockMvc
            .get(path)
            .andReturn()
            .response.status shouldBe 401
        mockMvc
            .put(path) {
                contentType = MediaType.APPLICATION_JSON
                header(HttpHeaders.IF_MATCH, "\"0\"")
                content = """{"environment":"HOMOLOGATION","enabled":false}"""
            }.andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `the real Manager and Receptionist roles are refused with 403 on GET and PUT`(): Unit =
        with(iam) {
            val etag = etagOf()
            for (roleName in listOf("Gerente", "Recepcionista")) {
                val user = userWithTemplateRole(roleName)

                call(GET, path, asTestUser(user)).problem(403, "PERMISSION_DENIED")
                call(PUT, path, asTestUser(user), fullBody, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            }
            etagOf() shouldBe etag
        }

    @Test
    fun `an If-Match without quotes, a weak one, a list and a star are all 412 and change nothing`(): Unit =
        with(iam) {
            val etag = etagOf()
            val bare = etag.trim('"')

            for (bad in listOf(bare, "W/$etag", "$etag, \"999\"", "*", "abc")) {
                call(PUT, path, asAdmin(), fullBody, ifMatch = bad).problem(412, "PRECONDITION_FAILED")
            }
            call(PUT, path, asAdmin(), fullBody, ifMatch = "   ").problem(428, "PRECONDITION_REQUIRED")
            etagOf() shouldBe etag
        }

    @Test
    fun `an unknown environment, a missing environment and a missing enabled are 400, not 500`(): Unit =
        with(iam) {
            val etag = etagOf()
            val bodies =
                listOf(
                    fullBody + ("environment" to "STAGING"),
                    fullBody + ("environment" to "homologation"),
                    fullBody + ("environment" to null),
                    fullBody - "environment",
                    fullBody - "enabled",
                    fullBody + ("enabled" to null),
                    fullBody + ("enabled" to "talvez"),
                )

            for (body in bodies) {
                val response = call(PUT, path, asAdmin(), body, ifMatch = etag)
                response.status shouldBe 400
                val text = response.getContentAsString(Charsets.UTF_8)
                text shouldNotContain password
                text shouldNotContain "52998224725"
                text shouldNotContain cpf
            }
            etagOf() shouldBe etag
        }

    @Test
    fun `username and password above 200 characters are 400 on the field, 200 exactly is accepted`(): Unit =
        with(iam) {
            val etag = etagOf()
            val longUser = call(PUT, path, asAdmin(), fullBody + ("username" to "u".repeat(201)), ifMatch = etag)
            fieldsOf(longUser) shouldContainExactly listOf("username")
            val longPassword = "p".repeat(201)
            val tooLong = call(PUT, path, asAdmin(), fullBody + ("password" to longPassword), ifMatch = etag)
            fieldsOf(tooLong) shouldContainExactly listOf("password")
            tooLong.getContentAsString(Charsets.UTF_8) shouldNotContain longPassword
            etagOf() shouldBe etag

            val edge = fullBody + ("username" to "u".repeat(200)) + ("password" to "p".repeat(200))

            call(PUT, path, asAdmin(), edge, ifMatch = etag).ok()["username"].asString().length shouldBe 200
        }

    @Test
    fun `malformed CPFs are 400 without echo and change nothing`(): Unit =
        with(iam) {
            val etag = etagOf()

            for (bad in listOf("abc", "123", "529.982.247-26", "52998224726", "5299822472500", "", "   ")) {
                val response = call(PUT, path, asAdmin(), fullBody + ("defaultRequesterCpf" to bad), ifMatch = etag)
                response.status shouldBe 400
                val text = response.getContentAsString(Charsets.UTF_8)
                if (bad.isNotBlank()) text shouldNotContain bad
                text shouldNotContain password
            }
            etagOf() shouldBe etag
        }

    @Test
    fun `a CPF without punctuation is accepted and shown masked the same way`(): Unit =
        with(iam) {
            val digitsOnly = fullBody + ("defaultRequesterCpf" to "52998224725")

            val response = call(PUT, path, asAdmin(), digitsOnly, ifMatch = etagOf())

            response.ok()["defaultRequesterCpf"].asString() shouldBe "***.***.***-25"
        }

    @Test
    fun `a PUT with the same values and no password keeps the ETag`(): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()
            val etag = first.getHeader(HttpHeaders.ETAG).shouldNotBeNull()

            val again = call(PUT, path, asAdmin(), fullBody - "password", ifMatch = etag)

            again.ok()["passwordSet"].asBoolean() shouldBe true
            again.getHeader(HttpHeaders.ETAG) shouldBe etag
            etagOf() shouldBe etag
        }

    @Test
    fun `clearing the username or the CPF while enabled is 422 and keeps the old state`(): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()
            val etag = first.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            val keep = fullBody - "password"

            call(PUT, path, asAdmin(), keep + ("username" to null), ifMatch = etag)
                .problem(422, "FNRH_SETTINGS_INCOMPLETE")
            call(PUT, path, asAdmin(), keep + ("defaultRequesterCpf" to null), ifMatch = etag)
                .problem(422, "FNRH_SETTINGS_INCOMPLETE")
            call(PUT, path, asAdmin(), keep + ("username" to "   "), ifMatch = etag)
                .problem(422, "FNRH_SETTINGS_INCOMPLETE")

            etagOf() shouldBe etag
            val off = keep + ("enabled" to false) + ("username" to null)
            call(PUT, path, asAdmin(), off, ifMatch = etag).ok()["enabled"].asBoolean() shouldBe false
        }

    @Test
    fun `a 422 problem and a malformed JSON never echo the password nor the CPF`(): Unit =
        with(iam) {
            val etag = etagOf()
            val domainRefusal = call(PUT, path, asAdmin(), fullBody - "username", ifMatch = etag)
            val malformed =
                mockMvc
                    .put(path) {
                        contentType = MediaType.APPLICATION_JSON
                        header(HttpHeaders.IF_MATCH, etag)
                        with(asAdmin())
                        content = """{"password":"$password","defaultRequesterCpf":"$cpf","environment":"""
                    }.andReturn()
                    .response

            domainRefusal.status shouldBe 422
            malformed.status shouldBe 400
            for (r in listOf(domainRefusal, malformed)) {
                val text = r.getContentAsString(Charsets.UTF_8)
                text shouldNotContain password
                text shouldNotContain "52998224725"
                text shouldNotContain cpf
            }
        }

    @Test
    fun `no request, accepted or refused, writes the password or the CPF to the logs`(output: CapturedOutput): Unit =
        with(iam) {
            val first = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
            first.ok()
            val etag = first.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            val other = "Outra-Senha-Marcador-77"
            val production = fullBody + ("password" to other) + ("environment" to "PRODUCTION")
            call(PUT, path, asAdmin(), production, ifMatch = etag).ok()
            call(PUT, path, asAdmin(), fullBody + ("password" to other), ifMatch = "\"0\"").status shouldBe 412
            val badCpf = fullBody + ("password" to other) + ("defaultRequesterCpf" to "111.111.111-11")
            call(PUT, path, asAdmin(), badCpf, ifMatch = etag).status shouldBe 400
            call(PUT, path, asAdmin(), fullBody + ("password" to "   "), ifMatch = etag).status shouldBe 400
            call(GET, path, asAdmin()).ok()

            val logs = output.all
            logs shouldNotContain password
            logs shouldNotContain other
            logs shouldNotContain "52998224725"
            logs shouldNotContain cpf
            logs shouldNotContain "11111111111"
            logs shouldNotContain "111.111.111-11"
        }

    @Test
    fun `the stored username, password and CPF are not plain text`(): Unit =
        with(iam) {
            call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf()).ok()

            val row =
                jdbc
                    .sql("SELECT password, default_cpf, username FROM fnrh.settings")
                    .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3)) }
                    .single()

            for (value in row) {
                value.shouldNotBeNull()
                value shouldNotContain password
                value shouldNotContain "52998224725"
                value shouldNotContain "hotel-user"
            }
        }

    @Test
    fun `two concurrent PUTs with the same ETag end with one 200 and one 409 or 412, never a 500`(): Unit =
        with(iam) {
            val etag = etagOf()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val futures =
                    listOf("user-a", "user-b").map { name ->
                        pool.submit<MockHttpServletResponse> {
                            start.await()
                            call(PUT, path, asAdmin(), fullBody + ("username" to name), ifMatch = etag)
                        }
                    }
                start.countDown()
                val statuses = futures.map { it.get().status }.sorted()

                statuses.first() shouldBe 200
                (statuses.last() in listOf(409, 412)) shouldBe true
            } finally {
                pool.shutdownNow()
            }
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

    private fun IamTestSupport.fieldsOf(response: MockHttpServletResponse): List<String> =
        response.problem(400, "VALIDATION_FAILED")["errors"].values().map { it["field"].asString() }

    private fun IamTestSupport.etagOf(): String {
        val response = call(GET, path, asAdmin())
        response.status shouldBe 200
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }
}
