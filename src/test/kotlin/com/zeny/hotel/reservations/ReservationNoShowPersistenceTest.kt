package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A situação no-show no banco (M6 E11, Task 2): as `CHECK` do tipo do histórico e do motivo do fim da alocação aceitam
 * `NO_SHOW` e `RESERVATION_NO_SHOW` e continuam recusando valor desconhecido.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class ReservationNoShowPersistenceTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `the history accepts the no-show kind and still refuses an unknown one`() {
        val reservation = reservation()

        insertHistory(reservation, "NO_SHOW")

        support.historyKinds(reservation) shouldBe listOf("NO_SHOW")
        shouldThrow<DataIntegrityViolationException> { insertHistory(reservation, "NO_SHOW_X") }
    }

    @Test
    fun `a released assignment accepts the no-show reason and still refuses an unknown one`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val reservation = reservation(type)

        insertReleasedAssignment(reservation, rooms[0], "RESERVATION_NO_SHOW")

        jdbc
            .sql("SELECT release_reason FROM reservations.room_assignment WHERE reservation_id = :id")
            .param("id", reservation)
            .query(String::class.java)
            .single() shouldBe "RESERVATION_NO_SHOW"
        shouldThrow<DataIntegrityViolationException> {
            insertReleasedAssignment(reservation, rooms[1], "RESERVATION_NO_SHOW_X")
        }
    }

    private fun reservation(type: UUID = inventory.roomTypeWithRooms(1, today).first): UUID {
        val plan = support.plan(listOf(type), "NONE")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(30))
        val created =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(type, plan.id, today, today.plusDays(1), adults = 1),
                ),
            )
        return UUID.fromString(created["reservations"][0]["id"].asString())
    }

    private fun insertHistory(
        reservation: UUID,
        kind: String,
    ) = jdbc
        .sql(
            """
            INSERT INTO reservations.reservation_history (id, reservation_id, kind, business_date, occurred_at,
                before_state, after_state, version)
            VALUES (:id, :reservation, :kind, :date, now(), '{}'::jsonb, '{}'::jsonb, 1)
            """.trimIndent(),
        ).param("id", UuidV7.generate())
        .param("reservation", reservation)
        .param("kind", kind)
        .param("date", today)
        .update()

    private fun insertReleasedAssignment(
        reservation: UUID,
        room: UUID,
        reason: String,
    ) = jdbc
        .sql(
            """
            INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                assigned_at, released_at, release_reason)
            VALUES (:id, :reservation, :room, :from, :to, 'INITIAL', now(), now(), :reason)
            """.trimIndent(),
        ).param("id", UuidV7.generate())
        .param("reservation", reservation)
        .param("room", room)
        .param("from", today)
        .param("to", today.plusDays(1))
        .param("reason", reason)
        .update()
}
