package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionListeners
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
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
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O listener do `ReservationMarkedNoShow` (M6 E14, Task 5; D-E14-2): a reserva marcada como no-show, como o fechamento
 * a marca, grava uma submissão `NO_SHOW` com a chave `no-show:v<versão>` e a versão causal do evento; a reentrega não
 * grava outra, nem com a submissão já enviada. Cada teste cria a própria reserva e olha só a fila dela.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhNoShowListenerTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val listeners: FnrhSubmissionListeners,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val tx: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    /** Uma linha `NO_SHOW` da fila, só com o que os testes conferem. */
    private data class Row(
        val id: UUID,
        val eventKey: String,
        val causalVersion: Long,
        val performedBy: UUID?,
        val code: String,
        val status: FnrhSubmissionStatus,
    )

    @Test
    fun `a no-show records one NO_SHOW submission`() {
        val id = arrivingToday()

        val marked = markNoShow(id)

        awaitNoShow(id).also {
            it.eventKey shouldBe "no-show:v${marked.version}"
            it.causalVersion shouldBe marked.version
            it.performedBy.shouldBeNull()
            it.code shouldBe marked.code.toString()
            it.status shouldBe FnrhSubmissionStatus.PENDING
        }
        // Uma só, mesmo dando tempo a uma gravação a mais aparecer.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until { noShows(id).size == 1 }
    }

    @Test
    fun `redelivering the event does not duplicate`() {
        val id = arrivingToday()
        val marked = markNoShow(id)
        val first = awaitNoShow(id)
        val event = eventOf(marked)

        // Reentregue com a submissão pendente.
        listeners.on(event)
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until { noShows(id).size == 1 }
        noShows(id).single().id shouldBe first.id

        // Reentregue com a submissão já enviada.
        tx.executeWithoutResult {
            val loaded = checkNotNull(store.find(first.id))
            loaded.markSent(Instant.now())
            store.save(loaded)
        }
        listeners.on(event)
        listeners.on(event)
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until { noShows(id).size == 1 }
        noShows(id).single().also {
            it.id shouldBe first.id
            it.status shouldBe FnrhSubmissionStatus.SENT
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma reserva confirmada chegando hoje (o no-show só vale até a data operacional), com o registro na fila. */
    private fun arrivingToday(): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(support.guest(), support.reservation(type, plan.id, today, today.plusDays(2))),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until {
            jdbc
                .sql(
                    "SELECT count(*) FROM fnrh.submission WHERE reservation_id = :id " +
                        "AND operation = 'REGISTER_RESERVATION'",
                ).param("id", id)
                .query(Int::class.java)
                .single() == 1
        }
        return id
    }

    /** Marca a reserva como no-show como o fechamento marca (o evento sai pelo outbox no commit). */
    private fun markNoShow(reservation: UUID): ReservationSummary =
        checkNotNull(tx.execute { reservationsApi.markNoShow(ReservationId(reservation)) }).also {
            it.status shouldBe ReservationStatus.NO_SHOW
        }

    /** O evento como `reservations` o publica, para a reentrega. */
    private fun eventOf(marked: ReservationSummary) =
        ReservationMarkedNoShow(
            reservationId = marked.id.value,
            bookingId = marked.bookingId.value,
            roomTypeId = marked.roomTypeId.value,
            roomId = null,
            arrival = marked.arrival,
            departure = marked.departure,
            dayUse = false,
            businessDate = today,
            penaltyCharged = false,
            performedBy = null,
            version = marked.version,
            markedAt = Instant.now(),
        )

    private fun noShows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, event_key, causal_version, performed_by, reservation_code, status FROM fnrh.submission " +
                    "WHERE reservation_id = :id AND operation = :operation ORDER BY sequence",
            ).param("id", reservation)
            .param("operation", FnrhOperation.NO_SHOW.name)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("event_key"),
                    rs.getLong("causal_version"),
                    rs.getObject("performed_by", UUID::class.java),
                    rs.getString("reservation_code"),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                )
            }.list()

    /** Espera o listener gravar o `NO_SHOW` da reserva e devolve a única linha. */
    private fun awaitNoShow(reservation: UUID): Row {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until {
            noShows(reservation).isNotEmpty()
        }
        return noShows(reservation).single()
    }

    private companion object {
        const val PRICED_DAYS = 30L
    }
}
