package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayIssueSink
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.steps.DailyStatisticsStep
import com.zeny.hotel.endofday.application.steps.EndOfDayReportsStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * As etapas `daily-statistics` (800) e `end-of-day-reports` (900) no fechamento real (M6 E13, Task 1, D1, D2 e D4):
 * o snapshot e o PDF arquivado da data que fecha, as duas etapas antes do avanço da data, e a repetição das etapas
 * para a mesma data (a retomada de uma etapa `RUNNING`) sem segundo snapshot nem segundo anexo (Review Focus 2).
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
class EndOfDayReportingStepsTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val dailyStatistics: DailyStatisticsStep,
    @Autowired private val reports: EndOfDayReportsStep,
    @Autowired private val render: PrintingRenderProbe,
) {
    private data class Timed(
        val name: String,
        val status: String,
        val startedAt: OffsetDateTime,
        val finishedAt: OffsetDateTime,
    )

    @Test
    fun `a full run stores the snapshot and the sensitive day report before advancing the date`() {
        val date = hotel.currentBusinessDate()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            val steps = stepsOf(run)
            steps
                .map {
                    it.name to it.status
                }.filter { it.first in REPORTING_STEPS || it.first == ADVANCE } shouldContainExactly
                listOf(
                    "daily-statistics" to "COMPLETED",
                    "end-of-day-reports" to "COMPLETED",
                    "advance-business-date" to "COMPLETED",
                )
            steps.map { it.name }.takeLast(AFTER_ORDER) shouldContainExactly
                listOf("daily-statistics", "end-of-day-reports", ADVANCE)
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            statisticsRows(date) shouldBe 1
            val archived = archived(date)
            archived.size shouldBe 1
            val file = storedFile(archived.single())
            file["sensitive"] shouldBe true
            file["encryption_key_id"] shouldNotBe null
            file["content_type"] shouldBe "application/pdf"
            println("D4 (fechamento real, relógio parado): ${durations(steps)}")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `repeating both steps for the same date keeps one snapshot and one attachment`() {
        // Uma data histórica só deste teste: o fechamento real já cobre a data corrente.
        val date = LocalDate.of(HISTORIC_YEAR, 1, 1)
        val context = EndOfDayStepContext(EndOfDayRunId.new(), date, EndOfDayIssueSink { _, _, _ -> })

        val statsStart = System.nanoTime()
        dailyStatistics.execute(context)
        val statsMillis = Duration.ofNanos(System.nanoTime() - statsStart).toMillis()
        val reportsStart = System.nanoTime()
        reports.execute(context)
        val reportsMillis = Duration.ofNanos(System.nanoTime() - reportsStart).toMillis()
        val createdAt = snapshotCreatedAt(date)
        val file = archived(date).single()
        val renderMillis = Duration.ofNanos(render.renderNanos).toMillis()
        println(
            "D4 (chamada direta, data histórica sem movimento): daily-statistics=${statsMillis}ms " +
                "end-of-day-reports=${reportsMillis}ms (render=${renderMillis}ms)",
        )

        dailyStatistics.execute(context)
        reports.execute(context)

        statisticsRows(date) shouldBe 1
        snapshotCreatedAt(date) shouldBe createdAt
        archived(date) shouldContainExactly listOf(file)
        attachments(file) shouldBe 1
    }

    private fun stepsOf(run: EndOfDayRunId): List<Timed> =
        jdbc
            .sql(
                "SELECT name, status, started_at, finished_at FROM endofday.run_step " +
                    "WHERE run_id = :id ORDER BY position",
            ).param("id", run.value)
            .query { rs, _ ->
                Timed(
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getObject("started_at", OffsetDateTime::class.java),
                    rs.getObject("finished_at", OffsetDateTime::class.java),
                )
            }.list()

    private fun durations(steps: List<Timed>): String =
        steps
            .filter { it.name in REPORTING_STEPS }
            .joinToString { "${it.name}=${Duration.between(it.startedAt, it.finishedAt).toMillis()}ms" }

    private fun statisticsRows(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM reporting.daily_statistics WHERE business_date = :date")
            .param("date", date)
            .query(Int::class.java)
            .single()

    private fun snapshotCreatedAt(date: LocalDate): OffsetDateTime =
        jdbc
            .sql("SELECT created_at FROM reporting.daily_statistics WHERE business_date = :date")
            .param("date", date)
            .query { rs, _ -> rs.getObject("created_at", OffsetDateTime::class.java) }
            .single()

    private fun archived(date: LocalDate): List<UUID> =
        jdbc
            .sql("SELECT id FROM reporting.archived_report WHERE business_date = :date AND kind = 'DAY_REPORT'")
            .param("date", date)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun attachments(owner: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM files.stored_file " +
                    "WHERE purpose = 'reporting.day-report' AND owner_entity_id = :owner",
            ).param("owner", owner.toString())
            .query(Int::class.java)
            .single()

    private fun storedFile(owner: UUID): Map<String, Any?> =
        jdbc
            .sql(
                "SELECT sensitive, encryption_key_id, content_type FROM files.stored_file " +
                    "WHERE purpose = 'reporting.day-report' AND owner_entity_id = :owner",
            ).param("owner", owner.toString())
            .query { rs, _ ->
                mapOf(
                    "sensitive" to rs.getBoolean("sensitive"),
                    "encryption_key_id" to rs.getString("encryption_key_id"),
                    "content_type" to rs.getString("content_type"),
                )
            }.single()

    private companion object {
        val REPORTING_STEPS = setOf("daily-statistics", "end-of-day-reports")
        const val ADVANCE = "advance-business-date"
        const val AFTER_ORDER = 3
        const val HISTORIC_YEAR = 2001
    }
}
