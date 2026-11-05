package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/**
 * O status da integração com a FNRH e as métricas (M5 E07): os instantes do último sucesso e da última falha do
 * ambiente atual, a permissão `fnrh.submissions.read` e as métricas `zeny.fnrh.*` no Prometheus.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhStatusApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val gateway: FnrhGateway,
    @Autowired private val integrationLog: FnrhIntegrationLog,
) {
    private val path = "/api/v1/fnrh/status"
    private val cpf = "52998224725"

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
    fun `after a success and a failure the status has both instants and the failure outcome`(): Unit =
        with(iam) {
            val before = Instant.now().minusSeconds(1)
            FnrhTestServer.stubRegisterPerson_v2_4("person-1", cpf)
            gateway.registerPerson(FnrhPersonData(name = "A"), Cpf.of(cpf), "corr-ok")
            FnrhTestServer.reset()
            FnrhTestServer.stubAny(403, "{}")
            shouldThrow<FnrhGatewayException> {
                gateway.registerPerson(FnrhPersonData(name = "A"), Cpf.of(cpf), "corr-no")
            }

            val body = call(GET, path, asAdmin()).ok()

            body["configured"].asBoolean() shouldBe true
            body["enabled"].asBoolean() shouldBe true
            body["environment"].asString() shouldBe "HOMOLOGATION"
            Instant.parse(body["lastSuccessAt"].asString()).isAfter(before) shouldBe true
            Instant.parse(body["lastFailureAt"].asString()).isAfter(before) shouldBe true
            body["lastFailureOutcome"].asString() shouldBe "FORBIDDEN"
        }

    @Test
    fun `records of the other environment do not enter the status`(): Unit =
        with(iam) {
            integrationLog.record(record("HOMOLOGATION", "SUCCESS"))
            integrationLog.record(record("HOMOLOGATION", "VALIDATION"))
            val before = call(GET, path, asAdmin()).ok()

            integrationLog.record(record("PRODUCTION", "SUCCESS"))
            integrationLog.record(record("PRODUCTION", "UNAVAILABLE"))
            val after = call(GET, path, asAdmin()).ok()

            after["lastSuccessAt"] shouldBe before["lastSuccessAt"]
            after["lastFailureAt"] shouldBe before["lastFailureAt"]
            after["lastFailureOutcome"].asString() shouldBe "VALIDATION"
        }

    @Test
    fun `the Prometheus endpoint has the FNRH metrics`() {
        FnrhTestServer.stubRegisterPerson_v2_4("person-2", cpf)
        gateway.registerPerson(FnrhPersonData(name = "A"), Cpf.of(cpf), "corr-metric")

        val text =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString

        text shouldContain "zeny_fnrh_calls_total"
        text shouldContain "operation=\"registerPerson\""
        text shouldContain "zeny_fnrh_last_success_seconds"
        text shouldContain "zeny_fnrh_last_failure_seconds"
        text shouldContain "zeny_fnrh_configured"
    }

    @Test
    fun `a user without the permission gets 403`(): Unit =
        with(iam) {
            val housekeeper = createUserWith()

            call(GET, path, asTestUser(housekeeper)).problem(403, "PERMISSION_DENIED")
        }

    private fun record(
        environment: String,
        outcome: String,
    ) = FnrhCall("registerReservation", FnrhEnvironment.valueOf(environment), outcome, 200, 5, "corr-env", null)
}
