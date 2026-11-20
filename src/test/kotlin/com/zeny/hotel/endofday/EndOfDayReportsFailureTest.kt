package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A falha do render do PDF no meio do fechamento (M6 E13, Task 1, Review Focus 1): a execução fica `FAILED` em
 * `end-of-day-reports`, a data não avança e nenhum anexo fica vinculado; desarmada a [PrintingRenderProbe], a retomada
 * pela API (com aprovação) conclui, a data avança, há um `DAY_REPORT` e o snapshot da primeira passagem é o mesmo.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayReportsFailureTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val render: PrintingRenderProbe,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    @Test
    fun `a render failure fails the reports step and the approved resume completes with the same snapshot`() {
        val date = hotel.currentBusinessDate()
        render.failRender()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            runStatus(run) shouldBe "FAILED"
            stepStatuses(run) shouldBe
                mapOf(
                    "daily-statistics" to "COMPLETED",
                    "end-of-day-reports" to "FAILED",
                    "advance-business-date" to "PENDING",
                )
            hotel.currentBusinessDate() shouldBe date
            archived(date).shouldBeEmpty()
            attachmentsOfDate(date) shouldBe 0
            val firstSnapshot = snapshotCreatedAt(date)

            render.reset()
            val resumed = resumeWithApproval(run)

            resumed["status"].asString() shouldBe "COMPLETED"
            runStatus(run) shouldBe "COMPLETED"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            archived(date).size shouldBe 1
            attachmentsOfDate(date) shouldBe 1
            snapshotCreatedAt(date) shouldBe firstSnapshot
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun resumeWithApproval(run: EndOfDayRunId): JsonNode {
        val requester = iam.createUser()
        val sessionId = UuidV7.generate()
        val auth = asUser(requester.id.value, sessionId)
        val required = resume(auth, run).also { it.status shouldBe 403 }
        val token = approve(requester, sessionId, json.readTree(required.body))
        val response = resume(auth, run, token["approvalToken"].asString())
        withClue(response.body) { response.status shouldBe 200 }
        return json.readTree(response.body)
    }

    private class Reply(
        val status: Int,
        val body: String,
    )

    private fun resume(
        auth: RequestPostProcessor,
        run: EndOfDayRunId,
        approvalToken: String? = null,
    ): Reply {
        val response =
            mockMvc
                .post("/api/v1/end-of-day/runs/${run.value}/resume") {
                    with(auth)
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
                }.andReturn()
                .response
        return Reply(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    /** Um aprovador com `runs.execute` e PIN, e a resposta da aprovação para o solicitante na sessão dada. */
    private fun approve(
        requester: TestUser,
        sessionId: UUID,
        required: JsonNode,
    ): JsonNode {
        synchronizer.synchronize()
        val approver = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))
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
                    "reason" to "Retomada depois da falha do PDF",
                ),
            )
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
        return json.readTree(response.getContentAsString(Charsets.UTF_8))
    }

    private fun runStatus(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun stepStatuses(run: EndOfDayRunId): Map<String, String> =
        jdbc
            .sql(
                "SELECT name, status FROM endofday.run_step WHERE run_id = :id " +
                    "AND name IN ('daily-statistics', 'end-of-day-reports', 'advance-business-date')",
            ).param("id", run.value)
            .query { rs, _ -> rs.getString("name") to rs.getString("status") }
            .list()
            .toMap()

    private fun archived(date: LocalDate): List<UUID> =
        jdbc
            .sql("SELECT id FROM reporting.archived_report WHERE business_date = :date")
            .param("date", date)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun attachmentsOfDate(date: LocalDate): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM files.stored_file f WHERE f.purpose = 'reporting.day-report' " +
                    "AND f.owner_entity_id IN " +
                    "(SELECT id::text FROM reporting.archived_report WHERE business_date = :date)",
            ).param("date", date)
            .query(Int::class.java)
            .single()

    private fun snapshotCreatedAt(date: LocalDate): OffsetDateTime =
        jdbc
            .sql("SELECT created_at FROM reporting.daily_statistics WHERE business_date = :date")
            .param("date", date)
            .query { rs, _ -> rs.getObject("created_at", OffsetDateTime::class.java) }
            .single()
}
