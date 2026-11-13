package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
 * As consultas por data da `ReservationsApi` que o fechamento do dia usa (M6 E11, H4): as chegadas pendentes, as
 * saídas pendentes e as hospedadas que já chegaram. Cada teste cria os próprios tipos de UH e reservas (banco
 * compartilhado, sem avançar a data operacional) e olha só os próprios ids.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class ReservationDateQueriesTest(
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
    fun `pending arrivals are confirmed reservations arriving on or before the date`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = planFor(type)
        val due = created(type, plan, today, today.plusDays(1))
        val preReservation = created(type, planFor(type, "DEPOSIT"), today.plusDays(10), today.plusDays(11))
        val cancelled = created(type, plan, today, today.plusDays(1))
        with(support.billing) { support.cancel(cancelled).expect(200) }
        val inHouse = created(type, plan, today, today.plusDays(1))
        support.forceInHouse(inHouse, rooms[0])
        val tomorrow = created(type, plan, today.plusDays(1), today.plusDays(2))
        support.status(preReservation) shouldBe "PROVISIONAL"
        // A pré-reserva com chegada vencida: leva a chegada para a data (a API não cria uma na data de hoje).
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :d WHERE id = :id")
            .param("d", today)
            .param("id", preReservation)
            .update()

        val pending = reservationsApi.pendingArrivals(today).map { it.value }

        pending shouldContain due
        pending shouldNotContain preReservation
        pending shouldNotContain cancelled
        pending shouldNotContain inHouse
        pending shouldNotContain tomorrow
        reservationsApi.pendingArrivals(today.plusDays(1)).map { it.value } shouldContain tomorrow
    }

    @Test
    fun `pending departures are in-house stays leaving on or before the date`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = planFor(type)
        val leavingToday = created(type, plan, today, today)
        support.forceInHouse(leavingToday, rooms[0])
        val leavingTomorrow = created(type, plan, today, today.plusDays(1))
        support.forceInHouse(leavingTomorrow, rooms[1])
        val checkedOut = created(type, plan, today, today)
        support.forceCheckedOut(checkedOut)

        val pending = reservationsApi.pendingDepartures(today).map { it.value }

        pending shouldContain leavingToday
        pending shouldNotContain leavingTomorrow
        pending shouldNotContain checkedOut
        reservationsApi.pendingDepartures(today.plusDays(1)).map { it.value } shouldContain leavingTomorrow
    }

    @Test
    fun `in house lists every in-house stay that has arrived`() {
        val (type, rooms) = inventory.roomTypeWithRooms(5, today)
        val plan = planFor(type)
        val stays = listOf(1L, 2L).map { created(type, plan, today, today.plusDays(it)) }
        val dayUse = created(type, plan, today, today)
        val leavingToday = created(type, plan, today, today)
        val listed = stays + dayUse + leavingToday
        listed.forEachIndexed { i, id -> support.forceInHouse(id, rooms[i]) }
        val checkedOut = created(type, plan, today, today)
        support.forceCheckedOut(checkedOut)
        val confirmed = created(type, plan, today, today.plusDays(1))

        val inHouse = reservationsApi.inHouse(today).map { it.value }

        listed.forEach { inHouse shouldContain it }
        inHouse shouldNotContain checkedOut
        inHouse shouldNotContain confirmed
    }

    private fun planFor(
        type: UUID,
        guarantee: String = "NONE",
    ): UUID {
        val plan = support.plan(listOf(type), guarantee)
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
