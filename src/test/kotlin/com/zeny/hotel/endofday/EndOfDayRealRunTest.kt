package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Money
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O fechamento real ponta a ponta (M6 E11, Task 20) no contexto das etapas reais: as oito etapas em ordem, com o avanço
 * da data por último e só depois das anteriores; a falha no meio das diárias deixa a data parada e a retomada não
 * duplica nada; e um lançamento manual concorrente ao avanço cai inteiro antes ou depois do fechamento (RF-FEC-05).
 * Cada teste termina com [EndOfDayTestRuns.settle], devolve o [MutableClock] e afirma só sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayRealRunTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val rooms: RoomsApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, rooms, jsonMapper)

    /** Uma etapa da execução, como o teste a confere. */
    private data class Step(
        val name: String,
        val position: Int,
        val status: String,
        val result: String?,
        val startedAt: Instant?,
        val finishedAt: Instant?,
    )

    /** Um lançamento da conta, como o teste o confere. */
    private data class Line(
        val id: UUID,
        val key: String?,
        val businessDate: LocalDate,
        val source: String,
        val postedAt: Instant,
    )

    @Test
    @Suppress("LongMethod") // o cenário com uma reserva por etapa e as conferências de cada uma
    fun `a full run executes every step in order and advances the date last`() {
        val start = hotel.currentBusinessDate()
        val stay = frontdesk.stay(rooms = 2)
        // Hospedada de D-1 a D+1 e a que sai em D, ambas com check-in em D-1 (o fechamento de D-1 vem a seguir).
        val hosted = stay.checkedIn(departure = start.plusDays(2), room = stay.rooms[0])
        val leaving = stay.checkedIn(departure = start.plusDays(1), room = stay.rooms[1])
        try {
            statusOf(EndOfDayTestRuns.closeCurrentDate(context)) shouldBe "COMPLETED"
            val date = hotel.currentBusinessDate()
            date shouldBe start.plusDays(1)
            // O fechamento de D-1 já deixou a UH ocupada suja: limpa de novo, o de D a suja outra vez.
            rooms.markClean(RoomId(stay.rooms[0]), false, RoomStatusSource.MANUAL)
            val (type, typeRooms) = inventory.roomTypeWithRooms(3, date)
            val plan = support.plan(listOf(type), cancellationBody = fixedPenalty("150.00"))
            support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
            val arrival = created(type, plan.id, date, date.plusDays(2))
            assign(arrival.id(), typeRooms[0])
            val block = block(plan.id, type, date.plusDays(7), cutoff = date)
            val provisional = expiredProvisional(type, date)

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            val steps = stepsOf(run)
            steps.map { Triple(it.name, it.position, it.status) } shouldContainExactly
                STEPS.map { (name, position) -> Triple(name, position, "COMPLETED") }
            steps.single { it.name == "card-pre-authorizations" }.result shouldBe "NOT_APPLICABLE"
            steps.zipWithNext().forEach { (previous, next) ->
                checkNotNull(next.startedAt) shouldBeGreaterThanOrEqualTo checkNotNull(previous.finishedAt)
            }
            steps.last().name shouldBe "advance-business-date"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)

            // no-show (100): a multa da política copiada, na data que fecha, e a UH solta.
            support.status(arrival.id()) shouldBe "NO_SHOW"
            support.postings(arrival.folio()).map { it["amount"] to it["businessDate"] } shouldContainExactly
                listOf("150.00" to date)
            openAssignments(arrival.id()) shouldBe 0
            // pending-departures (200): a pendência, sem check-out.
            support.status(leaving.id) shouldBe "IN_HOUSE"
            issuesOf(run, listOf(hosted.id, leaving.id, arrival.id(), provisional)) shouldContainExactly
                listOf("PENDING_DEPARTURE" to leaving.id.toString())
            // room-charges (300): a noite D da hospedada, uma vez, na data D e com origem END_OF_DAY; nada da que sai.
            val night = lines(hosted.folio).filter { it.key?.startsWith(nightKey(hosted.id, date)) == true }
            night.mapNotNull { it.key } shouldContainExactly night.mapNotNull { it.key }.distinct()
            night.count { it.key == nightKey(hosted.id, date) + "room" } shouldBe 1
            night.forEach {
                it.businessDate shouldBe date
                it.source shouldBe "END_OF_DAY"
            }
            lines(leaving.folio).filter { it.key?.startsWith(nightKey(leaving.id, date)) == true }.shouldBeEmpty()
            // block-release (400) e provisional-expiry (500).
            blockStatus(block) shouldBe "RELEASED"
            val expired = support.reservation(provisional)["reservation"]
            expired["status"].asString() shouldBe "CANCELLED"
            expired["cancellation"]["reason"].asString() shouldBe "EXPIRED"
            // occupied-rooms-dirty (700): a UH ocupada suja pelo fechamento.
            rooms.status(RoomId(stay.rooms[0]))!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            lastHousekeepingSource(stay.rooms[0]) shouldBe "END_OF_DAY"
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the date does not advance while an earlier step failed`() {
        val date = hotel.currentBusinessDate()
        val stay = frontdesk.stay(rooms = 2)
        val healthy = stay.checkedIn(departure = date.plusDays(2), room = stay.rooms[0])
        val failing = stay.checkedIn(departure = date.plusDays(2), room = stay.rooms[1])
        probe.failOn(FolioId(failing.folio))
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "FAILED"
            stepsOf(run).map { it.name to it.status } shouldContainExactly
                STEPS.map { (name, position) ->
                    name to
                        when {
                            position < ROOM_CHARGES_POSITION -> "COMPLETED"
                            position == ROOM_CHARGES_POSITION -> "FAILED"
                            else -> "PENDING"
                        }
                }
            hotel.currentBusinessDate() shouldBe date
            lines(failing.folio).filter { it.key != null }.shouldBeEmpty()

            probe.reset()
            resume(run)

            statusOf(run) shouldBe "COMPLETED"
            stepsOf(run).map { it.status }.distinct() shouldBe listOf("COMPLETED")
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            listOf(healthy, failing).forEach { booked ->
                val keys = lines(booked.folio).mapNotNull { it.key }
                keys shouldContainExactly keys.distinct()
                keys.count { it == nightKey(booked.id, date) + "room" } shouldBe 1
            }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Suppress("LongMethod") // as duas threads, a espera do avanço e as conferências antes e depois do fechamento
    fun `a manual posting racing the run lands entirely before or after the close`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(1, date)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        // Confirmada futura: o fechamento de D não toca a conta dela.
        val future = created(type, plan.id, date.plusDays(3), date.plusDays(5))
        val folio = FolioId(future.folio())
        val posted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val clockStart = clock.instant()
        try {
            // A: lança na conta (a data travada em FOR SHARE) e segura a transação aberta.
            val manual =
                pool.submit<PostingSummary> {
                    checkNotNull(
                        tx.execute { _ ->
                            jdbc.sql("SET LOCAL lock_timeout = '$HOLDER_LOCK_TIMEOUT'").update()
                            billingApi.post(folio, manualCharge()).also {
                                posted.countDown()
                                release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                            }
                        },
                    )
                }
            posted.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            // O que o fechamento grava daqui em diante fica depois do lançamento de A no relógio.
            clock.advance(TICK)
            val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }

            // O avanço chega à data e espera a trava de A: a execução não conclui.
            awaitAdvanceWaiting(date)
            running.isDone shouldBe false
            runStatusOn(date) shouldBe "RUNNING"
            hotel.currentBusinessDate() shouldBe date

            release.countDown()
            val held = manual.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val run = running.get(WAIT_SECONDS, TimeUnit.SECONDS)

            statusOf(run) shouldBe "COMPLETED"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            val finished = finishedAt(run)
            held.businessDate shouldBe date

            clock.advance(TICK)
            val after = checkNotNull(tx.execute { _ -> billingApi.post(folio, manualCharge()) })

            after.businessDate shouldBe date.plusDays(1)
            val own = lines(folio.value)
            own.map { it.id to it.businessDate } shouldContainExactly
                listOf(held.id.value to date, after.id.value to date.plusDays(1))
            own.first().postedAt shouldBeLessThan finished
            own.last().postedAt shouldBeGreaterThan finished
            own.filter { it.businessDate == date }.forEach { it.postedAt shouldBeLessThanOrEqualTo finished }
            own.filter { it.postedAt > finished }.forEach { it.businessDate shouldBe date.plusDays(1) }
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            pool.shutdownNow()
            clock.advance(Duration.between(clock.instant(), clockStart))
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun fixedPenalty(amount: String) =
        support.rates.cancellationBody(
            deadlineHours = 8760,
            penaltyType = "FIXED_AMOUNT",
            penaltyNights = null,
            penaltyAmount = amount,
        )

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, departure)),
        )["reservations"][0]

    private fun assign(
        id: UUID,
        room: UUID,
    ) = with(iam) { call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to room)).ok() }

    /** Um bloqueio de uma unidade na noite [night], com o corte em [cutoff]. */
    private fun block(
        plan: UUID,
        type: UUID,
        night: LocalDate,
        cutoff: LocalDate,
    ): UUID =
        UUID.fromString(
            support
                .createdBlock(
                    support.blockBody(
                        plan,
                        cutoff,
                        support.allocation(type, night, night.plusDays(1), 1),
                        holderGuest = support.guest(),
                    ),
                )["id"]
                .asString(),
        )

    /**
     * Uma pré-reserva (plano com garantia por depósito) com a validade já vencida no relógio do teste: a validade é
     * gravada no passado direto no banco, sem avançar o relógio (que o gatilho automático também lê).
     */
    private fun expiredProvisional(
        type: UUID,
        date: LocalDate,
    ): UUID {
        val plan =
            support.plan(
                listOf(type),
                guarantee = "DEPOSIT",
                cancellationBody = support.rates.cancellationBody(deadlineHours = 8760, penaltyNights = 1),
            )
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        val id = created(type, plan.id, date.plusDays(3), date.plusDays(5)).id()
        support.status(id) shouldBe "PROVISIONAL"
        jdbc
            .sql("UPDATE reservations.reservation SET provisional_until = :until WHERE id = :id")
            .param("until", OffsetDateTime.ofInstant(clock.instant().minus(TICK), clock.zone))
            .param("id", id)
            .update() shouldBe 1
        return id
    }

    private fun manualCharge(): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
            unitPrice = Money.of(BigDecimal("80.00")),
            source = PostingSource.MANUAL,
        )

    /** Retoma a execução `FAILED` como o `settle` e a roda pelo motor. */
    private fun resume(run: EndOfDayRunId) {
        tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run)).resume(clock.instant()) }
        engine.execute(run, Duration.ofSeconds(WAIT_SECONDS))
    }

    /**
     * Espera, com prazo, a etapa de avanço da execução de [date] começar e alguma sessão esperar a trava da data
     * (`FOR UPDATE` em `hotel.business_day`).
     */
    private fun awaitAdvanceWaiting(date: LocalDate) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (advanceStatusOn(date) != "RUNNING" || advanceWaiters() == 0) {
            check(System.nanoTime() < deadline) { "O avanço da data não chegou a esperar a trava" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun advanceWaiters(): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock' AND query ILIKE '%business_day%update%'
                """.trimIndent(),
            ).query(Int::class.java)
            .single()

    private fun advanceStatusOn(date: LocalDate): String? =
        jdbc
            .sql(
                "SELECT s.status FROM endofday.run_step s JOIN endofday.run r ON r.id = s.run_id " +
                    "WHERE r.business_date = :date AND s.name = 'advance-business-date'",
            ).param("date", date)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun runStatusOn(date: LocalDate): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE business_date = :date")
            .param("date", date)
            .query(String::class.java)
            .single()

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun finishedAt(run: EndOfDayRunId): Instant =
        jdbc
            .sql("SELECT finished_at FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query { rs, _ -> rs.getObject("finished_at", OffsetDateTime::class.java).toInstant() }
            .single()

    private fun stepsOf(run: EndOfDayRunId): List<Step> =
        jdbc
            .sql(
                "SELECT name, position, status, result, started_at, finished_at FROM endofday.run_step " +
                    "WHERE run_id = :id ORDER BY position",
            ).param("id", run.value)
            .query { rs, _ ->
                Step(
                    rs.getString("name"),
                    rs.getInt("position"),
                    rs.getString("status"),
                    rs.getString("result"),
                    rs.getObject("started_at", OffsetDateTime::class.java)?.toInstant(),
                    rs.getObject("finished_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.list()

    /** As pendências da execução sobre as [reservations] deste teste, como `kind to referência`. */
    private fun issuesOf(
        run: EndOfDayRunId,
        reservations: List<UUID>,
    ): List<Pair<String, String?>> {
        val ids = reservations.map { it.toString() }.toSet()
        return jdbc
            .sql("SELECT kind, reference FROM endofday.run_issue WHERE run_id = :id ORDER BY created_at, id")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()
            .filter { it.second in ids }
    }

    private fun lines(folio: UUID): List<Line> =
        jdbc
            .sql(
                "SELECT id, source_reference, business_date, source, posted_at FROM billing.posting " +
                    "WHERE folio_id = :folio ORDER BY posted_at, id",
            ).param("folio", folio)
            .query { rs, _ ->
                Line(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("source_reference"),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getString("source"),
                    rs.getObject("posted_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    /** O prefixo das chaves da noite [night] da reserva [reservation] (`…:<noite>:`). */
    private fun nightKey(
        reservation: UUID,
        night: LocalDate,
    ): String = "reservations.reservation:$reservation:$night:"

    private fun openAssignments(reservation: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM reservations.room_assignment " +
                    "WHERE reservation_id = :id AND released_at IS NULL",
            ).param("id", reservation)
            .query(Int::class.java)
            .single()

    private fun blockStatus(block: UUID): String =
        jdbc
            .sql("SELECT status FROM reservations.room_block WHERE id = :id")
            .param("id", block)
            .query(String::class.java)
            .single()

    private fun lastHousekeepingSource(room: UUID): String =
        jdbc
            .sql(
                "SELECT source FROM rooms.room_status_change WHERE room_id = :id " +
                    "AND field = 'HOUSEKEEPING_STATUS' ORDER BY changed_at DESC, id DESC LIMIT 1",
            ).param("id", room)
            .query(String::class.java)
            .single()

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private companion object {
        /** As etapas reais da E11, na ordem das posições. */
        val STEPS =
            listOf(
                "no-show" to 100,
                "pending-departures" to 200,
                "room-charges" to 300,
                "block-release" to 400,
                "provisional-expiry" to 500,
                "card-pre-authorizations" to 600,
                "occupied-rooms-dirty" to 700,
                "advance-business-date" to 1000,
            )
        const val ROOM_CHARGES_POSITION = 300
        const val PRICED_DAYS = 10L
        const val WAIT_SECONDS = 30L
        const val HOLD_SECONDS = 50L
        const val POLL_MILLIS = 20L
        const val HOLDER_LOCK_TIMEOUT = "15s"
        val TICK: Duration = Duration.ofSeconds(1)
    }
}
