package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Ataques à persistência da execução do fechamento (M6 E10, Task 3), no banco isolado. Datas 50 anos à frente da data
 * operacional, com deslocamentos de 1000 a 1999 dias (os testes do desenvolvedor usam 100 a 600).
 */
@EndOfDayIntegrationTest
@Suppress("LargeClass", "TooManyFunctions") // um teste por restrição do banco
class EndOfDayRunPersistenceAttackTest(
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
) {
    private val slots = listOf(StepSlot("fake-one", 10), StepSlot("fake-two", 20), StepSlot("fake-three", 30))
    private val fixed: Instant = Instant.parse("2150-03-04T05:06:07.123456Z")

    private fun farDate(days: Long): LocalDate = hotelApi.currentBusinessDate().plusYears(FAR_YEARS).plusDays(days)

    private fun saved(
        days: Long,
        steps: List<StepSlot> = slots,
    ): EndOfDayRun =
        tx
            .execute { runs.save(EndOfDayRun.start(farDate(days), RunTrigger.AUTOMATIC, null, steps, fixed)) }
            .shouldNotBeNull()

    private fun version(run: EndOfDayRun): Long =
        jdbc
            .sql("SELECT version FROM endofday.run WHERE id = :id")
            .param("id", run.id.value)
            .query(Long::class.java)
            .single()

    private fun mutate(
        run: EndOfDayRun,
        change: (EndOfDayRun) -> Unit,
    ) {
        tx.executeWithoutResult { _ -> change(runs.lockForUpdate(run.id).shouldNotBeNull()) }
    }

    private fun sqlState(block: () -> Unit): String? {
        val error = shouldThrow<DataIntegrityViolationException> { block() }
        return (error.mostSpecificCause as? SQLException)?.sqlState
    }

    private fun insertRun(
        id: UUID,
        days: Long,
        trigger: String = "AUTOMATIC",
        status: String = "RUNNING",
        finishedAt: Instant? = null,
    ) = jdbc
        .sql(
            "INSERT INTO endofday.run " +
                "(id, business_date, trigger, status, started_at, finished_at, updated_at, version) " +
                "VALUES (:id, :date, :trigger, :status, :now, :finished, :now, 0)",
        ).param("id", id)
        .param("date", farDate(days))
        .param("trigger", trigger)
        .param("status", status)
        .param("now", java.sql.Timestamp.from(fixed))
        .param("finished", finishedAt?.let { java.sql.Timestamp.from(it) }, java.sql.Types.TIMESTAMP)
        .update()

    private fun loadDetached(run: EndOfDayRun): EndOfDayRun =
        tx
            .execute { _ ->
                runs.findById(run.id.value).orElseThrow().also { loaded -> loaded.steps.size }
            }.shouldNotBeNull()

    @Suppress("LongParameterList") // uma coluna por parâmetro, com padrão válido
    private fun insertStep(
        runId: UUID,
        name: String = "s",
        position: Int = 1,
        status: String = "PENDING",
        attempts: Int = 0,
        result: String? = null,
    ) = jdbc
        .sql(
            "INSERT INTO endofday.run_step (run_id, name, position, status, attempts, result) " +
                "VALUES (:run, :name, :position, :status, :attempts, :result)",
        ).param("run", runId)
        .param("name", name)
        .param("position", position)
        .param("status", status)
        .param("attempts", attempts)
        .param("result", result, java.sql.Types.VARCHAR)
        .update()

    private fun insertIssue(
        runId: UUID,
        kind: String = "KIND",
        reference: String? = null,
        message: String? = "m",
    ) = jdbc
        .sql(
            "INSERT INTO endofday.run_issue (id, run_id, kind, reference, message, resolved, created_at) " +
                "VALUES (:id, :run, :kind, :reference, :message, false, :now)",
        ).param("id", UUID.randomUUID())
        .param("run", runId)
        .param("kind", kind)
        .param("reference", reference, java.sql.Types.VARCHAR)
        .param("message", message, java.sql.Types.VARCHAR)
        .param("now", java.sql.Timestamp.from(fixed))
        .update()

    // --- versão e transições persistidas -------------------------------------------------------------------------

    @Test
    fun `every persisted transition raises the version by one even with a frozen clock`() {
        val run = saved(1000)
        val v0 = version(run)

        mutate(run) { it.beginNext(slots, fixed) }
        version(run) shouldBe v0 + 1
        mutate(run) { it.failStep("fake-one", "BOOM", fixed) }
        version(run) shouldBe v0 + 2
        mutate(run) { it.resume(fixed) }
        version(run) shouldBe v0 + 3
        mutate(run) { it.beginNext(slots, fixed) }
        version(run) shouldBe v0 + 4
        mutate(run) { it.completeStep("fake-one", "DONE", fixed) }
        version(run) shouldBe v0 + 5
    }

    @Test
    fun `a reconcile without change does not raise the version`() {
        val run = saved(1001)
        val v0 = version(run)

        mutate(run) { it.reconcile(slots, fixed.plusSeconds(5)).size shouldBe 0 }

        version(run) shouldBe v0
    }

    @Test
    fun `reconcile persists a skipped step with UNREGISTERED and a new step as pending`() {
        val run = saved(1002)
        val registered = listOf(StepSlot("fake-one", 10), StepSlot("fake-three", 30), StepSlot("fake-new", 40))

        mutate(run) { it.reconcile(registered, fixed.plusSeconds(1)) shouldContainExactly listOf("fake-two") }

        tx.executeWithoutResult { _ ->
            val loaded = runs.lockForUpdate(run.id).shouldNotBeNull()
            loaded.steps.map { Triple(it.name, it.position, it.status) } shouldContainExactly
                listOf(
                    Triple("fake-one", 10, StepStatus.PENDING),
                    Triple("fake-two", 20, StepStatus.SKIPPED),
                    Triple("fake-three", 30, StepStatus.PENDING),
                    Triple("fake-new", 40, StepStatus.PENDING),
                )
            loaded.steps[1].result shouldBe "UNREGISTERED"
            loaded.steps[1].finishedAt.shouldNotBeNull()
        }
    }

    @Test
    fun `failure and resume are persisted with the finish consistent with the status`() {
        val run = saved(1003)
        mutate(run) { it.beginNext(slots, fixed.plusSeconds(1)) }

        mutate(run) { it.failStep("fake-one", "BOOM", fixed.plusSeconds(2)) }
        tx.executeWithoutResult { _ ->
            val loaded = runs.lockForUpdate(run.id).shouldNotBeNull()
            loaded.status shouldBe RunStatus.FAILED
            loaded.finishedAt shouldBe fixed.plusSeconds(2).truncatedTo(ChronoUnit.MICROS)
            loaded.steps.first().result shouldBe "BOOM"
        }

        mutate(run) { it.resume(fixed.plusSeconds(3)) }
        tx.executeWithoutResult { _ ->
            val loaded = runs.lockForUpdate(run.id).shouldNotBeNull()
            loaded.status shouldBe RunStatus.RUNNING
            loaded.finishedAt.shouldBeNull()
            loaded.steps.first().status shouldBe StepStatus.PENDING
            loaded.steps.first().attempts shouldBe 1
        }
    }

    @Test
    fun `a run completed through the steps is persisted as completed with its finish`() {
        val single = listOf(StepSlot("only", 10))
        val run = saved(1004, single)

        mutate(run) { it.beginNext(single, fixed.plusSeconds(1)) }
        mutate(run) { it.completeStep("only", "DONE", fixed.plusSeconds(2)) }

        tx.executeWithoutResult { _ ->
            val loaded = runs.findByBusinessDate(farDate(1004)).shouldNotBeNull()
            loaded.status shouldBe RunStatus.COMPLETED
            loaded.finishedAt.shouldNotBeNull()
        }
    }

    @Test
    fun `two transactions changing the same run version do not both win`() {
        val run = saved(1005)
        val first = loadDetached(run)
        val second = loadDetached(run)

        tx.executeWithoutResult { _ ->
            runs.save(first.also { it.beginNext(slots, fixed.plusSeconds(1)) })
        }

        shouldThrow<ObjectOptimisticLockingFailureException> {
            tx.executeWithoutResult { _ ->
                runs.save(second.also { it.beginNext(slots, fixed.plusSeconds(2)) })
            }
        }
    }

    @Test
    fun `lockForUpdate returns null for an unknown run`() {
        tx.executeWithoutResult { _ -> runs.lockForUpdate(EndOfDayRunId.new()).shouldBeNull() }
    }

    @Test
    fun `boundary lengths are persisted and read back`() {
        val single = listOf(StepSlot("n".repeat(60), 10))
        val run = saved(1006, single)
        mutate(run) { it.beginNext(single, fixed.plusSeconds(1)) }
        mutate(run) { it.completeStep("n".repeat(60), "R".repeat(200), fixed.plusSeconds(2)) }
        tx.executeWithoutResult { _ ->
            issues.save(
                EndOfDayIssue.open(run.id, "n".repeat(60), "K".repeat(60), "r".repeat(120), "m".repeat(500), fixed),
            )
        }

        tx.executeWithoutResult { _ ->
            runs
                .findByBusinessDate(farDate(1006))
                .shouldNotBeNull()
                .steps
                .single()
                .result
                ?.length shouldBe 200
        }
        val issue = issues.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).single()
        issue.kind.length shouldBe 60
        issue.reference?.length shouldBe 120
        issue.message.length shouldBe 500
        issue.resolved shouldBe false
    }

    @Test
    fun `issues of a run come back in creation order`() {
        val run = saved(1007)
        listOf("FIRST", "SECOND", "THIRD").forEachIndexed { index, kind ->
            tx.executeWithoutResult { _ ->
                issues.save(EndOfDayIssue.open(run.id, null, kind, null, "m", fixed.plusSeconds(index.toLong())))
            }
        }

        issues.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).map { it.kind } shouldContainExactly
            listOf("FIRST", "SECOND", "THIRD")
    }

    // --- restrições do banco, escrita direta pelo papel da aplicação ---------------------------------------------

    @Test
    fun `the database refuses an unknown trigger or status on a run`() {
        sqlState { insertRun(UUID.randomUUID(), 1100, trigger = "ROBOT") } shouldBe CHECK_VIOLATION
        sqlState { insertRun(UUID.randomUUID(), 1101, status = "PAUSED") } shouldBe CHECK_VIOLATION
    }

    @Test
    fun `the database requires finished_at if and only if the run is not running`() {
        sqlState { insertRun(UUID.randomUUID(), 1102, status = "RUNNING", finishedAt = fixed) } shouldBe CHECK_VIOLATION
        sqlState { insertRun(UUID.randomUUID(), 1103, status = "COMPLETED", finishedAt = null) } shouldBe
            CHECK_VIOLATION
        sqlState { insertRun(UUID.randomUUID(), 1104, status = "FAILED", finishedAt = null) } shouldBe CHECK_VIOLATION
        insertRun(UUID.randomUUID(), 1105, status = "FAILED", finishedAt = fixed) shouldBe 1
        insertRun(UUID.randomUUID(), 1106, status = "COMPLETED", finishedAt = fixed) shouldBe 1
    }

    @Test
    fun `the database refuses an update that breaks the finish rule`() {
        val run = saved(1107)

        sqlState {
            jdbc.sql("UPDATE endofday.run SET status = 'COMPLETED' WHERE id = :id").param("id", run.id.value).update()
        } shouldBe CHECK_VIOLATION
        sqlState {
            jdbc
                .sql("UPDATE endofday.run SET finished_at = now() WHERE id = :id")
                .param("id", run.id.value)
                .update()
        } shouldBe CHECK_VIOLATION
    }

    @Test
    fun `the database refuses a second run for a date even by direct insert`() {
        val run = saved(1108)

        sqlState { insertRun(UUID.randomUUID(), 1108) } shouldBe UNIQUE_VIOLATION
        run.businessDate shouldBe farDate(1108)
    }

    @Test
    fun `the database refuses an invalid step status, negative attempts and a long result or name`() {
        val run = saved(1109, listOf(StepSlot("seed", 1)))

        sqlState { insertStep(run.id.value, "a", 2, status = "DONE") } shouldBe CHECK_VIOLATION
        sqlState { insertStep(run.id.value, "b", 3, attempts = -1) } shouldBe CHECK_VIOLATION
        sqlState { insertStep(run.id.value, "c", 4, result = "R".repeat(201)) } shouldBe VALUE_TOO_LONG
        sqlState { insertStep(run.id.value, "n".repeat(61), 5) } shouldBe VALUE_TOO_LONG
        insertStep(run.id.value, "ok", 6, result = "R".repeat(200)) shouldBe 1
    }

    @Test
    fun `the database refuses a repeated position or name inside a run but allows them across runs`() {
        val first = saved(1110, listOf(StepSlot("seed", 1)))
        val second = saved(1111, listOf(StepSlot("seed", 1)))

        sqlState { insertStep(first.id.value, "other", 1) } shouldBe UNIQUE_VIOLATION
        sqlState { insertStep(first.id.value, "seed", 2) } shouldBe UNIQUE_VIOLATION
        insertStep(second.id.value, "other", 2) shouldBe 1
    }

    @Test
    fun `the database refuses a step or an issue of a run that does not exist`() {
        sqlState { insertStep(UUID.randomUUID()) } shouldBe FK_VIOLATION
        sqlState { insertIssue(UUID.randomUUID()) } shouldBe FK_VIOLATION
    }

    @Test
    fun `the database refuses a malformed issue kind and over-long issue texts`() {
        val run = saved(1112)

        sqlState { insertIssue(run.id.value, kind = "lower") } shouldBe CHECK_VIOLATION
        sqlState { insertIssue(run.id.value, kind = "1ABC") } shouldBe CHECK_VIOLATION
        sqlState { insertIssue(run.id.value, kind = "ABC\n") } shouldBe CHECK_VIOLATION
        sqlState { insertIssue(run.id.value, kind = "") } shouldBe CHECK_VIOLATION
        sqlState { insertIssue(run.id.value, kind = "K".repeat(61)) } shouldBe VALUE_TOO_LONG
        sqlState { insertIssue(run.id.value, reference = "r".repeat(121)) } shouldBe VALUE_TOO_LONG
        sqlState { insertIssue(run.id.value, message = "m".repeat(501)) } shouldBe VALUE_TOO_LONG
        sqlState { insertIssue(run.id.value, message = null) } shouldBe NOT_NULL_VIOLATION
        insertIssue(
            run.id.value,
            kind = "K".repeat(60),
            reference = "r".repeat(120),
            message = "m".repeat(500),
        ) shouldBe
            1
    }

    @Test
    fun `the app role cannot delete steps or issues nor truncate the tables`() {
        val run = saved(1113)
        insertIssue(run.id.value)

        listOf(
            "DELETE FROM endofday.run_step WHERE run_id = '${run.id.value}'",
            "DELETE FROM endofday.run_issue WHERE run_id = '${run.id.value}'",
            "TRUNCATE endofday.run_issue",
            "TRUNCATE endofday.run_step",
            "TRUNCATE endofday.run CASCADE",
        ).forEach { statement ->
            val error = shouldThrow<DataAccessException> { jdbc.sql(statement).update() }
            (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
        issues.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).size shouldBe 1
    }

    private companion object {
        const val FAR_YEARS = 50L
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
        const val FK_VIOLATION = "23503"
        const val NOT_NULL_VIOLATION = "23502"
        const val VALUE_TOO_LONG = "22001"
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
