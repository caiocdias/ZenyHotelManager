package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.PostgresContainer
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * Ataques do testador ao registro das chamadas à FNRH (M5 E07, Task 5): dado pessoal em tabela, log, status e
 * métricas; uma linha de log por chamada em cada resultado; isolamento da transação; privilégios; `record` que não
 * lança nem suja a transação do chamador; e o contador com as tags certas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // os beans que o teste ataca, injetados pelo construtor
class FnrhIntegrationLogAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val gateway: FnrhGateway,
    @Autowired private val integrationLog: FnrhIntegrationLog,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
) {
    private val name = "ZULEICA FERNANDES DA ROCHA"
    private val cpf = "52998224725"
    private val requester = Cpf.of(cpf)
    private val person =
        FnrhPersonData(
            name = name,
            race = "INDIGENA",
            disabilityOption = "SIM",
            disabilityType = "AUDITIVA",
            birthDate = LocalDate.parse("1985-03-02"),
            email = "zuleica@exemplo.com",
            phone = "11988887777",
            document = FnrhDocument(FnrhDocumentType.CPF, cpf),
        )

    private fun newRef() = "atk-${UUID.randomUUID()}"

    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
        iam.enableFnrh()
    }

    @AfterEach
    fun tearDown() {
        jdbc.resetFnrhSettings()
        FnrhTestServer.reset()
    }

    private fun count(correlation: String): Long =
        jdbc
            .sql("SELECT count(*) FROM fnrh.integration_call WHERE correlation_ref = :ref")
            .param("ref", correlation)
            .query(Long::class.java)
            .single()

    @Test
    fun `no personal data reaches the table, the log, the status or Prometheus after a 400 that echoes everything`(
        output: CapturedOutput,
    ) {
        val secrets =
            listOf(name, cpf, "INDIGENA", "AUDITIVA", "zuleica@exemplo.com", "11988887777", FnrhTestServer.PASSWORD)
        val echo = secrets.joinToString(" ")
        // O MockMvc imprime a requisição do setUp (com a senha) no log de teste; só vale o que a chamada escreveu.
        val already = output.all.length
        FnrhTestServer.stubAny(400, """{"message":"$echo"}""")

        shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, newRef()) }

        val table =
            jdbc
                .sql("SELECT coalesce(string_agg(t::text, E'\\n'), '') FROM fnrh.integration_call t")
                .query(String::class.java)
                .single()
        val status =
            with(iam) { call(HttpMethod.GET, "/api/v1/fnrh/status", asAdmin()) }
                .getContentAsString(Charsets.UTF_8)
        val prometheus =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString
        val where =
            mapOf(
                "table" to table,
                "log" to output.all.substring(already),
                "status" to status,
                "prometheus" to prometheus,
            )
        for ((place, text) in where) {
            for (secret in secrets) {
                withClue("$place leaked $secret") { text shouldNotContain secret }
            }
        }
    }

    @Test
    fun `the table has no free text column besides the constrained operation, outcome, environment and refs`() {
        val textColumns =
            jdbc
                .sql(
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'fnrh' AND table_name = 'integration_call' " +
                        "AND data_type IN ('text', 'character varying', 'json', 'jsonb')",
                ).query(String::class.java)
                .list()

        textColumns shouldContainExactlyInAnyOrder
            listOf("operation", "environment", "outcome", "correlation_ref", "remote_id")
    }

    @Test
    fun `each outcome leaves exactly one table row and one log line, INFO only for the success`(
        output: CapturedOutput,
    ) {
        val cases =
            listOf(
                200 to "SUCCESS",
                400 to "VALIDATION",
                401 to "UNAUTHORIZED",
                403 to "FORBIDDEN",
                404 to "NOT_FOUND",
                500 to "SERVER_ERROR",
            )
        for ((http, outcome) in cases) {
            FnrhTestServer.reset()
            if (http == 200) FnrhTestServer.stubRegisterPerson_v2_4("p-x", cpf) else FnrhTestServer.stubAny(http, "{}")
            val correlation = newRef()

            if (http == 200) {
                gateway.registerPerson(person, requester, correlation)
            } else {
                shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, correlation) }
            }

            count(correlation) shouldBe 1L
            val outcomeInTable =
                jdbc
                    .sql("SELECT outcome FROM fnrh.integration_call WHERE correlation_ref = :ref")
                    .param("ref", correlation)
                    .query(String::class.java)
                    .single()
            outcomeInTable shouldBe outcome
            val lines = output.all.lines().filter { correlation in it }
            withClue("HTTP $http: $lines") { lines shouldHaveSize 1 }
            lines.single() shouldContain (if (http == 200) " INFO " else " WARN ")
        }
    }

    @Test
    fun `a failed call still leaves its row when the caller transaction rolls back`() {
        FnrhTestServer.stubAny(500, "{}")
        val correlation = newRef()

        transactions.executeWithoutResult { status ->
            shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, correlation) }
            status.setRollbackOnly()
        }

        count(correlation) shouldBe 1L
    }

    @Test
    fun `the application role has no UPDATE, DELETE nor TRUNCATE privilege and TRUNCATE fails`() {
        for (privilege in listOf("UPDATE", "DELETE", "TRUNCATE")) {
            val has =
                jdbc
                    .sql("SELECT has_table_privilege(:role, 'fnrh.integration_call', :priv)")
                    .param("role", PostgresContainer.APP_USERNAME)
                    .param("priv", privilege)
                    .query(Boolean::class.java)
                    .single()
            withClue(privilege) { has shouldBe false }
        }
        shouldThrow<DataAccessException> { jdbc.sql("TRUNCATE fnrh.integration_call").update() }
    }

    @Test
    fun `record never throws on a refused row, writes a WARN without data and leaves the caller transaction usable`(
        output: CapturedOutput,
    ) {
        val secretRef = "SEGREDO-" + "x".repeat(120)
        val bad =
            FnrhCall("registerPerson", FnrhEnvironment.HOMOLOGATION, "SUCCESS", 200, 1, secretRef, "REMOTO-SECRETO")

        val result =
            transactions.execute {
                integrationLog.record(bad)
                jdbc.sql("SELECT 1").query(Int::class.java).single()
            }

        result shouldBe 1
        output.all shouldContain "o registro da chamada falhou"
        output.all shouldNotContain "SEGREDO"
        output.all shouldNotContain "REMOTO-SECRETO"
    }

    @Test
    fun `record refuses invalid values without throwing and writes nothing`() {
        val ref = newRef()
        val env = FnrhEnvironment.HOMOLOGATION
        listOf(
            FnrhCall("registerPerson", env, "SUCCESS", 200, -1, ref, null),
            FnrhCall("registerPerson", env, "SUCCESS", 99, 1, ref, null),
            FnrhCall("registerPerson", env, "SUCCESS", 600, 1, ref, null),
            FnrhCall("Maria 123", env, "SUCCESS", 200, 1, ref, null),
            FnrhCall("registerPerson", env, "success lower", 200, 1, ref, null),
        ).forEach(integrationLog::record)

        count(ref) shouldBe 0L
    }

    @Test
    fun `the calls counter has the operation, outcome and environment tags and grows by one per call`() {
        val tags = arrayOf("operation", "registerPerson", "outcome", "FORBIDDEN", "environment", "HOMOLOGATION")
        val before =
            registry
                .find("zeny.fnrh.calls")
                .tags(*tags)
                .counter()
                ?.count() ?: 0.0
        FnrhTestServer.stubAny(403, "{}")

        repeat(2) {
            shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, newRef()) }
        }

        val after =
            registry
                .find("zeny.fnrh.calls")
                .tags(*tags)
                .counter()!!
                .count()
        after - before shouldBe 2.0
        val prometheus =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString
        val line = prometheus.lines().first { it.startsWith("zeny_fnrh_calls_total") && "FORBIDDEN" in it }
        listOf("operation=\"registerPerson\"", "outcome=\"FORBIDDEN\"", "environment=\"HOMOLOGATION\"").forEach {
            line shouldContain it
        }
    }
}
