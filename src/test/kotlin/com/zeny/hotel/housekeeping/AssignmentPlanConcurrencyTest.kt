package com.zeny.hotel.housekeeping

import com.zeny.hotel.endofday.EndOfDayRealStepsTestConfiguration
import com.zeny.hotel.endofday.EndOfDayTestRuns
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.PLANS
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Concorrência e criação do plano do dia (M6 E03, Task 2), no banco isolado do fechamento (H10), o único onde se acha
 * uma data da janela sem plano: o `GET` e o `PATCH` antes de existir plano (`404`); os dois primeiros `suggest`
 * simultâneos da mesma data (um cria o plano, o outro recebe `428` ou `412`, nunca `500`, pelo `INSERT … ON CONFLICT
 * DO NOTHING` + `SELECT … FOR UPDATE`); e dois `PATCH` com o mesmo `If-Match` (um passa, o outro dá `412`).
 *
 * Determinismo: uma sessão do dono segura a trava (a tabela em `EXCLUSIVE`, ou a linha do plano em `FOR UPDATE`) até as
 * duas requisições estarem esperando por ela (`pg_blocking_pids`); só então confirma.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class AssignmentPlanConcurrencyTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val context: ApplicationContext,
    @Autowired staffApi: StaffApi,
    @Autowired flyway: Flyway,
) {
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)
    private val owner: DataSource = flyway.configuration.dataSource

    @Test
    fun `before the plan exists GET and PATCH are 404, and two first suggests create one plan, never a 500`() {
        try {
            val date = freeDate()
            with(iam) {
                plans.plan(date).problem(404, "ASSIGNMENT_PLAN_NOT_FOUND")
                plans
                    .adjust(date, mapOf("remove" to listOf(plans.insertTask(date, area = UUID.randomUUID()))), "\"0\"")
                    .problem(404, "ASSIGNMENT_PLAN_NOT_FOUND")
            }

            val responses =
                racing("LOCK TABLE housekeeping.assignment_plan IN EXCLUSIVE MODE", date) {
                    iam.call(POST, "$PLANS/$date/suggest", iam.asAdmin(), mapOf("employeeIds" to null))
                }

            val statuses = responses.map { it.status }
            withClue(responses.map { it.getContentAsString(Charsets.UTF_8) }) {
                statuses.count { it == 200 } shouldBe 1
                statuses.single { it != 200 } shouldBeIn listOf(412, 428)
            }
            val created = responses.single { it.status == 200 }
            plans.etagOrNull(date) shouldBe created.getHeader(HttpHeaders.ETAG)
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `two PATCH with the same If-Match, one passes and the other is 412`() {
        try {
            val date = hotel.currentBusinessDate().plusDays(1)
            val attendant = plans.attendant(date)
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val tasks = List(2) { plans.insertTask(date, area = UUID.randomUUID()) }

            val bodies = tasks.map { mapOf("items" to listOf(mapOf("taskId" to it, "employeeId" to attendant))) }
            val responses =
                racing(
                    "SELECT id FROM housekeeping.assignment_plan WHERE business_date = '$date' FOR UPDATE",
                    date,
                    bodies.map { body -> { iam.call(PATCH, "$PLANS/$date", iam.asAdmin(), body, etag) } },
                )

            withClue(responses.map { it.getContentAsString(Charsets.UTF_8) }) {
                responses.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 412)
            }
            responses.single { it.status == 412 }.let { with(iam) { it.problem(412, "PRECONDITION_FAILED") } }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    /** A primeira data da janela sem plano neste banco; sem nenhuma, falha com a explicação. */
    private fun freeDate(): LocalDate {
        val today = hotel.currentBusinessDate()
        return (0L..WINDOW_DAYS).map { today.plusDays(it) }.firstOrNull { plans.etagOrNull(it) == null }
            .shouldNotBeNull { "Todas as datas da janela ($today a +$WINDOW_DAYS) já têm plano no banco isolado." }
    }

    private fun racing(
        lockSql: String,
        date: LocalDate,
        request: () -> MockHttpServletResponse,
    ): List<MockHttpServletResponse> = racing(lockSql, date, List(2) { request })

    /**
     * Segura a trava de [lockSql] numa sessão do dono, dispara as [requests] em paralelo, espera as duas bloqueadas por
     * ela e só então confirma; devolve as respostas.
     */
    private fun racing(
        lockSql: String,
        date: LocalDate,
        requests: List<() -> MockHttpServletResponse>,
    ): List<MockHttpServletResponse> {
        val pool = Executors.newFixedThreadPool(requests.size)
        try {
            val futures: List<Future<MockHttpServletResponse>>
            owner.connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute(lockSql) }
                val pid = pidOf(holder)
                futures = requests.map { pool.submit(Callable { it() }) }
                awaitBlocked(pid, requests.size, futures, date)
                holder.commit()
            }
            return futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun pidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun awaitBlocked(
        holder: Int,
        expected: Int,
        futures: List<Future<*>>,
        date: LocalDate,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (blockedBy(holder) < expected) {
            withClue("uma requisição de $date terminou sem esperar a trava") { futures.none { it.isDone } shouldBe true }
            check(System.nanoTime() < deadline) { "As requisições não chegaram a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * Quantas sessões esperam, direta ou indiretamente, a sessão [holder]: na trava de linha, a segunda requisição
     * espera a primeira (a fila da trava da tupla), não o [holder].
     */
    private fun blockedBy(holder: Int): Int =
        JdbcClient
            .create(owner)
            .sql(
                """
                WITH RECURSIVE waiting(pid) AS (
                    SELECT a.pid FROM pg_stat_activity a
                    WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                    UNION
                    SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY (pg_blocking_pids(a.pid))
                    WHERE a.datname = current_database()
                )
                SELECT count(*) FROM waiting
                """.trimIndent(),
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    private companion object {
        const val WINDOW_DAYS = 7L
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 20L
    }
}
