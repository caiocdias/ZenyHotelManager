package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintJobsMaintenance
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** O gauge `zeny_printing_jobs{status}` (M5 E10, Task 12): a fila e as falhas recentes, por diferença. */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList")
class PrintingMetricsTest(
    @Autowired private val registry: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: org.springframework.jdbc.core.simple.JdbcClient,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val delivery: PrintAgentJobsService,
    @Autowired private val maintenance: PrintJobsMaintenance,
    @Autowired private val realm: PrintAgentRealm,
    @Autowired private val clock: MutableClock,
) {
    private var advanced: Duration = Duration.ZERO

    @AfterEach
    fun restoreClock() {
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
    }

    private fun gauge(status: String) =
        registry
            .get("zeny.printing.jobs")
            .tag("status", status)
            .gauge()
            .value()

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun printerWithToken(): Triple<UUID, UUID, String> {
        val agent =
            jsonMapper.readTree(
                iam
                    .call(
                        POST,
                        "/api/v1/print-agents",
                        iam.asAdmin(),
                        mapOf("name" to "Agente ${suffix()}"),
                    ).contentAsString,
            )
        val agentId = UUID.fromString(agent["id"].asString())
        val body =
            mapOf(
                "name" to "Imp ${suffix()}",
                "kind" to "A4_PDF",
                "address" to "a-${suffix()}",
                "agentId" to agentId,
            )
        val printer = jsonMapper.readTree(iam.call(POST, "/api/v1/printers", iam.asAdmin(), body).contentAsString)
        return Triple(UUID.fromString(printer["id"].asString()), agentId, agent["token"].asString())
    }

    /** A contagem de referência, no mesmo instante e pela mesma regra do gauge (independe do que o banco já tem). */
    private fun expected(status: String): Double {
        val since = clock.instant().minus(Duration.ofHours(24))
        val query =
            if (status == "FAILED") {
                jdbc
                    .sql("SELECT count(*) FROM printing.print_job WHERE status = 'FAILED' AND finished_at >= :since")
                    .param("since", java.sql.Timestamp.from(since))
            } else {
                jdbc.sql("SELECT count(*) FROM printing.print_job WHERE status = '$status'")
            }
        return query.query(Long::class.java).single().toDouble()
    }

    private fun statusOf(id: UUID): String =
        jdbc
            .sql("SELECT status FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun gaugesMatchTheRule() = listOf("PENDING", "SENT", "FAILED").forEach { gauge(it) shouldBe expected(it) }

    @Test
    fun `the gauge follows the queue and a FAILED job older than the retention leaves the count`() {
        val (printer, _, token) = printerWithToken()
        val pending = gauge("PENDING")
        val sent = gauge("SENT")

        val job = jobs.enqueue(NewPrintJob(printer, "frontdesk.registration-card", "%PDF-1.7 x".toByteArray()))
        gauge("PENDING") shouldBe pending + 1 // nada mais mexe no banco entre as duas leituras
        delivery.claim(realm.authenticate(token)!!, 1)
        gauge("PENDING") shouldBe pending
        gauge("SENT") shouldBe sent + 1
        gaugesMatchTheRule()

        advanced = Duration.ofMinutes(3)
        clock.advance(advanced)
        maintenance.run() // também mexe nos órfãos de outros testes: daqui em diante, só a regra de referência
        statusOf(job) shouldBe "PENDING"
        gaugesMatchTheRule()

        advanced = advanced.plus(Duration.ofHours(25))
        clock.advance(Duration.ofHours(25))
        maintenance.run()
        statusOf(job) shouldBe "FAILED"
        gaugesMatchTheRule()
        gauge("FAILED") shouldBeGreaterThanOrEqual 1.0 // o job deste teste acaba de falhar, dentro da guarda

        val body =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString
        listOf("PENDING", "SENT", "FAILED").forEach {
            body shouldContain
                "zeny_printing_jobs{application=\"zeny-hotel-manager\",status=\"$it\"}"
        }

        val recentlyFailed = gauge("FAILED")
        advanced = advanced.plus(Duration.ofHours(25))
        clock.advance(Duration.ofHours(25))
        gaugesMatchTheRule()
        gauge("FAILED") shouldBeLessThan recentlyFailed
    }
}
