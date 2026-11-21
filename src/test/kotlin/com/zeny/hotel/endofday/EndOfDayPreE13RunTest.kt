package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * O fechamento aberto antes das etapas da E13 existirem (M6 E13, Review Focus 3): uma execução `RUNNING` com as etapas
 * até `occupied-rooms-dirty` concluídas e sem as linhas de 800, 900 e 950 é retomada e conclui com as três antes do
 * avanço da data; uma data `COMPLETED` sem snapshot não quebra o painel, os relatórios nem a consulta do arquivo.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
class EndOfDayPreE13RunTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val iam: IamTestSupport,
) {
    @Test
    fun `a run opened before the new steps picks them up before advancing the date`() {
        EndOfDayTestRuns.settle(context)
        val date = hotel.currentBusinessDate()
        val run = insertRun(date, "RUNNING", completedThrough = LAST_OLD_POSITION, includeAdvance = true)
        try {
            engine.execute(EndOfDayRunId(run), WAIT)

            val steps =
                jdbc
                    .sql("SELECT name, status FROM endofday.run_step WHERE run_id = :id ORDER BY position")
                    .param("id", run)
                    .query { rs, _ -> rs.getString("name") to rs.getString("status") }
                    .list()
            steps.takeLast(NEW_AND_ADVANCE) shouldContainExactly
                listOf(
                    "daily-statistics" to "COMPLETED",
                    "end-of-day-reports" to "COMPLETED",
                    "end-of-day-pending" to "COMPLETED",
                    "advance-business-date" to "COMPLETED",
                )
            steps.all { it.second == "COMPLETED" } shouldBe true
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a completed date without a snapshot still answers the panel, the reports and the archive`() {
        val date = LocalDate.of(PAST_YEAR, 1, 1).plusDays(UUID.randomUUID().leastSignificantBits.mod(PAST_SPAN))
        insertRun(date, "COMPLETED", completedThrough = Int.MAX_VALUE, includeAdvance = true)
        val admin = iam.asAdmin()

        listOf(
            "/api/v1/reports/day-panel",
            "/api/v1/reports/end-of-day-pending",
            "/api/v1/reports/housekeeping?from=$date&to=$date",
            "/api/v1/reports/archive?date=$date",
        ).forEach { path -> iam.call(GET, path, admin).status shouldBe 200 }
        iam.json(iam.call(GET, "/api/v1/reports/archive?date=$date", admin))["reports"].size() shouldBe 0
    }

    private fun insertRun(
        date: LocalDate,
        status: String,
        completedThrough: Int,
        includeAdvance: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO endofday.run " +
                    "(id, business_date, trigger, status, started_at, finished_at, updated_at, version) " +
                    "VALUES (:id, :date, 'AUTOMATIC', :status, now(), " +
                    "CASE WHEN :status = 'RUNNING' THEN NULL ELSE now() END, now(), 0)",
            ).param("id", id)
            .param("date", date)
            .param("status", status)
            .update()
        registry.slots
            .filter { it.position <= LAST_OLD_POSITION || (includeAdvance && it.position == ADVANCE_POSITION) }
            .forEach {
                val done = it.position <= completedThrough
                jdbc
                    .sql(
                        "INSERT INTO endofday.run_step " +
                            "(run_id, name, position, status, started_at, finished_at, result, attempts) " +
                            "VALUES (:run, :name, :position, :status, " +
                            "CASE WHEN :done THEN now() END, CASE WHEN :done THEN now() END, " +
                            "CASE WHEN :done THEN 'DONE' END, :attempts)",
                    ).param("run", id)
                    .param("name", it.name)
                    .param("position", it.position)
                    .param("status", if (done) "COMPLETED" else "PENDING")
                    .param("done", done)
                    .param("attempts", if (done) 1 else 0)
                    .update()
            }
        return id
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
        const val LAST_OLD_POSITION = 700
        const val ADVANCE_POSITION = 1000
        const val NEW_AND_ADVANCE = 4
        const val PAST_YEAR = 1990
        const val PAST_SPAN = 3000L
    }
}
