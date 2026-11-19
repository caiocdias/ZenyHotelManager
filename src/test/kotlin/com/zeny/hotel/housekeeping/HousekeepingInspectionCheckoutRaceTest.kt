package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A aprovação da inspeção de uma tarefa disputando a UH com o check-out dela (M6 E05, risco de concorrência): sem
 * `500` nem exceção, e a UH termina num estado coerente com a ordem em que as duas terminaram.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList")
class HousekeepingInspectionCheckoutRaceTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `approving the inspection while the guest checks out of the room is never a 500 nor an exception`(): Unit =
        with(iam) {
            val today = hotel.currentBusinessDate()
            val type = support.cleaningType()
            val (roomType, rooms) = reservations.inventory.roomTypeWithRooms(ROUNDS, today)
            val plan = stays.planFor(roomType)
            val inspector = asTestUser(support.inspector())
            val pool = Executors.newFixedThreadPool(2)
            try {
                rooms.forEach { room ->
                    val (stay, _) = stays.inHouse(roomType, plan, room, today.plusDays(1))
                    val task =
                        UUID.fromString(
                            support.housekeeping
                                .requestTask(support.housekeeping.taskBody(type, roomId = room))["id"]
                                .asString(),
                        )
                    val attendant = execution.attendant()
                    execution.assign(task, attendant.employee)
                    execution.acted(task, "start", asTestUser(attendant.user))
                    execution.acted(task, "complete", asTestUser(attendant.user))
                    val start = CountDownLatch(1)
                    val approval =
                        pool.submit<Int> {
                            start.await()
                            execution
                                .act(
                                    task,
                                    "inspection/complete",
                                    inspector,
                                    body = support.complete("APPROVED"),
                                ).status
                        }
                    val checkOut =
                        pool.submit<Result<Unit>> {
                            start.await()
                            runCatching { reservationsApi.checkOut(stay) }.map { }
                        }
                    start.countDown()

                    val status = approval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val checkOutResult = checkOut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    (status < SERVER_ERROR) shouldBe true
                    checkOutResult.exceptionOrNull() shouldBe null
                    roomsApi.status(RoomId(room)).shouldNotBeNull()
                    execution.plans.task(task)["status"].asString() shouldBe
                        if (status == 200) "DONE" else "AWAITING_INSPECTION"
                }
            } finally {
                pool.shutdownNow()
            }
        }

    private companion object {
        const val ROUNDS = 3
        const val TIMEOUT_SECONDS = 60L
        const val SERVER_ERROR = 500
    }
}
