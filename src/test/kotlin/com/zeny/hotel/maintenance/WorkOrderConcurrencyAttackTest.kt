package com.zeny.hotel.maintenance

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Concorrência das ordens de serviço (M6 E07, Riscos): duas ações com o mesmo `If-Match`, a conclusão contra a
 * liberação manual do mesmo período e dois bloqueios da UH. Determinismo: uma sessão do dono segura a trava da linha
 * até as requisições estarem esperando por ela (`pg_blocking_pids`); só então confirma.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class WorkOrderConcurrencyAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired staffApi: StaffApi,
    @Autowired flyway: Flyway,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)
    private val owner: DataSource = flyway.configuration.dataSource

    private fun text(response: MockHttpServletResponse) = response.getContentAsString(Charsets.UTF_8)

    private fun blockBody(): Map<String, Any?> =
        mapOf(
            "kind" to "OUT_OF_ORDER",
            "startsOn" to hotelApi.currentBusinessDate().toString(),
            "endsOn" to hotelApi.currentBusinessDate().plusDays(2).toString(),
        )

    @Test
    fun `two starts with the same If-Match, exactly one wins and the other is 412`() {
        val id = support.openedId()
        val etag = support.etag(id)

        val responses =
            racing(
                "SELECT id FROM maintenance.work_order WHERE id = '$id' FOR UPDATE",
                List(2) { { support.act(id, "start", ifMatch = etag) } },
            )

        withClue(responses.map(::text)) { responses.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 412) }
        support.order(id)["status"].asString() shouldBe "IN_PROGRESS"
        support.row(id)["version"] shouldBe 1L
        support.audit(id).count { it["action"] == "maintenance.work-order.start" } shouldBe 1
    }

    @Test
    fun `start against cancel with the same If-Match, one wins, the other is 412, and the state is not mixed`() {
        val id = support.openedId()
        val etag = support.etag(id)

        val responses =
            racing(
                "SELECT id FROM maintenance.work_order WHERE id = '$id' FOR UPDATE",
                listOf(
                    { support.act(id, "start", ifMatch = etag) },
                    { support.act(id, "cancel", body = mapOf("reason" to "Duplicada"), ifMatch = etag) },
                ),
            )

        withClue(responses.map(::text)) { responses.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 412) }
        val order = support.order(id)
        val row = support.row(id)
        row["version"] shouldBe 1L
        when (order["status"].asString()) {
            "IN_PROGRESS" -> {
                row["closed_at"] shouldBe null
                row["cancellation_reason"] shouldBe null
            }

            "CANCELLED" -> {
                row["started_at"] shouldBe null
                row["closed_at"].shouldNotBeNull()
            }

            else -> {
                error("estado inesperado ${order["status"]}")
            }
        }
    }

    @Test
    fun `completing while the same period is released by hand never fails, releases once and leaves the room Dirty`() {
        val room = support.room()
        roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
        val order = support.openedId(body = support.body(roomId = room))
        val period =
            iam
                .call(POST, "$WORK_ORDERS/$order/room-block", iam.asAdmin(), blockBody(), support.etag(order))
                .let { with(iam) { it.ok()["outOfOrderId"].asString() } }

        val etag = support.etag(order)
        val responses =
            racing(
                "SELECT id FROM rooms.out_of_order WHERE id = '$period' FOR UPDATE",
                listOf(
                    { support.act(order, "complete", body = emptyMap<String, Any>(), ifMatch = etag) },
                    { iam.call(POST, "/api/v1/rooms/$room/out-of-order/$period/release", iam.asAdmin()) },
                ),
            )

        withClue(responses.map { it.status to text(it) }) {
            responses.forEach { it.status shouldNotBe 500 }
            // A conclusão vence, ou perde a corrida pelo período (412/409 retentável, ordem intacta); a liberação à mão
            // sempre responde 200 (ou 409 retentável).
            responses[0].status shouldBeIn listOf(200, 409, 412)
            responses[1].status shouldBeIn listOf(200, 409)
        }
        if (responses[0].status != 200) {
            support.order(order)["status"].asString() shouldBe "OPEN"
            with(iam) { support.act(order, "complete", body = emptyMap<String, Any>()).ok() }
        }
        support.order(order)["status"].asString() shouldBe "DONE"
        jdbc
            .sql("SELECT count(*) FROM rooms.out_of_order WHERE room_id = :room AND released_on IS NOT NULL")
            .param("room", room)
            .query(Int::class.java)
            .single() shouldBe 1
        roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY
    }

    @Test
    fun `two room-blocks at once on the same order, one wins and the other is 409, with a single period`() {
        val room = support.room()
        val order = support.openedId(body = support.body(roomId = room))
        val etag = support.etag(order)

        val responses =
            racing(
                "SELECT id FROM maintenance.work_order WHERE id = '$order' FOR UPDATE",
                List(2) { { iam.call(POST, "$WORK_ORDERS/$order/room-block", iam.asAdmin(), blockBody(), etag) } },
            )

        withClue(responses.map { it.status to text(it) }) {
            responses.count { it.status == 200 } shouldBe 1
            responses.single { it.status != 200 }.status shouldBeIn listOf(409, 412)
        }
        jdbc
            .sql("SELECT count(*) FROM rooms.out_of_order WHERE room_id = :room")
            .param("room", room)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    private infix fun Int.shouldBeIn(allowed: List<Int>) = (this in allowed) shouldBe true

    private fun racing(
        lockSql: String,
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
                awaitBlocked(pid, requests.size, futures)
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
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (blockedBy(holder) < expected) {
            withClue("uma requisição terminou sem esperar a trava") { futures.none { it.isDone } shouldBe true }
            check(System.nanoTime() < deadline) { "As requisições não chegaram a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Quantas sessões esperam, direta ou indiretamente, a sessão [holder]. */
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
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 20L
    }
}
