package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Extensão e redução do hospedado de ponta a ponta (M5 E05; RF-REC-05, RN-14, D2, D3): o
 * `PUT /api/v1/reservations/{id}` da E01 com a UH ocupada por um check-in real da recepção. A extensão toma o
 * inventário e estende a alocação sem soltar a UH; as recusas não mudam nada; a redução devolve o inventário e mantém
 * o preço das noites que ficam; a ocupação do hospedado é fixa.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
class FrontdeskInHouseChangeApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val billing = support.billing
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `extending an in-house stay with the room free takes the inventory and keeps the room occupied`() {
        val stay = frontdesk.stay(rooms = 2)
        val booked = stay.checkedIn()
        val newNight = today.plusDays(2)
        val before = roomsApi.status(RoomId(stay.room))!!
        before.occupancy.name shouldBe "OCCUPIED"
        val sold = inventory.row(stay.type, newNight)!!.sold

        val response = extend(booked.id, today.plusDays(3)).expect(200)["reservation"]

        response["status"].asString() shouldBe "IN_HOUSE"
        response["departure"].asString() shouldBe today.plusDays(3).toString()
        response["roomAssignment"]["endsOn"].asString() shouldBe today.plusDays(3).toString()
        response["roomAssignment"]["roomId"].asString() shouldBe stay.room.toString()
        inventory.row(stay.type, newNight)!!.sold shouldBe sold + 1
        inventory.row(stay.type, today.plusDays(1))!!.sold shouldBe 1
        roomsApi.status(RoomId(stay.room))!!.let {
            it.occupancy.name shouldBe "OCCUPIED"
            it.guestCount shouldBe before.guestCount
        }
    }

    @Test
    fun `extending without availability is refused with nothing changed`() {
        val stay = frontdesk.stay(rooms = 1)
        val booked = stay.checkedIn()
        val newNight = today.plusDays(2)
        // Outra reserva do mesmo tipo esgota a única UH nas noites novas.
        other(stay, newNight, today.plusDays(4))
        val soldBefore = inventory.row(stay.type, newNight)!!.sold
        val before = state(booked.id, stay)

        extend(booked.id, today.plusDays(3)).problem(409, "ROOM_TYPE_UNAVAILABLE")

        state(booked.id, stay) shouldBe before
        inventory.row(stay.type, newNight)!!.sold shouldBe soldBefore
    }

    @Test
    fun `extending into nights where the room is assigned to another reservation is refused`() {
        val stay = frontdesk.stay(rooms = 2)
        val booked = stay.checkedIn()
        // Outra reserva alocada na mesma UH a partir da noite seguinte à saída.
        val next = other(stay, today.plusDays(3), today.plusDays(5))
        with(iam) {
            call(POST, "/api/v1/reservations/$next/assign-room", asAdmin(), mapOf("roomId" to stay.room)).ok()
        }
        val soldBefore = inventory.row(stay.type, today.plusDays(2))!!.sold
        val before = state(booked.id, stay)

        extend(booked.id, today.plusDays(4)).problem(409, "ROOM_ALREADY_ASSIGNED")

        state(booked.id, stay) shouldBe before
        inventory.row(stay.type, today.plusDays(2))!!.sold shouldBe soldBefore
    }

    @Test
    fun `shortening an in-house stay returns the inventory and keeps the past nights`() {
        val stay = frontdesk.stay(rooms = 2)
        val booked = stay.checkedIn(departure = today.plusDays(3))
        val frozen = nightlyAmounts(booked.id)
        frozen.map { it.first } shouldContainExactly listOf(today, today.plusDays(1), today.plusDays(2))
        // A tarifa sobe depois do check-in: as noites que ficam guardam o preço congelado (RN-14).
        support.setPrices(stay.plan, listOf(stay.type), today, today.plusDays(PRICED_DAYS), twoAdults = "550.00")
        val removed = today.plusDays(2)
        val soldBefore = inventory.row(stay.type, removed)!!.sold
        val keptSold = inventory.row(stay.type, today.plusDays(1))!!.sold

        val response = extend(booked.id, today.plusDays(2)).expect(200)["reservation"]

        response["departure"].asString() shouldBe today.plusDays(2).toString()
        response["status"].asString() shouldBe "IN_HOUSE"
        inventory.row(stay.type, removed)!!.sold shouldBe soldBefore - 1
        inventory.row(stay.type, today.plusDays(1))!!.sold shouldBe keptSold
        nightlyAmounts(booked.id) shouldContainExactly frozen.take(2)
        roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "OCCUPIED"
    }

    @Test
    fun `changing the occupancy of an in-house stay is refused`() {
        val stay = frontdesk.stay(rooms = 1)
        val booked = stay.checkedIn()
        val before = state(booked.id, stay)
        val current = support.reservation(booked.id)["reservation"]

        val problem =
            support
                .change(booked.id, support.changeBody(current, adults = current["adults"].asInt() + 1))
                .let { with(billing) { it.problem(409, "IN_HOUSE_STAY_FIXED") } }

        problem["fields"].values().map { it.asString() } shouldContainExactly listOf("adults")
        state(booked.id, stay) shouldBe before
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun extend(
        id: UUID,
        departure: LocalDate,
    ) = frontdesk.Response(
        support.reservation(id)["reservation"].let { current ->
            support.change(id, support.changeBody(current, departure = departure))
        },
    )

    /** Uma reserva confirmada do tipo da estadia, sem UH. */
    private fun other(
        stay: FrontdeskTestSupport.Stay,
        arrival: LocalDate,
        departure: LocalDate,
    ): UUID =
        UUID.fromString(
            support
                .created(
                    support.booking(frontdesk.guest(), support.reservation(stay.type, stay.plan, arrival, departure)),
                )["reservations"][0]["id"]
                .asString(),
        )

    private fun nightlyAmounts(reservation: UUID): List<Pair<LocalDate, String>> =
        support.reservation(reservation)["reservation"]["nightlyRates"].values().map {
            LocalDate.parse(it["date"].asString()) to it["amount"].asString()
        }

    /** O que uma recusa não pode mudar: versão, saída, alocação, inventário das noites e a UH. */
    private fun state(
        reservation: UUID,
        stay: FrontdeskTestSupport.Stay,
    ): List<Any?> {
        val detail = support.reservation(reservation)["reservation"]
        return listOf(
            support.etag(reservation),
            detail["departure"].asString(),
            detail["roomAssignment"]["endsOn"].asString(),
            (0L..4L).map { inventory.row(stay.type, today.plusDays(it))?.sold },
            roomsApi.status(RoomId(stay.room))!!.let { it.occupancy.name to it.guestCount },
        )
    }

    private companion object {
        const val PRICED_DAYS = 10L
    }
}
