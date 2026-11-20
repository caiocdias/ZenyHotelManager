package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * O ataque ao fechamento real ponta a ponta (M6 E11, Task 20, testador): o lançamento manual pedido enquanto o avanço
 * já segura a data (cai inteiro na data nova), o check-in da própria chegada que o no-show está processando (no-show
 * ou hospedada, nunca os dois, e nunca 500), o hóspede além da saída ao longo de dois fechamentos (uma pendência por
 * fechamento, sem diária e sem check-out), dois fechamentos seguidos com a mesma hospedada (uma diária por noite, a UH
 * suja a cada fechamento, nada levantado sobre ela) e o fechamento pedido de novo para uma data concluída (409, nenhum
 * efeito). Contexto das etapas reais no banco isolado; cada teste afirma só sobre os próprios dados e termina com
 * [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class, EndOfDayRealRunAttackTest.AdvanceGate::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions") // os apoios do contexto e um cenário por teste
class EndOfDayRealRunAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val rooms: RoomsApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val gate: AdvanceGate,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, json, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, rooms, json)

    /**
     * Segura, quando armada, a transação da etapa de avanço logo depois de a data mudar: o `BusinessDateAdvanced` sai
     * pelo publicador do Spring na transação da etapa, então este escutador síncrono roda com a linha de
     * `hotel.business_day` em `FOR UPDATE` e a data nova ainda não confirmada. Desarmada (o normal), não faz nada.
     */
    @Component
    class AdvanceGate(
        private val jdbc: JdbcClient,
    ) {
        private val armed = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()

        /** O pid da sessão (do banco) da etapa de avanço segurada; zero antes de o portão ser alcançado. */
        val pid = AtomicInteger()

        /** Arma uma vez: o próximo avanço conta [entered] e espera [release] abrir (no máximo 30 s). */
        fun arm(
            entered: CountDownLatch,
            release: CountDownLatch,
        ) {
            armed.set(entered to release)
        }

        fun disarm() {
            armed.set(null)
        }

        @EventListener
        fun on(
            @Suppress("UNUSED_PARAMETER") event: BusinessDateAdvanced,
        ) {
            val (entered, release) = armed.getAndSet(null) ?: return
            // Na transação da etapa: a conexão dela é a que segura a linha da data em FOR UPDATE.
            pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
            entered.countDown()
            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** Um lançamento da conta, como o teste o confere. */
    private data class Line(
        val key: String?,
        val amount: BigDecimal,
        val businessDate: LocalDate,
        val source: String,
    )

    // --- RF-FEC-05: o lançamento pedido enquanto o avanço segura a data ---------------------------------------------

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Suppress("LongMethod") // as duas threads, a espera do lançamento e as conferências depois do fechamento
    fun `a manual posting asked while the advance holds the date waits and lands entirely on the new date`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(1, date)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        // Confirmada futura: o fechamento de D não toca a conta dela.
        val folio = FolioId(created(type, plan.id, date.plusDays(3), date.plusDays(5)).folio())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gate.arm(entered, release)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }
            // O prazo do portão conta a partir da etapa de avanço `RUNNING`, não do disparo: as etapas anteriores
            // rodam sobre tudo o que o banco isolado acumulou e não podem comer o prazo da espera.
            awaitAdvanceRunning(date, running)
            withClue(
                "o avanço não chegou a mudar a data",
            ) { entered.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true }

            // B pede a data com o avanço já segurando a linha em FOR UPDATE e a data nova sem commit.
            val manual =
                pool.submit<PostingSummary> {
                    checkNotNull(
                        tx.execute { _ ->
                            billingApi.post(folio, charge())
                        },
                    )
                }
            // B espera a trava da própria sessão do avanço (pg_blocking_pids), com a tabela da data já travada.
            awaitBlockedBy(gate.pid.get(), manual, BUSINESS_DAY)
            manual.isDone shouldBe false
            lines(folio.value).shouldBeEmpty()

            release.countDown()
            val posted = manual.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val run = running.get(WAIT_SECONDS, TimeUnit.SECONDS)

            statusOf(run) shouldBe "COMPLETED"
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            posted.businessDate shouldBe date.plusDays(1)
            lines(folio.value).map { it.businessDate to it.source } shouldContainExactly
                listOf(date.plusDays(1) to "MANUAL")
        } finally {
            gate.disarm()
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- No-show x check-in da mesma chegada -------------------------------------------------------------------------

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Suppress("LongMethod") // a chegada, as duas threads e as conferências de cada desfecho
    fun `a check-in racing the no-show of its own arrival ends as no-show or in house, never both, never 500`() {
        val date = hotel.currentBusinessDate()
        val (type, typeRooms) = inventory.roomTypeWithRooms(1, date)
        val room = typeRooms.single()
        val plan = support.plan(listOf(type), cancellationBody = fixedPenalty("150.00"))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        val holder = frontdesk.guest()
        val arrival =
            support.created(
                support.booking(holder, support.reservation(type, plan.id, date, date.plusDays(2))),
            )["reservations"][0]
        val id = arrival.id()
        val folio = arrival.folio()
        assign(id, room)
        frontdesk.Stay(type, typeRooms, plan.id).ready(room)
        val hold = CountDownLatch(1)
        val itemPid = AtomicInteger()
        val itemHeld = CountDownLatch(1)
        // O item do no-show trava a reserva e para antes de lançar a multa, com a transação dele aberta; guarda o pid
        // da sessão do item (a sonda roda na transação dele).
        probe.holdOn(FolioId(folio), hold) {
            itemPid.set(backendPid())
            itemHeld.countDown()
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }
            awaitRowLocked(id)
            withClue("o item do no-show não chegou à sonda") {
                itemHeld.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
            withClue("antes do check-in, ninguém espera o item") { blockedBy(itemPid.get(), null) shouldBe 0 }

            val checkIn = pool.submit<MockHttpServletResponse> { frontdesk.checkIn(id, holder).raw }
            awaitBlockedBy(itemPid.get(), checkIn, null)
            // O check-in esbarra na trava do item (a sessão dele espera a do item): a corrida é real, não um check-in
            // depois do no-show.
            checkIn.isDone shouldBe false
            support.status(id) shouldBe "CONFIRMED"
            hold.countDown()
            val response = checkIn.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val run = running.get(WAIT_SECONDS, TimeUnit.SECONDS)

            val body = response.getContentAsString(Charsets.UTF_8)
            withClue(body) { response.status shouldBeLessThan 500 }
            statusOf(run) shouldBe "COMPLETED"
            issuesOf(run, listOf(id.toString(), room.toString())).shouldBeEmpty()
            val fees = lines(folio).filter { it.key?.endsWith(":no-show") == true }
            when (val status = support.status(id)) {
                "NO_SHOW" -> {
                    withClue(body) { response.status shouldBe 409 }
                    fees.map { it.amount to it.businessDate } shouldContainExactly listOf(BigDecimal("150.00") to date)
                    openAssignments(id) shouldBe 0
                    rooms.status(RoomId(room))!!.occupancy shouldBe OccupancyStatus.VACANT
                }

                "IN_HOUSE" -> {
                    withClue(body) { response.status shouldBe 200 }
                    fees.shouldBeEmpty()
                    openAssignments(id) shouldBe 1
                    rooms.status(RoomId(room))!!.occupancy shouldBe OccupancyStatus.OCCUPIED
                }

                else -> {
                    fail("A chegada terminou em $status (check-in ${response.status}: $body)")
                }
            }
        } finally {
            hold.countDown()
            pool.shutdown()
            pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Hóspede além da saída em dois fechamentos -------------------------------------------------------------------

    @Test
    fun `an overstay through two closes gets one pending departure per close, no night and no check-out`() {
        val start = hotel.currentBusinessDate()
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(departure = start.plusDays(1))
        try {
            val first = EndOfDayTestRuns.closeCurrentDate(context)
            statusOf(first) shouldBe "COMPLETED"
            issuesOf(first, listOf(booked.id.toString())).shouldBeEmpty()
            val charged = lines(booked.folio).filter { it.key != null }
            charged.mapNotNull { it.key }.filter { it.endsWith(":room") } shouldContainExactly
                listOf(nightKey(booked.id, start) + "room")

            val due = EndOfDayTestRuns.closeCurrentDate(context)
            val beyond = EndOfDayTestRuns.closeCurrentDate(context)

            listOf(due, beyond).forEach { run ->
                statusOf(run) shouldBe "COMPLETED"
                issuesOf(run, listOf(booked.id.toString())) shouldContainExactly
                    listOf("PENDING_DEPARTURE" to booked.id.toString())
            }
            hotel.currentBusinessDate() shouldBe start.plusDays(3)
            support.status(booked.id) shouldBe "IN_HOUSE"
            openAssignments(booked.id) shouldBe 1
            rooms.status(RoomId(stay.room))!!.occupancy shouldBe OccupancyStatus.OCCUPIED
            lines(booked.folio).filter { it.key != null } shouldBe charged
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Dois fechamentos seguidos e o fechamento repetido -----------------------------------------------------------

    @Test
    fun `two closes in a row charge each night once, dirty the room at each close and raise nothing for the stay`() {
        val start = hotel.currentBusinessDate()
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(departure = start.plusDays(3))
        try {
            val first = EndOfDayTestRuns.closeCurrentDate(context)
            rooms.status(RoomId(stay.room))!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            // A governança limpa a UH durante o dia; o fechamento seguinte a suja outra vez.
            rooms.markClean(RoomId(stay.room), false, RoomStatusSource.MANUAL)
            val second = EndOfDayTestRuns.closeCurrentDate(context)

            listOf(first, second).forEach { run ->
                statusOf(run) shouldBe "COMPLETED"
                issuesOf(run, listOf(booked.id.toString(), stay.room.toString())).shouldBeEmpty()
            }
            hotel.currentBusinessDate() shouldBe start.plusDays(2)
            support.status(booked.id) shouldBe "IN_HOUSE"
            val charged = lines(booked.folio).filter { it.key != null }
            val keys = charged.mapNotNull { it.key }
            keys.size shouldBe keys.toSet().size
            keys.filter { it.endsWith(":room") } shouldContainExactlyInAnyOrder
                listOf(nightKey(booked.id, start) + "room", nightKey(booked.id, start.plusDays(1)) + "room")
            charged.forEach {
                checkNotNull(it.key) shouldContain ":${it.businessDate}:"
                it.source shouldBe "END_OF_DAY"
            }
            rooms.status(RoomId(stay.room))!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            endOfDayDirtyEntries(stay.room) shouldBe 2
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a close asked again for a completed date is refused and changes nothing`() {
        val date = hotel.currentBusinessDate()
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(departure = date.plusDays(2))
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            statusOf(run) shouldBe "COMPLETED"
            val linesBefore = lines(booked.folio)
            val roomBefore = rooms.status(RoomId(stay.room))
            val dirtyBefore = endOfDayDirtyEntries(stay.room)
            val issuesBefore = issueCount(run)
            val versionBefore = runVersion(run)
            val auth = asUser(iam.createUserWith(TestGrant(execute, PermissionScope.ALL)).id.value)

            problem(start(auth, date), 409, "END_OF_DAY_ALREADY_CLOSED")
            problem(resume(auth, run), 409, "END_OF_DAY_RUN_NOT_RESUMABLE")

            runCount(date) shouldBe 1
            runCount(date.plusDays(1)) shouldBe 0
            hotel.currentBusinessDate() shouldBe date.plusDays(1)
            statusOf(run) shouldBe "COMPLETED"
            runVersion(run) shouldBe versionBefore
            issueCount(run) shouldBe issuesBefore
            lines(booked.folio) shouldBe linesBefore
            rooms.status(RoomId(stay.room)) shouldBe roomBefore
            endOfDayDirtyEntries(stay.room) shouldBe dirtyBefore
            support.status(booked.id) shouldBe "IN_HOUSE"
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

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

    private fun charge(): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
            unitPrice = Money.of(BigDecimal("80.00")),
            source = PostingSource.MANUAL,
        )

    private fun start(
        auth: RequestPostProcessor,
        date: LocalDate,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs") {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(mapOf("businessDate" to date.toString()))
            }.andReturn()
            .response

    private fun resume(
        auth: RequestPostProcessor,
        run: EndOfDayRunId,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs/${run.value}/resume") {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
            }.andReturn()
            .response

    private fun problem(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        val body = response.getContentAsString(Charsets.UTF_8)
        withClue(body) {
            response.status shouldBe status
            json.readTree(body)["code"].asString() shouldBe code
        }
    }

    /**
     * Espera, com prazo (desde a chamada), alguma sessão esperar uma trava segura pela sessão [holder]
     * (`pg_blocking_pids`) e, com [relation], já ter travado essa tabela. Se a [pending] terminar antes de esperar,
     * segue: as conferências do teste (`isDone`) dizem que ela não esperou.
     */
    private fun awaitBlockedBy(
        holder: Int,
        pending: Future<*>,
        relation: String?,
    ) {
        check(holder != 0) { "O pid da sessão que segura a trava não foi lido" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS)
        while (!pending.isDone && blockedBy(holder, relation) == 0) {
            check(System.nanoTime() < deadline) { "Nenhuma sessão chegou a esperar a trava da sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Quantas sessões deste banco esperam a sessão [holder] (`pg_blocking_pids`), com [relation] travada se dada. */
    private fun blockedBy(
        holder: Int,
        relation: String?,
    ): Int {
        val locked =
            relation?.let {
                " AND EXISTS (SELECT 1 FROM pg_locks l " +
                    "WHERE l.pid = a.pid AND l.relation = CAST(:relation AS regclass))"
            }
        return jdbc
            .sql(
                "SELECT count(*) FROM pg_stat_activity a WHERE a.datname = current_database() " +
                    "AND :holder = ANY (pg_blocking_pids(a.pid))" + locked.orEmpty(),
            ).param("holder", holder)
            .let { if (relation != null) it.param("relation", relation) else it }
            .query(Int::class.java)
            .single()
    }

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    /**
     * Espera a etapa `advance-business-date` da execução de [date] ficar `RUNNING`, com prazo contado do disparo e
     * folgado (as etapas anteriores agem sobre tudo o que o banco isolado acumulou). Falha logo se a execução terminar
     * antes.
     */
    private fun awaitAdvanceRunning(
        date: LocalDate,
        running: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ADVANCE_REACH_SECONDS)
        while (advanceStatusOn(date) != "RUNNING") {
            check(!running.isDone) { "A execução terminou sem a etapa de avanço ficar RUNNING" }
            check(System.nanoTime() < deadline) { "A etapa de avanço não começou em $ADVANCE_REACH_SECONDS s" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun advanceStatusOn(date: LocalDate): String? =
        jdbc
            .sql(
                "SELECT s.status FROM endofday.run_step s JOIN endofday.run r ON r.id = s.run_id " +
                    "WHERE r.business_date = :date AND s.name = 'advance-business-date'",
            ).param("date", date)
            .query(String::class.java)
            .optional()
            .orElse(null)

    /** Espera, com prazo, outra transação travar a linha da reserva [id] (`FOR UPDATE NOWAIT` recusado). */
    private fun awaitRowLocked(id: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!rowLocked(id)) {
            check(System.nanoTime() < deadline) { "O no-show não chegou a travar a reserva" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun rowLocked(id: UUID): Boolean =
        try {
            tx.executeWithoutResult { _ ->
                jdbc
                    .sql("SELECT id FROM reservations.reservation WHERE id = :id FOR UPDATE NOWAIT")
                    .param("id", id)
                    .query(UUID::class.java)
                    .list()
            }
            false
        } catch (e: DataAccessException) {
            // 55P03 lock_not_available: a linha está travada por outra transação.
            ((e.mostSpecificCause as? SQLException)?.sqlState ?: throw e) == LOCK_NOT_AVAILABLE || throw e
        }

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun runVersion(run: EndOfDayRunId): Long =
        jdbc
            .sql("SELECT version FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(Long::class.java)
            .single()

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :date")
            .param("date", date)
            .query(Int::class.java)
            .single()

    private fun issueCount(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id")
            .param("id", run.value)
            .query(Int::class.java)
            .single()

    /** As pendências da execução com referência em [references], como `kind to referência`. */
    private fun issuesOf(
        run: EndOfDayRunId,
        references: List<String>,
    ): List<Pair<String, String?>> =
        jdbc
            .sql(
                "SELECT kind, reference FROM endofday.run_issue " +
                    "WHERE run_id = :id AND step_name <> 'end-of-day-pending' ORDER BY created_at, id",
            ).param("id", run.value)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()
            .filter { it.second in references }

    private fun lines(folio: UUID): List<Line> =
        jdbc
            .sql(
                "SELECT source_reference, amount, business_date, source FROM billing.posting " +
                    "WHERE folio_id = :folio ORDER BY posted_at, id",
            ).param("folio", folio)
            .query { rs, _ ->
                Line(
                    rs.getString("source_reference"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getString("source"),
                )
            }.list()

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

    private fun endOfDayDirtyEntries(room: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM rooms.room_status_change WHERE room_id = :id " +
                    "AND field = 'HOUSEKEEPING_STATUS' AND source = 'END_OF_DAY'",
            ).param("id", room)
            .query(Int::class.java)
            .single()

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private companion object {
        const val PRICED_DAYS = 10L
        const val WAIT_SECONDS = 30L
        const val HOLD_SECONDS = 30L
        const val ADVANCE_REACH_SECONDS = 40L
        const val LOCK_WAIT_SECONDS = 15L
        const val BUSINESS_DAY = "hotel.business_day"
        const val POLL_MILLIS = 20L
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
