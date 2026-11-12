package com.zeny.hotel.billing

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.UUID

/**
 * Ataques ao contrato HTTP das rotas do extrato como documento (M5 E11, Task 10): `audience` inválido, `Accept`
 * variados, cabeçalhos de resposta, corpo e chave de idempotência do POST, impressora inativa, método errado e o
 * `api/openapi.yaml`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class FolioStatementDocumentsApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @BeforeEach
    fun prepare() {
        iam.asAdmin() // garante o contexto do administrador
    }

    @Test
    fun `an invalid lowercase or repeated audience on the PDF route is 400 problem json`() {
        val folio = folioWithCharge()

        for (query in listOf("audience=BOGUS", "audience=guest")) {
            val response = get("/api/v1/folios/$folio/statement/pdf?$query")
            withClue("query $query: ${response.contentAsString}") {
                response.status shouldBe 400
                response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
            }
        }
    }

    @Test
    fun `a repeated audience parameter is 400 instead of silently picking one`() {
        val folio = folioWithCharge()

        val response = get("/api/v1/folios/$folio/statement/pdf?audience=GUEST&audience=INTERNAL")

        withClue("status ${response.status} type ${response.contentType}") { response.status shouldBe 400 }
        response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
    }

    @Test
    fun `an invalid audience on the print route is 400 and does not enqueue anything`() {
        val folio = folioWithCharge()
        val printer = newPrinter()

        for (query in listOf("audience=BOGUS", "audience=guest")) {
            val response = print(folio, "?$query", """{"printerId":"$printer"}""", UuidV7.generate().toString())
            withClue("query $query: ${response.contentAsString}") {
                response.status shouldBe 400
                response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
            }
        }
    }

    @Test
    fun `a malformed folio id is 400 problem json on both routes`() {
        val pdf = get("/api/v1/folios/not-a-uuid/statement/pdf")
        pdf.status shouldBe 400
        pdf.contentType.shouldNotBeNull() shouldContain "application/problem+json"

        val print =
            mockMvc
                .request(HttpMethod.POST, "/api/v1/folios/not-a-uuid/statement/print") {
                    with(billing.admin)
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.generate().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"${UuidV7.generate()}"}"""
                }.andReturn()
                .response
        print.status shouldBe 400
        print.contentType.shouldNotBeNull() shouldContain "application/problem+json"
    }

    @Test
    fun `Accept pdf and star and absent and html on success all answer the PDF`() {
        val folio = folioWithCharge()

        for (accept in listOf("application/pdf", "*/*", null, "application/pdf, application/json;q=0.5")) {
            val response = get("/api/v1/folios/$folio/statement/pdf", accept)
            withClue("Accept $accept") {
                response.status shouldBe 200
                response.contentType shouldBe "application/pdf"
            }
        }
    }

    @Test
    fun `Accept json or html on the PDF route never yields a body that is neither the PDF nor a 406 problem`() {
        val folio = folioWithCharge()

        for (accept in listOf("application/json", "text/html")) {
            val response = get("/api/v1/folios/$folio/statement/pdf", accept)
            withClue("Accept $accept: status ${response.status} type ${response.contentType}") {
                if (response.status == 200) {
                    response.contentType shouldBe "application/pdf"
                    String(response.contentAsByteArray, Charsets.ISO_8859_1) shouldStartWith "%PDF-"
                } else {
                    response.status shouldBe 406
                    response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
                }
            }
        }
    }

    @Test
    fun `errors on the PDF route stay problem json for every Accept`() {
        for (accept in listOf("application/pdf", "*/*", "application/json", "text/html", null)) {
            val response = get("/api/v1/folios/${UuidV7.generate()}/statement/pdf", accept)
            withClue("Accept $accept: status ${response.status} type ${response.contentType}") {
                response.status shouldBe 404
                response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
            }
        }
    }

    @Test
    fun `the PDF headers carry one attachment disposition with a safe name and no cache`() {
        val folio = folioWithCharge()
        val response = get("/api/v1/folios/$folio/statement/pdf")

        response.status shouldBe 200
        val disposition = response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)
        disposition shouldHaveSize 1
        disposition.single() shouldContain "attachment"
        disposition.single() shouldNotContain "\n"
        disposition.single() shouldNotContain "\r"
        response.getHeaders(HttpHeaders.CACHE_CONTROL).single() shouldBe "no-store"
        response.getHeader("X-Content-Type-Options").let { it == null || it == "nosniff" } shouldBe true
    }

    @Test
    fun `printing without a body or with a malformed one is 400 problem json`() {
        val folio = folioWithCharge()

        for (body in listOf(null, "", "{", "[]", "not json")) {
            val response = print(folio, "?audience=GUEST", body, UuidV7.generate().toString())
            withClue("body $body: ${response.contentAsString}") {
                response.status shouldBe 400
                response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
            }
        }
    }

    @Test
    fun `printing with a missing null or non uuid printerId is 400 problem json`() {
        val folio = folioWithCharge()

        for (body in listOf(
            "{}",
            """{"printerId":null}""",
            """{"printerId":"abc"}""",
            """{"printerId":""}""",
            """{"printerId":1}""",
        )) {
            val response = print(folio, "?audience=GUEST", body, UuidV7.generate().toString())
            withClue("body $body: ${response.contentAsString}") {
                response.status shouldBe 400
                response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
            }
        }
    }

    @Test
    fun `printing with a malformed Idempotency-Key is 400 IDEMPOTENCY_KEY_INVALID`() {
        val folio = folioWithCharge()
        val body = """{"printerId":"${newPrinter()}"}"""

        for (key in listOf("com espaco e acento", "a".repeat(256))) {
            val response = print(folio, "?audience=GUEST", body, key)
            withClue("key $key: ${response.contentAsString}") {
                with(billing) { response.problem(400, "IDEMPOTENCY_KEY_INVALID") }
            }
        }
    }

    @Test
    fun `reusing the key with another printer is 422 IDEMPOTENCY_KEY_REUSED`() {
        val folio = folioWithCharge()
        val key = UuidV7.generate().toString()

        print(folio, "?audience=GUEST", """{"printerId":"${newPrinter()}"}""", key).status shouldBe 202
        val second = print(folio, "?audience=GUEST", """{"printerId":"${newPrinter()}"}""", key)

        with(billing) { second.problem(422, "IDEMPOTENCY_KEY_REUSED") }
    }

    @Test
    fun `reusing the key with another audience in the query is 422 IDEMPOTENCY_KEY_REUSED`() {
        val folio = folioWithCharge()
        val body = """{"printerId":"${newPrinter()}"}"""
        val key = UuidV7.generate().toString()

        print(folio, "?audience=GUEST", body, key).status shouldBe 202
        val second = print(folio, "?audience=INTERNAL", body, key)

        with(billing) { second.problem(422, "IDEMPOTENCY_KEY_REUSED") }
    }

    @Test
    fun `printing on a deactivated printer is 422 PRINTER_INACTIVE`() {
        val folio = folioWithCharge()
        val printer = newPrinter()
        asAdminActor { agents.deactivatePrinter(printer) }

        val response = print(folio, "?audience=GUEST", """{"printerId":"$printer"}""", UuidV7.generate().toString())

        with(billing) { response.problem(422, "PRINTER_INACTIVE") }
    }

    @Test
    fun `without a token both routes are 401 problem json`() {
        val folio = folioWithCharge()
        val pdf = mockMvc.request(HttpMethod.GET, "/api/v1/folios/$folio/statement/pdf").andReturn().response
        val print =
            mockMvc
                .request(HttpMethod.POST, "/api/v1/folios/$folio/statement/print") {
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.generate().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"${UuidV7.generate()}"}"""
                }.andReturn()
                .response

        pdf.status shouldBe 401
        print.status shouldBe 401
        pdf.contentType.shouldNotBeNull() shouldContain "application/problem+json"
    }

    @Test
    fun `the wrong method on each route is 405`() {
        val folio = folioWithCharge()

        for ((method, path) in listOf(
            HttpMethod.POST to "/api/v1/folios/$folio/statement/pdf",
            HttpMethod.PUT to "/api/v1/folios/$folio/statement/pdf",
            HttpMethod.DELETE to "/api/v1/folios/$folio/statement/pdf",
            HttpMethod.GET to "/api/v1/folios/$folio/statement/print",
            HttpMethod.PUT to "/api/v1/folios/$folio/statement/print",
        )) {
            val response = mockMvc.request(method, path) { with(billing.admin) }.andReturn().response
            withClue("$method $path: ${response.status}") { response.status shouldBe 405 }
        }
    }

    @Test
    fun `the contract describes the 202 PrintJobAccepted the idempotency header and the audience parameter`() {
        val spec = File("api/openapi.yaml").readText()
        val pdf = spec.substringAfter("operationId: getFolioStatementPdf").substringBefore("operationId:")
        val print = spec.substringAfter("operationId: printFolioStatement").substringBefore("operationId:")

        pdf shouldContain "name: audience"
        pdf shouldContain "application/pdf"
        pdf shouldContain "format: binary"
        print shouldContain "name: audience"
        print shouldContain "\"202\""
        print shouldContain "#/components/schemas/PrintJobAccepted"
        print shouldContain "#/components/schemas/PrintDocumentRequest"
        print shouldContain "#/components/parameters/IdempotencyKey"
        spec.substringAfter("    IdempotencyKey:").take(PARAMETER_WINDOW) shouldContain "name: Idempotency-Key"
    }

    // --- apoio ---

    private fun get(
        path: String,
        accept: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(HttpMethod.GET, path) {
                with(billing.admin)
                accept?.let { header(HttpHeaders.ACCEPT, it) }
            }.andReturn()
            .response

    private fun print(
        folio: UUID,
        query: String,
        body: String?,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .request(HttpMethod.POST, "/api/v1/folios/$folio/statement/print$query") {
                with(billing.admin)
                header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                if (body != null) content = body
            }.andReturn()
            .response

    private fun folioWithCharge(): UUID =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            folio
        }

    private fun <T> asAdminActor(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun newPrinter(): UUID =
        asAdminActor {
            val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
            val agent = agents.createAgent("Agente $suffix").view.id
            agents.createPrinter(NewPrinter("Imp $suffix", PrinterKind.A4_PDF, "addr-$suffix", agent)).id
        }

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PARAMETER_WINDOW = 1500
    }
}
