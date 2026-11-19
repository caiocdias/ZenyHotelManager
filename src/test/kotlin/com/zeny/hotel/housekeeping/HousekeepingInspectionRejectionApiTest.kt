package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * A reprovação da inspeção (M6 E05, Task 2; D3 e D6): a tarefa `AWAITING_INSPECTION` vai a `REJECTED` com as chaves
 * reprovadas do checklist de inspeção, gravadas na inspeção e expostas na tarefa; a UH Limpa com inspeção pendente fica
 * Suja (`RoomsApi.rejectInspectionIfPending`); a camareira atribuída é avisada (`notifications.inspection-rejected`,
 * sem dado de hóspede; sem usuário vinculado, o aviso é omitido sem erro); `housekeeping.task` é publicado. Refazer é o
 * `start` da E04. A UH que já perdeu a pendência não muda (Review Focus 2). `rejectedItems`: com modelo, chaves da
 * versão do checklist, pelo menos uma; sem modelo, vazio.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // as APIs conferidas e o canal de tempo real
class HousekeepingInspectionRejectionApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
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
    fun `rejecting dirties the room, rejects the task with the items, notifies the attendant and publishes`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            support.inspectionTemplate(type)
            val attendant = execution.attendant()
            val (task, room) = support.awaitingInspection(type, attendant)
            val inspector = support.inspector()
            val auth = asTestUser(inspector)
            execution.acted(task, "inspection/start", auth)
            InspectionTestSupport.Listener(hub, json, clock, HousekeepingRealtime.TOPIC).use { listener ->
                val (etag, response) =
                    execution.acted(
                        task,
                        "inspection/complete",
                        auth,
                        support.complete("REJECTED", listOf("towels", "floor"), listOf(support.check("bed"))),
                    )
                val body = json(response)

                body["task"]["status"].asString() shouldBe "REJECTED"
                body["inspection"]["result"].asString() shouldBe "REJECTED"
                body["inspection"]["status"].asString() shouldBe "COMPLETED"
                body["inspection"]["rejectedItems"].values().map { it.asString() } shouldContainExactly
                    listOf("towels", "floor")
                body["roomChanged"].asBoolean() shouldBe true
                roomsApi.status(room).shouldNotBeNull().let {
                    it.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
                    it.inspectionPending shouldBe false
                }
                support.rejectedItemsOf(task) shouldContainExactly listOf("towels", "floor")
                execution.plans.task(task).let {
                    it["status"].asString() shouldBe "REJECTED"
                    it["rejectedItems"].values().map { item -> item.asString() } shouldContainExactly
                        listOf("towels", "floor")
                }
                val notice = support.notices(attendant.user, "notifications.inspection-rejected").single()
                val number = roomsApi.room(room).shouldNotBeNull().number
                notice["title"].asString() shouldContain number
                notice["body"].asString() shouldContain "2"
                support.notices(inspector, "notifications.inspection-rejected").shouldBeEmpty()
                await().atMost(Duration.ofSeconds(5)).until {
                    listener.events.any { event ->
                        val message = json.readTree(event.data)
                        message["type"]?.asString() == HousekeepingRealtime.TASK &&
                            message["id"]?.asString() == task.toString() &&
                            message["version"]?.asLong() == etag.trim('"').toLong()
                    }
                }
            }
        }

    @Test
    fun `a rejected task is redone by the start of the attendant`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val attendant = execution.attendant()
            val (task, _) = support.awaitingInspection(type, attendant)
            execution.acted(task, "inspection/complete", asTestUser(support.inspector()), support.complete("REJECTED"))

            val (_, response) = execution.acted(task, "start", asTestUser(attendant.user))

            json(response)["status"].asString() shouldBe "IN_PROGRESS"
        }

    @Test
    fun `rejecting with the room already dirty, inspected or occupied leaves the room (Review Focus 2)`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val (checkedOut, dirtyRoom) = support.awaitingInspection(type)
            roomsApi.markVacant(dirtyRoom, RoomStatusSource.CHECK_OUT)
            val (inspected, inspectedRoom) = support.awaitingInspection(type)
            roomsApi.markInspected(inspectedRoom, RoomStatusSource.MANUAL)
            val (occupied, occupiedRoom) = support.awaitingInspection(type)
            roomsApi.markInspected(occupiedRoom, RoomStatusSource.MANUAL)
            roomsApi.markOccupied(occupiedRoom, 2, RoomStatusSource.CHECK_IN)

            listOf(
                checkedOut to dirtyRoom,
                inspected to inspectedRoom,
                occupied to occupiedRoom,
            ).forEach { (task, room) ->
                withClue("task $task") {
                    val before = roomsApi.status(room).shouldNotBeNull()

                    val body =
                        json(
                            execution
                                .acted(
                                    task,
                                    "inspection/complete",
                                    asTestUser(support.inspector()),
                                    support.complete("REJECTED"),
                                ).second,
                        )

                    body["task"]["status"].asString() shouldBe "REJECTED"
                    body["roomChanged"].asBoolean() shouldBe false
                    roomsApi.status(room) shouldBe before
                }
            }
        }

    @Test
    fun `an attendant without a user is not notified, and the rejection goes on`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val employee = execution.plans.attendant()
            val (task, room) = support.assignedTask(type, employee)
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            execution.acted(task, "start", housekeeper)
            execution.acted(task, "complete", housekeeper)

            val body =
                json(
                    execution
                        .acted(
                            task,
                            "inspection/complete",
                            asTestUser(support.inspector()),
                            support.complete("REJECTED"),
                        ).second,
                )

            body["task"]["status"].asString() shouldBe "REJECTED"
            roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        }

    @Test
    fun `an unknown, missing, null or malformed rejected key is 400 and nothing changes`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            support.inspectionTemplate(type)
            val (task, room) = support.awaitingInspection(type)
            val auth = asTestUser(support.inspector())
            execution.acted(task, "inspection/start", auth)
            val before = roomsApi.status(room).shouldNotBeNull()
            val answers = listOf(support.check("bed"))

            listOf(
                listOf("towels", "mirror"),
                emptyList(),
                null,
                listOf(null),
                listOf("Towels"),
                listOf(" "),
            ).forEach { items ->
                withClue("rejectedItems $items") {
                    val body =
                        if (items == null) {
                            support.complete("REJECTED", answers = answers)
                        } else {
                            support.complete("REJECTED", items, answers)
                        }
                    val problem =
                        execution
                            .act(
                                task,
                                "inspection/complete",
                                auth,
                                body = body,
                            ).problem(400, "VALIDATION_FAILED")
                    problem["errors"][0]["field"].asString() shouldContain "rejectedItems"
                }
            }
            execution.plans.task(task)["status"].asString() shouldBe "AWAITING_INSPECTION"
            support.inspectionRow(task)["status"] shouldBe "IN_PROGRESS"
            roomsApi.status(room) shouldBe before
        }

    @Test
    fun `without a template, rejecting with items is 400, and with none rejects the task`(): Unit =
        with(iam) {
            val type = support.cleaningType()
            val (task, _) = support.awaitingInspection(type)
            val auth = asTestUser(support.inspector())

            execution
                .act(task, "inspection/complete", auth, body = support.complete("REJECTED", listOf("towels")))
                .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                .asString() shouldBe "rejectedItems"
            execution.plans.task(task)["status"].asString() shouldBe "AWAITING_INSPECTION"

            val body = json(execution.acted(task, "inspection/complete", auth, support.complete("REJECTED")).second)
            body["task"]["status"].asString() shouldBe "REJECTED"
            body["inspection"]["rejectedItems"].values().toList() shouldHaveSize 0
        }
}
