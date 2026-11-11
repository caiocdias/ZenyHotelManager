package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import java.util.Base64
import java.util.UUID

/** Os endpoints da fila de impressão (M5 E10, Task 9; D-E10-5, D-E10-7 e D-E10-8). */
@IntegrationTest
@Import(IamTestConfiguration::class)
class PrintJobsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    private val path = "/api/v1/print-jobs"

    private fun userWithTemplate(template: RoleTemplate) =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    private fun newPrinter(kind: String = "THERMAL_ESCPOS"): String =
        with(iam) {
            val agent = call(POST, "/api/v1/print-agents", asAdmin(), mapOf("name" to "Agente")).expectStatus(201)!!
            val body =
                mapOf(
                    "name" to "Balcão",
                    "kind" to kind,
                    "address" to "tcp://10.0.0.5:9100",
                    "agentId" to agent["id"].asString(),
                )
            call(POST, "/api/v1/printers", asAdmin(), body).expectStatus(201)!!["id"].asString()
        }

    private fun b64(size: Int) = Base64.getEncoder().encodeToString(ByteArray(size) { 'a'.code.toByte() })

    private fun jobJson(
        printerId: String,
        content: String,
    ) = """{"printerId":"$printerId","documentType":"frontdesk.registration-card","content":"$content"}"""

    private fun post(
        body: String,
        key: String? = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, path) {
                with(iam.asAdmin())
                if (key != null) header("Idempotency-Key", key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun postJob(
        printerId: String,
        content: String,
        key: String? = UUID.randomUUID().toString(),
    ) = post(jobJson(printerId, content), key)

    @Test
    fun `creating a job is 201 without the content and the repeat with the same key returns the same job`(): Unit =
        with(iam) {
            val printer = newPrinter()
            val key = UUID.randomUUID().toString()

            val first = postJob(printer, b64(10), key)
            val again = postJob(printer, b64(10), key)

            val created = first.expectStatus(201)!!
            created["status"].asString() shouldBe "PENDING"
            created["printerId"].asString() shouldBe printer
            created.has("content") shouldBe false
            first.getContentAsString(Charsets.UTF_8) shouldNotContain b64(10)
            again.expectStatus(201)!!["id"].asString() shouldBe created["id"].asString()
            jdbc
                .sql("SELECT count(*) FROM printing.print_job WHERE printer_id = :p")
                .param("p", UUID.fromString(printer))
                .query(Int::class.java)
                .single() shouldBe 1
        }

    @Test
    fun `without the Idempotency-Key the POST is 400`(): Unit =
        with(iam) { postJob(newPrinter(), b64(10), key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED") }

    @Test
    fun `an invalid base64 content is 400 on the field without echo`(): Unit =
        with(iam) {
            val response = postJob(newPrinter(), "isto nao e base64 !!")

            val problem = response.problem(400, "VALIDATION_FAILED")
            problem["errors"].values().map { it["field"].asString() } shouldContain "content"
            response.getContentAsString(Charsets.UTF_8) shouldNotContain "isto nao e base64"
        }

    @Test
    fun `the manual ceiling is 716800 bytes, 422 above it, and a body over 1 MB is 413`(): Unit =
        with(iam) {
            val printer = newPrinter()

            postJob(printer, b64(716_800)).expectStatus(201)
            postJob(printer, b64(716_801)).problem(422, "PRINT_CONTENT_TOO_LARGE")
            postJob(printer, b64(800_000)).problem(413, "PAYLOAD_TOO_LARGE")
        }

    @Test
    fun `the receptionist reads the list without content and cannot create`(): Unit =
        with(iam) {
            val printer = newPrinter()
            val id = postJob(printer, b64(10)).expectStatus(201)!!["id"].asString()
            val receptionist = asTestUser(userWithTemplate(RoleTemplate.RECEPTIONIST))

            val list = call(GET, "$path?printerId=$printer", receptionist).ok()

            list["items"].values().map { it["id"].asString() } shouldContain id
            list["items"].values().none { it.has("content") } shouldBe true
            mockMvc
                .request(POST, path) {
                    with(receptionist)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = jobJson(printer, b64(10))
                }.andReturn()
                .response
                .problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `the list filters by status and is paginated by cursor`(): Unit =
        with(iam) {
            val printer = newPrinter()
            repeat(3) { postJob(printer, b64(10)).expectStatus(201) }

            call(GET, "$path?printerId=$printer&status=PRINTED", asAdmin()).ok()["items"].size() shouldBe 0
            val first = call(GET, "$path?printerId=$printer&status=PENDING&limit=2", asAdmin()).ok()
            first["items"].size() shouldBe 2
            val next = first["nextCursor"].asString()
            val second = call(GET, "$path?printerId=$printer&status=PENDING&limit=2&cursor=$next", asAdmin()).ok()
            second["items"].size() shouldBe 1
        }

    @Test
    fun `the contract publishes status as an extensible enum`() {
        val spec = java.io.File("api/openapi.yaml").readText()
        spec.contains("    PrintJobResponse:") shouldBe true
        val block = spec.substringAfter("    PrintJobResponse:").substringBefore("\n    Printer")
        block.contains("x-extensible-enum") shouldBe true
    }
}
