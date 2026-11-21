package com.zeny.hotel.endofday

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationMarkedNoShow
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O ataque à etapa `no-show` (M6 E11, Task 11): chegada atrasada, recusa no meio de várias, falha que não é de domínio
 * e a retomada sem multa duplicada, check-in concorrente com a etapa em andamento, um evento por reserva e a pendência
 * sem dado pessoal. Contexto das etapas reais; cada teste afirma só sobre os próprios dados e termina com
 * [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class, NoShowStepAttackTest.Recorder::class)
@Suppress("LongParameterList", "LargeClass")
class NoShowStepAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val recorder: Recorder,
    @Autowired private val iamSupport: IamTestSupport,
    @Autowired roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iamSupport, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iamSupport, support, hotel, roomsApi, jsonMapper)

    /**
     * Grava os `ReservationMarkedNoShow` confirmados (depois do commit): sem escutador de produção ainda (a FNRH vem na
     * E14), o outbox do Modulith não persiste o evento, então o teste o escuta.
     */
    @Component
    class Recorder {
        val events: MutableList<ReservationMarkedNoShow> = CopyOnWriteArrayList()

        @TransactionalEventListener
        fun on(event: ReservationMarkedNoShow) {
            events += event
        }
    }

    private class Booking(
        val id: UUID,
        val folio: UUID,
        val room: UUID,
    )

    private class Setup(
        val type: UUID,
        val plan: UUID,
        val bookings: List<Booking>,
    )

    @Test
    fun `a late arrival still confirmed from a previous date becomes no-show with the penalty`() {
        val date = hotel.currentBusinessDate()
        val setup = setup(1, date)
        val late = setup.bookings.single()
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :arrival WHERE id = :id")
            .param("arrival", date.minusDays(1))
            .param("id", late.id)
            .update() shouldBe 1
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            support.status(late.id) shouldBe "NO_SHOW"
            penalties(late.folio) shouldContainExactly listOf("150.00")
            issuesOf(run, setup) shouldBe emptyList()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a refusal in the middle of many leaves the others as no-show and a single issue`() {
        val date = hotel.currentBusinessDate()
        val setup = setup(5, date)
        val refused = setup.bookings[2]
        with(support.billing) { call(POST, "/api/v1/folios/${refused.folio}/close", support.admin).expect(200) }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            setup.bookings.map { support.status(it.id) } shouldContainExactly
                listOf("NO_SHOW", "NO_SHOW", "CONFIRMED", "NO_SHOW", "NO_SHOW")
            setup.bookings.map { penalties(it.folio) } shouldContainExactly
                listOf(listOf("150.00"), listOf("150.00"), emptyList(), listOf("150.00"), listOf("150.00"))
            issuesOf(run, setup) shouldContainExactly listOf("NO_SHOW_FAILED" to refused.id.toString())
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a non-domain failure in the middle fails the step and the resume charges nothing twice`() {
        val date = hotel.currentBusinessDate()
        val setup = setup(4, date)
        probe.failOn(FolioId(setup.bookings[2].folio))
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "FAILED"
            stepOf(run, "no-show").first shouldBe "FAILED"
            setup.bookings.map { support.status(it.id) } shouldContainExactly
                listOf("NO_SHOW", "NO_SHOW", "CONFIRMED", "CONFIRMED")
            penalties(setup.bookings[2].folio) shouldBe emptyList()

            EndOfDayTestRuns.settle(context)

            support.status(setup.bookings[2].id) shouldBe "NO_SHOW"
            setup.bookings.forEach {
                support.status(it.id) shouldBe "NO_SHOW"
                penalties(it.folio) shouldContainExactly listOf("150.00")
                support.historyKinds(it.id).count { kind -> kind == "NO_SHOW" } shouldBe 1
                noShowEvents(it.id) shouldBe 1
            }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a check-in made while the step is held keeps the reservation in house without penalty`() {
        val date = hotel.currentBusinessDate()
        val holder = frontdesk.guest()
        // O que faz check-in fica noutro tipo de UH: o item segurado trava o inventário do tipo dele, e o check-in do
        // mesmo tipo esperaria o commit dele (contenção esperada), sem provar nada sobre a etapa.
        val setup = setup(2, date, holder)
        val other = setup(1, date, holder)
        val (first, held) = setup.bookings
        val arriving = other.bookings.single()
        frontdesk.Stay(other.type, listOf(arriving.room), other.plan).ready(arriving.room)
        val gate = CountDownLatch(1)
        probe.holdOn(FolioId(held.folio), gate)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (support.status(first.id) != "NO_SHOW") {
                check(System.nanoTime() < deadline) { "A etapa não chegou à reserva segurada" }
                Thread.sleep(POLL_MILLIS)
            }
            support.status(held.id) shouldBe "CONFIRMED"

            val checkedIn = pool.submit<Int> { frontdesk.checkIn(arriving.id, holder).raw.status }
            checkedIn.get(CHECK_IN_WAIT_SECONDS, TimeUnit.SECONDS) shouldBe 200
            gate.countDown()
            val run = running.get(60, TimeUnit.SECONDS)

            statusOf(run) shouldBe "COMPLETED"
            support.status(first.id) shouldBe "NO_SHOW"
            support.status(held.id) shouldBe "NO_SHOW"
            support.status(arriving.id) shouldBe "IN_HOUSE"
            penalties(arriving.folio) shouldBe emptyList()
            noShowEvents(arriving.id) shouldBe 0
            issuesOf(run, setup) shouldBe emptyList()
            issuesOf(run, other) shouldBe emptyList()
        } finally {
            gate.countDown()
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the no-show event is published once per reservation`() {
        val date = hotel.currentBusinessDate()
        val setup = setup(3, date)
        try {
            EndOfDayTestRuns.closeCurrentDate(context)

            setup.bookings.forEach {
                support.status(it.id) shouldBe "NO_SHOW"
                noShowEvents(it.id) shouldBe 1
            }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the issue carries only the code and the reservation id, never personal data`() {
        val date = hotel.currentBusinessDate()
        val setup = setup(1, date)
        val refused = setup.bookings.single()
        with(support.billing) { call(POST, "/api/v1/folios/${refused.folio}/close", support.admin).expect(200) }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            val row =
                jdbc
                    .sql(
                        "SELECT step_name, kind, reference, message FROM endofday.run_issue " +
                            "WHERE run_id = :run AND reference = :ref AND step_name <> 'end-of-day-pending'",
                    ).param("run", run.value)
                    .param("ref", refused.id.toString())
                    .query { rs, _ ->
                        listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                    }.single()
            row[0] shouldBe "no-show"
            row[1] shouldBe "NO_SHOW_FAILED"
            Regex("^NO_SHOW_FAILED: [A-Z_]+$").matches(row[3]) shouldBe true
            val guestNames =
                jdbc
                    .sql(
                        "SELECT g.full_name FROM guests.guest g " +
                            "JOIN reservations.booking b ON b.holder_guest_id = g.id " +
                            "JOIN reservations.reservation r ON r.booking_id = b.id WHERE r.id = :id",
                    ).param("id", refused.id)
                    .query(String::class.java)
                    .list()
            guestNames.filterNotNull().forEach { row[3] shouldNotContain it }
            row[3] shouldNotContain refused.folio.toString()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun setup(
        count: Int,
        date: LocalDate,
        holder: UUID? = null,
    ): Setup {
        val (type, rooms) = inventory.roomTypeWithRooms(count, date)
        val plan =
            support.plan(
                listOf(type),
                cancellationBody =
                    support.rates.cancellationBody(
                        deadlineHours = 8760,
                        penaltyType = "FIXED_AMOUNT",
                        penaltyNights = null,
                        penaltyAmount = "150.00",
                    ),
            )
        support.setPrices(plan.id, listOf(type), date, date.plusDays(10))
        val created =
            rooms.map { room ->
                val node: JsonNode =
                    support.created(
                        support.booking(
                            holder ?: support.guest(),
                            support.reservation(type, plan.id, date, date.plusDays(2)),
                        ),
                    )["reservations"][0]
                val id = UUID.fromString(node["id"].asString())
                with(iamSupport) {
                    call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to room)).ok()
                }
                Booking(id, UUID.fromString(node["folioId"].asString()), room)
            }
        // a etapa processa por id: a ordem do teste é a do processamento
        return Setup(type, plan.id, created.sortedBy { it.id })
    }

    private fun penalties(folio: UUID): List<String> =
        support
            .postings(folio)
            .filter { it["key"].toString() == "NO_SHOW_FEE" }
            .map { it["amount"].toString() }

    private fun noShowEvents(reservation: UUID): Int = recorder.events.count { it.reservationId == reservation }

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun stepOf(
        run: EndOfDayRunId,
        name: String,
    ): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", name)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun issuesOf(
        run: EndOfDayRunId,
        setup: Setup,
    ): List<Pair<String, String?>> {
        val ids = setup.bookings.map { it.id.toString() }.toSet()
        return jdbc
            .sql(
                "SELECT kind, reference FROM endofday.run_issue " +
                    "WHERE run_id = :id AND step_name <> 'end-of-day-pending' ORDER BY created_at, id",
            ).param("id", run.value)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()
            .filter { it.second in ids }
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val CHECK_IN_WAIT_SECONDS = 30L
    }
}
