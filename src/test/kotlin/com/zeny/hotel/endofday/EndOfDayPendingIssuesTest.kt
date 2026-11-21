package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reporting.EndOfDayPendingSeed
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A etapa real `end-of-day-pending` (M6 E13, D3, 950): as contas com saldo, os caixas abertos e as submissões FNRH
 * pendentes viram pendências do fechamento, com a mensagem no formato do D3 e sem dado de hóspede, e o aviso só leva o
 * tipo e a referência ou a contagem (Review Focus 4). O banco isolado acumula pendências: afirma só pelos ids próprios.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayPendingIssuesTest(
    @Autowired hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val seeds = EndOfDayPendingSeed(iam, ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc), jdbc, hotel)

    @Test
    fun `the closing raises one issue per pending item and the notice carries no personal data`() {
        val reader =
            iam.createUserWith(
                TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
            )
        val seeded = seeds.seed()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            jdbc
                .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = 'end-of-day-pending'")
                .param("id", run.value)
                .query { rs, _ -> rs.getString("status") to rs.getString("result") }
                .single() shouldBe ("COMPLETED" to "DONE")
            val issues = issuesOf(run, iam.asTestUser(reader))
            val mine =
                issues.filter {
                    it["reference"].asString() in
                        setOf("${seeded.folioId}", "${seeded.shiftId}", "${seeded.reservationId}")
                }
            mine.forEach { it["stepName"].asString() shouldBe "end-of-day-pending" }
            val rows = mine.map { Triple(it["kind"].asString(), it["reference"].asString(), it["message"].asString()) }
            rows shouldContain
                Triple(
                    "FOLIO_OPEN_BALANCE",
                    "${seeded.folioId}",
                    "FOLIO_OPEN_BALANCE: conta ${seeded.folioNumber} da reserva ${seeded.reservationCode}",
                )
            rows shouldContain
                Triple(
                    "CASHIER_SHIFT_OPEN",
                    "${seeded.shiftId}",
                    "CASHIER_SHIFT_OPEN: caixa ${seeded.shiftNumber} aberto em ${seeded.openedOn}",
                )
            rows shouldContain
                Triple(
                    "FNRH_SUBMISSION_PENDING",
                    "${seeded.reservationId}",
                    "FNRH_SUBMISSION_PENDING: FNRH UPDATE_RESERVATION FAILED",
                )
            mine.joinToString { it.toString() } shouldNotContain seeded.marker
            mine.joinToString { it.toString() } shouldNotContain seeded.balance
            mine.joinToString { it.toString() } shouldNotContain EndOfDayPendingSeed.LAST_FAILURE

            val notices = noticeBodies(reader.id.value)
            notices.count { it.startsWith("FOLIO_OPEN_BALANCE:") } shouldBe 1
            notices.count { it.startsWith("CASHIER_SHIFT_OPEN:") } shouldBe 1
            notices.count { it.startsWith("FNRH_SUBMISSION_PENDING:") } shouldBe 1
            notices.joinToString("|").let {
                it shouldNotContain seeded.folioNumber
                it shouldNotContain seeded.reservationCode
                it shouldNotContain seeded.shiftNumber
                it shouldNotContain "conta "
                it shouldNotContain "FNRH UPDATE"
                it shouldNotContain seeded.marker
            }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun issuesOf(
        run: EndOfDayRunId,
        auth: org.springframework.test.web.servlet.request.RequestPostProcessor,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "&cursor=$it" }.orEmpty()
            val page = iam.json(iam.call(GET, "/api/v1/end-of-day/runs/${run.value}/issues?limit=$PAGE$suffix", auth))
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return items
    }

    private fun noticeBodies(userId: UUID): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", userId)
            .query { rs, _ -> rs.getString("body") }
            .list()

    private companion object {
        const val PAGE = 100
    }
}
