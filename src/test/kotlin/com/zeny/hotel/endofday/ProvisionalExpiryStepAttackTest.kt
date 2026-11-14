package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.steps.ProvisionalExpiryStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.application.ProvisionalExpiry
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O ataque à etapa `provisional-expiry` (M6 E11, Task 15) e às operações `dueProvisionals` e `expireProvisional`: a
 * pré-reserva ainda válida, a confirmada, a que sumiu da lista, a lista velha, a ordem e o limite de validade, a
 * pendência, a segunda execução e a corrida com o job e com o cancelamento da recepção. Contexto das etapas reais;
 * relógio devolvido no `finally`, `settle` ao fim de quem fecha a data, asserções só sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions")
class ProvisionalExpiryStepAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val reservations: ReservationsApi,
    @Autowired private val job: ProvisionalExpiry,
    @Autowired private val step: ProvisionalExpiryStep,
    @Autowired private val items: EndOfDayItems,
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory

    @Test
    fun `expireProvisional leaves a still valid provisional untouched and answers false`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(2, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val valid = created(type, plan, arrival)
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry.minusSeconds(1))

            reservations.dueProvisionals().map { it.value }.contains(valid.id()) shouldBe false
            reservations.expireProvisional(ReservationId(valid.id())) shouldBe false

            support.status(valid.id()) shouldBe "PROVISIONAL"
            support.historyKinds(valid.id()).contains("CANCEL") shouldBe false
            inventory.row(type, arrival)!!.sold shouldBe sold
            expireAudits(valid.id()) shouldBe 0
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `a confirmed reservation is not due and expireProvisional answers false after the validity passed`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(2, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val booked = created(type, plan, arrival)
            support.confirm(booked.id(), "CARD").status shouldBe 200
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry.plusHours(1))

            reservations.dueProvisionals().map { it.value }.contains(booked.id()) shouldBe false
            reservations.expireProvisional(ReservationId(booked.id())) shouldBe false

            support.status(booked.id()) shouldBe "CONFIRMED"
            support.historyKinds(booked.id()).contains("CANCEL") shouldBe false
            inventory.row(type, arrival)!!.sold shouldBe sold
            expireAudits(booked.id()) shouldBe 0
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `expireProvisional of an unknown id is refused as not found and writes nothing`() {
        val unknown = ReservationId(UUID.randomUUID())

        val refusal = shouldThrow<NotFoundException> { reservations.expireProvisional(unknown) }

        refusal.code.value shouldBe "RESERVATION_NOT_FOUND"
        expireAudits(unknown.value) shouldBe 0
    }

    @Test
    fun `dueProvisionals includes the exact validity instant and orders by validity then id`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(4, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            // Mais nova pelo id, mas com a validade mais cedo: criada depois de o relógio voltar.
            clock.advance(Duration.ofHours(1))
            val later = created(type, plan, arrival).id()
            rewind(start)
            val sameA = created(type, plan, arrival).id()
            val sameB = created(type, plan, arrival).id()
            val earliest = until(sameA)
            until(sameB) shouldBe earliest
            val sameValidityById = listOf(sameA, sameB).sortedBy { it.toString() }
            val mine = setOf(later, sameA, sameB)

            at(earliest.minusNanos(1_000))
            dueOf(mine).shouldBeEmpty()

            at(earliest)
            dueOf(mine) shouldContainExactly sameValidityById

            at(until(later))
            dueOf(mine) shouldContainExactly sameValidityById + later
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `a stale list skips a confirmed or valid reservation without issue`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(4, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val confirmed = created(type, plan, arrival).id()
            support.confirm(confirmed, "CARD").status shouldBe 200
            val expired = created(type, plan, arrival).id()
            clock.advance(hotel.operationalSettings().preReservationExpiry)
            val sold = inventory.row(type, arrival)!!.sold
            // Uma reserva criada depois de passar a validade das outras: ainda válida agora.
            val valid = created(type, plan, arrival).id()
            val sink = Sink()

            val outcome = stepWithList(listOf(confirmed, valid, expired)).execute(contextOf(sink))

            outcome shouldBe EndOfDayStepOutcome.DONE
            sink.issues.shouldBeEmpty()
            support.status(confirmed) shouldBe "CONFIRMED"
            support.status(valid) shouldBe "PROVISIONAL"
            support.status(expired) shouldBe "CANCELLED"
            support.historyKinds(expired) shouldContainExactly listOf("CANCEL")
            inventory.row(type, arrival)!!.sold shouldBe sold
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `a refused item becomes one issue referenced by id and the others are still expired`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(3, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val first = created(type, plan, arrival).id()
            val second = created(type, plan, arrival).id()
            clock.advance(hotel.operationalSettings().preReservationExpiry)
            val ghost = UUID.randomUUID()
            val sink = Sink()

            stepWithList(listOf(ghost, first, second)).execute(contextOf(sink)) shouldBe EndOfDayStepOutcome.DONE

            sink.issues shouldHaveSize 1
            val issue = sink.issues.single()
            issue.kind shouldBe "PROVISIONAL_EXPIRY_FAILED"
            issue.reference shouldBe ghost.toString()
            issue.message shouldBe "PROVISIONAL_EXPIRY_FAILED: RESERVATION_NOT_FOUND"
            issue.message shouldNotContain ghost.toString()
            support.status(first) shouldBe "CANCELLED"
            support.status(second) shouldBe "CANCELLED"
        } finally {
            rewind(start)
        }
    }

    @Test
    fun `running the step twice for the same date has no duplicated effect`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(2, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val due = created(type, plan, arrival)
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry)
            val sink = Sink()

            step.execute(contextOf(sink))
            val afterFirst = inventory.row(type, arrival)!!.sold
            step.execute(contextOf(sink))

            afterFirst shouldBe sold - 1
            inventory.row(type, arrival)!!.sold shouldBe sold - 1
            support.historyKinds(due.id()) shouldContainExactly listOf("CANCEL")
            expireAudits(due.id()) shouldBe 1
            support.postings(due.folio()).shouldBeEmpty()
            sink.issues.shouldBeEmpty()
        } finally {
            rewind(start)
        }
    }

    @Test
    @Timeout(120)
    fun `the step racing the expiry job expires each reservation once and returns inventory once`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(8, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val ids = (1..RACE_SIZE).map { created(type, plan, arrival).id() }
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry)

            val (run, sweep) = raced { job.expireDue() }

            sweep.failed shouldBe 0
            runStatus(run) shouldBe "COMPLETED"
            issueCount(run) shouldBe 0
            ids.forEach {
                support.status(it) shouldBe "CANCELLED"
                support.historyKinds(it) shouldContainExactly listOf("CANCEL")
                expireAudits(it) shouldBe 1
            }
            inventory.row(type, arrival)!!.sold shouldBe sold - RACE_SIZE
        } finally {
            rewind(start)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    @Timeout(120)
    fun `the step racing a reception cancellation leaves one cancellation per reservation`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(8, date)
        val plan = plan(type, date, "CARD")
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val ids = (1..RACE_SIZE).map { created(type, plan, arrival).id() }
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry)

            val (run, responses) = raced { ids.map { support.cancel(it).status } }

            // A recepção cancela ou perde a corrida (4xx); nunca 5xx.
            responses.none { it >= SERVER_ERROR } shouldBe true
            runStatus(run) shouldBe "COMPLETED"
            issueCount(run) shouldBe 0
            ids.forEach {
                support.status(it) shouldBe "CANCELLED"
                support.historyKinds(it) shouldContainExactly listOf("CANCEL")
            }
            inventory.row(type, arrival)!!.sold shouldBe sold - RACE_SIZE
        } finally {
            rewind(start)
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Apoio ---------------------------------------------------------------------------------------------------

    private class Issue(
        val kind: String,
        val reference: String?,
        val message: String,
    )

    private class Sink : com.zeny.hotel.endofday.application.EndOfDayIssueSink {
        val issues = java.util.concurrent.CopyOnWriteArrayList<Issue>()

        override fun raise(
            kind: String,
            reference: String?,
            message: String,
        ) {
            issues += Issue(kind, reference, message)
        }
    }

    private fun contextOf(sink: Sink) =
        EndOfDayStepContext(EndOfDayRunId(UUID.randomUUID()), hotel.currentBusinessDate(), sink)

    /** A etapa real sobre uma lista fixa de candidatos; a expiração de cada um continua a real. */
    private fun stepWithList(ids: List<UUID>): ProvisionalExpiryStep {
        val fixed =
            object : ReservationsApi by reservations {
                override fun dueProvisionals(): List<ReservationId> = ids.map { ReservationId(it) }
            }
        return ProvisionalExpiryStep(fixed, items)
    }

    /** Fecha a data corrente enquanto [other] roda ao mesmo tempo, largando juntos. */
    private fun <T> raced(other: () -> T): Pair<EndOfDayRunId, T> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val closing =
                pool.submit<EndOfDayRunId> {
                    go.await()
                    EndOfDayTestRuns.closeCurrentDate(context)
                }
            val racing =
                pool.submit<T> {
                    go.await()
                    other()
                }
            go.countDown()
            return closing.get(RACE_WAIT, TimeUnit.SECONDS) to racing.get(RACE_WAIT, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan(
        type: UUID,
        date: LocalDate,
        guarantee: String,
    ): UUID {
        val plan =
            support.plan(
                listOf(type),
                guarantee = guarantee,
                cancellationBody = support.rates.cancellationBody(deadlineHours = 8760, penaltyNights = 1),
            )
        support.setPrices(plan.id, listOf(type), date, date.plusDays(20))
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, arrival.plusDays(2))),
        )["reservations"][0]

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private fun until(id: UUID): Instant =
        jdbc
            .sql("SELECT provisional_until FROM reservations.reservation WHERE id = :id")
            .param("id", id)
            .query(Instant::class.java)
            .single()

    private fun at(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun rewind(start: Instant) = at(start)

    private fun dueOf(mine: Set<UUID>): List<UUID> =
        reservations.dueProvisionals().map { it.value }.filter { it in mine }

    private fun expireAudits(id: UUID): Int =
        support.auditEntries(id).count { it["action"] == "reservations.reservation.expire" }

    private fun runStatus(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run_step WHERE run_id = :id AND name = 'provisional-expiry'")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun issueCount(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'PROVISIONAL_EXPIRY_FAILED'")
            .param("id", run.value)
            .query(Int::class.java)
            .single()

    private companion object {
        const val RACE_SIZE = 6
        const val RACE_WAIT = 90L
        const val SERVER_ERROR = 500
    }
}
