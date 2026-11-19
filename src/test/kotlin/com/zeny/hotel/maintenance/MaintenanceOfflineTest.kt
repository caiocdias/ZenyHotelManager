package com.zeny.hotel.maintenance

import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Os comandos offline da ordem de serviço (M6 E08, Task 3, D3 e D5): `maintenance.open-work-order` (o `commandId` é o
 * id da ordem), `start`, `hold`, `resume` e `complete` (os mesmos casos de uso do online, sem versão, com o
 * `occurredAt` corrigido e a trilha `<verbo>-offline`) e `add-work-order-note` (o `commandId` é o id da observação).
 * A recusa é `REJECTED` com o código do online (ordem final, id já usado, fora do escopo, payload fora do formato); os
 * instantes da ordem nunca andam para trás (Review Focus 5).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, banco, UHs e dia operacional
class MaintenanceOfflineTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        occurredAt: Instant = Instant.now(),
        id: UUID = UuidV7.generate(),
    ) = SyncCommand(id, "maintenance.$verb", payload, occurredAt, Instant.now())

    private fun sync(
        auth: RequestPostProcessor,
        vararg commands: SyncCommand,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
        }

    private fun statuses(results: List<JsonNode>) = results.map { it["status"].asString() }

    private fun instant(
        node: JsonNode,
        field: String,
    ): Instant = Instant.parse(node[field].asString())

    private fun auditActions(order: UUID): List<String> = support.audit(order).map { it["action"] as String }

    private fun ref(order: UUID) = mapOf("workOrderId" to order)

    @Test
    fun `an order opened offline gets the command id and the offline trail, and a replay creates no other`() {
        val auth = iam.asAdmin()
        val room = support.room()
        val command =
            command(
                "open-work-order",
                support.body(roomId = room, description = "Lâmpada queimada", category = "ELECTRICAL"),
                Instant.now().minus(MINUTES_10),
            )

        val first = sync(auth, command)
        val replay = sync(auth, command)

        val order = support.order(command.id)
        assertSoftly {
            statuses(first) shouldContainExactly listOf("APPLIED")
            replay.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true)
            order["status"].asString() shouldBe "OPEN"
            order["origin"].asString() shouldBe "RECEPTION"
            order["roomId"].asString() shouldBe room.toString()
            order["description"].asString() shouldBe "Lâmpada queimada"
            instant(order, "openedAt") shouldBe instant(first[0], "occurredAt")
            auditActions(command.id) shouldContainExactly listOf("maintenance.work-order.open-offline")
            count(command.id) shouldBe 1
        }
    }

    @Test
    fun `opening with the id of an existing order is rejected and overwrites nothing`() {
        val auth = iam.asAdmin()
        val existing = support.openedId(body = support.body(roomId = support.room(), description = "Original"))
        val command =
            command("open-work-order", support.body(roomId = support.room(), description = "Outra"), id = existing)

        val results = sync(auth, command)

        assertSoftly {
            statuses(results) shouldContainExactly listOf("REJECTED")
            results[0]["code"].asString() shouldBe "WORK_ORDER_ID_IN_USE"
            support.order(existing)["description"].asString() shouldBe "Original"
            auditActions(existing) shouldContainExactly listOf("maintenance.work-order.open")
        }
    }

    @Test
    fun `a requester opens offline but cannot act on the order, and an invalid payload is rejected and recorded`() {
        val requester = iam.createUser()
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :key")
                .param("key", RoleTemplate.RECEPTIONIST.name)
                .query(UUID::class.java)
                .single()
        iam.assignRole(requester, RoleId(role))
        val auth = iam.asTestUser(requester)
        val open = command("open-work-order", support.body(roomId = support.room()))
        val start = command("start-work-order", ref(open.id))
        val badCategory = command("open-work-order", support.body(roomId = support.room()) + ("category" to "NOPE"))
        val noOrder = command("start-work-order", mapOf("workOrderId" to null))
        val badLocation = command("open-work-order", support.body(roomId = support.room(), areaId = support.area()))

        val results = sync(auth, open, start, badCategory, noOrder, badLocation)

        assertSoftly {
            statuses(results) shouldContainExactly listOf("APPLIED", "REJECTED", "REJECTED", "REJECTED", "REJECTED")
            results[1]["code"].asString() shouldBe "PERMISSION_DENIED"
            results[2]["code"].asString() shouldBe "COMMAND_PAYLOAD_INVALID"
            results[3]["code"].asString() shouldBe "COMMAND_PAYLOAD_INVALID"
            results[4]["code"].asString() shouldBe "WORK_ORDER_LOCATION_INVALID"
            support.order(open.id)["status"].asString() shouldBe "OPEN"
            count(badCategory.id) shouldBe 0
            count(badLocation.id) shouldBe 0
        }
    }

    @Test
    fun `start, hold, resume and complete offline give the same state as online, with the corrected instants`() {
        val auth = iam.asAdmin()
        val online = support.openedId()
        val offline = support.openedId()
        with(iam) {
            support.act(online, "start").ok()
            support.act(online, "hold", body = mapOf("reason" to "Aguardando peça")).ok()
            support.act(online, "resume").ok()
            support.act(online, "complete", body = mapOf("notes" to "Trocada", "laborCost" to "30.00")).ok()
        }

        val start = sync(auth, command("start-work-order", ref(offline)))
        val afterStart = support.order(offline)
        val hold = sync(auth, command("hold-work-order", ref(offline) + ("reason" to "Aguardando peça")))
        val afterHold = support.order(offline)
        val resume = sync(auth, command("resume-work-order", ref(offline)))
        val complete =
            sync(
                auth,
                command("complete-work-order", ref(offline) + mapOf("notes" to "Trocada", "laborCost" to "30.00")),
            )
        val done = support.order(offline)
        val reference = support.order(online)

        assertSoftly {
            listOf(start, hold, resume, complete).forEach { statuses(it) shouldContainExactly listOf("APPLIED") }
            afterStart["status"].asString() shouldBe "IN_PROGRESS"
            instant(afterStart, "startedAt") shouldBe instant(start[0], "occurredAt")
            afterHold["status"].asString() shouldBe "ON_HOLD"
            afterHold["holdReason"].asString() shouldBe "Aguardando peça"
            instant(afterHold, "updatedAt") shouldBe instant(hold[0], "occurredAt")
            instant(done, "closedAt") shouldBe instant(complete[0], "occurredAt")
            listOf("status", "holdReason", "completionNotes", "laborCost", "thirdPartyCost", "partsCost").forEach {
                done[it] shouldBe reference[it]
            }
            auditActions(offline) shouldContainExactly
                listOf(
                    "maintenance.work-order.open",
                    "maintenance.work-order.start-offline",
                    "maintenance.work-order.hold-offline",
                    "maintenance.work-order.resume-offline",
                    "maintenance.work-order.complete-offline",
                )
            // O replay do mesmo lote não muda nada.
            sync(auth, command("start-work-order", ref(offline))).let {
                statuses(it) shouldContainExactly
                    listOf("REJECTED")
            }
        }
    }

    @Test
    fun `completing offline an order with an OOO in effect releases the period and leaves the room Dirty`() {
        val room = support.room()
        roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
        val order = support.openedId(body = support.body(roomId = room))
        val today = hotelApi.currentBusinessDate()
        val blocked =
            with(iam) {
                support
                    .act(
                        order,
                        "room-block",
                        body =
                            mapOf(
                                "kind" to "OUT_OF_ORDER",
                                "startsOn" to today.toString(),
                                "endsOn" to today.plusDays(2).toString(),
                            ),
                    ).ok()
            }
        val period = UUID.fromString(blocked["outOfOrderId"].asString())
        val technician =
            iam.createUserWith(
                TestGrant(
                    MaintenancePermissions.permissions.single {
                        it.code ==
                            MaintenancePermissions.MANAGE
                    },
                    OWN,
                ),
            )
        support.employee(userId = technician.id.value).also {
            with(
                iam,
            ) { support.update(order, support.updateBody(support.order(order), "assigneeEmployeeId" to it)).ok() }
        }

        val results = sync(iam.asTestUser(technician), command("complete-work-order", ref(order)))

        assertSoftly {
            statuses(results) shouldContainExactly listOf("APPLIED")
            support.order(order)["status"].asString() shouldBe "DONE"
            jdbc
                .sql("SELECT released_on FROM rooms.out_of_order WHERE id = :id")
                .param("id", period)
                .query()
                .singleRow()["released_on"]
                .shouldNotBeNull()
            roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        }
    }

    @Test
    fun `an old occurredAt never moves the instants of the order backwards`() {
        val auth = iam.asAdmin()
        val order = support.openedId()
        val opened = instant(support.order(order), "openedAt")
        val stale = Instant.now().minus(HOURS_1)

        sync(auth, command("start-work-order", ref(order), stale))
        val started = support.order(order)
        sync(auth, command("hold-work-order", ref(order) + ("reason" to "Peça"), stale))
        sync(auth, command("resume-work-order", ref(order), stale))
        sync(auth, command("complete-work-order", ref(order), stale))
        val done = support.order(order)

        assertSoftly {
            instant(started, "startedAt") shouldBe opened
            instant(done, "startedAt") shouldBe opened
            (instant(done, "closedAt") >= instant(done, "startedAt")) shouldBe true
            (instant(done, "updatedAt") >= opened) shouldBe true
            done["status"].asString() shouldBe "DONE"
        }
    }

    @Test
    fun `a command on a final order is rejected with the online code, changes nothing and replays the result`() {
        val auth = iam.asAdmin()
        val done = support.openedId()
        val cancelled = support.openedId()
        with(iam) {
            support.act(done, "complete", body = emptyMap<String, Any?>()).ok()
            support.act(cancelled, "cancel", body = mapOf("reason" to "Duplicada")).ok()
        }
        val versions = listOf(done, cancelled).map { support.row(it)["version"] }
        val commands =
            listOf(
                command("start-work-order", ref(done)),
                command("complete-work-order", ref(cancelled)),
                command("hold-work-order", ref(done) + ("reason" to "x")),
                command("add-work-order-note", ref(done) + ("text" to "tarde")),
            )

        val results = sync(auth, *commands.toTypedArray())
        val replay = sync(auth, commands[0])

        assertSoftly {
            statuses(results) shouldContainExactly List(4) { "REJECTED" }
            results.map { it["code"].asString() } shouldContainExactly List(4) { "WORK_ORDER_TRANSITION_NOT_ALLOWED" }
            listOf(done, cancelled).map { support.row(it)["version"] } shouldBe versions
            replay.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true)
            replay[0]["code"].asString() shouldBe "WORK_ORDER_TRANSITION_NOT_ALLOWED"
            auditActions(done) shouldContainExactly
                listOf("maintenance.work-order.open", "maintenance.work-order.complete")
        }
    }

    @Test
    fun `a technician with the own scope is rejected on an order that is not his`() {
        val technician =
            iam.createUserWith(
                TestGrant(
                    MaintenancePermissions.permissions.single {
                        it.code ==
                            MaintenancePermissions.MANAGE
                    },
                    OWN,
                ),
            )
        val alien = support.openedId()

        val results = sync(iam.asTestUser(technician), command("start-work-order", ref(alien)))

        assertSoftly {
            statuses(results) shouldContainExactly listOf("REJECTED")
            results[0]["code"].asString() shouldBe "RESOURCE_OUT_OF_SCOPE"
            support.order(alien)["status"].asString() shouldBe "OPEN"
        }
    }

    @Test
    fun `a note offline takes the command id as its id, the corrected time and the offline trail, never the text`() {
        val auth = iam.asAdmin()
        val order = support.openedId()
        val note = command("add-work-order-note", ref(order) + ("text" to "Voltei lá, falta a peça"))

        val results = sync(auth, note)
        val replay = sync(auth, note)

        val notes = support.order(order)["notes"].values().toList()
        val trail =
            jdbc
                .sql(
                    "SELECT action, after_state::text AS state FROM audit.audit_entry " +
                        "WHERE entity_type = 'maintenance.work-order-note' AND entity_id = :id",
                ).param("id", note.id.toString())
                .query()
                .listOfRows()
        assertSoftly {
            statuses(results) shouldContainExactly listOf("APPLIED")
            replay.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true)
            notes.map { it["id"].asString() } shouldContainExactly listOf(note.id.toString())
            notes[0]["text"].asString() shouldBe "Voltei lá, falta a peça"
            instant(notes[0], "createdAt") shouldBe instant(results[0], "occurredAt")
            trail.map { it["action"] } shouldContainExactly listOf("maintenance.work-order-note.add-offline")
            withClue(trail.toString()) { (trail.single()["state"] as String).contains("falta a peça") shouldBe false }
        }
    }

    @Test
    fun `a note offline with a bad text or the id of an existing note is rejected`() {
        val auth = iam.asAdmin()
        val order = support.openedId()
        val first = command("add-work-order-note", ref(order) + ("text" to "Primeira"))
        sync(auth, first)
        val reused = command("add-work-order-note", ref(order) + ("text" to "Outra"), id = first.id)
        val empty = command("add-work-order-note", ref(order) + ("text" to "  "))
        val long = command("add-work-order-note", ref(order) + ("text" to "x".repeat(1001)))

        val results = sync(auth, empty, long)
        val replay = sync(auth, reused)

        assertSoftly {
            statuses(results) shouldContainExactly listOf("REJECTED", "REJECTED")
            replay.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true)
            support.order(order)["notes"].size() shouldBe 1
        }
    }

    private fun count(id: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM maintenance.work_order WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()

    private companion object {
        val MINUTES_10: Duration = Duration.ofMinutes(10)
        val HOURS_1: Duration = Duration.ofHours(1)
    }
}
