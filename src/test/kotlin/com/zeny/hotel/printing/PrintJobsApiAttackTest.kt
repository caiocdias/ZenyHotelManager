package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Ataques do testador aos endpoints da fila de impressão (M5 E10, Task 9; D-E10-5, D-E10-7 e D-E10-8): idempotência
 * (corpo diferente, outro usuário, erro repetido, concorrência), base64 inválido sem eco, limites exatos, o conteúdo
 * fora de resposta, log e trilha, a lista (filtros e cursor), papéis reais, o anônimo e o contrato.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions") // um teste por ataque
class PrintJobsApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    private val path = "/api/v1/print-jobs"
    private val maxBody = 1024 * 1024

    private fun MockHttpServletResponse.isProblem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { this@isProblem.problem(status, code) }

    private fun MockHttpServletResponse.expectJson(status: Int): JsonNode? =
        with(iam) { this@expectJson.expectStatus(status) }

    private fun MockHttpServletResponse.text(): String = getContentAsString(Charsets.UTF_8)

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
        documentType: String = "frontdesk.registration-card",
    ) = """{"printerId":"$printerId","documentType":"$documentType","content":"$content"}"""

    private fun send(
        body: String,
        key: String? = UUID.randomUUID().toString(),
        auth: RequestPostProcessor? = iam.asAdmin(),
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, path) {
                if (auth != null) with(auth)
                if (key != null) header("Idempotency-Key", key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun postJob(
        printerId: String,
        content: String,
        key: String? = UUID.randomUUID().toString(),
        auth: RequestPostProcessor? = iam.asAdmin(),
    ) = send(jobJson(printerId, content), key, auth)

    private fun jobsOf(printerId: String): Int =
        jdbc
            .sql("SELECT count(*) FROM printing.print_job WHERE printer_id = :p")
            .param("p", UUID.fromString(printerId))
            .query(Int::class.java)
            .single()

    private fun createAudits(printerId: String): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE action = 'printing.job.create' " +
                    "AND after_state::text LIKE :p",
            ).param("p", "%$printerId%")
            .query(Int::class.java)
            .single()

    private fun list(
        query: String,
        auth: RequestPostProcessor = iam.asAdmin(),
    ) = mockMvc
        .request(GET, "$path?$query") { with(auth) }
        .andReturn()
        .response

    @Test
    fun `the same key with a different body is 422 IDEMPOTENCY_KEY_REUSED and creates only the first job`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()

        val first = postJob(printer, b64(10), key).expectJson(201)!!
        postJob(printer, b64(11), key).isProblem(422, "IDEMPOTENCY_KEY_REUSED")
        send(jobJson(printer, b64(10), documentType = "frontdesk.other-card"), key)
            .isProblem(422, "IDEMPOTENCY_KEY_REUSED")

        jobsOf(printer) shouldBe 1
        postJob(printer, b64(10), key).expectJson(201)!!["id"].asString() shouldBe first["id"].asString()
    }

    @Test
    fun `a key used by another user is independent and never replays the first user's job`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()
        val manager = iam.asTestUser(userWithTemplate(RoleTemplate.MANAGER))

        val byAdmin = postJob(printer, b64(10), key).expectJson(201)!!
        val byManager = postJob(printer, b64(10), key, manager)

        byManager.getHeader("Idempotent-Replayed") shouldBe null
        byManager.expectJson(201)!!["id"].asString() shouldNotBe byAdmin["id"].asString()
        jobsOf(printer) shouldBe 2
    }

    @Test
    fun `a 422 is stored and replayed with the same key, and the fixed body then needs a new key`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()

        val first = postJob(printer, b64(716_801), key)
        val again = postJob(printer, b64(716_801), key)

        first.isProblem(422, "PRINT_CONTENT_TOO_LARGE")
        again.isProblem(422, "PRINT_CONTENT_TOO_LARGE")
        again.getHeader("Idempotent-Replayed") shouldBe "true"
        postJob(printer, b64(10), key).isProblem(422, "IDEMPOTENCY_KEY_REUSED")
        jobsOf(printer) shouldBe 0
        postJob(printer, b64(10)).expectJson(201)
        jobsOf(printer) shouldBe 1
    }

    @Test
    fun `parallel repeats with the same key create exactly one job and never a 500`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<MockHttpServletResponse> {
                        start.await()
                        postJob(printer, b64(10), key)
                    }
                }
            start.countDown()
            val responses = futures.map { it.get() }

            withClue(responses.map { it.status to it.text() }.toString()) {
                responses.all { it.status == 201 || it.status == 409 } shouldBe true
                (responses.count { it.status == 201 } >= 1) shouldBe true
            }
            val ids = responses.filter { it.status == 201 }.map { iam.json(it)["id"].asString() }
            ids.toSet().size shouldBe 1
            jobsOf(printer) shouldBe 1
            createAudits(printer) shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `malformed base64 is 400 on the content field, creates nothing and never echoes the value`() {
        val printer = newPrinter()
        val secret = "SEGREDO${UUID.randomUUID().toString().replace("-", "")}"
        val valid = Base64.getEncoder().encodeToString("corpo-valido".toByteArray())
        val cases =
            mapOf(
                "padding of one byte too many" to "YQ===",
                "padding in the middle" to "YQ==YQ==",
                "padding at the start" to "=YQ=",
                "a lone quantum with one pad" to "Y===",
                "base64url dash" to "a-b_".repeat(4),
                "base64url underscore" to "YW_j",
                "a space inside" to "$valid $valid",
                "a trailing newline" to "$valid\\n",
                "mime line break" to "${valid.take(4)}\\r\\n${valid.drop(4)}",
                "a tab" to "$valid\\t",
                "outside the alphabet" to "$secret!!",
                "non-ascii" to "${valid}ção",
                "a nul escape" to "$valid\\u0000",
                "only a space" to " ",
            )
        cases.forEach { (name, content) ->
            val response = postJob(printer, content)
            withClue("$name -> ${response.status} ${response.text()}") {
                val problem = response.isProblem(400, "VALIDATION_FAILED")
                problem["errors"].values().map { it["field"].asString() } shouldContain "content"
                response.text() shouldNotContain secret
                if (content.length >= 4 && !content.contains('\\')) response.text() shouldNotContain content
            }
        }
        jobsOf(printer) shouldBe 0
        createAudits(printer) shouldBe 0
    }

    @Test
    fun `base64 without the final padding is refused like any other malformed content`() {
        val printer = newPrinter()

        // "YQ" é "YQ==" sem o preenchimento; o RFC 4648 exige o `=`, então o campo é inválido.
        val response = postJob(printer, "YQ")

        withClue("${response.status} ${response.text()}") { response.status shouldBe 400 }
        jobsOf(printer) shouldBe 0
    }

    @Test
    fun `an empty, missing, null or non-string content is refused without a 500 and without a job`() {
        val printer = newPrinter()
        val base = """"printerId":"$printer","documentType":"frontdesk.registration-card""""

        postJob(printer, "").isProblem(422, "PRINT_CONTENT_EMPTY")
        listOf("{$base}", "{$base,\"content\":null}", "{$base,\"content\":[\"YQ==\"]}")
            .forEach { body ->
                val response = send(body)
                withClue("$body -> ${response.status} ${response.text()}") { response.status shouldBe 400 }
            }
        send("{$base,\"content\":\"YQ==\"").status shouldBe 400
        send("não é json").status shouldBe 400
        jobsOf(printer) shouldBe 0
    }

    @Test
    fun `the body ceiling is exact, 1048576 bytes reach the rule and 1048577 are 413 without job or audit`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()
        val head = jobJson(printer, b64(716_801)).removeSuffix("}")

        fun padded(total: Int) = head + " ".repeat(total - head.toByteArray().size - 1) + "}"

        send(padded(maxBody)).isProblem(422, "PRINT_CONTENT_TOO_LARGE")
        send(padded(maxBody + 1), key).isProblem(413, "PAYLOAD_TOO_LARGE")
        send(padded(maxBody * 3), key).isProblem(413, "PAYLOAD_TOO_LARGE")

        jobsOf(printer) shouldBe 0
        createAudits(printer) shouldBe 0
        // o 413 não consome a chave: ela ainda serve para um corpo pequeno
        postJob(printer, b64(10), key).expectJson(201)
        jobsOf(printer) shouldBe 1
    }

    @Test
    fun `the manual ceiling is exact and the oversized content is not echoed in the problem`() {
        val printer = newPrinter()

        postJob(printer, b64(716_800)).expectJson(201)
        val over = postJob(printer, b64(716_801))

        over.isProblem(422, "PRINT_CONTENT_TOO_LARGE")
        (over.text().length < 4_000) shouldBe true
        over.text() shouldNotContain b64(64)
        jobsOf(printer) shouldBe 1
        createAudits(printer) shouldBe 1
    }

    @Test
    fun `the content never leaves in a response, the log, the trail or the stored row`(output: CapturedOutput) {
        val printer = newPrinter()
        val secret = "SEGREDO-${UUID.randomUUID()}-fim"
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray())
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(secret.toByteArray())

        val created = postJob(printer, encoded)
        val id = created.expectJson(201)!!["id"].asString()
        val listed = list("printerId=$printer")
        val rejected = send(jobJson(printer, encoded, documentType = "Inválido $secret"))
        val unknownPrinter = postJob(UUID.randomUUID().toString(), encoded)
        val wrongKind = send(jobJson(printer, encoded).replace("\"content\"", "\"extra\":\"$secret\",\"content\""))

        listOf(created, listed, rejected, unknownPrinter, wrongKind).forEach { response ->
            response.text() shouldNotContain secret
            response.text() shouldNotContain encoded
            response.text() shouldNotContain urlSafe
        }
        iam.json(listed)["items"].values().forEach { it.has("content") shouldBe false }
        output.all shouldNotContain secret
        output.all shouldNotContain encoded
        val trail =
            jdbc
                .sql("SELECT row_to_json(a)::text FROM audit.audit_entry a WHERE a.entity_id::text = :id")
                .param("id", id)
                .query(String::class.java)
                .list()
        trail.size shouldBe 1
        trail.single() shouldNotContain secret
        trail.single() shouldNotContain encoded
        val row =
            jdbc
                .sql("SELECT row_to_json(j)::text FROM printing.print_job j WHERE j.id = :id")
                .param("id", UUID.fromString(id))
                .query(String::class.java)
                .single()
        row shouldNotContain secret
        row shouldNotContain encoded
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE before_state::text LIKE :p OR after_state::text LIKE :p")
            .param("p", "%${secret.take(20)}%")
            .query(Long::class.java)
            .single() shouldBe 0L
    }

    @Test
    fun `an unknown, lowercase or empty status, a bad printerId and a bad limit are 400 and never 500`() {
        val printer = newPrinter()
        postJob(printer, b64(10)).expectJson(201)
        val bad =
            listOf(
                "status=NOPE",
                "status=pending",
                "status=PENDING,SENT",
                "status=%00",
                "printerId=nao-e-uuid",
                "printerId=${UUID.randomUUID()}x",
                "limit=0",
                "limit=-1",
                "limit=201",
                "limit=abc",
                "limit=",
                "limit=99999999999999999999",
            )
        bad.forEach { query ->
            val response = list(query)
            withClue("?$query -> ${response.status} ${response.text()}") {
                (response.status in 400..499) shouldBe true
            }
        }
        // vazio é o mesmo que ausente
        list("status=&printerId=$printer").status shouldBe 200
        list("printerId=&limit=1").status shouldBe 200
        list("limit=200&printerId=$printer").status shouldBe 200
        list("limit=1&printerId=$printer").status shouldBe 200
    }

    @Test
    fun `a printerId that does not exist is an empty page and a status filter never mixes other printers`() {
        val printer = newPrinter()
        val other = newPrinter()
        postJob(printer, b64(10)).expectJson(201)
        postJob(other, b64(10)).expectJson(201)

        list("printerId=${UUID.randomUUID()}").expectJson(200)!!["items"].size() shouldBe 0
        val mine = list("printerId=$printer&status=PENDING").expectJson(200)!!["items"]
        mine.size() shouldBe 1
        mine.values().all { it["printerId"].asString() == printer } shouldBe true
    }

    @Test
    fun `a tampered, truncated, foreign or oversized cursor is 400 INVALID_CURSOR`() {
        val printer = newPrinter()
        repeat(3) { postJob(printer, b64(10)).expectJson(201) }
        val page = list("printerId=$printer&limit=1").expectJson(200)!!
        val cursor = page["nextCursor"].asString()

        fun encode(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val tampered =
            listOf(
                cursor.dropLast(3),
                cursor + "AAAA",
                cursor.reversed(),
                "x$cursor",
                "!!!!",
                "%20",
                encode("v9:1|2"),
                encode("c1:"),
                encode("c1:nao-numero|nao-uuid"),
                encode("c1:~z"),
                "A".repeat(2000),
            )
        tampered.forEach { token ->
            val response = list("printerId=$printer&limit=1&cursor=$token")
            withClue("cursor $token -> ${response.status} ${response.text()}") {
                (response.status in 400..499) shouldBe true
            }
        }
        list("printerId=$printer&limit=1&cursor=${cursor.dropLast(3)}").isProblem(400, "INVALID_CURSOR")
        list("printerId=$printer&limit=1&cursor=$cursor").expectJson(200)!!["items"].size() shouldBe 1
    }

    @Test
    fun `the pages walk every job exactly once, newest first, and carry no content field`() {
        val printer = newPrinter()
        val ids = (1..5).map { postJob(printer, b64(10 + it)).expectJson(201)!!["id"].asString() }
        val seen = mutableListOf<String>()
        val fields =
            setOf(
                "id",
                "printerId",
                "documentType",
                "status",
                "attempts",
                "createdAt",
                "sentAt",
                "finishedAt",
                "failureCode",
            )
        var cursor: String? = null
        do {
            val query = "printerId=$printer&limit=2" + cursor?.let { "&cursor=$it" }.orEmpty()
            val page = list(query).expectJson(200)!!
            page["items"].values().forEach {
                (it.propertyNames().toSet() - fields) shouldBe emptySet()
                it.has("content") shouldBe false
                seen += it["id"].asString()
            }
            cursor = page["nextCursor"]?.takeUnless { it.isNull }?.asString()
        } while (cursor != null)

        seen shouldBe ids.reversed()
    }

    @Test
    fun `real roles - manager creates and lists, receptionist only lists, others and no role get 403`() {
        val printer = newPrinter()
        val manager = iam.asTestUser(userWithTemplate(RoleTemplate.MANAGER))
        val receptionist = iam.asTestUser(userWithTemplate(RoleTemplate.RECEPTIONIST))
        val noRole = iam.asTestUser(iam.createUser())
        val denied =
            listOf(
                RoleTemplate.HOUSEKEEPER,
                RoleTemplate.ROOM_ATTENDANT,
                RoleTemplate.MAINTENANCE,
                RoleTemplate.WAITER,
                RoleTemplate.FNB_CASHIER,
                RoleTemplate.KITCHEN_BAR,
            ).map { it.name to iam.asTestUser(userWithTemplate(it)) } + ("NO_ROLE" to noRole)

        postJob(printer, b64(10), auth = manager).expectJson(201)
        list("printerId=$printer", manager).status shouldBe 200
        list("printerId=$printer", receptionist).status shouldBe 200
        postJob(printer, b64(10), auth = receptionist).isProblem(403, "PERMISSION_DENIED")
        denied.forEach { (name, auth) ->
            withClue(name) {
                postJob(printer, b64(10), auth = auth).status shouldBe 403
                list("printerId=$printer", auth).status shouldBe 403
            }
        }
        jobsOf(printer) shouldBe 1
        createAudits(printer) shouldBe 1
    }

    @Test
    fun `a 403 does not burn the key and the same key works for a user who may create`() {
        val printer = newPrinter()
        val key = UUID.randomUUID().toString()
        val receptionist = iam.asTestUser(userWithTemplate(RoleTemplate.RECEPTIONIST))

        postJob(printer, b64(10), key, receptionist).status shouldBe 403

        postJob(printer, b64(10), key).expectJson(201)
        jobsOf(printer) shouldBe 1
    }

    @Test
    fun `anonymous callers are 401 on both routes, with or without key, and nothing is written`() {
        val printer = newPrinter()

        postJob(printer, b64(10), auth = null).isProblem(401, "UNAUTHENTICATED")
        postJob(printer, b64(10), key = null, auth = null).isProblem(401, "UNAUTHENTICATED")
        send(jobJson(printer, "!!"), auth = null).isProblem(401, "UNAUTHENTICATED")
        mockMvc
            .request(GET, "$path?printerId=$printer") {}
            .andReturn()
            .response
            .isProblem(401, "UNAUTHENTICATED")
        mockMvc
            .request(GET, "$path?printerId=$printer") { header("Authorization", "Bearer lixo") }
            .andReturn()
            .response
            .status shouldBe 401
        jobsOf(printer) shouldBe 0
    }

    @Test
    fun `the contract has 201, a required request body, the extensible status, the 413 and the cursor params`() {
        val spec = Yaml.mapper().readValue(Path.of("api/openapi.yaml").toFile(), OpenAPI::class.java)
        val item = spec.paths["/api/v1/print-jobs"]!!
        val post = item.post
        val get = item.get

        post.operationId shouldBe "createPrintJob"
        post.responses.keys.contains("201") shouldBe true
        post.responses.keys.contains("200") shouldBe false
        post.requestBody.required shouldBe true
        post.requestBody.content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/PrintJobRequest"
        post.parameters.map { it.`$ref` ?: it.name } shouldContain "#/components/parameters/IdempotencyKey"
        post.description shouldContain "413"
        post.description shouldContain "PAYLOAD_TOO_LARGE"
        post.description shouldContain "716800"
        post.description shouldContain "PRINT_CONTENT_TOO_LARGE"
        get.operationId shouldBe "listPrintJobs"
        get.parameters.map { it.name } shouldContain "status"
        get.parameters.map { it.name } shouldContain "printerId"
        get.parameters.map { it.name } shouldContain "limit"
        get.parameters.map { it.name } shouldContain "cursor"

        val schemas = spec.components.schemas
        schemas["PrintJobResponse"]!!.properties["status"]!!.extensions.keys shouldContain "x-extensible-enum"
        schemas["PrintJobResponse"]!!.properties.keys.contains("content") shouldBe false
        schemas["PrintJobRequest"]!!.properties["content"]!!.format shouldBe "byte"
        schemas["PrintJobRequest"]!!.required shouldContain "content"
        schemas["PrintJobRequest"]!!.required shouldContain "printerId"
        schemas["PrintJobRequest"]!!.required shouldContain "documentType"
    }
}
