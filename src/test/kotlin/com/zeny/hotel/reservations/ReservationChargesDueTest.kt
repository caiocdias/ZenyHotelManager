package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * `ReservationsApi.chargesDue` (M6 E11, Task 5): as cobranças devidas das noites congeladas de uma hospedada, da
 * data operacional do check-in até a data pedida. Banco compartilhado, sem avançar a data operacional.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class ReservationChargesDueTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `an in-house stay is charged from the check-in business date to the requested date`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val id = created(type, plan, today, today.plusDays(4))
        support.forceInHouse(id, rooms[0])
        // Chegada em D-2 e check-in em D-1, com D = hoje + 2: o check-in é forçado depois da chegada.
        val checkInDate = today.plusDays(1)
        jdbc
            .sql("UPDATE reservations.reservation SET checked_in_on = :c WHERE id = :id")
            .param("c", checkInDate)
            .param("id", id)
            .update()
        val summary = reservationsApi.reservation(ReservationId(id))!!

        val due = reservationsApi.chargesDue(ReservationId(id), today.plusDays(2))!!

        due.reservationId shouldBe ReservationId(id)
        due.folioId shouldBe summary.folioId
        due.charges.map { it.date }.distinct() shouldBe listOf(today.plusDays(1), today.plusDays(2))
        due.charges.map { it.date } shouldBe due.charges.map { it.date }.sorted()
    }

    @Test
    fun `a confirmed or checked out reservation has no charges due`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val confirmed = created(type, plan, today, today.plusDays(1))
        val closed = created(type, plan, today, today.plusDays(1))
        support.forceCheckedOut(closed)

        reservationsApi.chargesDue(ReservationId(confirmed), today).shouldBeNull()
        reservationsApi.chargesDue(ReservationId(closed), today).shouldBeNull()
        reservationsApi.chargesDue(ReservationId(UUID.randomUUID()), today).shouldBeNull()
    }

    @Test
    fun `a date before the check-in has nothing due`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val id = created(type, plan, today, today.plusDays(2))
        support.forceInHouse(id, rooms[0])

        reservationsApi.chargesDue(ReservationId(id), today.minusDays(1))!!.charges.shouldBeEmpty()
    }

    private fun planFor(type: UUID): UUID {
        val plan = support.plan(listOf(type), "NONE")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(30))
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): UUID =
        UUID.fromString(
            support
                .created(
                    support.booking(support.guest(), support.reservation(type, plan, arrival, departure, adults = 1)),
                )["reservations"][0]["id"]
                .asString(),
        )
}
