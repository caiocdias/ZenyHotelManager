package com.zeny.hotel.shared.web

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import tools.jackson.databind.json.JsonMapper

/**
 * Exposição do actuator (ARCHITECTURE §16, roadmap M0 E06): só liveness, readiness e
 * prometheus respondem, sem autenticação e sem detalhes; nenhum outro endpoint existe na web,
 * nem para um usuário autenticado. A readiness depende do banco; a liveness, não.
 */
@IntegrationTest
class ActuatorExposureTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val endpoints: PathMappedEndpoints,
    @Autowired private val healthGroups: HealthEndpointGroups,
) {
    @Test
    fun `liveness and readiness answer anonymously with the status only`() {
        listOf(LIVENESS, READINESS).forEach { path ->
            withClue(path) {
                val response = mockMvc.get(path).andReturn().response
                response.status shouldBe 200
                // Sem componentes nem detalhes: só o estado agregado.
                jsonMapper.readTree(response.contentAsString).propertyNames().toList() shouldBe listOf("status")
                jsonMapper.readTree(response.contentAsString)["status"].asString() shouldBe "UP"
            }
        }
    }

    @Test
    fun `prometheus answers anonymously with technical metrics`() {
        val response = mockMvc.get(PROMETHEUS).andReturn().response

        response.status shouldBe 200
        response.contentType!! shouldContain "text/plain"
        val body = response.contentAsString
        body shouldContain "jvm_memory_used_bytes"
        body shouldContain "hikaricp_connections"
        body shouldContain "application=\"zeny-hotel-manager\""
    }

    @Test
    fun `only health and prometheus are exposed on the web`() {
        endpoints.allPaths.toSet() shouldBe setOf("/actuator/health", "/actuator/prometheus")
        // Endpoints sensíveis nem chegam a existir como bean (acesso padrão `none`).
        SENSITIVE_ENDPOINT_BEANS.forEach { type ->
            withClue(type) { context.getBeanNamesForType(Class.forName(type)).toList().shouldBeEmpty() }
        }
    }

    @Test
    fun `readiness depends on the database and liveness does not`() {
        val readiness = healthGroups.get("readiness").shouldNotBeNull()
        val liveness = healthGroups.get("liveness").shouldNotBeNull()

        readiness.isMember("db") shouldBe true
        readiness.isMember("readinessState") shouldBe true
        liveness.isMember("db") shouldBe false
        liveness.isMember("livenessState") shouldBe true
    }

    @Test
    fun `every other actuator path is closed to anonymous requests`() {
        assertSoftly {
            CLOSED_PATHS.forEach { path ->
                withClue("GET $path") {
                    mockMvc
                        .get(path)
                        .andReturn()
                        .response.status shouldBe 401
                }
            }
            listOf(LIVENESS, READINESS, PROMETHEUS).forEach { path ->
                withClue("POST $path") {
                    mockMvc
                        .request(HttpMethod.POST, path)
                        .andReturn()
                        .response.status shouldNotBe 200
                }
            }
        }
    }

    @Test
    @WithMockUser(roles = ["ADMIN", "ACTUATOR"])
    fun `every other actuator path is closed even to an authenticated user`() {
        assertSoftly {
            CLOSED_PATHS.forEach { path ->
                withClue("GET $path") {
                    val status =
                        mockMvc
                            .get(path)
                            .andReturn()
                            .response.status
                    status shouldBeGreaterThanOrEqual 400
                }
            }
        }
    }

    private companion object {
        const val LIVENESS = "/actuator/health/liveness"
        const val READINESS = "/actuator/health/readiness"
        const val PROMETHEUS = "/actuator/prometheus"

        val CLOSED_PATHS =
            listOf(
                "/actuator",
                "/actuator/",
                "/actuator/health",
                "/actuator/health/db",
                "/actuator/health/readiness/db",
                "/actuator/health/liveness/livenessState",
                "/actuator/env",
                "/actuator/env/ZENY_ENCRYPTION_KEYS",
                "/actuator/heapdump",
                "/actuator/threaddump",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/conditions",
                "/actuator/mappings",
                "/actuator/metrics",
                "/actuator/info",
                "/actuator/loggers",
                "/actuator/logfile",
                "/actuator/flyway",
                "/actuator/scheduledtasks",
                "/actuator/shutdown",
                "/actuator/startup",
                "/actuator/sbom",
                "/actuator/caches",
                "/actuator/modulith",
                "/actuator/prometheus/extra",
            )

        val SENSITIVE_ENDPOINT_BEANS =
            listOf(
                "org.springframework.boot.actuate.env.EnvironmentEndpoint",
                "org.springframework.boot.actuate.management.HeapDumpWebEndpoint",
                "org.springframework.boot.actuate.management.ThreadDumpEndpoint",
                "org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint",
                "org.springframework.boot.actuate.beans.BeansEndpoint",
                "org.springframework.boot.actuate.logging.LoggersEndpoint",
                "org.springframework.boot.actuate.context.ShutdownEndpoint",
            )
    }
}
