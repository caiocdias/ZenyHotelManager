package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsRealtime
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * O aviso de UH liberada (M6 E05, Task 2; D5, RF-GOV-07): `notifications.room-released` só quando a UH passa de não
 * pronta a pronta para check-in — na aprovação da inspeção e na conclusão da E04 de um tipo sem inspeção numa UH
 * vaga —, aos usuários com `housekeeping.tasks.read` menos o inspetor ou quem concluiu, com o número da UH e nada de
 * hóspede; o painel rebusca a prontidão pelo `rooms.room-status` no tópico `rooms`. UH já pronta (inclusive a da
 * inspeção voluntária, que só aceita UH Limpa sem pendência, já pronta), ocupada ou em OOO: nenhum aviso (Review
 * Focus 4).
 *
 * Os avisos vão a todo usuário com a permissão no banco compartilhado: o teste confere só o aviso da própria UH, pelo
 * número no título.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // as APIs conferidas e o canal de tempo real
class HousekeepingRoomReleasedTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val clock: Clock,
    @Autowired private val json: JsonMapper,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `approving the inspection of a vacant room releases it to the readers, not to the inspector`(): Unit =
        with(iam) {
            val receptionist = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
            val (task, room) = support.awaitingInspection(support.cleaningType())
            val inspector = support.inspector()
            InspectionTestSupport.Listener(hub, json, clock, RoomsRealtime.TOPIC).use { listener ->
                execution.acted(task, "inspection/complete", asTestUser(inspector), support.complete("APPROVED"))

                val notice = released(receptionist, room).single()
                val number = number(room)
                notice["title"].asString() shouldBe "UH $number liberada"
                notice["body"].asString() shouldBe "A UH $number está pronta para check-in."
                released(inspector, room).shouldBeEmpty()
                await().atMost(Duration.ofSeconds(5)).until {
                    listener.received(RoomsRealtime.ROOM_STATUS, room.value)
                }
            }
        }

    @Test
    fun `completing a task without inspection in a vacant dirty room releases it, not to who completed`(): Unit =
        with(iam) {
            val reader = support.inspector()
            val housekeeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
            val (task, room) = support.assignedTask(support.cleaningType(requiresInspection = false), employee())
            execution.acted(task, "start", asTestUser(housekeeper))

            json(execution.acted(task, "complete", asTestUser(housekeeper)).second)["status"].asString() shouldBe "DONE"

            released(reader, room).single()["title"].asString() shouldBe "UH ${number(room)} liberada"
            released(housekeeper, room).shouldBeEmpty()
        }

    @Test
    fun `a room already ready, occupied or out of order is not released (Review Focus 4)`(): Unit =
        with(iam) {
            val reader = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            val withoutInspection = support.cleaningType(requiresInspection = false)

            val (alreadyClean, cleanRoom) = support.assignedTask(withoutInspection, employee())
            roomsApi.markClean(cleanRoom, false, RoomStatusSource.MANUAL)
            val (occupied, occupiedRoom) = support.assignedTask(withoutInspection, employee())
            roomsApi.markOccupied(occupiedRoom, 2, RoomStatusSource.CHECK_IN)
            val (outOfOrder, outOfOrderRoom) = support.assignedTask(withoutInspection, employee())
            val today = hotelApi.currentBusinessDate()
            roomsApi.openOutOfOrder(
                outOfOrderRoom,
                OutOfOrderKind.OUT_OF_ORDER,
                OutOfOrderRequest(today, today.plusDays(2), "Vazamento", "Manutenção"),
            )
            listOf(alreadyClean, occupied, outOfOrder).forEach { task ->
                execution.acted(task, "start", housekeeper)
                execution.acted(task, "complete", housekeeper)
            }
            val voluntary = RoomId(support.housekeeping.createRoom())
            roomsApi.markClean(voluntary, false, RoomStatusSource.MANUAL)
            call(POST, "/api/v1/room-inspections", asTestUser(support.inspector()), mapOf("roomId" to voluntary.value))
                .ok()

            listOf(cleanRoom, occupiedRoom, outOfOrderRoom, voluntary).forEach { room ->
                withClue(room) {
                    roomsApi.status(room).shouldNotBeNull()
                    released(reader, room).shouldBeEmpty()
                }
            }
        }

    @Test
    fun `approving the inspection of a room out of order inspects it but releases nothing (Review Focus 4)`(): Unit =
        with(iam) {
            val receptionist = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
            val inspector = support.inspector()
            val (task, room) = support.awaitingInspection(support.cleaningType())
            val today = hotelApi.currentBusinessDate()
            roomsApi.openOutOfOrder(
                room,
                OutOfOrderKind.OUT_OF_ORDER,
                OutOfOrderRequest(today, today.plusDays(2), "Vazamento", "Manutenção"),
            )

            val body =
                json(
                    execution
                        .acted(
                            task,
                            "inspection/complete",
                            asTestUser(inspector),
                            support.complete("APPROVED"),
                        ).second,
                )

            body["task"]["status"].asString() shouldBe "DONE"
            roomsApi.status(room).shouldNotBeNull().let {
                it.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
                it.inspectionPending shouldBe false
            }
            roomsApi.status(room).shouldNotBeNull().readyForCheckIn shouldBe false
            released(receptionist, room).shouldBeEmpty()
            released(inspector, room).shouldBeEmpty()
        }

    // --- Apoio -------------------------------------------------------------------------------------------

    private fun employee() = execution.plans.attendant()

    private fun number(room: RoomId): String = roomsApi.room(room).shouldNotBeNull().number

    /** Os avisos de UH liberada da [room] no painel do [user] (pelo número no título). */
    private fun released(
        user: TestUser,
        room: RoomId,
    ): List<JsonNode> {
        val number = number(room)
        return support
            .notices(user, "notifications.room-released")
            .filter { it["title"].asString() == "UH $number liberada" }
    }
}
