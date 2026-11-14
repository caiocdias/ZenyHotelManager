package com.zeny.hotel.endofday

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A data operacional travada para quem lança (RF-FEC-05, M6 E11): `HotelApi.lockBusinessDate` lê a data com a linha de
 * `hotel.business_day` em `FOR SHARE` até o commit, então o avanço (`FOR UPDATE`) espera quem já carimbou, e quem
 * trava depois do avanço confirmado lê a data nova, mesmo tendo lido a velha antes na mesma transação.
 *
 * Roda no banco isolado porque avança a data, direto pela `HotelApi` (sem execução de fechamento); cada teste termina
 * com [EndOfDayTestRuns.settle], que não acha execução da data nova.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
class BusinessDateLockTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a holder of the share lock delays the advance until it commits`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val locked = CountDownLatch(1)

            val holder =
                pool.submit<LocalDate> {
                    tx.execute {
                        val date = hotel.lockBusinessDate()
                        locked.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        date
                    }
                }
            locked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val advance = pool.submit<LocalDate> { hotel.advanceBusinessDate(current).current }

            shouldThrow<TimeoutException> { advance.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
            release.countDown()

            holder.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            hotel.currentBusinessDate() shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a transaction that read the date before the advance gets the new date when it locks`() {
        val advanced = CountDownLatch(1)
        withThreads(advanced) { pool ->
            val current = hotel.currentBusinessDate()
            val read = CountDownLatch(1)

            val reader =
                pool.submit<Pair<LocalDate, LocalDate>> {
                    tx.execute {
                        val before = hotel.currentBusinessDate()
                        read.countDown()
                        advanced.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        before to hotel.lockBusinessDate()
                    }
                }
            read.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            hotel.advanceBusinessDate(current)
            advanced.countDown()

            reader.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current to current.plusDays(1))
        }
    }

    /**
     * Roda [block] com um pool de duas threads e, mesmo se ele falhar, solta [latch], encerra as threads e cumpre a
     * regra do banco isolado ([EndOfDayTestRuns.settle]).
     */
    private fun withThreads(
        latch: CountDownLatch,
        block: (ExecutorService) -> Unit,
    ) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            block(pool)
        } finally {
            latch.countDown()
            pool.shutdownNow()
            pool.awaitTermination(LATCH_SECONDS, TimeUnit.SECONDS)
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        const val LATCH_SECONDS = 10L
        const val BLOCKED_MILLIS = 500L
    }
}
