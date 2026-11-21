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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Fecha as lacunas das revisões da Task 1 (M6 E13): o snapshot não é refeito (o `created_at` com o relógio parado não
 * prova isso; a linha inteira, com o `id`, prova) nem na retomada depois da falha do render nem na repetição das
 * etapas, e a sonda do render é zerada no início para medir só o fechamento real.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
class EndOfDayReportingIdentityTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val dailyStatistics: DailyStatisticsStep,
    @Autowired private val reports: EndOfDayReportsStep,
    @Autowired private val render: PrintingRenderProbe,
) {
    @Test
    fun `the resume after a render failure keeps the very same snapshot row and runs the statistics step once`() {
        val date = hotel.currentBusinessDate()
        render.reset()
        render.failRender()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            val before = snapshotRow(date)
            attempts(run, "daily-statistics") shouldBe 1

            EndOfDayTestRuns.settle(context)

            snapshotRow(date) shouldBe before
            attempts(run, "daily-statistics") shouldBe 1
            archived(date).size shouldBe 1
            attachments(archived(date).single()) shouldBe 1
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `repeating the steps keeps the same snapshot row and the same archived report`() {
        val date = LocalDate.of(HISTORIC_YEAR, 3, 4)
        val step = EndOfDayStepContext(EndOfDayRunId.new(), date, EndOfDayIssueSink { _, _, _ -> })
        render.reset()
        dailyStatistics.execute(step)
        reports.execute(step)
        val row = snapshotRow(date)
        val file = archived(date).single()

        dailyStatistics.execute(step)
        reports.execute(step)

        snapshotRow(date) shouldBe row
        archived(date) shouldContainExactly listOf(file)
        attachments(file) shouldBe 1
    }

    @Test
    fun `the real closing render time is measured from a zeroed probe`() {
        val date = hotel.currentBusinessDate()
        render.reset()
        render.renderNanos shouldBe 0
        try {
            EndOfDayTestRuns.closeCurrentDate(context)

            val nanos = render.renderNanos
            println("D4 (render do fechamento real): ${Duration.ofNanos(nanos).toMillis()}ms")
            (nanos > 0) shouldBe true
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun snapshotRow(date: LocalDate): String =
        jdbc
            .sql("SELECT to_jsonb(d)::text FROM reporting.daily_statistics d WHERE business_date = :date")
            .param("date", date)
            .query(String::class.java)
            .single()

    private fun attempts(
        run: EndOfDayRunId,
        step: String,
    ): Int =
        jdbc
            .sql("SELECT attempts FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", step)
            .query(Int::class.java)
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

    private companion object {
        const val HISTORIC_YEAR = 2002
    }
}
