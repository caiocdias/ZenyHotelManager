package com.zeny.hotel.endofday

import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A etapa real de avanço da data (M6 E11, `advance-business-date`, 1000) no contexto das etapas reais
 * ([EndOfDayRealStepsTestConfiguration]): fecha a data corrente do banco isolado, publica `BusinessDateAdvanced` e,
 * presa atrás de quem trava a data para lançar (RF-FEC-05), desiste no prazo em vez de esperar sem fim. Cada teste
 * termina com [EndOfDayTestRuns.settle] e olha só a própria execução e os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // o registro, o motor, os repositórios e os apoios de transação, JDBC e relógio
class EndOfDayAdvanceStepTest(
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val hotel: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `the registry of the real steps ends with the advance and has no fake step`() {
        registry.slots.last() shouldBe StepSlot(ADVANCE, ADVANCE_POSITION)
        registry.slots.filter { it.name.startsWith("fake-") } shouldBe emptyList()
    }

    @Test
    fun `closing the current date advances it once and the next date gets its checklist routines`() {
        val routine = dailyRoutine()
        val date = hotel.currentBusinessDate()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            stepsOf(run) shouldContainExactly listOf(Triple(ADVANCE, "COMPLETED", "DONE"))
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            advanceTrails(date) shouldBe 1
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { executionsOf(routine, date.plusDays(1)) == 1 }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the advance gives up instead of waiting forever behind a stuck holder of the date`() {
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val date = hotel.currentBusinessDate()
            val locked = CountDownLatch(1)
            val holder =
                pool.submit {
                    tx.executeWithoutResult { _ ->
                        hotel.lockBusinessDate()
                        locked.countDown()
                        release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                }
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val started = System.nanoTime()
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            val waited = Duration.ofNanos(System.nanoTime() - started)

            assertGaveUp(run, date, "CannotAcquireLockException")
            waited shouldBeGreaterThanOrEqualTo LOCK_TIMEOUT.minusSeconds(1)
            waited shouldBeLessThan STATEMENT_TIMEOUT

            release.countDown()
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
            resume(run)
            statusOf(run) shouldBe "COMPLETED"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the advance gives up instead of waiting forever behind overlapping holders of the date`() {
        val stop = AtomicBoolean(false)
        val holders = Executors.newFixedThreadPool(HOLDERS)
        val feeder = Executors.newSingleThreadExecutor()
        try {
            val date = hotel.currentBusinessDate()
            val first = CountDownLatch(1)
            feeder.submit { feedOverlappingHolders(holders, stop, first) }
            first.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val started = System.nanoTime()
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            val waited = Duration.ofNanos(System.nanoTime() - started)

            assertGaveUp(run, date, "QueryTimeoutException")
            waited shouldBeGreaterThanOrEqualTo STATEMENT_TIMEOUT.minusSeconds(1)

            stop.set(true)
            feeder.shutdown()
            feeder.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            holders.shutdown()
            holders.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            resume(run)
            statusOf(run) shouldBe "COMPLETED"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            stop.set(true)
            feeder.shutdownNow()
            holders.shutdownNow()
            feeder.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            holders.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            EndOfDayTestRuns.settle(context)
        }
    }

    /**
     * Abre, a cada [OVERLAP_STEP], uma transação que trava a data (`FOR SHARE`) por [HOLD]: sempre há alguém com a
     * trava, mas nenhum a segura por mais que o `lock_timeout` do avanço. Para em [stop] ou no fim de [FEED_LIMIT].
     */
    private fun feedOverlappingHolders(
        holders: ExecutorService,
        stop: AtomicBoolean,
        first: CountDownLatch,
    ) {
        val deadline = System.nanoTime() + FEED_LIMIT.toNanos()
        while (!stop.get() && System.nanoTime() < deadline) {
            holders.submit {
                tx.executeWithoutResult { _ ->
                    hotel.lockBusinessDate()
                    first.countDown()
                    Thread.sleep(HOLD.toMillis())
                }
            }
            Thread.sleep(OVERLAP_STEP.toMillis())
        }
    }

    /**
     * A execução parou `FAILED` no avanço pelo [reason] (o prazo que esgotou), com a pendência `STEP_FAILED` dele, e a
     * data não mudou.
     */
    private fun assertGaveUp(
        run: EndOfDayRunId,
        date: LocalDate,
        reason: String,
    ) {
        statusOf(run) shouldBe "FAILED"
        stepsOf(run) shouldContainExactly listOf(Triple(ADVANCE, "FAILED", reason))
        issuesOf(run) shouldContainExactly listOf("STEP_FAILED" to ADVANCE)
        hotel.currentBusinessDate() shouldBe date
    }

    /** Retoma a execução `FAILED` como o `settle` e a roda pelo motor. */
    private fun resume(run: EndOfDayRunId) {
        tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run)).resume(clock.instant()) }
        engine.execute(run, Duration.ofSeconds(WAIT_SECONDS))
    }

    /** Uma rotina diária nova, de um modelo publicado e um setor novos, criada pela API. */
    private fun dailyRoutine(): UUID {
        val templates = ChecklistsTestSupport(iam)
        val template = templates.create()
        templates.publish(template)
        val department = StaffTestSupport(iam).createDepartment()
        val body =
            mapOf(
                "name" to "Rotina ${UuidV7.generate().toString().takeLast(SUFFIX)}",
                "templateId" to template,
                "departmentId" to department,
                "frequency" to "DAILY",
                "weekdays" to emptyList<String>(),
                "dueAt" to "08:00",
                "location" to mapOf("type" to "rooms.area", "id" to UuidV7.generate()),
            )
        return with(iam) {
            UUID.fromString(call(POST, ROUTINES, asAdmin(), body).expectStatus(201)!!["id"].asString())
        }
    }

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun stepsOf(run: EndOfDayRunId): List<Triple<String, String, String?>> =
        jdbc
            .sql("SELECT name, status, result FROM endofday.run_step WHERE run_id = :id ORDER BY position")
            .param("id", run.value)
            .query { rs, _ -> Triple(rs.getString("name"), rs.getString("status"), rs.getString("result")) }
            .list()
            .filter { it.first == ADVANCE } // as outras etapas reais têm os próprios testes

    /** As pendências da etapa do avanço; as das outras etapas vêm de dados de outros testes do banco isolado. */
    private fun issuesOf(run: EndOfDayRunId): List<Pair<String, String?>> =
        jdbc
            .sql(
                "SELECT kind, reference FROM endofday.run_issue " +
                    "WHERE run_id = :id AND step_name = :step ORDER BY created_at, id",
            ).param("id", run.value)
            .param("step", ADVANCE)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()

    /** Os avanços de [from] para o dia seguinte gravados na trilha. */
    private fun advanceTrails(from: LocalDate): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE action = 'hotel.business-date.advance' " +
                    "AND outcome = 'SUCCEEDED' AND before_state ->> 'businessDate' = :from " +
                    "AND after_state ->> 'businessDate' = :to",
            ).param("from", from.toString())
            .param("to", from.plusDays(1).toString())
            .query(Int::class.java)
            .single()

    private fun executionsOf(
        routine: UUID,
        date: LocalDate,
    ): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM checklists.checklist_execution " +
                    "WHERE routine_id = :routine AND business_date = :date",
            ).param("routine", routine)
            .param("date", date)
            .query(Int::class.java)
            .single()

    private companion object {
        const val ADVANCE = "advance-business-date"
        const val ADVANCE_POSITION = 1000
        const val ROUTINES = "/api/v1/checklist-routines"
        const val SUFFIX = 8
        const val WAIT_SECONDS = 20L
        const val HOLD_SECONDS = 60L
        const val HOLDERS = 4
        val LOCK_TIMEOUT: Duration = Duration.ofSeconds(10)
        val STATEMENT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val HOLD: Duration = Duration.ofSeconds(3)
        val OVERLAP_STEP: Duration = Duration.ofMillis(1500)
        val FEED_LIMIT: Duration = Duration.ofSeconds(50)
    }
}
