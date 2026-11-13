package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.application.steps.AdvanceBusinessDateStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * O ataque à etapa de avanço da data e aos contextos de teste das etapas reais (M6 E11, Task 10): o `SET LOCAL` não
 * vaza para a conexão do pool, a etapa repetida não avança dois dias, a [BillingPostProbe] desarmada é transparente,
 * `failOn`/`holdOn` agem só na conta armada, `settle` desarma a sonda e deixa a data corrente sem execução ou
 * `COMPLETED`, e `BusinessDateAdvanced` sai uma vez por avanço.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList")
class EndOfDayAdvanceStepAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val step: AdvanceBusinessDateStep,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the step timeouts do not leak to other transactions of pooled connections`() {
        val before = timeoutsOnDistinctConnections()
        try {
            EndOfDayTestRuns.closeCurrentDate(context)
            val after = timeoutsOnDistinctConnections()
            after shouldBe before
            after.forEach { (statement, lock) ->
                statement shouldBe before.first().first
                lock shouldBe before.first().second
            }
            val folio = openFolio()
            val summary = tx.execute { billingApi.post(folio, charge(null)) }
            summary.businessDate shouldBe hotel.currentBusinessDate()
            val inside = tx.execute { showTimeouts() }
            inside shouldBe before.first()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `running the step again for an already advanced date fails clearly and advances nothing`() {
        val date = hotel.currentBusinessDate()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            hotel.currentBusinessDate() shouldBe date.plusDays(1)

            val error =
                shouldThrow<DomainException> {
                    tx.executeWithoutResult { _ -> step.execute(stepContext(run, date)) }
                }
            error.code.value shouldBe "BUSINESS_DATE_MISMATCH"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the business date advanced event is published once per advance`() {
        val date = hotel.currentBusinessDate()
        try {
            EndOfDayTestRuns.closeCurrentDate(context)
            val events =
                jdbc
                    .sql(
                        "SELECT DISTINCT serialized_event FROM shared.event_publication " +
                            "WHERE event_type LIKE '%BusinessDateAdvanced'",
                    ).query(String::class.java)
                    .list()
                    .filter { Regex("\"previous\"\\s*:\\s*\"$date\"").containsMatchIn(it.orEmpty()) }
            events.size shouldBe 1
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the disarmed probe is transparent and keeps the idempotency by key`() {
        probe.reset()
        val folio = openFolio()
        val key = "reservations.reservation:${UuidV7.generate()}:${hotel.currentBusinessDate()}:room"
        val first = tx.execute { billingApi.post(folio, charge(key)) }
        val again = tx.execute { billingApi.post(folio, charge(key)) }
        again.id shouldBe first.id
        billingApi.folio(folio)!!.balance shouldBe Money.of(BigDecimal("100.00"))
        billingApi.folio(folio)!!.id shouldBe folio
    }

    @Test
    fun `failOn breaks only the armed folio and writes nothing there`() {
        val armed = openFolio()
        val other = openFolio()
        try {
            probe.failOn(armed)
            val error = shouldThrow<IllegalStateException> { tx.execute { billingApi.post(armed, charge(null)) } }
            error.message!! shouldContain "Falha forçada pelo teste"
            tx.execute { billingApi.post(other, charge(null)) }.folioId shouldBe other
            billingApi.folio(armed)!!.balance shouldBe Money.ZERO
        } finally {
            probe.reset()
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `holdOn holds only the armed folio and releases when the gate opens`() {
        val held = openFolio()
        val free = openFolio()
        val gate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            probe.holdOn(held, gate)
            val posting = pool.submit<FolioId> { tx.execute { billingApi.post(held, charge(null)) }.folioId }
            tx.execute { billingApi.post(free, charge(null)) }.folioId shouldBe free
            shouldThrow<TimeoutException> { posting.get(1500, TimeUnit.MILLISECONDS) }
            gate.countDown()
            posting.get(10, TimeUnit.SECONDS) shouldBe held
        } finally {
            gate.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            probe.reset()
        }
    }

    @Test
    fun `settle disarms the probe even when the test failed before cleaning up`() {
        val folio = openFolio()
        probe.failOn(folio)
        shouldThrow<IllegalStateException> { tx.execute { billingApi.post(folio, charge(null)) } }
        EndOfDayTestRuns.settle(context)
        tx.execute { billingApi.post(folio, charge(null)) }.folioId shouldBe folio
    }

    @Test
    fun `settle also drops a hold armed on the probe`() {
        val folio = openFolio()
        probe.holdOn(folio, CountDownLatch(1))
        EndOfDayTestRuns.settle(context)
        val started = System.nanoTime()
        tx.execute { billingApi.post(folio, charge(null)) }.folioId shouldBe folio
        ((System.nanoTime() - started) / NANOS_PER_MILLI < FAST_MILLIS) shouldBe true
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `settle leaves the current date without a run or completed after a forced failure`() {
        val release = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val date = hotel.currentBusinessDate()
            pool.submit {
                tx.executeWithoutResult { _ ->
                    hotel.lockBusinessDate()
                    locked.countDown()
                    release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                }
            }
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            statusOf(run) shouldBe "FAILED"
            hotel.currentBusinessDate() shouldBe date

            release.countDown()
            pool.shutdown()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            EndOfDayTestRuns.settle(context)

            val current = hotel.currentBusinessDate()
            val status = statusIfAny(current)
            (status == null || status == "COMPLETED") shouldBe true
            statusOf(run) shouldBe "COMPLETED"
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the real context has no fake step and no fake probe`() {
        val slots = registry.slots
        slots.filter { it.name.startsWith("fake-") } shouldBe emptyList()
        slots.last().let { it.name to it.position } shouldBe (AdvanceBusinessDateStep.NAME to 1000)
        context.getBeanProvider(FakeStepsProbe::class.java).ifAvailable shouldBe null
    }

    private fun stepContext(
        run: EndOfDayRunId,
        date: LocalDate,
    ) = EndOfDayStepContext(run, date) { _, _, _ -> }

    private fun openFolio(): FolioId = billingApi.openFolio(NewFolio(FolioOwnerType.RESERVATION, UuidV7.generate())).id

    private fun charge(key: String?): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
            unitPrice = Money.of(BigDecimal("100.00")),
            source = PostingSource.MANUAL,
            sourceReference = key,
        )

    private fun showTimeouts(): Pair<String, String> =
        jdbc.sql("SHOW statement_timeout").query(String::class.java).single() to
            jdbc.sql("SHOW lock_timeout").query(String::class.java).single()

    /** Lê os dois prazos em transações simultâneas, para pegar conexões distintas do pool. */
    private fun timeoutsOnDistinctConnections(): List<Pair<String, String>> {
        val barrier = CyclicBarrier(CONNECTIONS)
        val pool = Executors.newFixedThreadPool(CONNECTIONS)
        try {
            val futures =
                List(CONNECTIONS) {
                    pool.submit<Pair<String, String>> {
                        tx.execute {
                            val seen = showTimeouts()
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            seen
                        }
                    }
                }
            return futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun statusIfAny(date: LocalDate): String? =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(String::class.java)
            .list()
            .singleOrNull()

    private companion object {
        const val CONNECTIONS = 4
        const val WAIT_SECONDS = 20L
        const val HOLD_SECONDS = 60L
        const val NANOS_PER_MILLI = 1_000_000L
        const val FAST_MILLIS = 5_000L
    }
}
