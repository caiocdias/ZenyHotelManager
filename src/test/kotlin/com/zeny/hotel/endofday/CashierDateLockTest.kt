package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.billing.CashMovementType
import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.application.CashMovementView
import com.zeny.hotel.billing.application.CashierShiftReport
import com.zeny.hotel.billing.application.CashierShiftService
import com.zeny.hotel.billing.application.CashierShiftView
import com.zeny.hotel.billing.application.DeclaredCount
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * O caixa carimba a data operacional travada (RF-FEC-05; revisão final da M6 E11, D1 e T1): a abertura de turno, o
 * movimento (sangria, suprimento) e o fechamento de turno leem a data por `HotelApi.lockBusinessDate`, com a linha de
 * `hotel.business_day` em `FOR SHARE` até o commit. O avanço espera a operação em curso, que fica na data velha `D`; a
 * operação de uma transação que leu a data (pela entidade) antes do avanço cai em `D+1`.
 *
 * A espera do avanço é provada por `pg_blocking_pids`: a sessão do avanço espera a trava da própria sessão que segura
 * a operação de caixa (o pid lido dentro da transação dela), não uma sessão qualquer.
 *
 * Banco isolado (H10) porque avança a data, direto pela `HotelApi` (sem execução de fechamento), como os testes de
 * concorrência das Tasks 7 e 8 ([BillingDateLockTest]). Toda transação das threads do teste tem `lock_timeout`, todo
 * latch tem prazo, o relógio não é tocado e o `finally` solta os latches, encerra as threads e chama
 * [EndOfDayTestRuns.settle], que não acha execução da data corrente.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class CashierDateLockTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val shifts: CashierShiftService,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val write = TransactionTemplate(transactionManager)
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    // --- A operação em curso segura o avanço e fica na data velha -------------------------------------------------

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a cash movement in flight holds the advance and stays on the old date`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val (operator, shift) = openShift()
            val current = hotel.currentBusinessDate()

            val movement =
                holding(pool, operator, release) {
                    shifts.recordMovement(shift, CashMovementType.WITHDRAWAL, Money.of("30.00"), "Sangria ao cofre")
                }
            val advance = advancing(pool, current, MOVEMENTS, shift.value)
            movement.awaitAdvanceBlocked(advance)
            hotel.currentBusinessDate() shouldBe current
            release.countDown()

            val recorded = movement.result.get(LATCH_SECONDS, TimeUnit.SECONDS)
            recorded.businessDate shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current.plusDays(1) to listOf(current))
            storedDates(MOVEMENTS, shift) shouldBe listOf(current)
            hotel.currentBusinessDate() shouldBe current.plusDays(1)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a shift close in flight holds the advance and closes on the old date`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val (operator, shift) = openShift()
            val current = hotel.currentBusinessDate()

            val closing =
                holding(pool, operator, release) { shifts.close(shift, listOf(cash(OPENING_FLOAT))) }
            val advance = advancing(pool, current, CLOSED_ON, shift.value)
            closing.awaitAdvanceBlocked(advance)
            hotel.currentBusinessDate() shouldBe current
            release.countDown()

            val report = closing.result.get(LATCH_SECONDS, TimeUnit.SECONDS)
            report.shift.closedOn shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current.plusDays(1) to listOf(current))
            storedDates(CLOSED_ON, shift) shouldBe listOf(current)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a shift opening in flight holds the advance and opens on the old date`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val operator = operator()
            val cashPoint = cashPoint()
            val current = hotel.currentBusinessDate()

            val opening = holding(pool, operator, release) { shifts.open(cashPoint, Money.of(OPENING_FLOAT)) }
            val advance = advancing(pool, current, OPENED_ON_BY, operator.id.value)
            opening.awaitAdvanceBlocked(advance)
            hotel.currentBusinessDate() shouldBe current
            release.countDown()

            val opened = opening.result.get(LATCH_SECONDS, TimeUnit.SECONDS)
            opened.openedOn shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe (current.plusDays(1) to listOf(current))
            storedDates(OPENED_ON, opened.id) shouldBe listOf(current)
        }
    }

    // --- O pedido depois do avanço fica na data nova ------------------------------------------------------------

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a movement and a close whose transaction read the date before the advance land on the new date`() {
        val advanced = CountDownLatch(1)
        withThreads(advanced) { pool ->
            val (operator, shift) = openShift()
            val current = hotel.currentBusinessDate()
            val read = CountDownLatch(1)

            val after =
                pool.submit<Triple<LocalDate, CashMovementView, CashierShiftReport>> {
                    actingAs(operator.id) {
                        checkNotNull(
                            write.execute { _ ->
                                setLockTimeout()
                                // A entidade da data fica carregada nesta transação com D, antes do avanço.
                                val before = hotel.currentBusinessDate()
                                read.countDown()
                                advanced.await(LATCH_SECONDS, TimeUnit.SECONDS)
                                Triple(
                                    before,
                                    shifts.recordMovement(shift, CashMovementType.SUPPLY, Money.of("20.00"), "Troco"),
                                    shifts.close(shift, listOf(cash("120.00"))),
                                )
                            },
                        )
                    }
                }
            read.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            advance(current)
            advanced.countDown()

            val (before, movement, report) = after.get(LATCH_SECONDS, TimeUnit.SECONDS)
            before shouldBe current
            movement.businessDate shouldBe current.plusDays(1)
            report.shift.closedOn shouldBe current.plusDays(1)
            storedDates(MOVEMENTS, shift) shouldBe listOf(current.plusDays(1))
            storedDates(CLOSED_ON, shift) shouldBe listOf(current.plusDays(1))
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a shift opened by a transaction that read the date before the advance opens on the new date`() {
        val advanced = CountDownLatch(1)
        withThreads(advanced) { pool ->
            val operator = operator()
            val cashPoint = cashPoint()
            val current = hotel.currentBusinessDate()
            val read = CountDownLatch(1)

            val after =
                pool.submit<Pair<LocalDate, CashierShiftView>> {
                    actingAs(operator.id) {
                        checkNotNull(
                            write.execute { _ ->
                                setLockTimeout()
                                val before = hotel.currentBusinessDate()
                                read.countDown()
                                advanced.await(LATCH_SECONDS, TimeUnit.SECONDS)
                                before to shifts.open(cashPoint, Money.of(OPENING_FLOAT))
                            },
                        )
                    }
                }
            read.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            advance(current)
            advanced.countDown()

            val (before, opened) = after.get(LATCH_SECONDS, TimeUnit.SECONDS)
            before shouldBe current
            opened.openedOn shouldBe current.plusDays(1)
            storedDates(OPENED_ON, opened.id) shouldBe listOf(current.plusDays(1))
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** A operação de caixa segurada: o resultado e o pid da sessão (do banco) que a roda. */
    private inner class Held<T>(
        val result: Future<T>,
        private val pid: AtomicInteger,
    ) {
        /**
         * Espera, com prazo, a sessão do [advance] esperar uma trava segura por esta operação: alguma sessão que tem
         * `hotel.business_day` entre as relações travadas e cujo `pg_blocking_pids` contém o pid desta operação. O
         * avanço ainda não terminou e a operação segue aberta.
         */
        fun awaitAdvanceBlocked(advance: Future<*>) {
            val holder = pid.get()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
            while (blockedOnBusinessDay(holder) == 0) {
                withClue("o avanço terminou sem esperar a operação de caixa") { advance.isDone shouldBe false }
                check(System.nanoTime() < deadline) { "O avanço não chegou a esperar a trava da sessão $holder" }
                Thread.sleep(POLL_MILLIS)
            }
            advance.isDone shouldBe false
            result.isDone shouldBe false
        }
    }

    /**
     * Roda [operation] como [operator] numa transação que guarda o pid da sessão e, depois da operação, segura a
     * transação aberta até [release]. Volta quando a operação já rodou (com a data travada) e o pid é conhecido.
     */
    private fun <T : Any> holding(
        pool: ExecutorService,
        operator: TestUser,
        release: CountDownLatch,
        operation: () -> T,
    ): Held<T> {
        val pid = AtomicInteger()
        val done = CountDownLatch(1)
        val result =
            pool.submit<T> {
                actingAs(operator.id) {
                    checkNotNull(
                        write.execute { _ ->
                            setLockTimeout()
                            pid.set(backendPid())
                            operation().also {
                                done.countDown()
                                release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                            }
                        },
                    )
                }
            }
        if (!done.await(LATCH_SECONDS, TimeUnit.SECONDS)) {
            if (result.isDone) result.get() // mostra a falha da operação
            error("A operação de caixa não rodou em $LATCH_SECONDS s")
        }
        return Held(result, pid)
    }

    private fun operator(): TestUser =
        iam.createUserWith(TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL))

    private fun cashPoint(): CashPointId =
        with(billing) {
            CashPointId(
                call(POST, "/api/v1/cash-points", admin, mapOf("name" to "Caixa ${UuidV7.generate()}"))
                    .expect(201)
                    .uuid("id"),
            )
        }

    /** Um operador novo com um turno aberto (fundo de troco [OPENING_FLOAT]) num ponto de caixa novo. */
    private fun openShift(): Pair<TestUser, CashierShiftId> {
        val operator = operator()
        return operator to CashierShiftId(billing.openShift(iam.asTestUser(operator), OPENING_FLOAT))
    }

    private fun cash(amount: String) = DeclaredCount(PaymentMethod.CASH, Money.of(amount))

    private fun advance(current: LocalDate) {
        write.executeWithoutResult { _ ->
            setLockTimeout()
            hotel.advanceBusinessDate(current)
        }
    }

    /** O avanço de [current] numa thread do [pool], lendo depois as datas de [sql] para [id] ([advanceAndRead]). */
    private fun advancing(
        pool: ExecutorService,
        current: LocalDate,
        sql: String,
        id: UUID,
    ): Future<Pair<LocalDate, List<LocalDate>>> = pool.submit(Callable { advanceAndRead(current, sql, id) })

    /**
     * Avança a data e, na mesma transação, lê as datas já confirmadas por [sql] para [id]: em `READ COMMITTED`, cada
     * consulta vê o que foi confirmado antes dela, então a operação que o avanço esperou aparece aqui.
     */
    private fun advanceAndRead(
        current: LocalDate,
        sql: String,
        id: UUID,
    ): Pair<LocalDate, List<LocalDate>> =
        checkNotNull(
            write.execute { _ ->
                setLockTimeout()
                hotel.advanceBusinessDate(current).current to datesOf(sql, id)
            },
        )

    private fun storedDates(
        sql: String,
        id: CashierShiftId,
    ): List<LocalDate> = datesOf(sql, id.value)

    private fun datesOf(
        sql: String,
        id: UUID,
    ): List<LocalDate> =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(LocalDate::class.java)
            .list()
            .filterNotNull()

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    /**
     * Quantas sessões deste banco esperam uma trava segura pela sessão [holder] (`pg_blocking_pids`) tendo travado
     * `hotel.business_day` (o `FOR UPDATE` do avanço pega a trava da tabela e espera a da linha).
     */
    private fun blockedOnBusinessDay(holder: Int): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                  AND EXISTS (SELECT 1 FROM pg_locks l
                              WHERE l.pid = a.pid AND l.relation = 'hotel.business_day'::regclass)
                """.trimIndent(),
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    private fun setLockTimeout() {
        jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
    }

    /** Roda [block] com o ator [userId] no contexto de segurança da thread (fora do `MockMvc`). */
    private fun <T> actingAs(
        userId: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

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
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"
        const val OPENING_FLOAT = "100.00"
        const val MOVEMENTS = "SELECT business_date FROM billing.cash_movement WHERE shift_id = :id"
        const val CLOSED_ON = "SELECT closed_on FROM billing.cashier_shift WHERE id = :id AND closed_on IS NOT NULL"
        const val OPENED_ON = "SELECT opened_on FROM billing.cashier_shift WHERE id = :id"
        const val OPENED_ON_BY = "SELECT opened_on FROM billing.cashier_shift WHERE operator_id = :id"
    }
}
