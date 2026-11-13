package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * O `billing` carimba todo lançamento com a data operacional travada (RF-FEC-05, M6 E11, Task 8): o carimbo lê a data
 * por `HotelApi.lockBusinessDate`, com a linha de `hotel.business_day` em `FOR SHARE` até o commit. O avanço espera o
 * lançamento em curso, que fica na data velha; o lançamento de uma transação que leu a data antes do avanço cai na data
 * nova; a trava pedida enquanto o avanço espera é atendida, então as ordens opostas conta/data não fazem deadlock; e a
 * correção de um lançamento de data já fechada vai para a data corrente, com a referência ao original (RN-03).
 *
 * Banco isolado porque avança a data, direto pela `HotelApi` (sem execução de fechamento). Toda transação das threads
 * do teste tem `lock_timeout`, todo latch tem prazo e o `finally` solta os latches, encerra as threads e chama
 * [EndOfDayTestRuns.settle], que não acha execução da data corrente.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class BillingDateLockTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val write = TransactionTemplate(transactionManager)
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a posting in flight holds the advance and stays on the old date`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val folio = openFolio()
            val current = hotel.currentBusinessDate()
            val posted = CountDownLatch(1)

            val posting =
                pool.submit<PostingSummary> {
                    write.execute {
                        setLockTimeout()
                        billingApi.post(folio, roomCharge()).also {
                            posted.countDown()
                            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            posted.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            // O avanço lê, na própria transação e depois de avançar, o que já está confirmado na conta.
            val advance = pool.submit<Pair<LocalDate, List<LocalDate>>> { advanceAndRead(current, folio) }

            shouldThrow<TimeoutException> { advance.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
            release.countDown()

            posting.get(LATCH_SECONDS, TimeUnit.SECONDS).businessDate shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current.plusDays(1) to listOf(current))
            storedBusinessDate(posting.get().id.value) shouldBe current
            hotel.currentBusinessDate() shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a posting whose transaction read the date before the advance lands on the new date`() {
        val advanced = CountDownLatch(1)
        withThreads(advanced) { pool ->
            val folio = openFolio()
            val current = hotel.currentBusinessDate()
            val read = CountDownLatch(1)

            val posting =
                pool.submit<Pair<LocalDate, PostingSummary>> {
                    write.execute {
                        setLockTimeout()
                        val before = hotel.currentBusinessDate()
                        read.countDown()
                        advanced.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        before to billingApi.post(folio, roomCharge())
                    }
                }
            read.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            advance(current)
            advanced.countDown()

            val (before, summary) = posting.get(LATCH_SECONDS, TimeUnit.SECONDS)
            before shouldBe current
            summary.businessDate shouldBe current.plusDays(1)
            storedBusinessDate(summary.id.value) shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a share lock is granted while the advance waits, so the opposite lock orders do not deadlock`() {
        val proceed = CountDownLatch(1)
        withThreads(proceed) { pool ->
            val folio = openFolio()
            val current = hotel.currentBusinessDate()
            val folioLocked = CountDownLatch(1)

            // A: trava a conta X primeiro (como a reabertura, que trava a conta e só depois carimba) e segura.
            val holder =
                pool.submit<LocalDate> {
                    write.execute {
                        setLockTimeout()
                        jdbc
                            .sql("SELECT id FROM billing.folio WHERE id = :id FOR UPDATE")
                            .param("id", folio.value)
                            .query(UUID::class.java)
                            .single()
                        folioLocked.countDown()
                        proceed.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        hotel.lockBusinessDate()
                    }
                }
            folioLocked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true

            // B: lança em X, trava a data em FOR SHARE e espera a conta.
            val posting =
                pool.submit<PostingSummary> {
                    write.execute {
                        setLockTimeout()
                        billingApi.post(folio, roomCharge())
                    }
                }
            awaitLockWaiter(FOLIO_QUERY)

            // C: o avanço espera a data atrás de B e, depois de avançar, lê o que já está confirmado na conta.
            val advance = pool.submit<Pair<LocalDate, List<LocalDate>>> { advanceAndRead(current, folio) }
            awaitLockWaiter(ADVANCE_QUERY)

            // A pede a data: atendido sem esperar C, confirma e solta X.
            proceed.countDown()
            holder.get(GRANTED_SECONDS, TimeUnit.SECONDS) shouldBe current
            posting.get(LATCH_SECONDS, TimeUnit.SECONDS).businessDate shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current.plusDays(1) to listOf(current))
            hotel.currentBusinessDate() shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a correction of a posting from a closed date goes to the current date with the original reference`() {
        withThreads { _ ->
            with(billing) {
                val folio = openFolio()
                val current = hotel.currentBusinessDate()
                val original = post(folio, codeId("ROOM_CHARGE"), "120.00")
                original["businessDate"].asString() shouldBe current.toString()

                hotel.advanceBusinessDate(current)

                val reversal = reverseWithReason(folio, original["id"].asString()).expect(201)
                reversal["type"].asString() shouldBe "REVERSAL"
                reversal["businessDate"].asString() shouldBe current.plusDays(1).toString()
                reversal["correctsId"].asString() shouldBe original["id"].asString()
                storedBusinessDate(UUID.fromString(original["id"].asString())) shouldBe current
            }
        }
    }

    private fun openFolio(): FolioId = billingApi.openFolio(NewFolio(FolioOwnerType.RESERVATION, UuidV7.generate())).id

    private fun roomCharge(): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
            unitPrice = Money.of(BigDecimal("100.00")),
            source = PostingSource.MANUAL,
        )

    private fun advance(current: LocalDate): LocalDate? =
        write.execute {
            setLockTimeout()
            hotel.advanceBusinessDate(current).current
        }

    /**
     * Avança a data e, na mesma transação, lê as datas dos lançamentos já confirmados na [folio]: em `READ COMMITTED`,
     * cada consulta vê o que foi confirmado antes dela, então um lançamento que o avanço esperou aparece aqui.
     */
    private fun advanceAndRead(
        current: LocalDate,
        folio: FolioId,
    ): Pair<LocalDate, List<LocalDate>>? =
        write.execute {
            setLockTimeout()
            hotel.advanceBusinessDate(current).current to
                jdbc
                    .sql("SELECT business_date FROM billing.posting WHERE folio_id = :id")
                    .param("id", folio.value)
                    .query(LocalDate::class.java)
                    .list()
                    .filterNotNull()
        }

    private fun storedBusinessDate(posting: UUID): LocalDate =
        jdbc
            .sql("SELECT business_date FROM billing.posting WHERE id = :id")
            .param("id", posting)
            .query(LocalDate::class.java)
            .single()

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
        const val THREADS = 3
        const val LATCH_SECONDS = 10L
        const val GRANTED_SECONDS = 3L
        const val BLOCKED_MILLIS = 500L
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"
        const val FOLIO_QUERY = "%billing.folio%update%"
        const val ADVANCE_QUERY = "%business_day%update%"
    }
}
