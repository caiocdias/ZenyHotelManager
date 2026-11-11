package com.zeny.hotel.fnrh

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Ataques do testador à ação de limpar a credencial da FNRH (M5 E16, Task 2): segredos com caracteres especiais
 * em resposta, trilha e log, `If-Match` malformado, corpo e métodos inesperados, corrida com o `PUT`, ressurreição
 * da senha, contrato publicado e status/métrica. O banco é compartilhado: cada teste devolve a configuração ao
 * estado inicial.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
class FnrhSettingsClearApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val registry: MeterRegistry,
) {
    private val path = "/api/v1/fnrh/settings"
    private val clearPath = "$path/clear-credentials"
    private val username = "usér-ção'\"<>&;--名前"
    private val password = "Pä\$\$w0rd\"'\\%_;--😀Ωmarcador"
    private val cpf = "529.982.247-25"

    private val fullBody =
        mapOf(
            "environment" to "HOMOLOGATION",
            "username" to username,
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
    fun `special and unicode secrets never appear in the response, the problems, the trail or the logs`(
        output: CapturedOutput,
    ): Unit =
        with(iam) {
            val logStart = output.all.length
            val etag = configure()
            val responses = mutableListOf<MockHttpServletResponse>()
            responses += call(POST, clearPath, asAdmin(), ifMatch = "\"999999\"")
            responses += call(POST, clearPath, asAdmin())
            val cleared = call(POST, clearPath, asAdmin(), ifMatch = etag)
            responses += cleared
            responses += call(POST, clearPath, asAdmin(), ifMatch = cleared.getHeader(HttpHeaders.ETAG))

            for (r in responses) {
                val text = r.getContentAsString(Charsets.UTF_8)
                for (secret in listOf(password, username, "52998224725", cpf, "Pä", "名前")) text shouldNotContain secret
            }
            val trail =
                jdbc
                    .sql(
                        "SELECT CAST(before_state AS text) || CAST(after_state AS text) FROM audit.audit_entry " +
                            "WHERE action = 'fnrh.settings.clear-credentials'",
                    ).query(String::class.java)
                    .list()
            trail.isNotEmpty() shouldBe true
            for (text in trail) {
                for (secret in listOf(password, username, "52998224725", cpf, "Pä", "名前")) text shouldNotContain secret
                text shouldNotContain "\"username\""
                text shouldNotContain "\"password\""
            }
            val logs = output.all.substring(logStart)
            for (secret in listOf(password, username, "52998224725", cpf, "名前")) logs shouldNotContain secret
        }

    @Test
    fun `an If-Match that is not a single strong ETag is 412, blank is 428, and nothing changes`(): Unit =
        with(iam) {
            val etag = configure()
            val before = clearEntries()
            val bare = etag.trim('"')
            val bad =
                listOf(
                    bare,
                    "W/$etag",
                    "$etag, \"999\"",
                    "$etag,$etag",
                    "*",
                    "abc",
                    "\"-1\"",
                    "\"\"",
                    "\"1.0\"",
                    "\"99999999999999999999\"",
                    "'$bare'",
                )

            for (header in bad) call(POST, clearPath, asAdmin(), ifMatch = header).problem(412, "PRECONDITION_FAILED")
            call(POST, clearPath, asAdmin(), ifMatch = "   ").problem(428, "PRECONDITION_REQUIRED")
            call(POST, clearPath, asAdmin()).problem(428, "PRECONDITION_REQUIRED")

            row().isConfigured() shouldBe true
            call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe etag
            clearEntries() shouldBe before
        }

    @Test
    fun `refused clears leave no trail entry`(): Unit =
        with(iam) {
            val etag = configure()
            val before = clearEntries()

            call(POST, clearPath, asAdmin(), ifMatch = "\"999999\"").status shouldBe 412
            call(POST, clearPath, asAdmin()).status shouldBe 428
            mockMvc
                .request(POST, clearPath)
                .andReturn()
                .response.status shouldBe 401

            clearEntries() shouldBe before
            call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe etag
        }

    @Test
    fun `a body sent to the action is ignored and never echoed, valid or not`(): Unit =
        with(iam) {
            var etag = configure()
            val bodies =
                listOf(
                    """{"username":"$username","password":"$password","enabled":true}""",
                    """{"password":"$password","defaultRequesterCpf":"$cpf","environment":""",
                    "texto solto com $password",
                )
            for ((index, content) in bodies.withIndex()) {
                val response =
                    mockMvc
                        .request(POST, clearPath) {
                            contentType = if (index == 2) MediaType.TEXT_PLAIN else MediaType.APPLICATION_JSON
                            header(HttpHeaders.IF_MATCH, etag)
                            with(iam.asAdmin())
                            this.content = content
                        }.andReturn()
                        .response

                response.status shouldBe 200
                val text = response.getContentAsString(Charsets.UTF_8)
                text shouldNotContain password
                text shouldNotContain cpf
                row() shouldBe Row(null, null, null, false)
                resetSeed()
                etag = configure()
            }
        }

    @Test
    fun `GET, PUT, PATCH and DELETE on the clear route are refused without a 5xx and change nothing`(): Unit =
        with(iam) {
            val etag = configure()
            val before = clearEntries()

            for (method in listOf(GET, PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                val response = call(method, clearPath, asAdmin(), ifMatch = etag)
                response.status shouldBe 405
                response.getContentAsString(Charsets.UTF_8) shouldNotContain password
            }
            row().isConfigured() shouldBe true
            call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe etag
            clearEntries() shouldBe before
        }

    @Test
    fun `a clear and a PUT racing on the same ETag end with one 200 and one 409 or 412, never a 500`(): Unit =
        with(iam) {
            repeat(ROUNDS) { round ->
                resetSeed()
                val etag = configure()
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                try {
                    val clear =
                        pool.submit<MockHttpServletResponse> {
                            start.await()
                            call(POST, clearPath, asAdmin(), ifMatch = etag)
                        }
                    val put =
                        pool.submit<MockHttpServletResponse> {
                            start.await()
                            call(PUT, path, asAdmin(), fullBody + ("username" to "racer-$round"), ifMatch = etag)
                        }
                    start.countDown()
                    val clearStatus = clear.get().status
                    val putStatus = put.get().status

                    withClue("round $round: clear=$clearStatus put=$putStatus") {
                        listOf(clearStatus, putStatus).count { it == 200 } shouldBe 1
                        (listOf(clearStatus, putStatus).single { it != 200 } in listOf(409, 412)) shouldBe true
                        if (clearStatus == 200) {
                            row() shouldBe Row(null, null, null, false)
                        } else {
                            row().isConfigured() shouldBe true
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }
            }
        }

    @Test
    fun `a PUT after the clear does not bring the old password back`(): Unit =
        with(iam) {
            val cleared = call(POST, clearPath, asAdmin(), ifMatch = configure())
            cleared.ok()
            val etag = cleared.getHeader(HttpHeaders.ETAG)
            val noPassword = fullBody - "password"

            call(PUT, path, asAdmin(), noPassword, ifMatch = etag).problem(422, "FNRH_SETTINGS_INCOMPLETE")
            row().password shouldBe null
            val off = call(PUT, path, asAdmin(), noPassword + ("enabled" to false), ifMatch = etag)
            off.ok()["passwordSet"].asBoolean() shouldBe false
            row().password shouldBe null
            call(PUT, path, asAdmin(), noPassword, ifMatch = off.getHeader(HttpHeaders.ETAG))
                .problem(422, "FNRH_SETTINGS_INCOMPLETE")
            call(GET, path, asAdmin()).ok()["passwordSet"].asBoolean() shouldBe false
        }

    @Test
    fun `clearing a partially filled disabled configuration empties it and changes the ETag`(): Unit =
        with(iam) {
            val partial =
                call(
                    PUT,
                    path,
                    asAdmin(),
                    mapOf(
                        "environment" to "PRODUCTION",
                        "username" to username,
                        "password" to password,
                        "enabled" to false,
                    ),
                    ifMatch = etagOf(),
                )
            partial.ok()
            val etag = partial.getHeader(HttpHeaders.ETAG).shouldNotBeNull()

            val response = call(POST, clearPath, asAdmin(), ifMatch = etag)

            response.ok()["username"].isNull shouldBe true
            response.getHeader(HttpHeaders.ETAG) shouldNotBe etag
            row() shouldBe Row(null, null, null, false)
            val env = jdbc.sql("SELECT environment FROM fnrh.settings").query(String::class.java).single()
            env shouldBe "PRODUCTION"
        }

    @Test
    fun `the status and the configured gauge read not configured after the clear`(): Unit =
        with(iam) {
            configure()
            call(GET, "/api/v1/fnrh/status", asAdmin()).ok()["configured"].asBoolean() shouldBe true
            registry.get("zeny.fnrh.configured").gauge().value() shouldBe 1.0
            val etag = etagOf()

            call(POST, clearPath, asAdmin(), ifMatch = etag).ok()

            val status = call(GET, "/api/v1/fnrh/status", asAdmin()).ok()
            status["configured"].asBoolean() shouldBe false
            status["enabled"].asBoolean() shouldBe false
            status["environment"].asString() shouldBe "HOMOLOGATION"
            registry.get("zeny.fnrh.configured").gauge().value() shouldBe 0.0
        }

    @Test
    fun `the published contract describes the clear action with If-Match, no request body and bearer security`() {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val start = lines.indexOf("  /api/v1/fnrh/settings/clear-credentials:")
        (start >= 0) shouldBe true
        val end =
            (start + 1 until lines.size).first {
                lines[it].startsWith("  /") ||
                    lines[it].startsWith("  components")
            }
        val block = lines.subList(start, end).joinToString("\n")

        block shouldContain "    post:"
        block shouldContain "operationId: clearFnrhCredentials"
        block shouldContain "name: If-Match"
        block shouldContain "in: header"
        block shouldContain "\"200\":"
        block shouldContain "FnrhSettingsResponse"
        block shouldContain "default:"
        block shouldContain "application/problem+json"
        block shouldNotContain "requestBody"
        block shouldNotContain "security: []"
        block shouldNotContain "    get:"
        block shouldNotContain "    put:"
        block shouldNotContain "    delete:"
        // O contrato inteiro continua exigindo o bearer por padrão (a operação não o desliga).
        lines.take(30).contains("- bearerAuth: []") shouldBe true
    }

    @Test
    fun `an anonymous clear with a valid ETag is 401, not 403 nor 428`(): Unit =
        with(iam) {
            val etag = configure()

            val response =
                mockMvc
                    .request(POST, clearPath) { header(HttpHeaders.IF_MATCH, etag) }
                    .andReturn()
                    .response

            response.status shouldBe 401
            row().isConfigured() shouldBe true
        }

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

    private fun clearEntries(): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'fnrh.settings.clear-credentials'")
            .query(Int::class.java)
            .single()

    private fun IamTestSupport.configure(): String {
        val response = call(PUT, path, asAdmin(), fullBody, ifMatch = etagOf())
        response.ok()
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun IamTestSupport.etagOf(): String {
        val response = call(GET, path, asAdmin())
        response.status shouldBe 200
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private companion object {
        const val ROUNDS = 8
    }
}
