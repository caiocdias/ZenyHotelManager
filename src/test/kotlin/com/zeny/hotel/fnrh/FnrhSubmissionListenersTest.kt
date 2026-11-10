package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteGuest
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionListeners
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationConfirmed
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationLifecycleEvents
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.StayTransitionsEvents
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Os listeners que gravam a submissão à FNRH (M5 E08, Task 3; D-E08-1, 2, 3, 8): uma submissão por evento de reserva
 * e de hospedagem, criada pelo fluxo real; a reentrega não cria outra (nem com a submissão já enviada); o desfazer do
 * check-in cancela só o `CHECK_IN` sem escrita em curso e sem hóspede no Serpro.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSubmissionListenersTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val listeners: FnrhSubmissionListeners,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val tx: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    /** Uma linha da fila da reserva, só com o que os testes conferem. */
    private data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val eventKey: String,
        val causalVersion: Long,
        val performedBy: UUID?,
        val code: String,
        val status: FnrhSubmissionStatus,
    )

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, operation, event_key, causal_version, performed_by, reservation_code, status " +
                    "FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence",
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
                )
            }.list()

    /** Espera o listener gravar a submissão da [operation] e devolve as da reserva nessa operação. */
    private fun awaitRows(
        reservation: UUID,
        operation: FnrhOperation,
        count: Int = 1,
    ): List<Row> {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until {
            rows(reservation).count { it.operation == operation } >= count
        }
        return rows(reservation).filter { it.operation == operation }
    }

    private fun codeOf(reservation: UUID): String =
        checkNotNull(reservationsApi.reservation(ReservationId(reservation))).code.toString()

    private fun <T> eventsOf(
        events: List<T>,
        reservation: UUID,
        id: (T) -> UUID,
    ): List<T> = events.filter { id(it) == reservation }

    // --- Reserva --------------------------------------------------------------------------------------------------

    @Test
    fun `the reservation lifecycle events each record one submission with the operation, key and causal version`() {
        val stay = frontdesk.stay()
        val card = support.plan(listOf(stay.type), guarantee = "CARD")
        support.setPrices(card.id, listOf(stay.type), today, today.plusDays(PRICED_DAYS))
        val arrival = today.plusDays(5)
        val created =
            support.created(
                support.booking(support.guest(), support.reservation(stay.type, card.id, arrival, arrival.plusDays(1))),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        val code = codeOf(id)

        // Criada (pré-reserva): REGISTER_RESERVATION na versão 0, sem a versão na chave.
        awaitRows(id, FnrhOperation.REGISTER_RESERVATION).single().also {
            it.eventKey shouldBe "created"
            it.causalVersion shouldBe 0
            it.performedBy.shouldBeNull()
            it.code shouldBe code
            it.status shouldBe FnrhSubmissionStatus.PENDING
        }

        // Confirmada.
        support.confirm(id, "CARD").also { with(support.billing) { it.expect(200) } }
        val confirmed = awaitRows(id, FnrhOperation.UPDATE_RESERVATION).single()
        val confirmedEvent = eventsOf(ReservationLifecycleEvents.confirmed, id) { it.reservationId }.single()
        confirmed.eventKey shouldBe "confirmed:v${confirmedEvent.version}"
        confirmed.causalVersion shouldBe confirmedEvent.version

        // Alterada.
        support
            .change(id, support.changeBody(created, departure = arrival.plusDays(2)))
            .also { with(support.billing) { it.expect(200) } }
        val changedEvent = eventsOf(ReservationLifecycleEvents.changed, id) { it.reservationId }.single()
        val updates = awaitRows(id, FnrhOperation.UPDATE_RESERVATION, count = 2)
        updates.map { it.eventKey to it.causalVersion } shouldContainExactlyInAnyOrder
            listOf(
                "confirmed:v${confirmedEvent.version}" to confirmedEvent.version,
                "changed:v${changedEvent.version}" to changedEvent.version,
            )

        // Cancelada e reativada.
        support.cancel(id).also { with(support.billing) { it.expect(200) } }
        val cancelledEvent = eventsOf(ReservationLifecycleEvents.cancelled, id) { it.reservationId }.single()
        awaitRows(id, FnrhOperation.CANCEL_RESERVATION).single().also {
            it.eventKey shouldBe "cancelled:v${cancelledEvent.version}"
            it.causalVersion shouldBe cancelledEvent.version
            it.code shouldBe code
        }
        support.reactivate(id).also { with(support.billing) { it.expect(200) } }
        val reactivatedEvent = eventsOf(ReservationLifecycleEvents.reactivated, id) { it.reservationId }.single()
        val all = awaitRows(id, FnrhOperation.UPDATE_RESERVATION, count = 3)
        all.map { it.eventKey } shouldContainExactlyInAnyOrder
            listOf(
                "confirmed:v${confirmedEvent.version}",
                "changed:v${changedEvent.version}",
                "reactivated:v${reactivatedEvent.version}",
            )
        awaitRows(id, FnrhOperation.REGISTER_RESERVATION) shouldHaveSize 1
    }

    // --- Hospedagem -----------------------------------------------------------------------------------------------

    @Test
    fun `check-in, undo and check-out each record one submission with the performer and the event version`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(departure = today)
        val id = booked.id
        val code = codeOf(id)

        val checkedInEvent = eventsOf(StayTransitionsEvents.checkedIn, id) { it.reservationId }.single()
        awaitRows(id, FnrhOperation.CHECK_IN).single().also {
            it.eventKey shouldBe "checked-in:v${checkedInEvent.version}"
            it.causalVersion shouldBe checkedInEvent.version
            it.performedBy shouldBe checkedInEvent.performedBy
            it.code shouldBe code
        }
        checkedInEvent.performedBy.shouldNotBeNull()

        undoCheckIn(id)
        val undoneEvent = eventsOf(StayTransitionsEvents.undone, id) { it.reservationId }.single()
        awaitRows(id, FnrhOperation.UNDO_CHECK_IN).single().also {
            it.eventKey shouldBe "check-in-undone:v${undoneEvent.version}"
            it.causalVersion shouldBe undoneEvent.version
            it.performedBy shouldBe undoneEvent.performedBy
        }

        frontdesk.checkIn(id, frontdesk.guest()).expect(200)
        awaitRows(id, FnrhOperation.CHECK_IN, count = 2)
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(
                POST,
                "/api/v1/reservations/$id/check-out",
                iam.asAdmin(),
                idempotencyKey = UuidV7.generate().toString(),
            ).expect(200)
        }
        val checkedOutEvent = eventsOf(StayTransitionsEvents.checkedOut, id) { it.reservationId }.single()
        awaitRows(id, FnrhOperation.CHECK_OUT).single().also {
            it.eventKey shouldBe "checked-out:v${checkedOutEvent.version}"
            it.causalVersion shouldBe checkedOutEvent.version
            it.performedBy shouldBe checkedOutEvent.performedBy
            it.code shouldBe code
        }
    }

    @Test
    fun `a walk-in records the registration and the check-in`() {
        val stay = frontdesk.stay()
        val guest = frontdesk.guest()
        stay.ready()
        val body =
            mapOf(
                "roomId" to stay.room,
                "ratePlanId" to stay.plan,
                "departure" to today.plusDays(2),
                "adults" to 1,
                "childAges" to emptyList<Int>(),
                "segment" to "LEISURE",
                "guests" to
                    listOf(
                        mapOf(
                            "guestId" to guest,
                            "primary" to true,
                            "travelReason" to "LAZER",
                            "transportMode" to "AUTOMOVEL",
                        ),
                    ),
            )
        val response =
            with(support.billing) {
                call(POST, "/api/v1/walk-ins", iam.asAdmin(), body, idempotencyKey = UuidV7.generate().toString())
                    .expect(201)
            }
        val id = UUID.fromString(response["reservation"]["id"].asString())

        awaitRows(id, FnrhOperation.REGISTER_RESERVATION).single().eventKey shouldBe "created"
        awaitRows(id, FnrhOperation.CHECK_IN).single().performedBy shouldBe
            eventsOf(StayTransitionsEvents.checkedIn, id) { it.reservationId }.single().performedBy
    }

    // --- Reentrega ------------------------------------------------------------------------------------------------

    @Test
    fun `redelivering an event records nothing new, even when the submission was already sent`() {
        val stay = frontdesk.stay()
        val id = stay.checkedIn().id
        val event = eventsOf(StayTransitionsEvents.checkedIn, id) { it.reservationId }.single()
        val first = awaitRows(id, FnrhOperation.CHECK_IN).single()
        awaitRows(id, FnrhOperation.REGISTER_RESERVATION)

        tx.executeWithoutResult {
            val loaded = checkNotNull(store.find(first.id))
            loaded.markSent(Instant.now())
            store.save(loaded)
        }
        listeners.on(event)
        listeners.on(event)

        rows(id).filter { it.operation == FnrhOperation.CHECK_IN } shouldHaveSize 1
        rows(id).single { it.operation == FnrhOperation.CHECK_IN }.status shouldBe FnrhSubmissionStatus.SENT
    }

    @Test
    fun `an event of a reservation the module does not find records nothing`() {
        val unknown = UuidV7.generate()

        listeners.on(
            ReservationConfirmed(
                reservationId = unknown,
                bookingId = UuidV7.generate(),
                roomTypeId = UuidV7.generate(),
                arrival = today,
                departure = today.plusDays(1),
                dayUse = false,
                guaranteeMethod = GuaranteeMethod.CARD,
                version = 1,
                confirmedAt = Instant.now(),
            ),
        )

        rows(unknown) shouldHaveSize 0
    }

    // --- Desfazer o check-in --------------------------------------------------------------------------------------

    @Test
    fun `undoing the check-in cancels the pending check-in that never reached Serpro`() {
        val id = frontdesk.stay().checkedIn().id
        val checkIn = awaitRows(id, FnrhOperation.CHECK_IN).single()

        undoCheckIn(id)
        awaitRows(id, FnrhOperation.UNDO_CHECK_IN)

        rows(id).single { it.id == checkIn.id }.status shouldBe FnrhSubmissionStatus.CANCELLED
        rows(id).single { it.operation == FnrhOperation.UNDO_CHECK_IN }.status shouldBe FnrhSubmissionStatus.PENDING
    }

    @Test
    fun `undoing the check-in keeps the check-in whose write is in flight`() {
        val id = frontdesk.stay().checkedIn().id
        val checkIn = awaitRows(id, FnrhOperation.CHECK_IN).single()
        store.markInFlight(checkIn.id)

        undoCheckIn(id)
        awaitRows(id, FnrhOperation.UNDO_CHECK_IN)

        rows(id).single { it.id == checkIn.id }.status shouldBe FnrhSubmissionStatus.PENDING
    }

    @Test
    fun `undoing the check-in keeps the check-in of a guest already in Serpro in the current environment`() {
        val id = frontdesk.stay().checkedIn().id
        val checkIn = awaitRows(id, FnrhOperation.CHECK_IN).single()
        iam.enableFnrh()
        try {
            remoteIds.saveGuest(
                FnrhRemoteGuest(id, UuidV7.generate(), FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("p-1"), null, false),
                checkIn.id,
            )

            undoCheckIn(id)
            awaitRows(id, FnrhOperation.UNDO_CHECK_IN)

            rows(id).single { it.id == checkIn.id }.status shouldBe FnrhSubmissionStatus.PENDING
        } finally {
            jdbc.resetFnrhSettings()
        }
    }

    private fun undoCheckIn(id: UUID) {
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/undo-check-in", iam.asAdmin(), idempotencyKey = null).expect(200)
        }
    }

    private companion object {
        const val PRICED_DAYS = 30L
    }
}
