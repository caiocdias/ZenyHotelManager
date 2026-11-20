package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID

/**
 * Variações da execução aberta antes do deploy da E13 (Review Focus 3) que o `EndOfDayPreE13RunTest` não cobre: ele
 * grava sempre a linha do avanço e sempre as etapas antigas concluídas. Aqui a execução para no meio das antigas e,
 * noutro teste, não tem a linha do avanço (a reconciliação do motor a recria).
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
class EndOfDayPreE13VariantsTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val engine: EndOfDayEngine,
) {
    @Test
    fun `a run stopped in the middle of the old steps finishes them and the new ones before advancing`() {
        EndOfDayTestRuns.settle(context)
        val date = hotel.currentBusinessDate()
        val run = insertRun(completedThrough = MIDDLE, withAdvanceRow = true)
        try {
            engine.execute(EndOfDayRunId(run), WAIT)

            allCompleted(run) shouldBe true
            order(run).last() shouldBe "advance-business-date"
            order(run).containsAll(listOf("daily-statistics", "end-of-day-reports", "end-of-day-pending")) shouldBe true
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a run without the advance row still ends with the new steps and the advance`() {
        EndOfDayTestRuns.settle(context)
        val date = hotel.currentBusinessDate()
        val run = insertRun(completedThrough = LAST_OLD_POSITION, withAdvanceRow = false)
        try {
            engine.execute(EndOfDayRunId(run), WAIT)

            allCompleted(run) shouldBe true
            order(run).takeLast(2) shouldBe listOf("end-of-day-pending", "advance-business-date")
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun allCompleted(run: UUID): Boolean =
        jdbc
            .sql("SELECT status FROM endofday.run_step WHERE run_id = :id")
            .param("id", run)
            .query(String::class.java)
            .list()
            .filterNotNull()
            .all { it == "COMPLETED" }

    private fun order(run: UUID): List<String> =
        jdbc
            .sql("SELECT name FROM endofday.run_step WHERE run_id = :id ORDER BY position")
            .param("id", run)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun insertRun(
        completedThrough: Int,
        withAdvanceRow: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO endofday.run " +
                    "(id, business_date, trigger, status, started_at, finished_at, updated_at, version) " +
                    "VALUES (:id, :date, 'AUTOMATIC', 'RUNNING', now(), NULL, now(), 0)",
            ).param("id", id)
            .param("date", hotel.currentBusinessDate())
            .update()
        registry.slots
            .filter { it.position <= LAST_OLD_POSITION || (withAdvanceRow && it.position == ADVANCE_POSITION) }
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
        const val MIDDLE = 300
        const val LAST_OLD_POSITION = 700
        const val ADVANCE_POSITION = 1000
    }
}
