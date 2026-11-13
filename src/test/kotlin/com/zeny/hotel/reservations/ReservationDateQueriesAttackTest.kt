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
 * Ataque às consultas por data da `ReservationsApi` (M6 E11, Task 1): fronteiras D-1/D/D+1, day use, situações
 * excluídas (NO_SHOW, CANCELLED, CHECKED_OUT, PROVISIONAL), ordem por id, disjunção entre as listas e reserva de
 * bloqueio de grupo. Banco compartilhado: só os ids de cada teste entram nas asserções.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class ReservationDateQueriesAttackTest(
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

    private fun arrivals(date: LocalDate) = reservationsApi.pendingArrivals(date).map { it.value }

    private fun departures(date: LocalDate) = reservationsApi.pendingDepartures(date).map { it.value }

    private fun inHouse(date: LocalDate) = reservationsApi.inHouse(date).map { it.value }

    @Test
    fun `pending arrivals respect the D-1, D and D+1 boundaries including overdue and day use`() {
        val (type, _) = inventory.roomTypeWithRooms(4, today)
        val plan = planFor(type)
        val overdue = created(type, plan, today, today.plusDays(1))
        moveArrival(overdue, today.minusDays(1))
        val onDate = created(type, plan, today, today.plusDays(1))
        val dayUse = created(type, plan, today, today)
        val tomorrow = created(type, plan, today.plusDays(1), today.plusDays(2))

        arrivals(today).also {
            it shouldContain overdue
            it shouldContain onDate
            it shouldContain dayUse
            it shouldNotContain tomorrow
        }
        arrivals(today.minusDays(1)).also {
            it shouldContain overdue
            it shouldNotContain onDate
            it shouldNotContain dayUse
        }
        arrivals(today.plusDays(1)).also {
            it shouldContain overdue
            it shouldContain onDate
            it shouldContain tomorrow
        }
    }

    @Test
    fun `pending arrivals exclude no-show, cancelled, checked-out and provisional reservations even when overdue`() {
        val (type, rooms) = inventory.roomTypeWithRooms(6, today)
        val plan = planFor(type)
        val confirmed = created(type, plan, today, today.plusDays(1))
        val noShow = created(type, plan, today, today.plusDays(1))
        setStatus(noShow, "NO_SHOW")
        val cancelled = created(type, plan, today, today.plusDays(1))
        with(support.billing) { support.cancel(cancelled).expect(200) }
        val checkedOut = created(type, plan, today, today.plusDays(1))
        support.forceCheckedOut(checkedOut)
        val provisional = created(type, planFor(type, "DEPOSIT"), today.plusDays(10), today.plusDays(11))
        moveArrival(provisional, today.minusDays(2))
        val stay = created(type, plan, today, today.plusDays(1))
        support.forceInHouse(stay, rooms[0])
        support.status(provisional) shouldBe "PROVISIONAL"

        val pending = arrivals(today.plusDays(5))

        pending shouldContain confirmed
        listOf(noShow, cancelled, checkedOut, provisional, stay).forEach { pending shouldNotContain it }
    }

    @Test
    fun `pending departures respect the boundaries and keep overdue stays`() {
        val (type, rooms) = inventory.roomTypeWithRooms(5, today)
        val plan = planFor(type)
        val overdue = created(type, plan, today, today.plusDays(1))
        val onDate = created(type, plan, today, today.plusDays(1))
        val dayUse = created(type, plan, today, today)
        val tomorrow = created(type, plan, today, today.plusDays(2))
        listOf(overdue, onDate, dayUse, tomorrow).forEachIndexed { i, id -> support.forceInHouse(id, rooms[i]) }
        moveDeparture(overdue, today.minusDays(1), arrival = today.minusDays(3))
        moveDeparture(onDate, today)
        moveDeparture(tomorrow, today.plusDays(1))

        departures(today).also {
            it shouldContain overdue
            it shouldContain onDate
            it shouldContain dayUse
            it shouldNotContain tomorrow
        }
        departures(today.minusDays(1)).also {
            it shouldContain overdue
            it shouldNotContain onDate
            it shouldNotContain dayUse
        }
        departures(today.plusDays(1)) shouldContain tomorrow
    }

    @Test
    fun `pending departures exclude confirmed, no-show, cancelled and checked-out reservations`() {
        val (type, rooms) = inventory.roomTypeWithRooms(5, today)
        val plan = planFor(type)
        val stay = created(type, plan, today, today)
        support.forceInHouse(stay, rooms[0])
        val confirmed = created(type, plan, today, today)
        val noShow = created(type, plan, today, today)
        setStatus(noShow, "NO_SHOW")
        val cancelled = created(type, plan, today, today)
        with(support.billing) { support.cancel(cancelled).expect(200) }
        val checkedOut = created(type, plan, today, today)
        support.forceCheckedOut(checkedOut)

        val pending = departures(today)

        pending shouldContain stay
        listOf(confirmed, noShow, cancelled, checkedOut).forEach { pending shouldNotContain it }
    }

    @Test
    fun `in house respects the arrival boundary and keeps overdue departures and day use`() {
        val (type, rooms) = inventory.roomTypeWithRooms(6, today)
        val plan = planFor(type)
        val arrivedYesterday = created(type, plan, today, today.plusDays(2))
        val overdueDeparture = created(type, plan, today, today.plusDays(1))
        val arrivedToday = created(type, plan, today, today.plusDays(1))
        val dayUse = created(type, plan, today, today)
        val arrivesTomorrow = created(type, plan, today.plusDays(1), today.plusDays(2))
        listOf(arrivedYesterday, overdueDeparture, arrivedToday, dayUse, arrivesTomorrow)
            .forEachIndexed { i, id -> support.forceInHouse(id, rooms[i]) }
        moveArrival(arrivedYesterday, today.minusDays(1))
        moveArrival(overdueDeparture, today.minusDays(4))
        moveDeparture(overdueDeparture, today.minusDays(2))

        inHouse(today).also {
            it shouldContain arrivedYesterday
            it shouldContain overdueDeparture
            it shouldContain arrivedToday
            it shouldContain dayUse
            it shouldNotContain arrivesTomorrow
        }
        inHouse(today.minusDays(1)).also {
            it shouldContain arrivedYesterday
            it shouldNotContain arrivedToday
            it shouldNotContain dayUse
        }
        inHouse(today.plusDays(1)) shouldContain arrivesTomorrow
    }

    @Test
    fun `in house excludes confirmed, no-show, cancelled, checked-out and provisional reservations`() {
        val (type, rooms) = inventory.roomTypeWithRooms(4, today)
        val plan = planFor(type)
        val stay = created(type, plan, today, today.plusDays(1))
        support.forceInHouse(stay, rooms[0])
        val confirmed = created(type, plan, today, today.plusDays(1))
        val noShow = created(type, plan, today, today.plusDays(1))
        setStatus(noShow, "NO_SHOW")
        val cancelled = created(type, plan, today, today.plusDays(1))
        with(support.billing) { support.cancel(cancelled).expect(200) }
        val checkedOut = created(type, plan, today, today.plusDays(1))
        support.forceCheckedOut(checkedOut)
        val provisional = created(type, planFor(type, "DEPOSIT"), today.plusDays(10), today.plusDays(11))
        moveArrival(provisional, today)

        val listed = inHouse(today.plusDays(30))

        listed shouldContain stay
        listOf(confirmed, noShow, cancelled, checkedOut, provisional).forEach { listed shouldNotContain it }
    }

    @Test
    fun `a reservation is never in the arrivals and in the in-house lists at once`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val confirmed = created(type, plan, today, today)
        val stay = created(type, plan, today, today)
        support.forceInHouse(stay, rooms[0])
        val date = today.plusDays(3)

        arrivals(date) shouldContain confirmed
        inHouse(date) shouldNotContain confirmed
        departures(date) shouldNotContain confirmed
        arrivals(date) shouldNotContain stay
        inHouse(date) shouldContain stay
        departures(date) shouldContain stay
    }

    @Test
    fun `every query returns ids in ascending order without duplicates`() {
        val (type, rooms) = inventory.roomTypeWithRooms(6, today)
        val plan = planFor(type)
        val confirmed = (1..4).map { created(type, plan, today, today.plusDays(1)) }
        val stays = (0..3).map { created(type, plan, today, today) }
        stays.forEachIndexed { i, id -> support.forceInHouse(id, rooms[i]) }

        for (list in listOf(arrivals(today), departures(today), inHouse(today))) {
            list shouldBe list.sortedBy { it.toString() }
            list.distinct() shouldBe list
        }
        arrivals(today).filter { it in confirmed } shouldBe confirmed.sortedBy { it.toString() }
        departures(today).filter { it in stays } shouldBe stays.sortedBy { it.toString() }
        inHouse(today).filter { it in stays } shouldBe stays.sortedBy { it.toString() }
    }

    @Test
    fun `a group block pick-up reservation is a pending arrival and then in house like any other`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = planFor(type)
        val arrival = today.plusDays(5)
        val block =
            support.createdBlock(
                support.blockBody(
                    plan,
                    today.plusDays(3),
                    support.allocation(type, arrival, arrival.plusDays(2), 2, "250.00"),
                    holderGuest = support.guest(),
                ),
            )
        val blockId = UUID.fromString(block["id"].asString())
        with(support.billing) {
            support.pickUp(blockId, support.blockReservation(type, arrival, arrival.plusDays(2))).expect(201)
        }
        val pickedUp = support.blockReservations(blockId).single()

        arrivals(arrival.minusDays(1)) shouldNotContain pickedUp
        arrivals(arrival) shouldContain pickedUp
        inHouse(arrival) shouldNotContain pickedUp

        support.forceInHouse(pickedUp, rooms[0])

        arrivals(arrival) shouldNotContain pickedUp
        inHouse(arrival) shouldContain pickedUp
        inHouse(arrival.minusDays(1)) shouldNotContain pickedUp
        departures(arrival.plusDays(2)) shouldContain pickedUp
        departures(arrival.plusDays(1)) shouldNotContain pickedUp
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

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

    private fun moveArrival(
        id: UUID,
        arrival: LocalDate,
    ) {
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :d WHERE id = :id")
            .param("d", arrival)
            .param("id", id)
            .update()
    }

    private fun moveDeparture(
        id: UUID,
        departure: LocalDate,
        arrival: LocalDate? = null,
    ) {
        jdbc
            .sql("UPDATE reservations.reservation SET departure = :d, arrival = COALESCE(:a, arrival) WHERE id = :id")
            .param("d", departure)
            .param("a", arrival)
            .param("id", id)
            .update()
    }

    private fun setStatus(
        id: UUID,
        status: String,
    ) {
        jdbc
            .sql("UPDATE reservations.reservation SET status = :s, provisional_until = NULL WHERE id = :id")
            .param("s", status)
            .param("id", id)
            .update()
    }
}
