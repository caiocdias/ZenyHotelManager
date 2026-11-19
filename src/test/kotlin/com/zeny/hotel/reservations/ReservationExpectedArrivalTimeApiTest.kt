package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.function.Supplier

/**
 * A hora prevista de chegada da reserva (M6 E22, D1 a D5): gravada na criação e na alteração (que substitui: ausente ou
 * nula apaga), sem mexer em total, noites, inventário nem alocação quando é a única mudança (inclusive de uma reserva
 * hospedada), com o retrato no histórico e na trilha, o campo no `ReservationChanged` e na exportação do titular, e a
 * entrada estrita (`HH:mm` ou `HH:mm:ss`). Cada teste cria os próprios tipos de UH (banco compartilhado).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class ReservationExpectedArrivalTimeApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a booking created with the expected arrival time returns it, and without it the field is null`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)

        val with = created(type, plan.id, arrival, "10:30")
        with["expectedArrivalTime"].asString() shouldBe "10:30:00"
        support.reservation(with.id())["reservation"]["expectedArrivalTime"].asString() shouldBe "10:30:00"

        val seconds = created(type, plan.id, arrival, "10:30:15")
        seconds["expectedArrivalTime"].asString() shouldBe "10:30:15"

        val without = created(type, plan.id, arrival, null)
        without["expectedArrivalTime"].isNull shouldBe true
        support.reservation(without.id())["reservation"]["expectedArrivalTime"].isNull shouldBe true
    }

    @Test
    @Suppress("LongMethod") // a alteração só da hora: valores, inventário, alocação, histórico, trilha e evento
    fun `changing only the time keeps total, nights, inventory and room, and records history, audit and event`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, "10:30")
        val id = reservation.id()
        iam
            .call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to rooms[0]))
            .status shouldBe 200
        val current = support.reservation(id)["reservation"]
        val soldBefore = inventory.row(type, arrival)!!.sold

        var changed: JsonNode? = null
        scenario
            .stimulate(
                Supplier {
                    with(support.billing) {
                        support
                            .change(id, changeBody(current, "14:45"))
                            .expect(200)
                            .also { changed = it }
                    }
                },
            ).andWaitForEventOfType(ReservationChanged::class.java)
            .matching { it.reservationId == id }
            .toArriveAndVerify { event ->
                event.changedFields shouldContainExactly listOf("expectedArrivalTime")
                event.version shouldBe current["version"].asLong() + 1
            }

        val after = changed!!["reservation"]
        after["expectedArrivalTime"].asString() shouldBe "14:45:00"
        after["total"].asString() shouldBe current["total"].asString()
        after["nightlyRates"] shouldBe current["nightlyRates"]
        after["roomAssignment"]["roomId"].asString() shouldBe rooms[0].toString()
        after["approvalId"].isNull shouldBe true
        inventory.row(type, arrival)!!.sold shouldBe soldBefore

        val history = with(iam) { call(GET, "/api/v1/reservations/$id/history", support.admin).ok() }
        val entry = history["items"].values().single()
        entry["kind"].asString() shouldBe "CHANGE"
        entry["changedFields"].values().map { it.asString() } shouldContainExactly listOf("expectedArrivalTime")
        entry["before"]["expectedArrivalTime"].asString() shouldBe "10:30:00"
        entry["after"]["expectedArrivalTime"].asString() shouldBe "14:45:00"

        val change = support.auditEntries(id).single { it["action"] == "reservations.reservation.change" }
        (change["before"] as String) shouldContain "10:30"
        (change["after"] as String) shouldContain "14:45"
    }

    @Test
    fun `changing with a null time, or without the field, erases the time`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)

        val nulled = created(type, plan.id, arrival, "09:00")
        with(support.billing) {
            val current = support.reservation(nulled.id())["reservation"]
            support
                .change(nulled.id(), changeBody(current, null))
                .expect(200)["reservation"]["expectedArrivalTime"]
                .isNull shouldBe true
        }

        val omitted = created(type, plan.id, arrival, "09:00")
        with(support.billing) {
            val current = support.reservation(omitted.id())["reservation"]
            val body = support.changeBody(current, specialRequests = "Andar alto")
            support
                .change(omitted.id(), body)
                .expect(200)["reservation"]["expectedArrivalTime"]
                .isNull shouldBe true
        }
        support.reservation(omitted.id())["reservation"]["expectedArrivalTime"].isNull shouldBe true
    }

    @Test
    fun `changing only the time of an in-house reservation is accepted and does not touch the nights`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reservation = created(type, plan.id, today.plusDays(2), "11:00")
        val id = reservation.id()
        support.forceInHouse(id, rooms.first())
        val current = support.reservation(id)["reservation"]

        with(support.billing) {
            val after = support.change(id, changeBody(current, "12:15")).expect(200)["reservation"]
            after["status"].asString() shouldBe "IN_HOUSE"
            after["expectedArrivalTime"].asString() shouldBe "12:15:00"
            after["nightlyRates"] shouldBe current["nightlyRates"]
            after["total"].asString() shouldBe current["total"].asString()
        }
    }

    @Test
    @Suppress("LongMethod") // cada valor inválido, na criação e na alteração
    fun `a malformed time is refused with VALIDATION_FAILED on the field, on creation and on change`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation = created(type, plan.id, arrival, null)
        val current = support.reservation(reservation.id())["reservation"]

        listOf<Any>("7:30", "24:00", "10:30:00.5", "", 1030, "10h30").forEach { value ->
            with(support.billing) {
                val create =
                    support.create(
                        support.booking(
                            support.guest(),
                            support.reservation(type, plan.id, arrival, arrival.plusDays(1)) +
                                ("expectedArrivalTime" to value),
                        ),
                    )
                val createFields =
                    create.problem(400, "VALIDATION_FAILED")["errors"].values().map { it["field"].asString() }
                createFields.single() shouldStartWith "reservations"
                createFields.single() shouldEndWith "expectedArrivalTime"

                val change =
                    support.change(
                        reservation.id(),
                        support.changeBody(current) + ("expectedArrivalTime" to value),
                    )
                change
                    .problem(400, "VALIDATION_FAILED")["errors"]
                    .values()
                    .map { it["field"].asString() }
                    .shouldContainExactly("expectedArrivalTime")
            }
        }
        support.reservation(reservation.id())["reservation"]["expectedArrivalTime"].isNull shouldBe true
        support.historyKinds(reservation.id()) shouldHaveSize 0
    }

    @Test
    fun `the holder data export has the expected arrival time in the reservation section`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val holder = support.guest()
        support.created(
            support.booking(
                holder,
                support.reservation(type, plan.id, today.plusDays(2), today.plusDays(3)) +
                    ("expectedArrivalTime" to "16:20"),
            ),
        )

        val exported = with(iam) { call(POST, "/api/v1/guests/$holder/export", support.admin).ok() }
        val section = exported["sections"].values().single { it["name"].asString() == "reservations" }["data"]
        section["reservations"].values().single()["expectedArrivalTime"].asString() shouldBe "16:20:00"
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        time: String?,
    ): JsonNode =
        support.created(
            support.booking(
                support.guest(),
                support.reservation(type, plan, arrival, arrival.plusDays(1)) + ("expectedArrivalTime" to time),
            ),
        )["reservations"][0]

    private fun changeBody(
        current: JsonNode,
        time: String?,
    ): Map<String, Any?> = support.changeBody(current) + ("expectedArrivalTime" to time)

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())
}
