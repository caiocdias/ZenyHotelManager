package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
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
import java.util.UUID

/**
 * Ataques do testador ao status e aos gauges da FNRH (M5 E07, Task 5): permissões com os papéis reais, 401, os gauges
 * acompanhando o banco e o ambiente, e um resultado desconhecido na tabela.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhStatusApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val integrationLog: FnrhIntegrationLog,
    @Autowired private val registry: MeterRegistry,
) {
    private val path = "/api/v1/fnrh/status"

    @BeforeEach
    fun setUp() {
        iam.enableFnrh()
    }

    @AfterEach
    fun tearDown() {
        jdbc.resetFnrhSettings()
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

    private fun gauge(name: String): Double = registry.find(name).gauge()!!.value()

    private fun call(
        environment: FnrhEnvironment,
        outcome: String,
        http: Int,
        ref: String,
    ) = FnrhCall("registerReservation", environment, outcome, http, 3, ref, null)

    @Test
    fun `an anonymous caller gets 401`() {
        mockMvc
            .get(path)
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `the real Manager and Receptionist roles read the status and the Room Attendant gets 403`(): Unit =
        with(iam) {
            for (role in listOf("Gerente", "Recepcionista")) {
                val body = call(GET, path, asTestUser(userWithTemplateRole(role))).ok()
                body["environment"].asString() shouldBe "HOMOLOGATION"
            }
            call(GET, path, asTestUser(userWithTemplateRole("Camareira"))).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `the gauges follow the database and the current environment`(): Unit =
        with(iam) {
            integrationLog.record(call(FnrhEnvironment.HOMOLOGATION, "SUCCESS", 200, "g-1"))
            integrationLog.record(call(FnrhEnvironment.HOMOLOGATION, "UNAVAILABLE", 503, "g-2"))
            val body = call(GET, path, asAdmin()).ok()
            val success = Instant.parse(body["lastSuccessAt"].asString()).epochSecond.toDouble()
            val failure = Instant.parse(body["lastFailureAt"].asString()).epochSecond.toDouble()

            gauge("zeny.fnrh.last.success") shouldBe success
            gauge("zeny.fnrh.last.failure") shouldBe failure
            gauge("zeny.fnrh.configured") shouldBe 1.0

            integrationLog.record(call(FnrhEnvironment.PRODUCTION, "SUCCESS", 200, "g-3"))
            gauge("zeny.fnrh.last.success") shouldBe success

            jdbc.resetFnrhSettings()
            gauge("zeny.fnrh.configured") shouldBe 0.0
        }

    @Test
    fun `an outcome the code does not know does not break the status and keeps the failure instant`(): Unit =
        with(iam) {
            integrationLog.record(call(FnrhEnvironment.HOMOLOGATION, "FUTURE_OUTCOME", 418, "g-4"))

            val body = call(GET, path, asAdmin()).ok()

            body["lastFailureAt"].isNull shouldBe false
        }
}
