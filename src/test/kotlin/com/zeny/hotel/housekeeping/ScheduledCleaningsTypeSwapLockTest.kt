package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.housekeeping.application.CleaningTypesService
import com.zeny.hotel.housekeeping.application.ScheduledCleaningsService
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A edição da faxina programada que troca o tipo para T, em corrida com a desativação de T (M6 E01, Task 3; Review
 * Focus 4, lacuna M4 da revisão), nas duas ordens: nunca sobra faxina ativa num tipo inativo. O `PUT` trava a faxina
 * e depois o tipo novo (ordem faxina → tipo) antes de conferir que ele está ativo; a desativação trava o mesmo tipo
 * antes de conferir as faxinas ativas.
 *
 * Mesmo padrão determinístico do `ScheduledCleaningsTypeLockTest`: a primeira operação fica com a transação aberta, a
 * segunda roda em outra thread, e o teste espera (com prazo) que a sessão dela fique bloqueada pela da primeira
 * (`pg_blocking_pids`) com `housekeeping.cleaning_type` entre as relações travadas; só então a primeira confirma.
 * As faxinas que ficam ativas no fim são desativadas num `finally` (o banco é compartilhado).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class ScheduledCleaningsTypeSwapLockTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val cleaningTypes: CleaningTypesService,
    @Autowired private val schedules: ScheduledCleaningsService,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val manager by lazy {
        iam.createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_MANAGE), ALL)).id
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a PUT swapping to the type in flight holds its deactivation, which then sees the active schedule`() {
        val source = housekeeping.createCleaningType()
        val target = housekeeping.createCleaningType()
        val schedule =
            housekeeping.createSchedule(
                housekeeping.scheduleBody(source, roomId = housekeeping.createRoom()),
            )
        try {
            race(
                first = { schedules.update(ScheduledCleaningId(schedule), 0, CleaningTypeId(target), FREQUENCY) },
                second = { cleaningTypes.deactivate(CleaningTypeId(target)) },
            ) { first, second ->
                first.isSuccess shouldBe true
                second.code() shouldBe "CLEANING_TYPE_HAS_ACTIVE_SCHEDULES"
            }
            typeActive(target) shouldBe true
            activeSchedules(target) shouldBe 1
            activeSchedules(source) shouldBe 0
        } finally {
            deactivateSchedule(schedule)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a deactivation of the type in flight holds the PUT swapping to it, which then sees the inactive type`() {
        val source = housekeeping.createCleaningType()
        val target = housekeeping.createCleaningType()
        val schedule =
            housekeeping.createSchedule(
                housekeeping.scheduleBody(source, roomId = housekeeping.createRoom()),
            )
        try {
            race(
                first = { cleaningTypes.deactivate(CleaningTypeId(target)) },
                second = { schedules.update(ScheduledCleaningId(schedule), 0, CleaningTypeId(target), FREQUENCY) },
            ) { first, second ->
                first.isSuccess shouldBe true
                second.code() shouldBe "CLEANING_TYPE_INACTIVE"
            }
            typeActive(target) shouldBe false
            activeSchedules(target) shouldBe 0
            activeSchedules(source) shouldBe 1
            jdbc
                .sql("SELECT version FROM housekeeping.scheduled_cleaning WHERE id = :id")
                .param("id", schedule)
                .query(Long::class.java)
                .single() shouldBe 0L
        } finally {
            deactivateSchedule(schedule)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /**
     * Roda [first] numa transação que fica aberta, depois [second] em outra thread; espera a sessão de [second] ficar
     * bloqueada pela de [first] no tipo de limpeza, confirma [first] e entrega os dois resultados a [verify].
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
                error("A primeira operação não rodou em $LATCH_SECONDS s")
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

    /**
     * Espera, com prazo, alguma sessão bloqueada pela sessão [holder] (`pg_blocking_pids`) e com
     * `housekeeping.cleaning_type` entre as relações travadas; [contender] não pode terminar antes disso.
     */
    private fun awaitBlocked(
        holder: Int,
        contender: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (blockedOnCleaningType(holder) == 0) {
            withClue("a segunda operação terminou sem esperar a trava do tipo: ${contender.resultText()}") {
                contender.isDone shouldBe false
            }
            check(System.nanoTime() < deadline) { "A segunda operação não chegou a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
        contender.isDone shouldBe false
    }

    private fun Future<*>.resultText(): String = if (isDone) runCatching { get() }.toString() else "em andamento"

    private fun blockedOnCleaningType(holder: Int): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                  AND EXISTS (SELECT 1 FROM pg_locks l
                              WHERE l.pid = a.pid AND l.relation = 'housekeeping.cleaning_type'::regclass)
                """.trimIndent(),
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    /**
     * Roda [block] como o gestor, numa transação com `lock_timeout`; uma recusa do caso de uso desfaz a transação (que
     * ele já marcou para desfazer), sem `UnexpectedRollbackException`.
     */
    private fun inTransaction(block: () -> Result<Any>): Result<Any> =
        actingAs(manager) {
            checkNotNull(
                write.execute { status ->
                    jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
                    block().also { if (it.isFailure) status.setRollbackOnly() }
                },
            )
        }

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

    private fun Result<Any>.code(): String? = (exceptionOrNull() as? DomainException)?.code?.value

    private fun deactivateSchedule(id: UUID) {
        jdbc
            .sql("UPDATE housekeeping.scheduled_cleaning SET active = false WHERE id = :id")
            .param("id", id)
            .update()
    }

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    private fun typeActive(type: UUID): Boolean =
        jdbc
            .sql("SELECT active FROM housekeeping.cleaning_type WHERE id = :id")
            .param("id", type)
            .query(Boolean::class.java)
            .single()

    private fun activeSchedules(type: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.scheduled_cleaning WHERE cleaning_type_id = :id AND active")
            .param("id", type)
            .query(Int::class.java)
            .single()

    private companion object {
        const val FREQUENCY = 7
        const val LATCH_SECONDS = 10L
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"
    }
}
