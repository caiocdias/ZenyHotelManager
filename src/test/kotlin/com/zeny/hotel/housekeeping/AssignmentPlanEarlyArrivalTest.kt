package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.orderOf
import com.zeny.hotel.housekeeping.application.ArrivalsByRoom
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A ordem da sugestão com a hora prevista de chegada (M6 E22, Task 2; D6 a D8): early check-in (hora estritamente antes
 * do `checkInTime` da política), depois as chegadas por hora, as sem hora e as demais tarefas; a hora igual ao
 * `checkInTime` não é early; a UH com duas chegadas fica com a menor hora, e a sem hora não apaga a hora da outra. A
 * resposta não traz dado de hóspede nem de reserva (RN-13). Banco compartilhado: só as próprias UHs e camareiras.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class AssignmentPlanEarlyArrivalTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)
    private val stays =
        HousekeepingStays(ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc), reservationsApi, hotel)
    private val checkIn: LocalTime get() = hotel.policy().checkInTime

    @Test
    fun `early check-in comes first, then arrivals by time, then those without time, then the other tasks`() {
        val date = hotel.currentBusinessDate().plusDays(2)
        val attendant = plans.attendant(date)
        val roomType = housekeeping.rooms.createRoomType()
        val plan = stays.planFor(roomType)
        val early = arrival(roomType, plan, date, checkIn.minusHours(1))
        val atCheckIn = arrival(roomType, plan, date, checkIn)
        val late = arrival(roomType, plan, date, checkIn.plusHours(1))
        val noTime = arrival(roomType, plan, date, null)
        val stayover = plans.insertTask(date, room = housekeeping.createRoom(), priority = 5)

        val (suggested, _) = plans.suggested(date, listOf(attendant))

        // Prioridades iguais nas tarefas de chegada (3, TOUCH_UP): só a hora separa uma da outra.
        orderOf(suggested, attendant) shouldContainExactly
            listOf(early.task, atCheckIn.task, late.task, noTime.task, stayover)
        listOf(
            early,
            atCheckIn,
            late,
            noTime,
        ).forEach { suggested.toString() shouldNotContain "${it.reservation.value}" }
    }

    @Test
    fun `a time equal to the check-in time is not early, it is ordered by time among the arrivals`() {
        val date = hotel.currentBusinessDate().plusDays(3)
        val attendant = plans.attendant(date)
        val roomType = housekeeping.rooms.createRoomType()
        val plan = stays.planFor(roomType)
        val earlier = arrival(roomType, plan, date, checkIn.plusMinutes(1))
        val equal = arrival(roomType, plan, date, checkIn)
        val urgentOther = plans.insertTask(date, room = housekeeping.createRoom(), priority = 5)

        val (suggested, _) = plans.suggested(date, listOf(attendant))

        orderOf(suggested, attendant) shouldContainExactly listOf(equal.task, earlier.task, urgentOther)
    }

    /**
     * A UH com duas chegadas na data: a alocação ativa exclui duas reservas na mesma UH (RN-04), então o cenário é
     * montado com as portas simuladas, direto na `ArrivalsByRoom`.
     */
    @Test
    fun `two arrivals in the same room give the room the earliest time, and one without time keeps the other's`() {
        val date = hotel.currentBusinessDate().plusDays(4)
        val ids = List(4) { ReservationId(UUID.randomUUID()) }
        val (timed, other, untimed) = ids
        val lone = ids[3]
        val room = RoomId(UUID.randomUUID())
        val loneRoom = RoomId(UUID.randomUUID())
        val reservations = mockk<ReservationsApi>()
        val simulatedHotel = mockk<HotelApi>()
        every { simulatedHotel.currentBusinessDate() } returns hotel.currentBusinessDate()
        every { reservations.pendingArrivals(date) } returns listOf(timed, other, untimed, lone)
        every { reservations.pendingArrivals(date.minusDays(1)) } returns emptyList()
        every { reservations.assignedRooms(any()) } returns
            mapOf(timed to room, other to room, untimed to room, lone to loneRoom)
        every { reservations.expectedArrivalTimes(any()) } returns
            mapOf(timed to LocalTime.of(10, 30), other to LocalTime.of(16, 0))

        val times = ArrivalsByRoom(reservations, simulatedHotel).arrivalTimesByRoom(date)

        times shouldBe mapOf(room to LocalTime.of(10, 30), loneRoom to null)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private data class Arrival(
        val reservation: ReservationId,
        val task: UUID,
    )

    /** Uma reserva que chega em [date] com a [time] numa UH nova, e a tarefa avulsa da UH (prioridade 3). */
    private fun arrival(
        type: UUID,
        plan: UUID,
        date: LocalDate,
        time: LocalTime?,
    ): Arrival {
        val room = housekeeping.rooms.createRoom(type)
        val reservation = reservation(type, plan, date, time, room)
        return Arrival(reservation, plans.insertTask(date, room = room, priority = 3))
    }

    /** Uma reserva confirmada que chega em [date], com a hora gravada e alocada à [room]. */
    private fun reservation(
        type: UUID,
        plan: UUID,
        date: LocalDate,
        time: LocalTime?,
        room: UUID,
    ): ReservationId {
        val (id, _) = stays.reservation(type, plan, arrival = date)
        reservationsApi.assignRoom(id, RoomId(room))
        jdbc
            .sql("UPDATE reservations.reservation SET expected_arrival_time = :time WHERE id = :id")
            .param("time", time)
            .param("id", id.value)
            .update() shouldBe 1
        return id
    }
}
