package com.zeny.hotel.housekeeping

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.actingAs
import com.zeny.hotel.housekeeping.application.HousekeepingDamages
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Duas cobranças simultâneas do mesmo dano (M6 E06, Task 2; Review Focus 1): a segunda espera a trava do dano (`FOR
 * UPDATE`) tomada pela primeira **antes** de chamar o `billing`, vê o dano já cobrado (a versão nova, `412`) e não
 * lança: a conta fica com um lançamento só.
 *
 * Determinístico, sem espera cega (padrão do `HousekeepingTaskLockTest`): a primeira cobrança roda numa transação que
 * fica aberta depois dela; a segunda começa noutra thread, e o teste espera (com prazo) que a sessão dela esteja
 * esperando uma trava da primeira (`pg_blocking_pids`, `wait_event_type = 'Lock'`, trava não concedida, depois de ter
 * tocado a tabela do dano). Só então a primeira confirma.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios das corridas
class HousekeepingDamageLockTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val damages: HousekeepingDamages,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager)
    private val support = DamageReportTestSupport(iam, jdbc)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two simultaneous charges of a damage post once, the second waits the lock and sees it charged`(): Unit =
        with(iam) {
            val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
            val (reservation, _) =
                stays.inHouse(type, stays.planFor(type), rooms[0], hotel.currentBusinessDate().plusDays(2))
            val folio =
                FolioId(
                    reservationsApi
                        .reservation(reservation)
                        .shouldNotBeNull()
                        .folioId.value,
                )
            val id = support.registered(body = support.body(roomId = rooms[0]))
            val code = support.createCode("CHARGE")
            support.act(id, "review", asAdmin(), support.reviewBody(code, reservationId = reservation.value)).ok()
            val version = support.etag(id).trim('"').toLong()
            val charge = { damages.charge(DamageReportId(id), folio, version) }

            race(first = charge, second = charge) { first, second ->
                withClue(first.exceptionOrNull()) { first.isSuccess shouldBe true }
                (second.exceptionOrNull() as? DomainException)?.code?.value shouldBe "PRECONDITION_FAILED"
            }
            support.postingsOf(id) shouldHaveSize 1
            support.damage(id)["status"].asString() shouldBe "CHARGED"
        }

    /**
     * Roda [first] numa transação que fica aberta, depois [second] em outra thread; espera a sessão de [second] ficar
     * bloqueada pela de [first] na tabela do dano, confirma [first] e entrega os dois resultados a [verify].
     */
    private fun race(
        first: () -> Any,
        second: () -> Any,
        verify: (Result<Any>, Result<Any>) -> Unit,
    ) {
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pid = AtomicInteger()
            val ran = CountDownLatch(1)
            val held =
                pool.submit<Result<Any>> {
                    inTransaction {
                        pid.set(backendPid())
                        runCatching(first).also {
                            ran.countDown()
                            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            if (!ran.await(LATCH_SECONDS, TimeUnit.SECONDS)) {
                if (held.isDone) held.get()
                error("A primeira cobrança não rodou em $LATCH_SECONDS s")
            }
            val contender = pool.submit<Result<Any>> { inTransaction { runCatching(second) } }
            awaitBlocked(pid.get(), contender)
            release.countDown()

            verify(held.get(LATCH_SECONDS, TimeUnit.SECONDS), contender.get(LATCH_SECONDS, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(LATCH_SECONDS * 2, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }

    private fun awaitBlocked(
        holder: Int,
        contender: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (waitingOn(holder) == 0) {
            withClue("a segunda cobrança terminou sem esperar a trava do dano") { contender.isDone shouldBe false }
            check(System.nanoTime() < deadline) { "A segunda cobrança não chegou a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
        contender.isDone shouldBe false
    }

    /** Sessões esperando uma trava não concedida, bloqueadas por [holder], que já tocaram a tabela do dano. */
    private fun waitingOn(holder: Int): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                  AND a.wait_event_type = 'Lock'
                  AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted)
                  AND EXISTS (SELECT 1 FROM pg_locks l
                              WHERE l.pid = a.pid AND l.relation = CAST(:relation AS regclass))
                """.trimIndent(),
            ).param("holder", holder)
            .param("relation", DAMAGE_RELATION)
            .query(Int::class.java)
            .single()

    /** Roda [block] como o administrador, numa transação com `lock_timeout`; a recusa desfaz a transação. */
    private fun inTransaction(block: () -> Result<Any>): Result<Any> =
        actingAs(iam.adminId()) {
            checkNotNull(
                write.execute { status ->
                    jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
                    block().also { if (it.isFailure) status.setRollbackOnly() }
                },
            )
        }

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    private companion object {
        const val DAMAGE_RELATION = "housekeeping.damage_report"
        const val LOCK_TIMEOUT = "20s"
        const val LATCH_SECONDS = 30L
        const val POLL_MILLIS = 50L
    }
}
