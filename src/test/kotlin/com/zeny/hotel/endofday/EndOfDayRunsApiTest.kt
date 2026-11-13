package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O fechamento manual pela API (M6 E10, Task 9): `POST /api/v1/end-of-day/runs`, síncrono com espera limitada, com
 * `Idempotency-Key` e aprovação de superior. No banco isolado, com as etapas falsas; todo teste termina com
 * [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto
class EndOfDayRunsApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val context: ApplicationContext,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private fun settled(block: () -> Unit) {
        try {
            probe.reset()
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun manager(): TestUser = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))

    private fun start(
        auth: RequestPostProcessor,
        body: Any? = null,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs") {
                with(auth)
                if (key != null) header(IDEMPOTENCY_KEY_HEADER, key)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(body ?: emptyMap<String, Any>())
            }.andReturn()
            .response

    private fun start(
        auth: RequestPostProcessor,
        date: LocalDate,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ) = start(auth, mapOf("businessDate" to date.toString()), key, approvalToken)

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single()

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    /** Um aprovador com `runs.execute` e PIN, e a resposta da aprovação para o solicitante na sessão dada. */
    private fun approve(
        requester: TestUser,
        sessionId: UUID,
        required: JsonNode,
    ): Pair<TestUser, JsonNode> {
        synchronizer.synchronize()
        val approver = manager()
        iam.setPin(approver, "4321")
        val response =
            iam.call(
                POST,
                "/api/v1/auth/approvals",
                asUser(requester.id.value, sessionId),
                mapOf(
                    "action" to required["action"].asString(),
                    "permission" to required["permission"].asString(),
                    "resourceRef" to required["resourceRef"].asString(),
                    "approverLogin" to approver.login,
                    "approverPin" to "4321",
                    "reason" to "Fechamento fora do horário",
                ),
            )
        return approver to response.body(201)
    }

    @Test
    fun `without the permission the manual run requires approval`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val user = iam.createUser()

            val problem = start(asUser(user.id.value), date).problem(403, "APPROVAL_REQUIRED")

            problem["action"].asString() shouldBe "endofday.run.start"
            problem["permission"].asString() shouldBe "endofday.runs.execute"
            problem["resourceRef"].asString() shouldBe "endofday.business-date:$date"
            runCount(date) shouldBe 0
        }

    @Test
    fun `without the permission the 409s are not revealed`() =
        settled {
            val closed = hotelApi.currentBusinessDate()
            start(asUser(manager().id.value), closed).body(201)["status"].asString() shouldBe "COMPLETED"

            start(asUser(iam.createUser().id.value), closed).problem(403, "APPROVAL_REQUIRED")
        }

    @Test
    fun `with an approval the run executes and the trail has requester and approver`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val required = start(auth, date).problem(403, "APPROVAL_REQUIRED")
            val (approver, approval) = approve(requester, sessionId, required)

            val run = start(auth, date, approvalToken = approval["approvalToken"].asString()).body(201)

            run["status"].asString() shouldBe "COMPLETED"
            run["trigger"].asString() shouldBe "MANUAL"
            run["triggeredBy"].asString() shouldBe requester.id.value.toString()
            run["steps"].values().map { it["status"].asString() } shouldContainExactly STEPS.map { "COMPLETED" }
            approval["approverId"].asString() shouldBe approver.id.value.toString()
            val trail =
                jdbc
                    .sql(
                        "SELECT user_id, approval_id FROM audit.audit_entry " +
                            "WHERE entity_id = :id AND action = 'endofday.run.start'",
                    ).param("id", run["id"].asString())
                    .query()
                    .listOfRows()
            trail.map { it["user_id"] } shouldContainExactly listOf(requester.id.value)
            trail.map { it["approval_id"] } shouldContainExactly
                listOf(UUID.fromString(approval["approvalId"].asString()))
        }

    @Test
    fun `a manager runs it directly`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            val response = start(asUser(manager().id.value), date)

            val run = response.body(201)
            response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/end-of-day/runs/${run["id"].asString()}"
            run["businessDate"].asString() shouldBe date.toString()
            run["status"].asString() shouldBe "COMPLETED"
            run["trigger"].asString() shouldBe "MANUAL"
            run["startedAt"].isNull shouldBe false
            run["finishedAt"].isNull shouldBe false
            run["version"].asLong() shouldBe
                jdbc
                    .sql("SELECT version FROM endofday.run WHERE id = :id")
                    .param("id", UUID.fromString(run["id"].asString()))
                    .query(Long::class.java)
                    .single()
            val steps = run["steps"].values()
            steps.map { it["name"].asString() } shouldContainExactly STEPS
            steps.map { it["position"].asInt() } shouldContainExactly listOf(10, 20, 30, 40)
            steps.map { it["attempts"].asInt() } shouldContainExactly listOf(1, 1, 1, 1)
            steps.forEach {
                it["startedAt"].isNull shouldBe false
                it["finishedAt"].isNull shouldBe false
                it["result"].asString() shouldBe "DONE"
            }
        }

    @Test
    fun `a new run for a closed date is a 409 with a stable code`() =
        settled {
            val auth = asUser(manager().id.value)
            val closed = hotelApi.currentBusinessDate()
            start(auth, closed).body(201)["status"].asString() shouldBe "COMPLETED"
            val current = hotelApi.currentBusinessDate()
            current shouldBe closed.plusDays(1)

            start(auth, closed).problem(409, "END_OF_DAY_ALREADY_CLOSED")
            val mismatch = start(auth, current.plusDays(1)).problem(409, "END_OF_DAY_DATE_MISMATCH")

            mismatch["currentBusinessDate"].asString() shouldBe current.toString()
            runCount(current.plusDays(1)) shouldBe 0
        }

    @Test
    fun `a failed run is reported as existing`() =
        settled {
            val auth = asUser(manager().id.value)
            val date = hotelApi.currentBusinessDate()
            probe.failNext(THREE, 1)

            val failed = start(auth, date).body(201)
            failed["status"].asString() shouldBe "FAILED"
            failed["steps"].values().map { it["status"].asString() } shouldContainExactly
                listOf("COMPLETED", "COMPLETED", "FAILED", "PENDING")

            val exists = start(auth, date).problem(409, "END_OF_DAY_RUN_EXISTS")
            exists["runId"].asString() shouldBe failed["id"].asString()
        }

    @Test
    fun `a run held by another executor is returned as RUNNING`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val held =
                lockProvider
                    .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
                    .orElseThrow()
            try {
                val run = start(asUser(manager().id.value), date).body(201)

                run["status"].asString() shouldBe "RUNNING"
                run["finishedAt"].isNull shouldBe true
                run["steps"].values().map { it["status"].asString() } shouldContainExactly STEPS.map { "PENDING" }
                invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            } finally {
                held.unlock()
            }
        }

    @Test
    fun `two simultaneous manual runs for the same date produce one run`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val calls =
                    (1..2).map {
                        pool.submit<MockHttpServletResponse> {
                            ready.countDown()
                            go.await()
                            start(auth, date)
                        }
                    }
                ready.await(5, TimeUnit.SECONDS) shouldBe true
                go.countDown()
                val responses = calls.map { it.get(60, TimeUnit.SECONDS) }

                responses.map { it.status }.sorted() shouldContainExactly listOf(201, 409)
                val refused = json.readTree(responses.single { it.status == 409 }.getContentAsString(Charsets.UTF_8))
                refused["code"].asString() shouldBeIn listOf("END_OF_DAY_RUN_EXISTS", "END_OF_DAY_ALREADY_CLOSED")
                runCount(date) shouldBe 1
                invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            } finally {
                pool.shutdownNow()
            }
        }

    @Test
    fun `repeating the POST with the same idempotency key returns the stored response`() =
        settled {
            val auth = asUser(manager().id.value)
            val date = hotelApi.currentBusinessDate()
            val key = UUID.randomUUID().toString()
            val first = start(auth, date, key)
            val stored = first.body(201)

            val repeated = start(auth, date, key)

            repeated.body(201) shouldBe stored
            repeated.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            repeated.getHeader(HttpHeaders.LOCATION) shouldBe first.getHeader(HttpHeaders.LOCATION)
            runCount(date) shouldBe 1
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `without an idempotency key the POST is refused`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            start(asUser(manager().id.value), date, key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")

            runCount(date) shouldBe 0
        }

    @Test
    fun `missing businessDate is a validation error`() =
        settled {
            val problem = start(asUser(manager().id.value)).problem(400, "VALIDATION_FAILED")

            problem["errors"].values().map { it["field"].asString() } shouldContainExactly listOf("businessDate")
            runCount(hotelApi.currentBusinessDate()) shouldBe 0
        }

    private companion object {
        val HELD_FOR: Duration = Duration.ofMinutes(1)
    }
}
