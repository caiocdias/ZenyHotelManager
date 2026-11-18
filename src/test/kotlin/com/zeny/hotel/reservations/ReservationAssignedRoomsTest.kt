package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.maps.shouldBeEmpty
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
 * A UH alocada em lote pela `ReservationsApi` (M6 E02, D7): só a alocação vigente (`released_at IS NULL`) de cada
 * reserva pedida; a reserva sem alocação, com a alocação solta (o check-out) ou inexistente fica de fora. Cada teste
 * cria os próprios tipos de UH e reservas (banco compartilhado, sem avançar a data operacional).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class ReservationAssignedRoomsTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `assigned rooms gives the current room of each reservation, leaving out the unassigned and released ones`() {
        val (type, rooms) = inventory.roomTypeWithRooms(4, today)
        val plan = planFor(type)
        val assigned = created(type, plan)
        reservationsApi.assignRoom(assigned, RoomId(rooms[0]))
        val reassigned = created(type, plan)
        reservationsApi.assignRoom(reassigned, RoomId(rooms[1]))
        reservationsApi.assignRoom(reassigned, RoomId(rooms[2]))
        val unassigned = created(type, plan)
        val (checkedOut, holder) = createdWithHolder(type, plan)
        reservationsApi.assignRoom(checkedOut, RoomId(rooms[3]))
        reservationsApi.checkIn(checkedOut, CheckIn(listOf(primary(holder))))
        reservationsApi.checkOut(checkedOut)
        val unknown = ReservationId(UuidV7.generate())

        val current = reservationsApi.assignedRooms(listOf(assigned, reassigned, unassigned, checkedOut, unknown))

        current shouldBe mapOf(assigned to RoomId(rooms[0]), reassigned to RoomId(rooms[2]))
    }

    @Test
    fun `assigned rooms of no reservation is empty`() {
        reservationsApi.assignedRooms(emptyList()).shouldBeEmpty()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun planFor(type: UUID): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
    ): ReservationId = createdWithHolder(type, plan).first

    private fun createdWithHolder(
        type: UUID,
        plan: UUID,
    ): Pair<ReservationId, UUID> {
        val holder = support.guest()
        val reservation =
            support.created(
                support.booking(holder, support.reservation(type, plan, today, today.plusDays(1), adults = 1)),
            )["reservations"][0]
        return ReservationId(UUID.fromString(reservation["id"].asString())) to holder
    }

    private fun primary(guest: UUID) =
        CheckInGuest(GuestId(guest), primary = true, travelReason = "LAZER", transportMode = "AUTOMOVEL")

    private companion object {
        const val PRICED_DAYS = 10L
    }
}
