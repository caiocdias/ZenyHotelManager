package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reporting.EndOfDayPendingSeed
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Fecha as lacunas das revisões da Task 2 (M6 E13): o formato exato do aviso (`<TIPO>: <uuid>` ou
 * `<TIPO>: <n> pendência(s)`), o saldo ausente também no formato pt-BR e o `step_name` nunca nulo nas pendências do
 * fechamento (os filtros `<> 'end-of-day-pending'` dos outros testes descartariam uma linha com `step_name` nulo).
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayPendingFormatTest(
    @Autowired hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val seeds = EndOfDayPendingSeed(iam, ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc), jdbc, hotel)

    @Test
    fun `the notice has the exact format, the balance is absent in both formats and no issue has a null step`() {
        val reader =
            iam.createUserWith(
                TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
            )
        val seeded = seeds.seed()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            val bodies = noticeBodies(reader.id.value).filter { it.substringBefore(':') in KINDS }
            bodies.map { it.substringBefore(':') }.toSet() shouldBe KINDS
            bodies.forEach { it shouldMatch FORMAT }

            val brazilian = seeded.balance.replace('.', ',')
            val everything = bodies + issueMessages(run)
            everything.joinToString("|").let {
                it shouldNotContain seeded.balance
                it shouldNotContain brazilian
                it shouldNotContain "R$"
            }
            seeded.balance shouldNotBe brazilian

            val nullSteps =
                jdbc
                    .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND step_name IS NULL")
                    .param("id", run.value)
                    .query(Int::class.java)
                    .single()
            nullSteps shouldBe 0
            val mine =
                jdbc
                    .sql(
                        "SELECT DISTINCT reference FROM endofday.run_issue WHERE run_id = :id " +
                            "AND step_name IS NOT DISTINCT FROM 'end-of-day-pending' AND reference IN (:refs)",
                    ).param("id", run.value)
                    .param("refs", listOf("${seeded.folioId}", "${seeded.shiftId}", "${seeded.reservationId}"))
                    .query(String::class.java)
                    .list()
                    .filterNotNull()
            mine shouldHaveSize 3
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun issueMessages(run: EndOfDayRunId): List<String> =
        jdbc
            .sql("SELECT message FROM endofday.run_issue WHERE run_id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun noticeBodies(userId: UUID): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", userId)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        val KINDS = setOf("FOLIO_OPEN_BALANCE", "CASHIER_SHIFT_OPEN", "FNRH_SUBMISSION_PENDING")
        val FORMAT = Regex("^[A-Z_]+: ([0-9a-f-]{36}|\\d+ pendência\\(s\\))$")
    }
}
