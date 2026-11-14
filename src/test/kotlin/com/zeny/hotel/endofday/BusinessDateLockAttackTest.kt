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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Ataque à data operacional travada para quem lança (RF-FEC-05, M6 E11, Task 7) no PostgreSQL real: travas `FOR SHARE`
 * simultâneas não se bloqueiam; uma trava pedida enquanto o avanço (`FOR UPDATE`) espera é atendida na hora, sem fila
 * atrás dele; uma trava pedida enquanto o avanço segura a linha espera e lê a data que o avanço deixou; a trava some no
 * rollback de quem a segura; a de uma `REQUIRES_NEW` dentro de uma só de leitura é dela, e não da de fora; travar duas
 * vezes na mesma transação, com o avanço na fila, não trava a si mesmo.
 *
 * Banco isolado porque avança a data, direto pela `HotelApi` (sem execução de fechamento). Toda transação das threads
 * do teste tem `lock_timeout`, todo latch tem prazo e o `finally` solta os latches, encerra as threads e chama
 * [EndOfDayTestRuns.settle], que não acha execução da data corrente.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
class BusinessDateLockAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
) {
    private val write = TransactionTemplate(transactionManager)

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `share locks held by several transactions at once do not block each other`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val locked = CountDownLatch(HOLDERS)

            val holders = List(HOLDERS) { pool.submit<LocalDate> { holdLock(locked, release) } }

            locked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            lockWaiters(SHARE_QUERY) shouldBe 0
            release.countDown()
            holders.map { it.get(LATCH_SECONDS, TimeUnit.SECONDS) } shouldBe List(HOLDERS) { current }
            hotel.currentBusinessDate() shouldBe current
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a share lock asked while the advance waits is granted at once and the advance waits for it too`() {
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        withThreads(releaseFirst, releaseSecond) { pool ->
            val current = hotel.currentBusinessDate()
            val firstLocked = CountDownLatch(1)
            val secondLocked = CountDownLatch(1)

            val first = pool.submit<LocalDate> { holdLock(firstLocked, releaseFirst) }
            firstLocked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)

            val second = pool.submit<LocalDate> { holdLock(secondLocked, releaseSecond) }
            secondLocked.await(GRANTED_SECONDS, TimeUnit.SECONDS) shouldBe true

            releaseFirst.countDown()
            first.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
            shouldThrow<TimeoutException> { advance.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }

            releaseSecond.countDown()
            second.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            hotel.currentBusinessDate() shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a share lock asked while the advance holds the row waits and then reads the new date`() {
        val commit = CountDownLatch(1)
        withThreads(commit) { pool ->
            val current = hotel.currentBusinessDate()
            val advanced = CountDownLatch(1)

            val advance = pool.submit<LocalDate> { advanceAndHold(current, advanced, commit, rollback = false) }
            advanced.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val locker = pool.submit<LocalDate> { lockOnce() }
            awaitLockWaiter(SHARE_QUERY)
            shouldThrow<TimeoutException> { locker.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }

            commit.countDown()
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            locker.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a share lock asked while the advance holds the row reads the old date when the advance rolls back`() {
        val rollback = CountDownLatch(1)
        withThreads(rollback) { pool ->
            val current = hotel.currentBusinessDate()
            val advanced = CountDownLatch(1)

            val advance = pool.submit<LocalDate> { advanceAndHold(current, advanced, rollback, rollback = true) }
            advanced.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val locker = pool.submit<LocalDate> { lockOnce() }
            awaitLockWaiter(SHARE_QUERY)

            rollback.countDown()
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            locker.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
            hotel.currentBusinessDate() shouldBe current
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the share lock is released when its holder rolls back`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val locked = CountDownLatch(1)

            val holder =
                pool.submit<LocalDate> {
                    write.execute {
                        setLockTimeout()
                        hotel.lockBusinessDate()
                        locked.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        error("o lançamento falhou depois de carimbar a data")
                    }
                }
            locked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)

            release.countDown()
            shouldThrow<ExecutionException> { holder.get(LATCH_SECONDS, TimeUnit.SECONDS) }
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a REQUIRES_NEW write transaction inside a read-only one takes the lock and releases it at its own commit`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val innerCommitted = CountDownLatch(1)
            val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
            val requiresNew =
                TransactionTemplate(transactionManager).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                }

            val outer =
                pool.submit<LocalDate> {
                    readOnly.execute {
                        val date = requiresNew.execute { hotel.lockBusinessDate() }
                        innerCommitted.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        date
                    }
                }
            innerCommitted.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true

            val advance = pool.submit<LocalDate> { advance(current) }
            advance.get(GRANTED_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            release.countDown()
            outer.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `locking twice in one transaction while the advance waits returns the same date without waiting for it`() {
        val proceed = CountDownLatch(1)
        withThreads(proceed) { pool ->
            val current = hotel.currentBusinessDate()
            val locked = CountDownLatch(1)

            val holder =
                pool.submit<Pair<LocalDate, LocalDate>> {
                    write.execute {
                        setLockTimeout()
                        val first = hotel.lockBusinessDate()
                        locked.countDown()
                        proceed.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        first to hotel.lockBusinessDate()
                    }
                }
            locked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)

            proceed.countDown()
            holder.get(GRANTED_SECONDS, TimeUnit.SECONDS) shouldBe (current to current)
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
        }
    }

    /** Trava a data numa transação de escrita, avisa [locked], segura a trava até [release] e confirma. */
    private fun holdLock(
        locked: CountDownLatch,
        release: CountDownLatch,
    ): LocalDate? =
        write.execute {
            setLockTimeout()
            val date = hotel.lockBusinessDate()
            locked.countDown()
            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
            date
        }

    private fun lockOnce(): LocalDate? =
        write.execute {
            setLockTimeout()
            hotel.lockBusinessDate()
        }

    /** O avanço como o chama o fechamento, só com `lock_timeout` na transação para nenhuma thread ficar pendurada. */
    private fun advance(current: LocalDate): LocalDate? =
        write.execute {
            setLockTimeout()
            hotel.advanceBusinessDate(current).current
        }

    /** Avança dentro de uma transação que segura a linha até [finish] e então confirma ou desfaz. */
    private fun advanceAndHold(
        current: LocalDate,
        advanced: CountDownLatch,
        finish: CountDownLatch,
        rollback: Boolean,
    ): LocalDate? =
        write.execute { status ->
            setLockTimeout()
            val date = hotel.advanceBusinessDate(current).current
            advanced.countDown()
            finish.await(LATCH_SECONDS, TimeUnit.SECONDS)
            if (rollback) status.setRollbackOnly()
            date
        }

    private fun setLockTimeout() {
        jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
    }

    /** Espera, com prazo, alguma sessão deste banco esperar trava numa consulta que case com [pattern]. */
    private fun awaitLockWaiter(pattern: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (lockWaiters(pattern) == 0) {
            check(System.nanoTime() < deadline) { "Nenhuma sessão ficou esperando trava em '$pattern'" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun lockWaiters(pattern: String): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock' AND query ILIKE :pattern
                """.trimIndent(),
            ).param("pattern", pattern)
            .query(Int::class.java)
            .single()

    /**
     * Roda [block] com um pool de threads e, mesmo se ele falhar, solta [latches], encerra as threads e cumpre a regra
     * do banco isolado ([EndOfDayTestRuns.settle]).
     */
    private fun withThreads(
        vararg latches: CountDownLatch,
        block: (ExecutorService) -> Unit,
    ) {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            block(pool)
        } finally {
            latches.forEach { it.countDown() }
            pool.shutdown()
            pool.awaitTermination(LATCH_SECONDS * 2, TimeUnit.SECONDS)
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        const val THREADS = 4
        const val HOLDERS = 3
        const val LATCH_SECONDS = 10L
        const val GRANTED_SECONDS = 3L
        const val BLOCKED_MILLIS = 500L
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"
        const val SHARE_QUERY = "%business_day%for share%"
        const val ADVANCE_QUERY = "%business_day%update%"
    }
}
