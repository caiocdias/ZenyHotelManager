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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
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

/**
 * A retomada manual pela API (M6 E10, Task 10): `POST /api/v1/end-of-day/runs/{id}/resume`, ação sensível com
 * aprovação de superior e `Idempotency-Key`. No banco isolado; todo teste termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto
class EndOfDayResumeApiTest(
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

    private fun resume(
        auth: RequestPostProcessor,
        id: String,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs/$id/resume") {
                with(auth)
                if (key != null) header(IDEMPOTENCY_KEY_HEADER, key)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
            }.andReturn()
            .response

    /** Fecha a data corrente com a terceira etapa falhando: devolve a execução `FAILED`. */
    private fun failedRun(): JsonNode {
        probe.failNext(THREE, 1)
        val response =
            mockMvc
                .post("/api/v1/end-of-day/runs") {
                    with(asUser(manager().id.value))
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"businessDate":"${hotelApi.currentBusinessDate()}"}"""
                }.andReturn()
                .response
        val run = response.body(201)
        run["status"].asString() shouldBe "FAILED"
        return run
    }

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun runStatus(id: String): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query(String::class.java)
            .single()

    @Test
    fun `without the permission the resume requires approval`() =
        settled {
            val id = failedRun()["id"].asString()

            val problem = resume(asUser(iam.createUser().id.value), id).problem(403, "APPROVAL_REQUIRED")

            problem["action"].asString() shouldBe "endofday.run.resume"
            problem["permission"].asString() shouldBe "endofday.runs.execute"
            problem["resourceRef"].asString() shouldBe "endofday.run:$id"
            runStatus(id) shouldBe "FAILED"
        }

    @Test
    fun `without the permission an unknown id is not revealed`() =
        settled {
            resume(asUser(iam.createUser().id.value), UuidV7.generate().toString()).problem(403, "APPROVAL_REQUIRED")
        }

    @Test
    fun `with an approval the run resumes and the trail has requester and approver`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val required = resume(auth, id).problem(403, "APPROVAL_REQUIRED")
            val (approver, approval) = approve(requester, sessionId, required)

            val resumed = resume(auth, id, approvalToken = approval["approvalToken"].asString()).body(200)

            resumed["status"].asString() shouldBe "COMPLETED"
            resumed["id"].asString() shouldBe id
            resumed["steps"].values().map { it["status"].asString() } shouldContainExactly STEPS.map { "COMPLETED" }
            invocations(date).take(2) shouldContainExactly listOf(1, 1)
            approval["approverId"].asString() shouldBe approver.id.value.toString()
            val trail =
                jdbc
                    .sql(
                        "SELECT user_id, approval_id FROM audit.audit_entry " +
                            "WHERE entity_id = :id AND action = 'endofday.run.resume'",
                    ).param("id", id)
                    .query()
                    .listOfRows()
            trail.map { it["user_id"] } shouldContainExactly listOf(requester.id.value)
            trail.map { it["approval_id"] } shouldContainExactly
                listOf(UUID.fromString(approval["approvalId"].asString()))
        }

    @Test
    fun `a manager resumes and a second resume is NOT_RESUMABLE`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)

            resume(auth, id).body(200)["status"].asString() shouldBe "COMPLETED"
            resume(auth, id).problem(409, "END_OF_DAY_RUN_NOT_RESUMABLE")

            invocations(date).take(2) shouldContainExactly listOf(1, 1)
        }

    @Test
    fun `an unknown id with the permission is a 404`() =
        settled {
            resume(asUser(manager().id.value), UuidV7.generate().toString())
                .problem(404, "END_OF_DAY_RUN_NOT_FOUND")
        }

    @Test
    fun `a malformed id is a 400`() =
        settled {
            resume(asUser(manager().id.value), "not-a-uuid").status shouldBe 400
        }

    @Test
    fun `a resume while another executor holds the lock is IN_PROGRESS`() =
        settled {
            val id = failedRun()["id"].asString()
            val held =
                lockProvider
                    .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
                    .orElseThrow()
            try {
                resume(asUser(manager().id.value), id).problem(409, "END_OF_DAY_RUN_IN_PROGRESS")
            } finally {
                held.unlock()
            }
        }

    @Test
    fun `repeating the POST with the same idempotency key returns the stored response`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val id = failedRun()["id"].asString()
            val auth = asUser(manager().id.value)
            val key = UUID.randomUUID().toString()
            val stored = resume(auth, id, key).body(200)

            val repeated = resume(auth, id, key)

            repeated.body(200) shouldBe stored
            repeated.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            invocations(date).take(2) shouldContainExactly listOf(1, 1)
        }

    @Test
    fun `without an idempotency key the POST is refused`() =
        settled {
            val id = failedRun()["id"].asString()

            resume(asUser(manager().id.value), id, key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")

            runStatus(id) shouldBe "FAILED"
        }

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
                    "reason" to "Retomada fora do horário",
                ),
            )
        return approver to response.body(201)
    }

    private companion object {
        val HELD_FOR: Duration = Duration.ofMinutes(1)
    }
}
