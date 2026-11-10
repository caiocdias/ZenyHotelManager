package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteGuest
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionListeners
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.GuestCheckInUndone
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationCancelled
import com.zeny.hotel.reservations.ReservationChanged
import com.zeny.hotel.reservations.ReservationConfirmed
import com.zeny.hotel.reservations.ReservationCreated
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationReactivated
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques aos listeners da submissão à FNRH (M5 E08, Task 3): reentrega concorrente e depois do estado final, chaves e
 * versão causal dos 8 eventos, o desfazer do check-in (D-E08-8), a reserva inexistente e o não bloqueio. Eventos
 * sintéticos, de versão alta, sobre uma reserva criada pelo fluxo real (o código vem da `ReservationsApi`); o banco é
 * compartilhado, então cada teste cria a própria reserva.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions") // portas, apoios e um teste por comportamento
class FnrhSubmissionListenersAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val listeners: FnrhSubmissionListeners,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val tx: TransactionTemplate,
    @Autowired @Qualifier("applicationTaskExecutor") private val executor: ThreadPoolTaskExecutor,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    private data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val eventKey: String,
        val causalVersion: Long,
        val performedBy: UUID?,
        val code: String,
        val status: FnrhSubmissionStatus,
        val occurredAt: Instant,
    )

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, operation, event_key, causal_version, performed_by, reservation_code, status, " +
                    "occurred_at FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    FnrhOperation.valueOf(rs.getString("operation")),
                    rs.getString("event_key"),
                    rs.getLong("causal_version"),
                    rs.getObject("performed_by", UUID::class.java),
                    rs.getString("reservation_code"),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("occurred_at").toInstant(),
                )
            }.list()

    /** As linhas da reserva menos a de `created` que o fluxo real grava sozinho. */
    private fun synthetic(reservation: UUID) = rows(reservation).filterNot { it.eventKey == "created" }

    private class Reservation(
        val id: UUID,
        val code: String,
    )

    /** Uma reserva real (confirmada, sem check-in) e o quiescente do outbox: nada assíncrono pendente depois. */
    private fun reservation(): Reservation {
        val booked = frontdesk.stay().book(frontdesk.guest())
        InventoryListenersQuiescence.await(jdbc)
        val code = checkNotNull(reservationsApi.reservation(ReservationId(booked.id))).code.toString()
        return Reservation(booked.id, code)
    }

    private val at: Instant = Instant.parse("2026-11-10T12:34:56.123456Z")

    private fun created(id: UUID) =
        ReservationCreated(
            reservationId = id,
            bookingId = UuidV7.generate(),
            status = ReservationStatus.PROVISIONAL,
            roomTypeId = UuidV7.generate(),
            ratePlanId = UuidV7.generate(),
            arrival = today,
            departure = today.plusDays(1),
            dayUse = false,
            adults = 1,
            children = 0,
            origin = ReservationOrigin.FRONT_DESK,
            channel = SalesChannel.DIRECT,
            createdAt = at,
        )

    private fun changed(
        id: UUID,
        version: Long,
    ) = ReservationChanged(
        reservationId = id,
        bookingId = UuidV7.generate(),
        status = ReservationStatus.CONFIRMED,
        roomTypeId = UuidV7.generate(),
        previousRoomTypeId = UuidV7.generate(),
        ratePlanId = UuidV7.generate(),
        previousRatePlanId = UuidV7.generate(),
        arrival = today,
        previousArrival = today,
        departure = today.plusDays(2),
        previousDeparture = today.plusDays(1),
        dayUse = false,
        adults = 1,
        children = 0,
        changedFields = listOf("departure"),
        version = version,
        changedAt = at,
    )

    private fun confirmed(
        id: UUID,
        version: Long,
    ) = ReservationConfirmed(
        id,
        UuidV7.generate(),
        UuidV7.generate(),
        today,
        today.plusDays(1),
        false,
        GuaranteeMethod.CARD,
        version,
        at,
    )

    private fun reactivated(
        id: UUID,
        version: Long,
    ) = ReservationReactivated(
        id,
        UuidV7.generate(),
        ReservationStatus.CANCELLED,
        UuidV7.generate(),
        today,
        today.plusDays(1),
        false,
        version,
        at,
    )

    private fun cancelled(
        id: UUID,
        version: Long,
    ) = ReservationCancelled(
        id,
        UuidV7.generate(),
        ReservationStatus.CONFIRMED,
        UuidV7.generate(),
        today,
        today.plusDays(1),
        false,
        CancellationReason.GUEST_REQUEST,
        penaltyCharged = false,
        penaltyWaived = false,
        version = version,
        cancelledAt = at,
    )

    private fun checkedIn(
        id: UUID,
        version: Long,
        by: UUID? = UuidV7.generate(),
    ) = GuestCheckedIn(
        id,
        UuidV7.generate(),
        UuidV7.generate(),
        UuidV7.generate(),
        today,
        today.plusDays(1),
        false,
        1,
        0,
        1,
        today,
        by,
        version,
        at,
    )

    private fun undone(
        id: UUID,
        version: Long,
        by: UUID? = UuidV7.generate(),
    ) = GuestCheckInUndone(id, UuidV7.generate(), UuidV7.generate(), today, by, version, at)

    private fun checkedOut(
        id: UUID,
        version: Long,
        by: UUID? = UuidV7.generate(),
    ) = GuestCheckedOut(
        id,
        UuidV7.generate(),
        UuidV7.generate(),
        UuidV7.generate(),
        today,
        today.plusDays(1),
        today.plusDays(1),
        false,
        today,
        by,
        version,
        at,
    )

    private fun change(
        id: UUID,
        action: (com.zeny.hotel.fnrh.domain.FnrhSubmission) -> Unit,
    ) = tx.executeWithoutResult {
        val s = checkNotNull(store.find(id))
        action(s)
        store.save(s)
    }

    /**
     * Os métodos de `@ApplicationModuleListener` são assíncronos: chamar o bean volta antes de gravar. Entrega o evento
     * e espera o executor esvaziar, para que a asserção veja o efeito (ou a ausência dele).
     */
    private fun deliver(event: Any) {
        dispatch(event)
        settle()
    }

    private fun dispatch(event: Any) {
        when (event) {
            is ReservationCreated -> listeners.on(event)
            is ReservationChanged -> listeners.on(event)
            is ReservationConfirmed -> listeners.on(event)
            is ReservationReactivated -> listeners.on(event)
            is ReservationCancelled -> listeners.on(event)
            is GuestCheckedIn -> listeners.on(event)
            is GuestCheckInUndone -> listeners.on(event)
            is GuestCheckedOut -> listeners.on(event)
            else -> error("evento sem listener: $event")
        }
    }

    private fun settle() {
        val pool = executor.threadPoolExecutor
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20)).until {
            pool.queue.isEmpty() && pool.activeCount == 0 && pool.completedTaskCount == pool.taskCount
        }
    }

    // --- Chaves, versão causal, performedBy e occurredAt ----------------------------------------------------------

    @Test
    fun `each of the 8 events records its operation, key, causal version, performer, instant and reservation code`() {
        val r = reservation()
        val by = UuidV7.generate()
        deliver(changed(r.id, 11))
        deliver(confirmed(r.id, 12))
        deliver(reactivated(r.id, 13))
        deliver(cancelled(r.id, 14))
        deliver(checkedIn(r.id, 15, by))
        deliver(undone(r.id, 16, by))
        deliver(checkedOut(r.id, 17, by))

        val micros = at.truncatedTo(ChronoUnit.MICROS)
        synthetic(
            r.id,
        ).map { listOf(it.operation, it.eventKey, it.causalVersion, it.performedBy, it.occurredAt) } shouldBe
            listOf(
                listOf(FnrhOperation.UPDATE_RESERVATION, "changed:v11", 11L, null, micros),
                listOf(FnrhOperation.UPDATE_RESERVATION, "confirmed:v12", 12L, null, micros),
                listOf(FnrhOperation.UPDATE_RESERVATION, "reactivated:v13", 13L, null, micros),
                listOf(FnrhOperation.CANCEL_RESERVATION, "cancelled:v14", 14L, null, micros),
                listOf(FnrhOperation.CHECK_IN, "checked-in:v15", 15L, by, micros),
                listOf(FnrhOperation.UNDO_CHECK_IN, "check-in-undone:v16", 16L, by, micros),
                listOf(FnrhOperation.CHECK_OUT, "checked-out:v17", 17L, by, micros),
            )
        synthetic(r.id).map { it.code }.toSet() shouldBe setOf(r.code)
    }

    @Test
    fun `a created event records the registration at version zero with the plain key`() {
        val r = reservation()
        deliver(created(r.id))

        val row = rows(r.id).single { it.operation == FnrhOperation.REGISTER_RESERVATION }
        row.eventKey shouldBe "created"
        row.causalVersion shouldBe 0L
        row.performedBy.shouldBeNull()
        row.code shouldBe r.code
    }

    @Test
    fun `a hospitality event without performer records a null performer`() {
        val r = reservation()
        deliver(checkedIn(r.id, 21, by = null))

        rows(r.id).single { it.eventKey == "checked-in:v21" }.performedBy.shouldBeNull()
    }

    @Test
    fun `a changed event with another version records another submission and the same version records none`() {
        val r = reservation()
        deliver(changed(r.id, 31))
        deliver(changed(r.id, 32))
        deliver(changed(r.id, 31))

        synthetic(r.id).map { it.eventKey } shouldBe listOf("changed:v31", "changed:v32")
    }

    @Test
    fun `confirmed, changed and reactivated at the same version are three distinct submissions`() {
        val r = reservation()
        deliver(changed(r.id, 41))
        deliver(confirmed(r.id, 41))
        deliver(reactivated(r.id, 41))

        synthetic(r.id).map { it.eventKey }.toSet() shouldBe setOf("changed:v41", "confirmed:v41", "reactivated:v41")
    }

    // --- Idempotência ---------------------------------------------------------------------------------------------

    @Test
    fun `the same event delivered by many threads at once records exactly one submission`() {
        val r = reservation()
        val event = changed(r.id, 51)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        ready.countDown()
                        go.await()
                        dispatch(event)
                    }
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        settle()

        synthetic(r.id).filter { it.eventKey == "changed:v51" } shouldHaveSize 1
    }

    @Test
    fun `the same undo event delivered by many threads at once records one undo and no error`() {
        val r = reservation()
        deliver(checkedIn(r.id, 61))
        val event = undone(r.id, 62)
        val threads = 6
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        go.await()
                        dispatch(event)
                    }
                }
            go.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        settle()

        synthetic(r.id).filter { it.operation == FnrhOperation.UNDO_CHECK_IN } shouldHaveSize 1
        synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe FnrhSubmissionStatus.CANCELLED
    }

    @Test
    fun `redelivering an event keeps a FAILED submission FAILED and a CANCELLED one CANCELLED`() {
        val r = reservation()
        val failedEvent = changed(r.id, 71)
        val cancelledEvent = changed(r.id, 72)
        deliver(failedEvent)
        deliver(cancelledEvent)
        val failed = synthetic(r.id).single { it.eventKey == "changed:v71" }
        val cancel = synthetic(r.id).single { it.eventKey == "changed:v72" }
        change(failed.id) { it.fail(FnrhSubmissionFailure.REMOTE_CANCELLED, Instant.now()) }
        change(cancel.id) { it.cancel(Instant.now()) }

        deliver(failedEvent)
        deliver(cancelledEvent)

        synthetic(r.id).associate { it.eventKey to it.status } shouldBe
            mapOf(
                "changed:v71" to FnrhSubmissionStatus.FAILED,
                "changed:v72" to FnrhSubmissionStatus.CANCELLED,
            )
    }

    @Test
    fun `redelivering an undo event does not record a second undo`() {
        val r = reservation()
        val event = undone(r.id, 81)
        deliver(event)
        deliver(event)

        synthetic(r.id).filter { it.operation == FnrhOperation.UNDO_CHECK_IN } shouldHaveSize 1
    }

    // --- Desfazer o check-in --------------------------------------------------------------------------------------

    @Test
    fun `undoing cancels every unresolved check-in of the reservation and leaves the other operations alone`() {
        val r = reservation()
        deliver(changed(r.id, 91))
        deliver(checkedIn(r.id, 92))
        deliver(checkedIn(r.id, 93))
        deliver(checkedOut(r.id, 94))

        deliver(undone(r.id, 95))

        synthetic(r.id).associate { it.eventKey to it.status } shouldBe
            mapOf(
                "changed:v91" to FnrhSubmissionStatus.PENDING,
                "checked-in:v92" to FnrhSubmissionStatus.CANCELLED,
                "checked-in:v93" to FnrhSubmissionStatus.CANCELLED,
                "checked-out:v94" to FnrhSubmissionStatus.PENDING,
                "check-in-undone:v95" to FnrhSubmissionStatus.PENDING,
            )
    }

    @Test
    fun `undoing leaves a SENT or DISMISSED check-in as it was`() {
        val r = reservation()
        deliver(checkedIn(r.id, 101))
        deliver(checkedIn(r.id, 102))
        val sent = synthetic(r.id).single { it.eventKey == "checked-in:v101" }
        val dismissed = synthetic(r.id).single { it.eventKey == "checked-in:v102" }
        change(sent.id) { it.markSent(Instant.now()) }
        change(dismissed.id) {
            it.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, Instant.now())
            it.dismiss(Instant.now())
        }

        deliver(undone(r.id, 103))

        synthetic(
            r.id,
        ).filter { it.operation == FnrhOperation.CHECK_IN }.associate { it.eventKey to it.status } shouldBe
            mapOf(
                "checked-in:v101" to FnrhSubmissionStatus.SENT,
                "checked-in:v102" to FnrhSubmissionStatus.DISMISSED,
            )
    }

    @Test
    fun `undoing does not touch the check-in of another reservation`() {
        val a = reservation()
        val b = reservation()
        deliver(checkedIn(a.id, 111))
        deliver(checkedIn(b.id, 112))

        deliver(undone(a.id, 113))

        synthetic(a.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe
            FnrhSubmissionStatus.CANCELLED
        synthetic(b.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe
            FnrhSubmissionStatus.PENDING
    }

    @Test
    fun `undoing with the integration configured but no remote guest still cancels the check-in`() {
        val r = reservation()
        iam.enableFnrh()
        try {
            deliver(checkedIn(r.id, 121))

            deliver(undone(r.id, 122))

            synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe
                FnrhSubmissionStatus.CANCELLED
        } finally {
            jdbc.resetFnrhSettings()
        }
    }

    @Test
    fun `a remote guest of another environment does not keep the check-in alive`() {
        val r = reservation()
        iam.enableFnrh("HOMOLOGATION")
        try {
            deliver(checkedIn(r.id, 131))
            val checkIn = synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }
            remoteIds.saveGuest(
                FnrhRemoteGuest(r.id, UuidV7.generate(), FnrhEnvironment.PRODUCTION, FnrhRemoteId("p-9"), null, false),
                checkIn.id,
            )

            deliver(undone(r.id, 132))

            synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe
                FnrhSubmissionStatus.CANCELLED
        } finally {
            jdbc.resetFnrhSettings()
        }
    }

    @Test
    fun `a remote guest of the current environment keeps every pending check-in alive`() {
        val r = reservation()
        iam.enableFnrh("HOMOLOGATION")
        try {
            deliver(checkedIn(r.id, 141))
            val checkIn = synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }
            remoteIds.saveGuest(
                FnrhRemoteGuest(
                    r.id,
                    UuidV7.generate(),
                    FnrhEnvironment.HOMOLOGATION,
                    FnrhRemoteId("p-8"),
                    null,
                    false,
                ),
                checkIn.id,
            )

            deliver(undone(r.id, 142))

            synthetic(r.id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe
                FnrhSubmissionStatus.PENDING
            synthetic(r.id).single { it.operation == FnrhOperation.UNDO_CHECK_IN }.status shouldBe
                FnrhSubmissionStatus.PENDING
        } finally {
            jdbc.resetFnrhSettings()
        }
    }

    @Test
    fun `redelivering an old undo does not cancel the check-in of a later check-in`() {
        val r = reservation()
        deliver(checkedIn(r.id, 151))
        val firstUndo = undone(r.id, 152)
        deliver(firstUndo)
        // O hóspede fez check-in de novo depois do desfazer: este CHECK_IN é legítimo e pendente.
        deliver(checkedIn(r.id, 153))

        // O outbox reentrega o desfazer antigo (listener que falhou, ou reenvio na retomada).
        deliver(firstUndo)

        synthetic(r.id).single { it.eventKey == "checked-in:v153" }.status shouldBe FnrhSubmissionStatus.PENDING
    }

    @Test
    fun `an undo that arrives after a later check-in listener does not cancel that later check-in`() {
        val r = reservation()
        // Listeners rodam em paralelo: o check-in da versão 163 pode gravar antes do desfazer da 162.
        deliver(checkedIn(r.id, 161))
        deliver(checkedIn(r.id, 163))

        deliver(undone(r.id, 162))

        synthetic(r.id).associate { it.eventKey to it.status }.also {
            it["checked-in:v161"] shouldBe FnrhSubmissionStatus.CANCELLED
            it["checked-in:v163"] shouldBe FnrhSubmissionStatus.PENDING
        }
    }

    @Test
    fun `undoing keeps the check-in with a write in flight even when another one is cancelled`() {
        val r = reservation()
        deliver(checkedIn(r.id, 171))
        deliver(checkedIn(r.id, 172))
        val inFlight = synthetic(r.id).single { it.eventKey == "checked-in:v171" }
        store.markInFlight(inFlight.id)

        deliver(undone(r.id, 173))

        synthetic(r.id).associate { it.eventKey to it.status }.also {
            it["checked-in:v171"] shouldBe FnrhSubmissionStatus.PENDING
            it["checked-in:v172"] shouldBe FnrhSubmissionStatus.CANCELLED
        }
    }

    // --- Reserva inexistente --------------------------------------------------------------------------------------

    @Test
    fun `an event of an unknown reservation records nothing for any of the 8 events and warns with the id only`(
        output: CapturedOutput,
    ) {
        val id = UuidV7.generate()

        deliver(created(id))
        deliver(changed(id, 1))
        deliver(confirmed(id, 2))
        deliver(reactivated(id, 3))
        deliver(cancelled(id, 4))
        deliver(checkedIn(id, 5))
        deliver(undone(id, 6))
        deliver(checkedOut(id, 7))

        rows(id) shouldHaveSize 0
        val lines = output.all.lines().filter { it.contains(id.toString()) }
        lines shouldHaveSize 8
        lines.forEach {
            it shouldContain "WARN"
            it shouldContain "FnrhSubmissionListeners"
        }
        output.all
            .lines()
            .filter { it.contains(id.toString()) }
            .joinToString("\n") shouldNotContain "cpf"
    }

    // --- Não bloqueio ---------------------------------------------------------------------------------------------

    @Test
    fun `the real flow leaves no fnrh listener publication pending and the quiescence returns`() {
        val booked = frontdesk.stay().checkedIn()

        InventoryListenersQuiescence.await(jdbc)

        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS NULL " +
                    "AND status IS DISTINCT FROM 'FAILED' AND listener_id LIKE '%FnrhSubmissionListeners%'",
            ).query(Int::class.java)
            .single() shouldBe 0
        rows(booked.id).map { it.operation }.toSet() shouldBe
            setOf(FnrhOperation.REGISTER_RESERVATION, FnrhOperation.CHECK_IN)
    }

    @Test
    fun `an error inside a listener does not undo the check-in already committed`() {
        val booked = frontdesk.stay().checkedIn()
        InventoryListenersQuiescence.await(jdbc)

        // Versão negativa quebra a restrição do banco: o listener falha, em outra thread, e o check-in segue de pé.
        dispatch(checkedIn(booked.id, -1))
        settle()

        reservationsApi.reservation(ReservationId(booked.id))?.status shouldBe ReservationStatus.IN_HOUSE
        rows(booked.id).count { it.operation == FnrhOperation.CHECK_IN } shouldBe 1
    }
}
