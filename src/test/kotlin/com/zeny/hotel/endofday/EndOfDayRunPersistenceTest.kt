package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * A persistência da execução do fechamento (M6 E10, Task 3), no banco isolado: as datas ficam 50 anos à frente da data
 * operacional, que nenhum outro teste usa, e a data corrente não é tocada.
 */
@EndOfDayIntegrationTest
@Suppress("LongParameterList") // os repositórios e os apoios de transação, JDBC e relógio
class EndOfDayRunPersistenceTest(
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
) {
    private val requiresNew =
        TransactionTemplate(tx.transactionManager.shouldNotBeNull()).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    private val slots = listOf(StepSlot("fake-three", 30), StepSlot("fake-one", 10), StepSlot("fake-two", 20))

    private fun farDate(days: Long): LocalDate = hotelApi.currentBusinessDate().plusYears(FAR_YEARS).plusDays(days)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun newRun(date: LocalDate) = EndOfDayRun.start(date, RunTrigger.AUTOMATIC, null, slots, now())

    private fun saved(date: LocalDate): EndOfDayRun = tx.execute { runs.save(newRun(date)) }.shouldNotBeNull()

    private fun storedVersion(run: EndOfDayRun): Long =
        jdbc
            .sql("SELECT version FROM endofday.run WHERE id = :id")
            .param("id", run.id.value)
            .query(Long::class.java)
            .single()

    @Test
    fun `a run round-trips with its steps in order`() {
        val date = farDate(100)
        val user = UserId(UUID.randomUUID())
        val startedAt = now()
        val id = tx.execute { runs.save(EndOfDayRun.start(date, RunTrigger.MANUAL, user, slots, startedAt)).id }

        tx.executeWithoutResult { _ ->
            val loaded = runs.findByBusinessDate(date).shouldNotBeNull()
            loaded.id shouldBe id
            loaded.trigger shouldBe RunTrigger.MANUAL
            loaded.status shouldBe RunStatus.RUNNING
            loaded.triggeredBy shouldBe user
            loaded.startedAt shouldBe startedAt
            loaded.updatedAt shouldBe startedAt
            loaded.steps.map { it.name to it.position } shouldContainExactly
                listOf("fake-one" to 10, "fake-two" to 20, "fake-three" to 30)
            loaded.steps.map { it.status }.toSet() shouldBe setOf(StepStatus.PENDING)

            runs
                .findById(id.shouldNotBeNull().value)
                .orElse(null)
                .shouldNotBeNull()
                .businessDate shouldBe date
        }
    }

    @Test
    fun `a second run for the same business date violates run_business_date_uk`() {
        val date = farDate(200)
        saved(date)

        val error = shouldThrow<DataIntegrityViolationException> { saved(date) }

        error.mostSpecificCause.message.shouldNotBeNull() shouldContain "run_business_date_uk"
    }

    @Test
    fun `a step change increments the run version`() {
        val run = saved(farDate(300))
        val before = storedVersion(run)

        tx.executeWithoutResult { _ ->
            runs.lockForUpdate(run.id).shouldNotBeNull().beginNext(slots, now())
        }
        storedVersion(run) shouldBe before + 1

        tx.executeWithoutResult { _ ->
            runs.lockForUpdate(run.id).shouldNotBeNull().completeStep("fake-one", "DONE", now())
        }
        storedVersion(run) shouldBe before + 2
        tx.executeWithoutResult { _ ->
            val loaded = runs.lockForUpdate(run.id).shouldNotBeNull()
            loaded.version shouldBe before + 2
            loaded.steps.map { it.name to it.status } shouldContainExactly
                listOf(
                    "fake-one" to StepStatus.COMPLETED,
                    "fake-two" to StepStatus.PENDING,
                    "fake-three" to StepStatus.PENDING,
                )
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `lockForUpdate does not block an issue insert from another transaction`() {
        val run = saved(farDate(400))

        tx.executeWithoutResult { _ ->
            runs.lockForUpdate(run.id).shouldNotBeNull()
            requiresNew.executeWithoutResult {
                issues.save(EndOfDayIssue.open(run.id, "fake-two", "TEST_ITEM", "item-1", "falhou", now()))
            }
        }

        issues.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).map { it.kind to it.reference } shouldContainExactly
            listOf("TEST_ITEM" to "item-1")
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `lockForUpdate makes a second locker wait`() {
        val run = saved(farDate(500))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            CompletableFuture.runAsync {
                tx.executeWithoutResult { _ ->
                    runs.lockForUpdate(run.id).shouldNotBeNull()
                    locked.countDown()
                    release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                }
            }
        try {
            locked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true

            shouldThrow<PessimisticLockingFailureException> {
                requiresNew.executeWithoutResult {
                    jdbc.sql("SET LOCAL lock_timeout = '200ms'").update()
                    runs.lockForUpdate(run.id)
                }
            }
        } finally {
            release.countDown()
            holder.get(LATCH_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `lockForUpdate outside a transaction fails before locking`() {
        val run = saved(farDate(650))

        shouldThrow<IllegalTransactionStateException> { runs.lockForUpdate(run.id) }
        shouldThrow<IllegalTransactionStateException> { runs.lockRowForNoKeyUpdate(run.id.value) }
    }

    @Test
    fun `the immutable run columns are never written by an update`() {
        val date = farDate(700)
        val run = saved(date)
        val stored = immutableColumns(run)

        tx.executeWithoutResult { _ ->
            val loaded = runs.lockForUpdate(run.id).shouldNotBeNull()
            overwrite(loaded, "businessDate", date.plusDays(1))
            overwrite(loaded, "trigger", RunTrigger.MANUAL)
            overwrite(loaded, "triggeredBy", UUID.randomUUID())
            overwrite(loaded, "startedAt", now().plusSeconds(3600))
            loaded.beginNext(slots, now())
        }

        immutableColumns(run) shouldBe stored
        storedVersion(run) shouldBe 1L
    }

    private fun immutableColumns(run: EndOfDayRun): Map<String, Any?> =
        jdbc
            .sql("SELECT business_date, trigger, triggered_by, started_at FROM endofday.run WHERE id = :id")
            .param("id", run.id.value)
            .query()
            .singleRow()

    private fun overwrite(
        run: EndOfDayRun,
        field: String,
        value: Any,
    ) {
        EndOfDayRun::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .set(run, value)
    }

    @Test
    fun `the app role cannot delete runs`() {
        val run = saved(farDate(600))

        val error =
            shouldThrow<SQLException> {
                dataSource.connection.use { connection ->
                    connection.prepareStatement("DELETE FROM endofday.run WHERE id = ?").use {
                        it.setObject(1, run.id.value)
                        it.executeUpdate()
                    }
                }
            }

        error.sqlState shouldBe INSUFFICIENT_PRIVILEGE
    }

    private companion object {
        const val FAR_YEARS = 50L
        const val LATCH_SECONDS = 5L
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
