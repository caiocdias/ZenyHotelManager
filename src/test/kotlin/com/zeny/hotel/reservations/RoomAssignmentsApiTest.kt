package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.application.ReallocationListener
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderAction
import com.zeny.hotel.rooms.OutOfOrderChanged
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * A alocação de UH (M4 E10; RF-RES-04, RN-04, RN-07, RF-UH-05, H5, H11): a sobreposição recusada pela API (`409`) e
 * pelo banco (o `INSERT` direto), a saída e a chegada no mesmo dia, o day use, a alocação de reserva cancelada que não
 * bloqueia, as sugestões sem OOO e com OOS por último, "não mover", a alteração da reserva que leva ou solta a UH e a
 * marca de realocação com a notificação a quem tem a permissão, uma vez só. Cada teste cria os próprios tipos de UH
 * (banco compartilhado). O mesmo contexto dos outros testes das reservas.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários da alocação de ponta a ponta e as portas que usam
class RoomAssignmentsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val reallocationListener: ReallocationListener,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val rooms = inventory.rooms
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Sobreposição (RN-04) ---------------------------------------------------------------------------------------

    @Test
    fun `an overlapping assignment of the same room is refused by the API with 409 and by the database`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(30))
        val first = created(type, plan, today.plusDays(5), today.plusDays(8))
        val second = created(type, plan, today.plusDays(7), today.plusDays(9))

        val assigned = assign(first.id(), room).ok()["reservation"]
        assigned["roomAssignment"]["roomId"].asString() shouldBe room.toString()
        assigned["roomAssignment"]["roomNumber"].asString() shouldBe number(room)
        assigned["roomAssignment"]["startsOn"].asString() shouldBe today.plusDays(5).toString()
        assigned["roomAssignment"]["endsOn"].asString() shouldBe today.plusDays(8).toString()
        assigned["roomAssignment"]["reason"].asString() shouldBe "INITIAL"
        assigned["version"].asLong() shouldBe 1
        support.etag(first.id()) shouldBe "\"1\""

        val refused = with(iam) { assign(second.id(), room).problem(409, "ROOM_ALREADY_ASSIGNED") }
        refused["detail"].asString() shouldContain number(room)
        support.reservation(second.id())["reservation"]["roomAssignment"].isNull shouldBe true

        // O banco recusa a sobreposição mesmo contornando a aplicação (o papel da aplicação, INSERT direto).
        val violation =
            shouldThrow<DataAccessException> {
                insertAssignment(
                    second.id(),
                    room,
                    today.plusDays(7),
                    today.plusDays(9),
                )
            }
        violation.constraint() shouldBe "room_assignment_overlap_ex"
        violation.sqlState() shouldBe "23P01"
        // Uma alocação solta (released_at preenchido) não bloqueia: o banco aceita.
        insertAssignment(second.id(), room, today.plusDays(7), today.plusDays(9), released = true)
        activeAssignments(room) shouldBe 1
    }

    @Test
    fun `the departure of one reservation and the arrival of another on the same day share the room`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(30))
        val leaving = created(type, plan, today.plusDays(3), today.plusDays(5))
        val arriving = created(type, plan, today.plusDays(5), today.plusDays(7))

        assign(leaving.id(), room).ok()
        assign(arriving.id(), room).ok()["reservation"]["roomAssignment"]["roomId"].asString() shouldBe room.toString()
        activeAssignments(room) shouldBe 2
        // Também pelo banco: a saída de uma e a chegada de outra no mesmo dia não violam a restrição.
        insertAssignment(
            created(type, plan, today.plusDays(7), today.plusDays(8)).id(),
            room,
            today.plusDays(7),
            today.plusDays(8),
        )
        activeAssignments(room) shouldBe 3
    }

    @Test
    fun `a day use shares the room with a departure and an arrival on its date, not with a stay crossing it`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(3, today)
        val (room, other) = roomIds
        val plan = planFor(type, today.plusDays(30))
        val day = today.plusDays(10)
        val leaving = created(type, plan, day.minusDays(2), day)
        val arriving = created(type, plan, day, day.plusDays(1))
        val dayUse = created(type, plan, day, day)

        // A saída e a chegada de outras reservas na data não conflitam com o day use (H5), em qualquer ordem.
        assign(leaving.id(), room).ok()
        assign(arriving.id(), room).ok()
        // A sugestão segue a mesma regra: a UH com a saída e a chegada na data serve ao day use.
        with(iam) { call(GET, "/api/v1/reservations/${dayUse.id()}/room-suggestions", support.admin).ok() }["items"]
            .values()
            .map { UUID.fromString(it["roomId"].asString()) } shouldContainExactlyInAnyOrder roomIds
        val assigned = assign(dayUse.id(), room).ok()["reservation"]["roomAssignment"]
        assigned["dayUse"].asBoolean() shouldBe true
        assigned["startsOn"].asString() shouldBe day.toString()
        assigned["endsOn"].asString() shouldBe day.toString()
        activeAssignments(room) shouldBe 3

        // A estadia que atravessa a data (chega antes, sai depois) e outro day use da data conflitam.
        val otherDayUse = created(type, plan, day, day)
        assign(otherDayUse.id(), other).ok()
        val crossing = created(type, plan, day.minusDays(1), day.plusDays(1))
        with(iam) { assign(crossing.id(), other).problem(409, "ROOM_ALREADY_ASSIGNED") }
        with(iam) { assign(created(type, plan, day, day).id(), other).problem(409, "ROOM_ALREADY_ASSIGNED") }
        // Também pelo banco: o INSERT direto da estadia que atravessa a data recusado; a que chega nela, aceito.
        val violation =
            shouldThrow<DataAccessException> {
                insertAssignment(
                    crossing.id(),
                    other,
                    day.minusDays(1),
                    day.plusDays(1),
                )
            }
        violation.constraint() shouldBe "room_assignment_overlap_ex"
        insertAssignment(created(type, plan, day, day.plusDays(2)).id(), other, day, day.plusDays(2))
        activeAssignments(other) shouldBe 2
    }

    @Test
    fun `two simultaneous assignments of the same room to overlapping reservations assign it once`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(60))
        repeat(ROUNDS) { round ->
            val start = today.plusDays(10L + round * 5)
            val a = created(type, plan, start, start.plusDays(2))
            val b = created(type, plan, start.plusDays(1), start.plusDays(3))
            val barrier = CyclicBarrier(2)
            val statuses = mutableListOf<Int>()
            listOf(a, b)
                .map { reservation ->
                    thread {
                        barrier.await()
                        val status = assign(reservation.id(), room).status
                        synchronized(statuses) { statuses += status }
                    }
                }.forEach { it.join() }
            statuses shouldContainExactlyInAnyOrder listOf(200, 409)
        }
        activeAssignments(room) shouldBe ROUNDS
    }

    // --- Reservas canceladas -------------------------------------------------------------------------------------

    @Test
    fun `cancelling releases the room, a cancelled reservation is not assigned and reactivation comes back roomless`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(3, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(30))
        val first = created(type, plan, today.plusDays(4), today.plusDays(6))
        assign(first.id(), room, doNotMove = true).ok()

        // O cancelamento solta a UH mesmo com "não mover".
        with(
            support.billing,
        ) { support.cancel(first.id()).expect(200) }["reservation"]["roomAssignment"].isNull shouldBe
            true
        with(iam) { assign(first.id(), room).problem(409, "ROOM_ASSIGNMENT_NOT_ALLOWED") }["reservationStatus"]
            .asString() shouldBe "CANCELLED"
        val history = assignments(first.id())
        history.map { it["releaseReason"].asString() } shouldContainExactly listOf("RESERVATION_CANCELLED")
        history.single()["active"].asBoolean() shouldBe false

        // A alocação da reserva cancelada não bloqueia mais a UH.
        val second = created(type, plan, today.plusDays(4), today.plusDays(6))
        assign(second.id(), room).ok()

        // A reativação volta sem UH (a UH pode já ser de outra reserva): realoca-se.
        with(
            support.billing,
        ) { support.reactivate(first.id()).expect(200) }["reservation"]["roomAssignment"].isNull shouldBe
            true
        with(iam) { assign(first.id(), room).problem(409, "ROOM_ALREADY_ASSIGNED") }
        assign(first.id(), roomIds.last()).ok()

        // O banco recusa a alocação ativa de uma reserva cancelada (gatilho adiado, no commit).
        val third = created(type, plan, today.plusDays(20), today.plusDays(21))
        with(support.billing) { support.cancel(third.id()).expect(200) }
        val refused =
            shouldThrow<Exception> {
                insertAssignment(third.id(), roomIds.last(), today.plusDays(20), today.plusDays(21))
            }
        refused.messages() shouldContain "não está ativa"
    }

    @Test
    fun `the database refuses to cancel a reservation that keeps an active assignment`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type, today.plusDays(30))
        val reservation = created(type, plan, today.plusDays(4), today.plusDays(5))
        assign(reservation.id(), roomIds.single()).ok()

        val refused =
            shouldThrow<Exception> {
                transactions.executeWithoutResult {
                    jdbc
                        .sql(
                            "UPDATE reservations.reservation SET status = 'NO_SHOW' WHERE id = :id",
                        ).param("id", reservation.id())
                        .update()
                }
            }
        refused.messages() shouldContain "ainda tem UH alocada"
        support.status(reservation.id()) shouldBe "CONFIRMED"
    }

    // --- Regras da UH ------------------------------------------------------------------------------------------------

    @Test
    fun `the room must exist, be active, be of the reservation type and have no OOO in the stay`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(3, today)
        val (_, otherRooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type, today.plusDays(30))
        val reservation = created(type, plan, today.plusDays(5), today.plusDays(8))

        with(iam) {
            assign(
                reservation.id(),
                UuidV7.generate(),
            ).problem(400, "UNKNOWN_ROOM")["errors"][0]["field"].asString() shouldBe
                "roomId"
            assign(reservation.id(), otherRooms.single()).problem(422, "ROOM_TYPE_MISMATCH")
            call(POST, "/api/v1/rooms/${roomIds[0]}/deactivate", support.admin).ok()
            assign(reservation.id(), roomIds[0]).problem(422, "ROOM_INACTIVE")
        }
        roomsApi.openOutOfOrder(
            RoomId(roomIds[1]),
            OutOfOrderKind.OUT_OF_ORDER,
            ooo(today.plusDays(7), today.plusDays(9)),
        )
        val problem = with(iam) { assign(reservation.id(), roomIds[1]).problem(422, "ROOM_OUT_OF_ORDER") }
        problem["dates"].values().map { it.asString() } shouldContainExactly listOf(today.plusDays(7).toString())
        // O OOS não impede (a UH continua vendável, como última opção).
        roomsApi.openOutOfOrder(
            RoomId(roomIds[2]),
            OutOfOrderKind.OUT_OF_SERVICE,
            ooo(today.plusDays(5), today.plusDays(6)),
        )
        assign(reservation.id(), roomIds[2]).ok()
    }

    @Test
    fun `assigning requires the assignment permission, reading the history requires the reservation read`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type, today.plusDays(30))
        val reservation = created(type, plan, today.plusDays(5), today.plusDays(6))
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.RESERVATIONS_READ), ALL))

        with(iam) {
            assign(reservation.id(), roomIds.single(), auth = asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/reservations/${reservation.id()}/room-suggestions", asTestUser(reader))
                .problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/reservations/${reservation.id()}/room-assignments", asTestUser(reader)).ok()
            call(GET, "/api/v1/room-assignments/reallocation-required", asTestUser(reader)).ok()
        }
    }

    // --- Sugestões ---------------------------------------------------------------------------------------------------

    @Test
    fun `suggestions list only free rooms of the type without OOO, with the OOS ones last and the guest floor first`() {
        val type = rooms.createRoomType()
        val view = rooms.createFeature()
        val taken = room(type, "1º andar")
        val inOoo = room(type, "3º andar", view)
        val outOfService = room(type, "3º andar", view)
        val plain = room(type, "1º andar")
        val preferred = room(type, "3º Andar")
        val withView = room(type, "1º andar", view)
        inventory.awaitRow(type, today) { it.total == 6 }
        val plan = planFor(type, today.plusDays(30))
        val guest = guestWithFloor("3º andar")
        val arrival = today.plusDays(6)
        val reservation = created(type, plan, arrival, arrival.plusDays(3), guest)
        assign(created(type, plan, arrival.plusDays(2), arrival.plusDays(4)).id(), taken).ok()
        roomsApi.openOutOfOrder(
            RoomId(inOoo),
            OutOfOrderKind.OUT_OF_ORDER,
            ooo(arrival.plusDays(1), arrival.plusDays(1)),
        )
        roomsApi.openOutOfOrder(RoomId(outOfService), OutOfOrderKind.OUT_OF_SERVICE, ooo(arrival, arrival))

        val suggestions =
            with(iam) {
                call(GET, "/api/v1/reservations/${reservation.id()}/room-suggestions?featureIds=$view", support.admin)
                    .ok()
            }

        suggestions["floorPreferenceConsidered"].asBoolean() shouldBe true
        suggestions["items"].values().map { UUID.fromString(it["roomId"].asString()) } shouldContainExactly
            listOf(withView, preferred, plain, outOfService)
        val items = suggestions["items"].values().associateBy { UUID.fromString(it["roomId"].asString()) }
        items.getValue(outOfService)["outOfService"].asBoolean() shouldBe true
        items.getValue(preferred)["matchesFloorPreference"].asBoolean() shouldBe true
        items.getValue(withView)["matchedFeatureIds"].values().map { it.asString() } shouldContainExactly
            listOf(view.toString())
        // Sem características pedidas, o andar preferido vem primeiro (o OOS continua por último).
        with(iam) { call(GET, "/api/v1/reservations/${reservation.id()}/room-suggestions", support.admin).ok() }
            .get("items")
            .values()
            .map { UUID.fromString(it["roomId"].asString()) }
            .first() shouldBe preferred
        // A preferência vem da GuestsApi (o perfil do hóspede principal).
        guestsApi.preferences(GuestId(guest))!!.floor shouldBe "3º andar"
    }

    // --- "Não mover" e realocação ---------------------------------------------------------------------------------

    @Test
    fun `do not move blocks reallocating and unassigning until the flag is removed`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val (first, second) = roomIds
        val plan = planFor(type, today.plusDays(30))
        val reservation = created(type, plan, today.plusDays(5), today.plusDays(7))
        assign(reservation.id(), first, doNotMove = true)
            .ok()["reservation"]["roomAssignment"]["doNotMove"]
            .asBoolean() shouldBe true

        with(iam) {
            assign(reservation.id(), second).problem(409, "ROOM_ASSIGNMENT_LOCKED")
            unassign(reservation.id()).problem(409, "ROOM_ASSIGNMENT_LOCKED")
        }
        doNotMove(reservation.id(), false).ok()["reservation"]["roomAssignment"]["doNotMove"].asBoolean() shouldBe false
        val moved = assign(reservation.id(), second, reason = "GUEST_REQUEST").ok()["reservation"]["roomAssignment"]
        moved["roomId"].asString() shouldBe second.toString()
        moved["reason"].asString() shouldBe "GUEST_REQUEST"
        unassign(reservation.id()).ok()["reservation"]["roomAssignment"].isNull shouldBe true
        with(iam) { unassign(reservation.id()).problem(409, "ROOM_NOT_ASSIGNED") }
        with(iam) { doNotMove(reservation.id(), true).problem(409, "ROOM_NOT_ASSIGNED") }

        assignments(
            reservation.id(),
        ).map { it["roomId"].asString() to it["releaseReason"].asString() } shouldContainExactly
            listOf(second.toString() to "UNASSIGNED", first.toString() to "REALLOCATED")
        // A trilha: as alocações, sem dado de hóspede.
        support
            .auditEntries(UUID.fromString(assignments(reservation.id()).first()["id"].asString()))
            .map { it["action"] } shouldContainExactly
            listOf("reservations.room-assignment.assign", "reservations.room-assignment.unassign")
    }

    @Test
    fun `a change of dates keeps the room while free, lets it go otherwise, and do not move refuses the change`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(40))
        val arrival = today.plusDays(5)
        val reservation = created(type, plan, arrival, arrival.plusDays(2))
        assign(reservation.id(), room).ok()

        val extended = changeDeparture(reservation.id(), arrival.plusDays(3)).expect(200)["reservation"]
        extended["roomAssignment"]["endsOn"].asString() shouldBe arrival.plusDays(3).toString()
        extended["roomAssignment"]["roomId"].asString() shouldBe room.toString()

        assign(created(type, plan, arrival.plusDays(3), arrival.plusDays(5)).id(), room).ok()
        doNotMove(reservation.id(), true).ok()
        with(support.billing) {
            changeDeparture(reservation.id(), arrival.plusDays(4)).problem(409, "ROOM_ASSIGNMENT_LOCKED")
        }
        support.reservation(reservation.id())["reservation"]["departure"].asString() shouldBe
            arrival.plusDays(3).toString()

        doNotMove(reservation.id(), false).ok()
        val released = changeDeparture(reservation.id(), arrival.plusDays(4)).expect(200)["reservation"]
        released["roomAssignment"].isNull shouldBe true
        assignments(reservation.id()).first()["releaseReason"].asString() shouldBe "RESERVATION_CHANGED"
    }

    @Test
    fun `a change of dates lets the room go when it was deactivated or moved to another type in the meantime`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(3, today)
        val (deactivated, moved) = roomIds
        val otherType = rooms.createRoomType()
        val plan = planFor(type, today.plusDays(40))
        val arrival = today.plusDays(6)
        val first = created(type, plan, arrival, arrival.plusDays(2))
        val second = created(type, plan, arrival, arrival.plusDays(2))
        assign(first.id(), deactivated).ok()
        assign(second.id(), moved).ok()

        with(iam) {
            call(POST, "/api/v1/rooms/$deactivated/deactivate", support.admin).ok()
            val path = "/api/v1/rooms/$moved"
            call(
                PUT,
                path,
                support.admin,
                rooms.roomBody(otherType, number = number(moved)),
                ifMatch = rooms.etagOf(path),
            ).ok()
        }
        InventoryListenersQuiescence.await(jdbc)

        // A UH desativada e a que mudou de tipo não servem mais à reserva: a alteração das datas as solta.
        changeDeparture(first.id(), arrival.plusDays(3)).expect(200)["reservation"]["roomAssignment"].isNull shouldBe
            true
        changeDeparture(second.id(), arrival.plusDays(1)).expect(200)["reservation"]["roomAssignment"].isNull shouldBe
            true
        assignments(first.id()).single()["releaseReason"].asString() shouldBe "RESERVATION_CHANGED"
        assignments(second.id()).single()["releaseReason"].asString() shouldBe "RESERVATION_CHANGED"
    }

    // --- OOO: requer realocação e notificação (RN-07, H11) --------------------------------------------------------

    @Test
    fun `the mark follows every OOO hitting the stay - another one keeps it, with no new notice, until none hits`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(40))
        val start = today.plusDays(20)
        val reservation = created(type, plan, start, start.plusDays(4))
        assign(reservation.id(), room).ok()
        val allocator = iam.createUserWith(TestGrant(definition(ReservationsPermissions.ASSIGNMENTS_MANAGE), ALL))

        val first =
            roomsApi.openOutOfOrder(
                RoomId(room),
                OutOfOrderKind.OUT_OF_ORDER,
                ooo(start.plusDays(1), start.plusDays(1)),
            )
        InventoryListenersQuiescence.await(jdbc)
        markOf(reservation.id()) shouldBe first.id.toString()
        val second =
            roomsApi.openOutOfOrder(
                RoomId(room),
                OutOfOrderKind.OUT_OF_ORDER,
                ooo(start.plusDays(3), start.plusDays(3)),
            )
        InventoryListenersQuiescence.await(jdbc)
        markOf(reservation.id()) shouldBe first.id.toString()
        notificationsOf(allocator, room).size shouldBe 1

        // O primeiro é liberado, mas o segundo ainda atinge a estadia: a marca fica e passa a citá-lo, sem notificar.
        roomsApi.releaseOutOfOrder(first.id, RoomStatusSource.MANUAL)
        InventoryListenersQuiescence.await(jdbc)
        markOf(reservation.id()) shouldBe second.id.toString()
        reallocationRequired().count { it["reservationId"].asString() == reservation.id().toString() } shouldBe 1
        notificationsOf(allocator, room).size shouldBe 1

        // Nenhum OOO atinge mais a estadia: a marca sai.
        roomsApi.releaseOutOfOrder(second.id, RoomStatusSource.MANUAL)
        InventoryListenersQuiescence.await(jdbc)
        markOf(reservation.id()) shouldBe null
        notificationsOf(allocator, room).size shouldBe 1
    }

    @Test
    @Suppress("LongMethod") // a marca, a lista, a notificação, a reentrega e a liberação
    fun `an OOO marks the assigned reservations it hits, lists them and notifies once only who can reallocate`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val room = roomIds.first()
        val plan = planFor(type, today.plusDays(40))
        val start = today.plusDays(12)
        val holderName = "Hóspede Sigiloso ${UuidV7.generate()}"
        val guest =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = holderName),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val hit = created(type, plan, start, start.plusDays(3), guest)
        val spared = created(type, plan, start.plusDays(6), start.plusDays(8))
        assign(hit.id(), room).ok()
        assign(spared.id(), room).ok()
        val allocator = iam.createUserWith(TestGrant(definition(ReservationsPermissions.ASSIGNMENTS_MANAGE), ALL))
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.RESERVATIONS_READ), ALL))

        val period =
            roomsApi.openOutOfOrder(
                RoomId(room),
                OutOfOrderKind.OUT_OF_ORDER,
                ooo(start.plusDays(2), start.plusDays(4)),
            )
        InventoryListenersQuiescence.await(jdbc)

        val marked = support.reservation(hit.id())["reservation"]["roomAssignment"]["reallocation"]
        marked["outOfOrderId"].asString() shouldBe period.id.toString()
        support.reservation(spared.id())["reservation"]["roomAssignment"]["reallocation"].isNull shouldBe true
        val listed = reallocationRequired().filter { it["assignment"]["roomId"].asString() == room.toString() }
        listed.map { it["reservationId"].asString() } shouldContainExactly listOf(hit.id().toString())
        listed.single()["assignment"]["roomNumber"].asString() shouldBe number(room)

        val code = hit["code"].asString()
        notificationsOf(allocator, room).map { it.second } shouldContainExactly
            listOf(
                "A UH ${number(room)} está em OOO de ${br(start.plusDays(2))} a ${br(start.plusDays(4))}: 1 reserva " +
                    "alocada nela precisa de outra UH ($code).",
            )
        notificationsOf(allocator, room).single().first shouldBe "Realocação necessária na UH ${number(room)}"
        notificationsOf(reader, room).shouldBeEmpty()
        notificationsOf(allocator, room).single().second shouldNotContain holderName

        // O evento reentregue (o listener chamado de novo) não marca nem notifica de novo.
        val direct = AopTestUtils.getTargetObject<ReallocationListener>(reallocationListener)
        repeat(2) { direct.on(changed(period, OutOfOrderAction.OPENED)) }
        notificationsOf(allocator, room).size shouldBe 1

        // A liberação do OOO tira a marca (o listener refaz a partir da RoomsApi).
        val releasedPeriod = roomsApi.releaseOutOfOrder(period.id, RoomStatusSource.MANUAL)
        InventoryListenersQuiescence.await(jdbc)
        support.reservation(hit.id())["reservation"]["roomAssignment"]["reallocation"].isNull shouldBe true
        reallocationRequired().none { it["assignment"]["roomId"].asString() == room.toString() } shouldBe true
        releasedPeriod.releasedOn shouldBe today
        notificationsOf(allocator, room).size shouldBe 1
    }

    @Test
    fun `an OOO on the date blocks a day use - out of the suggestions, refused, and marks the one already assigned`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val (room, other) = roomIds
        val plan = planFor(type, today.plusDays(40))
        val day = today.plusDays(25)
        val assigned = created(type, plan, day, day)
        assign(assigned.id(), room).ok()
        val allocator = iam.createUserWith(TestGrant(definition(ReservationsPermissions.ASSIGNMENTS_MANAGE), ALL))

        // O OOO bloqueia a data inteira: o day use já alocado nela fica marcado, com a notificação.
        val period = roomsApi.openOutOfOrder(RoomId(room), OutOfOrderKind.OUT_OF_ORDER, ooo(day, day))
        InventoryListenersQuiescence.await(jdbc)
        markOf(assigned.id()) shouldBe period.id.toString()
        notificationsOf(allocator, room).single().second shouldContain assigned["code"].asString()

        // Outro day use na data: a UH em OOO sai da sugestão e a alocação nela é recusada.
        val dayUse = created(type, plan, day, day)
        with(iam) { call(GET, "/api/v1/reservations/${dayUse.id()}/room-suggestions", support.admin).ok() }["items"]
            .values()
            .map { UUID.fromString(it["roomId"].asString()) } shouldContainExactly listOf(other)
        val problem = with(iam) { assign(dayUse.id(), room).problem(422, "ROOM_OUT_OF_ORDER") }
        problem["dates"].values().map { it.asString() } shouldContainExactly listOf(day.toString())
        assign(dayUse.id(), other).ok()
    }

    @Test
    fun `reallocating a marked reservation clears it from the list, and an OOS never marks`() {
        val (type, roomIds) = inventory.roomTypeWithRooms(2, today)
        val (room, other) = roomIds
        val plan = planFor(type, today.plusDays(40))
        val start = today.plusDays(15)
        val reservation = created(type, plan, start, start.plusDays(2))
        assign(reservation.id(), room).ok()

        roomsApi.openOutOfOrder(RoomId(room), OutOfOrderKind.OUT_OF_SERVICE, ooo(start, start))
        InventoryListenersQuiescence.await(jdbc)
        support.reservation(reservation.id())["reservation"]["roomAssignment"]["reallocation"].isNull shouldBe true

        roomsApi.openOutOfOrder(RoomId(room), OutOfOrderKind.OUT_OF_ORDER, ooo(start.plusDays(1), start.plusDays(1)))
        InventoryListenersQuiescence.await(jdbc)
        reallocationRequired().count { it["reservationId"].asString() == reservation.id().toString() } shouldBe 1

        assign(reservation.id(), other, reason = "OUT_OF_ORDER")
            .ok()["reservation"]["roomAssignment"]["reallocation"]
            .isNull shouldBe true
        reallocationRequired().none { it["reservationId"].asString() == reservation.id().toString() } shouldBe true
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    private fun markOf(reservation: UUID): String? =
        support
            .reservation(reservation)["reservation"]["roomAssignment"]["reallocation"]
            .takeUnless { it.isNull }
            ?.get("outOfOrderId")
            ?.asString()

    private fun planFor(
        type: UUID,
        until: LocalDate,
    ): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, until)
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        guest: UUID = support.guest(),
    ): JsonNode =
        support.created(
            support.booking(guest, support.reservation(type, plan, arrival, departure, adults = 1)),
        )["reservations"][0]

    private fun guestWithFloor(floor: String): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra =
                                    mapOf(
                                        "preferences" to mapOf("floor" to floor, "bed" to null, "pillow" to null),
                                    ),
                            ),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun room(
        type: UUID,
        floor: String,
        vararg features: UUID,
    ): UUID =
        with(iam) {
            UUID.fromString(
                call(
                    POST,
                    "/api/v1/rooms",
                    support.admin,
                    rooms.roomBody(type, featureIds = features.toList(), floor = floor),
                ).expectStatus(201)!!["id"]
                    .asString(),
            )
        }

    private fun assign(
        reservation: UUID,
        room: UUID,
        reason: String? = null,
        doNotMove: Boolean? = null,
        auth: RequestPostProcessor = support.admin,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/reservations/$reservation/assign-room",
            auth,
            mapOf("roomId" to room, "reason" to reason, "doNotMove" to doNotMove),
        )

    private fun unassign(reservation: UUID): MockHttpServletResponse =
        iam.call(POST, "/api/v1/reservations/$reservation/unassign-room", support.admin)

    private fun doNotMove(
        reservation: UUID,
        value: Boolean,
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/reservations/$reservation/do-not-move", support.admin, mapOf("doNotMove" to value))

    private fun MockHttpServletResponse.ok(): JsonNode = with(iam) { ok() }

    private fun assignments(reservation: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/reservations/$reservation/room-assignments", support.admin).ok() }["items"]
            .values()
            .toList()

    private fun reallocationRequired(): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val path =
                "/api/v1/room-assignments/reallocation-required?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty()
            val page = with(iam) { call(GET, path, support.admin).ok() }
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return items
    }

    private fun changeDeparture(
        id: UUID,
        departure: LocalDate,
    ): MockHttpServletResponse {
        val current = support.reservation(id)["reservation"]
        return support.change(id, support.changeBody(current, departure = departure))
    }

    private fun MockHttpServletResponse.expect(status: Int): JsonNode = with(support.billing) { expect(status) }

    private fun number(room: UUID): String = checkNotNull(roomsApi.room(RoomId(room))).number

    private fun br(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

    private fun ooo(
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = OutOfOrderRequest(startsOn, endsOn, "Infiltração", "Manutenção", HousekeepingStatus.DIRTY)

    private fun changed(
        period: OutOfOrderPeriod,
        action: OutOfOrderAction,
    ) = OutOfOrderChanged(
        outOfOrderId = period.id.value,
        roomId = period.roomId.value,
        kind = period.kind,
        action = action,
        startsOn = period.startsOn,
        endsOn = period.endsOn,
        releasedOn = period.releasedOn,
        blockedUntil = period.blockedUntil,
        previousStartsOn = null,
        previousEndsOn = null,
        version = period.version,
        changedAt = Instant.now(),
    )

    /** As notificações de realocação de [user] que citam a UH [room]: título e texto. */
    private fun notificationsOf(
        user: TestUser,
        room: UUID,
    ): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification WHERE recipient_id = :user " +
                    "AND type = 'reservations.reallocation-required' AND title = :title ORDER BY created_at",
            ).param("user", user.id.value)
            .param("title", "Realocação necessária na UH ${number(room)}")
            .query { rs, _ -> rs.getString("title") to rs.getString("body") }
            .list()

    private fun activeAssignments(room: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM reservations.room_assignment WHERE room_id = :room AND released_at IS NULL")
            .param("room", room)
            .query(Int::class.java)
            .single()

    /** Uma alocação gravada direto no banco pelo papel da aplicação, contornando a aplicação. */
    private fun insertAssignment(
        reservation: UUID,
        room: UUID,
        startsOn: LocalDate,
        endsOn: LocalDate,
        released: Boolean = false,
    ) = transactions.executeWithoutResult {
        jdbc
            .sql(
                """
                INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                    assigned_at, released_at, release_reason)
                VALUES (:id, :reservation, :room, :startsOn, :endsOn, 'INITIAL', now(),
                    CASE WHEN :released THEN now() END, CASE WHEN :released THEN 'UNASSIGNED' END)
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("reservation", reservation)
            .param("room", room)
            .param("startsOn", startsOn)
            .param("endsOn", endsOn)
            .param("released", released)
            .update()
    }

    /** As mensagens da cadeia de causas (a recusa no commit chega embrulhada pelo gerenciador de transações). */
    private fun Throwable.messages(): String =
        generateSequence(this) {
            it.cause
        }.mapNotNull { it.message }.joinToString(" | ")

    private fun DataAccessException.psql(): PSQLException? =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

    private fun DataAccessException.constraint(): String? = psql()?.serverErrorMessage?.constraint

    private fun DataAccessException.sqlState(): String? = psql()?.sqlState

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private companion object {
        const val ROUNDS = 5

        fun definition(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }
    }
}
