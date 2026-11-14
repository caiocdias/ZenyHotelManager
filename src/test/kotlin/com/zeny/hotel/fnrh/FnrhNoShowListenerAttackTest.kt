package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionListeners
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationMarkedNoShow
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O ataque ao listener do `ReservationMarkedNoShow` (M6 E14, Task 5; D-E14-2): o autor, o instante, a versão e o código
 * chegam à submissão; a reentrega depois de `FAILED`/`DISMISSED` e a entrega concorrente não duplicam; dois no-shows da
 * mesma reserva (reativada entre eles) ficam em ordem causal e só o primeiro chama o Serpro; a reserva inexistente só
 * gera um aviso com o id; nada de dado pessoal na submissão nem no log. Mesmo contexto do [FnrhNoShowSendTest]; cada
 * teste cria a própria reserva e olha só a fila e os pedidos dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhNoShowListenerAttackTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired sender: FnrhSubmissionSender,
    @Autowired store: FnrhSubmissionStore,
    @Autowired remoteIds: FnrhRemoteIds,
    @Autowired clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired tx: TransactionTemplate,
    @Autowired private val listeners: FnrhSubmissionListeners,
    @Autowired @Qualifier("applicationTaskExecutor") private val executor: ThreadPoolTaskExecutor,
) : FnrhReservationSendTestBase(
        iam,
        jdbc,
        hotelApi,
        reservationsApi,
        sender,
        store,
        remoteIds,
        clock,
        mockMvc,
        jsonMapper,
        tx,
    ) {
    /** Uma linha `NO_SHOW` com os campos que vêm do evento. */
    private data class NoShowRow(
        val id: UUID,
        val eventKey: String,
        val causalVersion: Long,
        val performedBy: UUID?,
        val occurredAt: Instant,
        val code: String,
        val status: FnrhSubmissionStatus,
    )

    @Test
    fun `the no-show event carries its performer, instant, version and reservation code into the submission`() {
        val id = arrivingToday()
        val by = UuidV7.generate()
        val at = Instant.parse("2026-11-14T21:43:05.123456Z")

        deliver(event(id, version = 41, by = by, at = at))

        noShows(id).single().also {
            it.eventKey shouldBe "no-show:v41"
            it.causalVersion shouldBe 41L
            it.performedBy shouldBe by
            it.occurredAt shouldBe at.truncatedTo(ChronoUnit.MICROS)
            it.code shouldBe codeOf(id)
            it.status shouldBe FnrhSubmissionStatus.PENDING
        }
    }

    @Test
    fun `redelivering the no-show after FAILED or DISMISSED records nothing new and keeps the status`() {
        val id = arrivingToday()
        val failedEvent = event(id, version = 51)
        val dismissedEvent = event(id, version = 52)
        deliver(failedEvent)
        deliver(dismissedEvent)
        val failed = noShows(id).single { it.eventKey == "no-show:v51" }
        val dismissed = noShows(id).single { it.eventKey == "no-show:v52" }
        change(failed.id) { it.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy()) }
        change(dismissed.id) {
            it.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy())
            it.dismiss()
        }

        deliver(failedEvent)
        deliver(dismissedEvent)
        deliver(dismissedEvent)

        noShows(id).map { Triple(it.id, it.eventKey, it.status) } shouldContainExactly
            listOf(
                Triple(failed.id, "no-show:v51", FnrhSubmissionStatus.FAILED),
                Triple(dismissed.id, "no-show:v52", FnrhSubmissionStatus.DISMISSED),
            )
    }

    @Test
    fun `the same no-show delivered by many threads at once records exactly one submission`() {
        val id = arrivingToday()
        val event = event(id, version = 61)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        go.await()
                        listeners.on(event)
                    }
                }
            go.countDown()
            futures.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        settle()

        noShows(id) shouldHaveSize 1
    }

    @Test
    fun `no-show, reactivation and no-show again record two no-shows in causal order and only the first calls`() {
        val id = arrivingToday()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)

        val first = markNoShow(id)
        support.reactivate(id).also { with(support.billing) { it.expect(200) } }
        val second = markNoShow(id)
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            noShows(id).size == 2 && rows(id).any { it.operation == FnrhOperation.UPDATE_RESERVATION }
        }

        // Duas submissões, uma por versão, e a da reativação entre elas na ordem causal.
        noShows(id).map { it.eventKey to it.causalVersion } shouldContainExactly
            listOf("no-show:v${first.version}" to first.version, "no-show:v${second.version}" to second.version)
        (first.version < second.version) shouldBe true
        causalOrder(id) shouldContainExactly
            listOf(
                FnrhOperation.REGISTER_RESERVATION,
                FnrhOperation.NO_SHOW,
                FnrhOperation.UPDATE_RESERVATION,
                FnrhOperation.NO_SHOW,
            )

        // O primeiro no-show sai (a reserva é no-show aqui); a reativação e o segundo, com a remota já no-show, não.
        sender.send(id) shouldBe FnrhSendRun(sent = 3, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        val (firstRow, secondRow) = noShows(id)
        correlations(listOf(firstRow.id, secondRow.id)) shouldContainExactly listOf(firstRow.id.toString())
        noShows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe true
    }

    @Test
    fun `a no-show event of an unknown reservation records nothing and warns with the id only`(output: CapturedOutput) {
        val id = UuidV7.generate()

        deliver(event(id, version = 7))

        noShows(id).shouldBeEmpty()
        val lines = output.all.lines().filter { it.contains(id.toString()) }
        lines shouldHaveSize 1
        lines.single().also {
            it shouldContain "WARN"
            it shouldContain "FnrhSubmissionListeners"
            it shouldContain "NO_SHOW"
        }
    }

    @Test
    fun `the no-show submission and the listener log carry no personal data`(output: CapturedOutput) {
        val holder = support.guest()
        val id = arrivingToday(holder)
        val name = guestName(holder)
        val marked = markNoShow(id)
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            noShows(id).isNotEmpty()
        }
        // A reentrega passa pelo caminho do "já gravada", que também loga.
        deliver(event(id, version = marked.version))

        val row = noShows(id).single()
        val stored =
            jdbc
                .sql("SELECT row_to_json(s)::text FROM fnrh.submission s WHERE id = :id")
                .param("id", row.id)
                .query(String::class.java)
                .single()
        stored shouldNotContain name
        stored shouldNotContain holder.toString()
        output.all
            .lines()
            .filter { it.contains(id.toString()) || it.contains(row.id.toString()) }
            .forEach {
                it shouldNotContain name
                it shouldNotContain holder.toString()
            }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma reserva confirmada chegando hoje (o no-show só vale até a data operacional), com o registro na fila. */
    private fun arrivingToday(holder: UUID = support.guest()): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(holder, support.reservation(type, plan.id, today, today.plusDays(2))),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    /** Marca a reserva como no-show como o fechamento marca (o evento sai pelo outbox no commit). */
    private fun markNoShow(reservation: UUID): ReservationSummary =
        checkNotNull(tx.execute { reservationsApi.markNoShow(ReservationId(reservation)) }).also {
            it.status shouldBe ReservationStatus.NO_SHOW
        }

    /** Um `ReservationMarkedNoShow` sintético sobre a reserva (o listener só usa id, autor, versão e instante). */
    private fun event(
        reservation: UUID,
        version: Long,
        by: UUID? = null,
        at: Instant = clock.instant(),
    ) = ReservationMarkedNoShow(
        reservationId = reservation,
        bookingId = UuidV7.generate(),
        roomTypeId = UuidV7.generate(),
        roomId = null,
        arrival = today,
        departure = today.plusDays(2),
        dayUse = false,
        businessDate = today,
        penaltyCharged = false,
        performedBy = by,
        version = version,
        markedAt = at,
    )

    /** O listener é assíncrono: entrega o evento e espera o executor esvaziar. */
    private fun deliver(event: ReservationMarkedNoShow) {
        listeners.on(event)
        settle()
    }

    private fun settle() {
        val pool = executor.threadPoolExecutor
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).pollInterval(Duration.ofMillis(20)).until {
            pool.queue.isEmpty() && pool.activeCount == 0 && pool.completedTaskCount == pool.taskCount
        }
    }

    private fun change(
        id: UUID,
        action: (com.zeny.hotel.fnrh.domain.FnrhSubmission) -> Unit,
    ) = tx.executeWithoutResult {
        val s = checkNotNull(store.find(id))
        action(s)
        store.save(s)
    }

    private fun noShows(reservation: UUID): List<NoShowRow> =
        jdbc
            .sql(
                "SELECT id, event_key, causal_version, performed_by, occurred_at, reservation_code, status " +
                    "FROM fnrh.submission WHERE reservation_id = :id AND operation = 'NO_SHOW' ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                NoShowRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("event_key"),
                    rs.getLong("causal_version"),
                    rs.getObject("performed_by", UUID::class.java),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("reservation_code"),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                )
            }.list()

    /** As operações da reserva na ordem da fila: versão causal e, nela, o posto da operação (D-E08-3, D-E14-2). */
    private fun causalOrder(reservation: UUID): List<FnrhOperation> =
        jdbc
            .sql("SELECT operation, causal_version FROM fnrh.submission WHERE reservation_id = :id")
            .param("id", reservation)
            .query { rs, _ -> FnrhOperation.valueOf(rs.getString(1)) to rs.getLong(2) }
            .list()
            .sortedWith(compareBy({ it.second }, { it.first.rank }))
            .map { it.first }

    /** Os `correlation_ref` de `fnrh.integration_call` entre os ids dados (uma linha por chamada ao Serpro). */
    private fun correlations(submissions: List<UUID>): List<String> =
        jdbc
            .sql("SELECT correlation_ref FROM fnrh.integration_call WHERE correlation_ref IN (:ids) ORDER BY id")
            .param("ids", submissions.map { it.toString() })
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun guestName(guest: UUID): String =
        jdbc
            .sql("SELECT full_name FROM guests.guest WHERE id = :id")
            .param("id", guest)
            .query(String::class.java)
            .single()

    private companion object {
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 50L
    }
}
