package com.zeny.hotel.endofday

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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import java.util.Base64
import java.util.UUID

/**
 * Ataques às consultas do fechamento (M6 E10, Task 11), pelo testador: cursor adulterado, de outra rota, truncado, com
 * chave de tipo errado ou valor extremo; `limit` fora da convenção; ordem total e estável ao percorrer as páginas, com
 * execução nova entre uma página e outra; `ETag` que acompanha a versão; permissão `execute` sem `read`; id malformado;
 * pendências isoladas por execução e resposta sem outro dado pessoal. No banco isolado, afirmando só sobre as próprias
 * execuções.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto e um ataque por teste
class EndOfDayQueriesApiAttackTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val context: ApplicationContext,
) {
    private val read = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }
    private val someId = UuidV7.generate().toString()

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

    /** Fecha a data corrente com a pendência do fake na etapa dois e a terceira falhando: a execução `FAILED`. */
    private fun failedRunId(): String {
        probe.raiseIssueOn(TWO, "TEST_PENDING")
        probe.failNext(THREE, 1)
        return startRun("FAILED")["id"].asString()
    }

    private fun get(
        path: String,
        auth: RequestPostProcessor? = null,
        headers: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                if (auth != null) with(auth)
                headers.forEach { (name, value) -> header(name, value) }
            }.andReturn()
            .response

    private fun MockHttpServletResponse.text(): String = getContentAsString(Charsets.UTF_8)

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(text()) { this.status shouldBe status }
        return json.readTree(text())
    }

    private fun encode(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    /** Um cursor v1 bem formado com as chaves dadas. */
    private fun forged(vararg keys: String) = encode("c1:" + keys.joinToString("|"))

    private fun ids(page: JsonNode): List<String> = page["items"].values().map { it["id"].asString() }

    private fun pagesOf(
        path: String,
        auth: RequestPostProcessor,
        limit: Int,
        onPage: (Int) -> Unit = {},
    ): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "&cursor=$it" }.orEmpty()
            val page = get("$path?limit=$limit$suffix", auth).body(200)
            pages += ids(page)
            onPage(pages.size)
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return pages
    }

    private fun MockHttpServletResponse.shouldBeInvalidCursor(what: String) {
        withClue("$what -> $status ${text()}") {
            status shouldBe 400
            json.readTree(text())["code"].asString() shouldBe "INVALID_CURSOR"
        }
    }

    @Test
    fun `forged cursors on the list and on the issues are 400 INVALID_CURSOR and never 500`() =
        settled {
            val runId = failedRunId()
            val auth = reader()
            val real = get("/api/v1/end-of-day/runs?limit=1", auth).body(200)["nextCursor"].asString()
            val realIssues = get("/api/v1/end-of-day/runs/$runId/issues?limit=1", auth).body(200)["nextCursor"]
            val forgeries =
                listOf(
                    real.dropLast(3),
                    real + "AAAA",
                    real.reversed(),
                    "x$real",
                    "!!!!",
                    "%20",
                    "a".repeat(2000),
                    encode("v9:2026-01-01|$someId"),
                    encode("c1:"),
                    encode("c1:|"),
                    forged("2026-01-01"),
                    forged("nao-e-data", someId),
                    forged("2026-01-01", "nao-e-uuid"),
                    forged("2026-01-01", someId.uppercase()),
                    forged("2026-01-01", ""),
                    forged("", ""),
                    forged("2026-02-30", someId),
                    forged("2026-01-01", "00000000-0000-0000-0000-00000000000"),
                    "c1:2026-01-01|$someId",
                )
            val issuesForgeries =
                forgeries +
                    listOf(
                        forged("2026-01-01", someId),
                        forged("nao-e-instante", someId),
                        forged("2026-01-01T00:00:00Z"),
                    ) +
                    listOfNotNull(realIssues.takeUnless { it.isNull }?.asString()?.dropLast(2))
            (forgeries + forged("2026-01-01T00:00:00Z", someId)).forEach {
                get("/api/v1/end-of-day/runs?cursor=$it", auth).shouldBeInvalidCursor("runs cursor=$it")
            }
            issuesForgeries.forEach {
                get("/api/v1/end-of-day/runs/$runId/issues?cursor=$it", auth)
                    .shouldBeInvalidCursor("issues cursor=$it")
            }
        }

    @Test
    fun `a cursor of the other route is INVALID_CURSOR in both directions`() =
        settled {
            val runId = failedRunId()
            val auth = reader()
            val runsCursor = get("/api/v1/end-of-day/runs?limit=1", auth).body(200)["nextCursor"]
            val issuesCursor = get("/api/v1/end-of-day/runs/$runId/issues?limit=1", auth).body(200)["nextCursor"]
            withClue("precisa de duas páginas em cada rota") {
                runsCursor.isNull shouldBe false
                issuesCursor.isNull shouldBe false
            }

            get("/api/v1/end-of-day/runs/$runId/issues?cursor=${runsCursor.asString()}", auth)
                .shouldBeInvalidCursor("cursor de runs em issues")
            get("/api/v1/end-of-day/runs?cursor=${issuesCursor.asString()}", auth)
                .shouldBeInvalidCursor("cursor de issues em runs")
        }

    @Test
    fun `extreme but well formed date cursors on the list never become a 500`() =
        settled {
            failedRunId()
            val auth = reader()
            val dates = listOf("+999999999-12-31", "-999999999-01-01", "0000-01-01", "9999-12-31", "+294277-01-01")
            dates.forEach {
                val r = get("/api/v1/end-of-day/runs?cursor=${forged(it, someId)}", auth)
                withClue("runs date $it -> ${r.status} ${r.text()}") {
                    (r.status in listOf(200, 400)) shouldBe true
                    if (r.status == 400) json.readTree(r.text())["code"].asString() shouldBe "INVALID_CURSOR"
                }
            }
        }

    @Test
    fun `extreme but well formed instant cursors on the issues never become a 500`() =
        settled {
            val runId = failedRunId()
            val auth = reader()
            val instants =
                listOf(
                    "+1000000000-12-31T23:59:59.999999999Z",
                    "-1000000000-01-01T00:00:00Z",
                    "0000-01-01T00:00:00Z",
                    "+294277-01-01T00:00:00Z",
                )
            val statuses =
                instants.associateWith {
                    get("/api/v1/end-of-day/runs/$runId/issues?cursor=${forged(it, someId)}", auth).status
                }
            withClue("status por instante: $statuses") {
                statuses.values.all { it == 200 || it == 400 } shouldBe true
            }
        }

    @Test
    fun `a bad limit is 400 on the list and on the issues and a good one is accepted`() {
        val auth = reader()
        val id = UuidV7.generate()
        listOf("0", "-1", "201", "abc", "1.5", "99999999999999999999").forEach { limit ->
            listOf("/api/v1/end-of-day/runs", "/api/v1/end-of-day/runs/$id/issues").forEach { path ->
                val r = get("$path?limit=$limit", auth)
                withClue("$path?limit=$limit -> ${r.status} ${r.text()}") { r.status shouldBe 400 }
            }
        }
        get("/api/v1/end-of-day/runs?limit=1", auth).status shouldBe 200
        get("/api/v1/end-of-day/runs?limit=200", auth).status shouldBe 200
    }

    @Test
    fun `walking every page never repeats nor skips an own issue and the order is by creation`() =
        settled {
            probe.raiseIssueOn(TWO, "TEST_PENDING")
            probe.itemsOn(THREE, 7, null)
            probe.failNext(THREE, 1)
            val runId = startRun("FAILED")["id"].asString()
            val auth = reader()
            val path = "/api/v1/end-of-day/runs/$runId/issues"

            val all = pagesOf(path, auth, 100).flatten()
            all.size shouldBe 9 // a do fake, 7 itens e a STEP_FAILED
            for (limit in listOf(1, 2, 3, 4)) {
                val pages = pagesOf(path, auth, limit)
                withClue("limit=$limit") {
                    pages.flatten() shouldContainExactly all
                    pages.dropLast(1).forEach { it.size shouldBe limit }
                }
            }
            val items = get("$path?limit=100", auth).body(200)["items"].values()
            items.map { it["createdAt"].asString() }.let { it shouldContainExactly it.sorted() }
        }

    @Test
    fun `a run created between two pages is neither repeated nor makes an own run skip`() =
        settled {
            startRun("COMPLETED")
            startRun("COMPLETED")
            val auth = reader()
            val before = pagesOf("/api/v1/end-of-day/runs", auth, 100).flatten()
            (before.size >= 2) shouldBe true

            var injected = ""
            val pages =
                pagesOf("/api/v1/end-of-day/runs", auth, 1) { n ->
                    if (n == 1) injected = failedRunId()
                }.flatten()

            pages.distinct() shouldContainExactly pages
            withClue("a nova é mais recente que o cursor: não entra nas páginas seguintes e nada some") {
                (injected in pages) shouldBe false
                pages shouldContainExactly before
            }
        }

    @Test
    fun `the ETag moves when the run changes and the list is date descending`() =
        settled {
            val id = failedRunId()
            val auth = reader()
            val failed = get("/api/v1/end-of-day/runs/$id", auth)
            val etagFailed = failed.getHeader("ETag")
            failed.body(200)["status"].asString() shouldBe "FAILED"

            EndOfDayTestRuns.settle(context)

            val done = get("/api/v1/end-of-day/runs/$id", auth)
            done.body(200)["status"].asString() shouldBe "COMPLETED"
            (done.getHeader("ETag") != etagFailed) shouldBe true
            get("/api/v1/end-of-day/runs/$id", auth).getHeader("ETag") shouldBe done.getHeader("ETag")
            val dates =
                get("/api/v1/end-of-day/runs?limit=200", auth)
                    .body(200)["items"]
                    .values()
                    .map { it["businessDate"].asString() }
            dates shouldContainExactly dates.sortedDescending()
        }

    @Test
    fun `If-None-Match never turns the detail into a 5xx`() =
        settled {
            val id = failedRunId()
            val auth = reader()
            val etag = get("/api/v1/end-of-day/runs/$id", auth).getHeader("ETag")!!

            listOf(etag, "*", "W/$etag", "lixo", "\"999999\"").forEach {
                val r = get("/api/v1/end-of-day/runs/$id", auth, mapOf("If-None-Match" to it))
                withClue("If-None-Match $it -> ${r.status}") { (r.status in listOf(200, 304)) shouldBe true }
            }
        }

    @Test
    fun `only execute without read is 403 on all three, a malformed id is 400 and 403 comes first`() {
        val onlyExecute = asUser(iam.createUserWith(TestGrant(execute, PermissionScope.ALL)).id.value)
        val id = UuidV7.generate()
        listOf("/api/v1/end-of-day/runs", "/api/v1/end-of-day/runs/$id", "/api/v1/end-of-day/runs/$id/issues")
            .forEach { get(it, onlyExecute).body(403)["code"].asString() shouldBe "PERMISSION_DENIED" }

        val auth = reader()
        listOf("nao-e-uuid", "${UUID.randomUUID()}x", "0", "%20").forEach { bad ->
            listOf("/api/v1/end-of-day/runs/$bad", "/api/v1/end-of-day/runs/$bad/issues").forEach {
                val r = get(it, auth)
                withClue("$it -> ${r.status} ${r.text()}") { r.status shouldBe 400 }
            }
        }
        val none = asUser(iam.createUser().id.value)
        get("/api/v1/end-of-day/runs/$id/issues?cursor=adulterado", none)
            .body(403)["code"]
            .asString() shouldBe "PERMISSION_DENIED"
    }

    @Test
    fun `the issues of a run never show up in another run`() =
        settled {
            probe.itemsOn(TWO, 2, null)
            val first = failedRunId()
            EndOfDayTestRuns.settle(context)
            probe.itemsOn(TWO, 3, null)
            val second = startRun("COMPLETED")["id"].asString()
            val auth = reader()

            val a = get("/api/v1/end-of-day/runs/$first/issues?limit=100", auth).body(200)["items"].values()
            val b = get("/api/v1/end-of-day/runs/$second/issues?limit=100", auth).body(200)["items"].values()

            a.all { it["runId"].asString() == first } shouldBe true
            b.all { it["runId"].asString() == second } shouldBe true
            a.count { it["kind"].asString() == "TEST_ITEM" } shouldBe 2
            b.count { it["kind"].asString() == "TEST_ITEM" } shouldBe 3
            val cursor = get("/api/v1/end-of-day/runs/$second/issues?limit=1", auth).body(200)["nextCursor"].asString()
            val crossed = get("/api/v1/end-of-day/runs/$first/issues?limit=100&cursor=$cursor", auth).body(200)
            crossed["items"].values().all { it["runId"].asString() == first } shouldBe true
        }

    @Test
    fun `the issue response has only the contract fields and the run views never carry the message`() =
        settled {
            val id = failedRunId()
            val auth = reader()

            val raw = get("/api/v1/end-of-day/runs/$id/issues", auth).body(200)
            raw["items"].values().forEach {
                it.propertyNames().toList().sorted() shouldContainExactly
                    listOf("createdAt", "id", "kind", "message", "reference", "resolved", "runId", "stepName")
            }
            get("/api/v1/end-of-day/runs/$id", auth).text() shouldNotContain "Pendência de teste."
            get("/api/v1/end-of-day/runs", auth).text() shouldNotContain "Pendência de teste."
        }
}
