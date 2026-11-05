package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.infrastructure.JdbcFnrhIntegrationLog
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * O registro das chamadas à FNRH (M5 E07, D-E07-8): uma linha na tabela e uma linha de log por chamada, sem dado
 * pessoal, gravada mesmo quando o chamador desfaz a transação, e imutável. O banco é compartilhado e a tabela não
 * admite expurgo: cada teste acha as próprias linhas pela referência de correlação.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class FnrhIntegrationLogTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val gateway: FnrhGateway,
    @Autowired private val context: ApplicationContext,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val name = "MARIA DA LUZ SANTOS"
    private val cpf = "52998224725"
    private val requester = Cpf.of("52998224725")

    private val person =
        FnrhPersonData(
            name = name,
            race = "PARDA",
            disabilityOption = "SIM",
            disabilityType = "VISUAL",
            birthDate = LocalDate.parse("1990-05-17"),
            document = FnrhDocument(FnrhDocumentType.CPF, cpf),
        )

    private fun newRef() = "corr-${UUID.randomUUID()}"

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

    @Test
    fun `there is a single integration log bean and it is the JDBC one`() {
        val beans = context.getBeansOfType(FnrhIntegrationLog::class.java)

        beans.size shouldBe 1
        AopUtils.getTargetClass(beans.values.single()) shouldBe JdbcFnrhIntegrationLog::class.java
    }

    @Test
    fun `a 400 that echoes the name and CPF leaves neither in the log nor in the table, with one row and one line`(
        output: CapturedOutput,
    ) {
        FnrhTestServer.stubAny(400, """{"message":"Pessoa $name CPF $cpf invalida"}""")
        val correlation = newRef()

        shouldThrow<FnrhGatewayException> { gateway.registerPerson(person, requester, correlation) }

        val rows = rows(correlation)
        rows shouldHaveSize 1
        rows.single() shouldBe
            mapOf(
                "operation" to "registerPerson",
                "environment" to "HOMOLOGATION",
                "outcome" to "VALIDATION",
                "http_status" to 400,
            )
        val lines = output.all.lines().filter { correlation in it }
        lines shouldHaveSize 1
        lines.single() shouldContain " WARN "
        val everything = output.all + "\n" + wholeTable()
        listOf(name, cpf, "PARDA", "VISUAL", FnrhTestServer.PASSWORD).forEach { everything shouldNotContain it }
    }

    @Test
    fun `a success leaves one row and one INFO line with the call fields and no URL`(output: CapturedOutput) {
        FnrhTestServer.stubRegisterPerson_v2_4("person-1", cpf)
        val correlation = newRef()

        gateway.registerPerson(person, requester, correlation)

        val rows = rows(correlation)
        rows shouldHaveSize 1
        rows.single()["outcome"] shouldBe "SUCCESS"
        rows.single()["http_status"] shouldBe 200
        val lines = output.all.lines().filter { correlation in it }
        lines shouldHaveSize 1
        val line = lines.single()
        line shouldContain " INFO "
        listOf("registerPerson", "HOMOLOGATION", "SUCCESS", "200").forEach { line shouldContain it }
        line shouldNotContain "http:"
        line shouldNotContain "/pessoas"
    }

    @Test
    fun `the row stays when the caller transaction rolls back`() {
        FnrhTestServer.stubRegisterPerson_v2_4("person-2", cpf)
        val correlation = newRef()

        transactions.executeWithoutResult { status ->
            gateway.registerPerson(person, requester, correlation)
            status.setRollbackOnly()
        }

        rows(correlation) shouldHaveSize 1
    }

    @Test
    fun `the table refuses UPDATE and DELETE`() {
        FnrhTestServer.stubRegisterPerson_v2_4("person-3", cpf)
        gateway.registerPerson(person, requester, newRef())

        shouldThrow<DataAccessException> { jdbc.sql("UPDATE fnrh.integration_call SET outcome = 'X'").update() }
        shouldThrow<DataAccessException> { jdbc.sql("DELETE FROM fnrh.integration_call").update() }
    }

    @Test
    fun `record never throws, even when the row is refused by the database`(output: CapturedOutput) {
        val log = context.getBean(FnrhIntegrationLog::class.java)
        val bad = FnrhCall("not an operation!", FnrhEnvironment.HOMOLOGATION, "SUCCESS", 200, 1, "corr-bad", null)

        log.record(bad)

        rows("corr-bad") shouldHaveSize 0
        output.all shouldContain "o registro da chamada falhou"
    }

    private fun rows(correlation: String): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT operation, environment, outcome, http_status FROM fnrh.integration_call " +
                    "WHERE correlation_ref = :ref",
            ).param("ref", correlation)
            .query { rs, _ ->
                mapOf(
                    "operation" to rs.getString("operation"),
                    "environment" to rs.getString("environment"),
                    "outcome" to rs.getString("outcome"),
                    "http_status" to rs.getObject("http_status"),
                )
            }.list()

    private fun wholeTable(): String =
        jdbc
            .sql("SELECT coalesce(string_agg(t::text, E'\\n'), '') FROM fnrh.integration_call t")
            .query(String::class.java)
            .single()
}
