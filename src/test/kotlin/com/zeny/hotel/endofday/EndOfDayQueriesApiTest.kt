package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * As consultas do fechamento pela API (M6 E10, Task 11): execuções, uma execução com as etapas e as pendências, todas
 * com `endofday.runs.read`. O banco isolado acumula execuções de outros testes: afirma-se só sobre as próprias.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto
class EndOfDayQueriesApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val context: ApplicationContext,
) {
    private val read = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private fun settled(block: () -> Unit) {
        try {
            probe.reset()
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun reader(): RequestPostProcessor =
        asUser(iam.createUserWith(TestGrant(read, PermissionScope.ALL)).id.value)

    /** Fecha a data corrente com a pendência do fake na etapa dois e a terceira falhando: a execução `FAILED`. */
    private fun failedRun(): JsonNode {
        probe.raiseIssueOn(TWO, "TEST_PENDING")
        probe.failNext(THREE, 1)
        return startRun("FAILED")
    }

    private fun startRun(expectedStatus: String): JsonNode {
        val response =
            mockMvc
                .post("/api/v1/end-of-day/runs") {
                    with(asUser(iam.createUserWith(TestGrant(execute, PermissionScope.ALL)).id.value))
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"businessDate":"${hotelApi.currentBusinessDate()}"}"""
                }.andReturn()
                .response
        val run = response.body(201)
        run["status"].asString() shouldBe expectedStatus
        return run
    }

    private fun get(
        path: String,
        auth: RequestPostProcessor? = null,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { if (auth != null) with(auth) }
            .andReturn()
            .response

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    /** Percorre as páginas de [path] (com `limit=100`) até o fim e junta os itens. */
    private fun allItems(
        path: String,
        auth: RequestPostProcessor,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "&cursor=$it" }.orEmpty()
            val page = get("$path?limit=$PAGE$suffix", auth).body(200)
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return items
    }

    @Test
    fun `the three queries without a token are 401`() {
        val id = UuidV7.generate()

        get("/api/v1/end-of-day/runs").status shouldBe 401
        get("/api/v1/end-of-day/runs/$id").status shouldBe 401
        get("/api/v1/end-of-day/runs/$id/issues").status shouldBe 401
    }

    @Test
    fun `the three queries without the permission are PERMISSION_DENIED`() {
        val auth = asUser(iam.createUser().id.value)
        val id = UuidV7.generate()

        get("/api/v1/end-of-day/runs", auth).problem(403, "PERMISSION_DENIED")
        get("/api/v1/end-of-day/runs/$id", auth).problem(403, "PERMISSION_DENIED")
        get("/api/v1/end-of-day/runs/$id/issues", auth).problem(403, "PERMISSION_DENIED")
    }

    @Test
    fun `the list pages with a cursor and finds the own run`() =
        settled {
            startRun("COMPLETED")
            val id = failedRun()["id"].asString()
            val auth = reader()

            val first = get("/api/v1/end-of-day/runs?limit=1", auth).body(200)

            first["items"].size() shouldBe 1
            first["nextCursor"].isNull shouldBe false
            val all = allItems("/api/v1/end-of-day/runs", auth)
            all.map { it["id"].asString() } shouldContain id
            val dates = all.map { it["businessDate"].asString() }
            dates shouldContainExactly dates.sortedDescending()
            all.first { it["id"].asString() == id }["status"].asString() shouldBe "FAILED"
        }

    @Test
    fun `the detail has the ETag of the version and the steps in order`() =
        settled {
            val id = failedRun()["id"].asString()

            val response = get("/api/v1/end-of-day/runs/$id", reader())

            val run = response.body(200)
            run["id"].asString() shouldBe id
            response.getHeader("ETag") shouldBe "\"${run["version"].asLong()}\""
            run["steps"].values().map { it["name"].asString() } shouldContainExactly STEPS
            run["steps"].values().map { it["status"].asString() } shouldContainExactly
                listOf("COMPLETED", "COMPLETED", "FAILED", "PENDING")
        }

    @Test
    fun `the issues list the step failure and the one raised by the fake`() =
        settled {
            val id = failedRun()["id"].asString()
            val auth = reader()

            val issues = allItems("/api/v1/end-of-day/runs/$id/issues", auth)

            issues.map { it["kind"].asString() } shouldContainExactly listOf("TEST_PENDING", "STEP_FAILED")
            issues.forEach {
                it["runId"].asString() shouldBe id
                it["id"] shouldNotBe null
                it["resolved"].asBoolean() shouldBe false
                it["createdAt"] shouldNotBe null
            }
            issues.last()["stepName"].asString() shouldBe THREE
            issues.last()["reference"].asString() shouldBe THREE
            issues.first()["stepName"].asString() shouldBe TWO
            val one = get("/api/v1/end-of-day/runs/$id/issues?limit=1", auth).body(200)
            one["items"].size() shouldBe 1
            one["nextCursor"].isNull shouldBe false
        }

    @Test
    fun `an unknown id is a 404 on the detail and on the issues`() {
        val auth = reader()
        val id = UuidV7.generate()

        get("/api/v1/end-of-day/runs/$id", auth).problem(404, "END_OF_DAY_RUN_NOT_FOUND")
        get("/api/v1/end-of-day/runs/$id/issues", auth).problem(404, "END_OF_DAY_RUN_NOT_FOUND")
    }

    @Test
    fun `a tampered cursor is INVALID_CURSOR on the list and on the issues`() =
        settled {
            val id = failedRun()["id"].asString()
            val auth = reader()

            get("/api/v1/end-of-day/runs?cursor=adulterado", auth).problem(400, "INVALID_CURSOR")
            get("/api/v1/end-of-day/runs/$id/issues?cursor=adulterado", auth).problem(400, "INVALID_CURSOR")
        }

    private companion object {
        const val PAGE = 100
    }
}
