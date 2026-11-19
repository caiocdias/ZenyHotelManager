package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ataque da E04 à trava da tarefa: uma transação externa segura a linha da tarefa, e a ação HTTP concorrente aparece
 * bloqueada **numa trava ainda não concedida** (`NOT l.granted`) pela sessão dela, e termina em `409 CONCURRENT_UPDATE`
 * pelo `lock_timeout` da conexão (10 s, `application.yaml`), sem mudar nada. E duas conclusões simultâneas de
 * tarefas da mesma UH (ordem tarefa, UH) não dão deadlock nem `500`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskLockAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired staffApi: StaffApi,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager)
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `an action blocked on a held task row waits ungranted, then is 409 CONCURRENT_UPDATE and changes nothing`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = execution.assignedTask(attendant.employee)
        execution.acted(task, "start", auth)
        val before =
            jdbc
                .sql(
                    "SELECT * FROM housekeeping.housekeeping_task WHERE id = :id",
                ).param("id", task)
                .query()
                .singleRow()
        val etag = housekeeping.etagOf("${HousekeepingExecutionTestSupport.TASKS}/$task")

        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        val pid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                write.executeWithoutResult {
                    jdbc
                        .sql(
                            "SELECT id FROM housekeeping.housekeeping_task WHERE id = :id FOR UPDATE",
                        ).param("id", task)
                        .query()
                        .singleRow()
                    pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                    held.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
            }
            held.await(10, TimeUnit.SECONDS) shouldBe true
            val contender = pool.submit<MockHttpServletResponse> { execution.act(task, "pause", auth, ifMatch = etag) }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (waitingOn(pid.get()) == 0) {
                withClue("a ação terminou sem esperar a trava") { contender.isDone shouldBe false }
                check(System.nanoTime() < deadline) { "A ação não chegou a esperar a sessão ${pid.get()}" }
                Thread.sleep(POLL_MILLIS)
            }
            val response = contender.get(40, TimeUnit.SECONDS)
            with(iam) { response.problem(409, "CONCURRENT_UPDATE") }
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(20, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
        jdbc
            .sql(
                "SELECT * FROM housekeeping.housekeeping_task WHERE id = :id",
            ).param("id", task)
            .query()
            .singleRow() shouldBe
            before
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two completions at once on tasks of the same room do not deadlock - both 200 and the room ends clean`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val room = RoomId(housekeeping.createRoom())
        val first = taskOn(room, attendant)
        val second = taskOn(room, attendant)
        execution.acted(first, "start", auth)
        execution.acted(second, "start", auth)

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(first, second).map { task ->
                    pool.submit<MockHttpServletResponse> {
                        start.await(10, TimeUnit.SECONDS)
                        execution.act(task, "complete", auth)
                    }
                }
            start.countDown()
            val statuses = futures.map { it.get(40, TimeUnit.SECONDS).status }
            withClue(statuses) { statuses shouldBe listOf(200, 200) }
        } finally {
            pool.shutdownNow()
        }
        roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
    }

    private fun taskOn(
        room: RoomId,
        attendant: HousekeepingExecutionTestSupport.Attendant,
    ): UUID {
        val body = housekeeping.taskBody(housekeeping.createCleaningType(), roomId = room.value)
        return UUID.fromString(housekeeping.requestTask(body)["id"].asString()).also {
            execution.assign(it, attendant.employee)
        }
    }

    /**
     * Sessões esperando uma trava **não concedida** (`wait_event_type = 'Lock'`) pela sessão [holder] e que já tocaram
     * a
     * tarefa. Um espera de linha (`FOR UPDATE`) é ungranted em `transactionid`, não na relação: filtrar `NOT granted`
     * pela relação nunca casa.
     */
    private fun waitingOn(holder: Int): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                  AND a.wait_event_type = 'Lock'
                  AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted)
                  AND EXISTS (SELECT 1 FROM pg_locks l
                              WHERE l.pid = a.pid AND l.granted
                                AND l.relation = CAST('housekeeping.housekeeping_task' AS regclass))
                """.trimIndent(),
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    private companion object {
        const val POLL_MILLIS = 20L
    }
}
