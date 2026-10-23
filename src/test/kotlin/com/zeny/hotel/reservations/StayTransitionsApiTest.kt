package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RestrictionsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PreconditionFailedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.function.Supplier

/**
 * As transições de hospedagem (M5 E01; SPEC §8.4, RF-REC-01, 04, 05 e 06, RN-04, RN-14, RN-24, H10, H16): o check-in
 * com os dados dos hóspedes e a comprovação do menor, o desfazer no mesmo dia, o check-out (no dia da chegada e
 * antecipado, inclusive do pick-up) que devolve o inventário e encerra a alocação na mesma transação, a troca de UH com
 * a tarifa mantida ou recalculada e o `EXCLUDE`, a alteração da saída do hospedado e os quatro eventos pelo outbox,
 * sem dado de hóspede. Cada teste cria os próprios tipos de UH e hóspedes (banco compartilhado); os que precisam de um
 * hospedado no meio da estadia avançam a data operacional (como o `BusinessDateTest`). O mesmo contexto dos outros
 * testes das reservas.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários das transições de ponta a ponta e as portas que usam
class StayTransitionsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val restrictions = RestrictionsTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Check-in -------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o check-in inteiro: RN-24, dados gravados, histórico, trilha, evento e rollback
    fun `a confirmed reservation checks in with the guests data and the proof of the minor, publishing GuestCheckedIn`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adultName = "Hóspede Adulto ${UuidV7.generate().toString().takeLast(8)}"
        val adult = guest(name = adultName)
        val minor = guest(birthDate = today.minusYears(10))
        val noBirthDate = guest(birthDate = null)
        val reservation = created(type, plan, today, today.plusDays(2), adult, adults = 3)
        val id = ReservationId(reservation.id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))

        // RN-24: o menor sem comprovação e o hóspede sem data de nascimento não entram; nada muda.
        refused { reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), plain(minor)))) }.let {
            it.code.value shouldBe "MINOR_AUTHORIZATION_REQUIRED"
            it.problemProperties["guestId"] shouldBe minor.toString()
        }
        refused { reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), plain(noBirthDate)))) }.code.value shouldBe
            "GUEST_BIRTH_DATE_REQUIRED"
        refused {
            reservationsApi.checkIn(
                id,
                CheckIn(listOf(primary(adult), proof(minor, responsible = noBirthDate), plain(noBirthDate))),
            )
        }.code.value shouldBe "GUEST_BIRTH_DATE_REQUIRED"
        support.status(id.value) shouldBe "CONFIRMED"
        val operator = iam.createUserWith(TestGrant(definition(ReservationsPermissions.RESERVATIONS_READ), ALL))

        scenario
            .stimulate(
                Supplier {
                    actingAs(operator.id) {
                        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), proof(minor, responsible = adult))))
                    }
                },
            ).andWaitForEventOfType(GuestCheckedIn::class.java)
            .matching { it.reservationId == id.value }
            .toArriveAndVerify { event ->
                event.roomId shouldBe rooms.single()
                event.roomTypeId shouldBe type
                event.guestCount shouldBe 2
                event.businessDate shouldBe today
                event.performedBy shouldBe operator.id.value
                event.version shouldBe 2
            }

        val detail = support.reservation(id.value)["reservation"]
        detail["status"].asString() shouldBe "IN_HOUSE"
        detail["checkIn"]["businessDate"].asString() shouldBe today.toString()
        detail["checkIn"]["by"].asString() shouldBe operator.id.value.toString()
        val guests = detail["guests"].values().associateBy { it["guestId"].asString() }
        guests.getValue(adult.toString())["travelReason"].asString() shouldBe "LAZER"
        guests.getValue(adult.toString())["transportMode"].asString() shouldBe "AUTOMOVEL"
        guests.getValue(adult.toString())["checkedInAt"].isNull shouldBe false
        val proof = guests.getValue(minor.toString())["minorAuthorization"]
        proof["proofType"].asString() shouldBe "PARENT_PRESENT"
        proof["responsibleGuestId"].asString() shouldBe adult.toString()
        proof["relationship"].asString() shouldBe "MAE"
        // Gravado no banco, com o tipo, o responsável e o parentesco.
        minorRow(id.value, minor) shouldBe Triple("PARENT_PRESENT", adult, "MAE")
        support.historyKinds(id.value) shouldContainExactly listOf("CHECK_IN")
        support.auditEntries(id.value).last()["action"] shouldBe "reservations.reservation.check-in"
        // Uma publicação, sem nome, documento nem os ids dos hóspedes.
        val published = support.lifecyclePublications(GuestCheckedIn::class.java, id.value, LISTENER).single()
        published shouldNotContain adultName
        published shouldNotContain adult.toString()
        published shouldNotContain minor.toString()
        published shouldContain "\"performedBy\":\"${operator.id.value}\""

        // A transação desfeita não grava nada nem publica pelo outbox (outra reserva, depois do cenário).
        val (otherType, otherRooms) = inventory.roomTypeWithRooms(1, today)
        val otherPlan = planFor(otherType)
        val other = ReservationId(created(otherType, otherPlan, today, today.plusDays(1), adult).id())
        reservationsApi.assignRoom(other, RoomId(otherRooms.single()))
        transactions.executeWithoutResult {
            reservationsApi.checkIn(other, CheckIn(listOf(primary(adult))))
            it.setRollbackOnly()
        }
        support.status(other.value) shouldBe "CONFIRMED"
        support.lifecyclePublications(GuestCheckedIn::class.java, other.value, LISTENER).shouldBeEmpty()
    }

    @Test
    fun `the check-in refuses a provisional, a cancelled, no room, a retyped or deactivated room and an early date`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val (otherType, _) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val cardPlan = support.plan(listOf(type), guarantee = "CARD")
        support.setPrices(cardPlan.id, listOf(type), today, today.plusDays(10))
        val adult = guest()
        val provisional = ReservationId(created(type, cardPlan.id, today, today.plusDays(1), adult).id())
        val cancelled = ReservationId(created(type, plan, today, today.plusDays(1), adult).id())
        with(support.billing) { support.cancel(cancelled.value).expect(200) }
        val unassigned = ReservationId(created(type, plan, today, today.plusDays(1), adult).id())
        val ahead = ReservationId(created(type, plan, today.plusDays(1), today.plusDays(2), adult).id())
        val checkIn = CheckIn(listOf(primary(adult)))

        refused { reservationsApi.checkIn(provisional, checkIn) }.let {
            it.code.value shouldBe "RESERVATION_TRANSITION_NOT_ALLOWED"
            it.problemProperties shouldBe mapOf("reservationStatus" to "PROVISIONAL", "action" to "CHECK_IN")
        }
        refused { reservationsApi.checkIn(cancelled, checkIn) }.code.value shouldBe "RESERVATION_TRANSITION_NOT_ALLOWED"
        refused { reservationsApi.checkIn(unassigned, checkIn) }.code.value shouldBe "ROOM_NOT_ASSIGNED"
        reservationsApi.assignRoom(ahead, RoomId(rooms[2]))
        refused { reservationsApi.checkIn(ahead, checkIn) }.code.value shouldBe "CHECK_IN_BEFORE_ARRIVAL"
        // A UH alocada que mudou de tipo ou foi desativada depois (Ajuste D 1 da M4 E10).
        reservationsApi.assignRoom(unassigned, RoomId(rooms[0]))
        retype(rooms[0], otherType)
        refused { reservationsApi.checkIn(unassigned, checkIn) }.code.value shouldBe "ROOM_TYPE_MISMATCH"
        retype(rooms[0], type)
        with(iam) { call(POST, "/api/v1/rooms/${rooms[0]}/deactivate", support.admin).ok() }
        refused { reservationsApi.checkIn(unassigned, checkIn) }.code.value shouldBe "ROOM_INACTIVE"
        // O formato: hóspede repetido, mais que a ocupação.
        refused {
            reservationsApi.checkIn(unassigned, CheckIn(listOf(primary(adult), plain(adult))))
        }.code.value shouldBe "CHECK_IN_INVALID"
        support.status(unassigned.value) shouldBe "CONFIRMED"
    }

    @Test
    fun `the check-in is undone on the same business date, keeping the room and the proof in the history, not later`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val minor = guest(birthDate = today.minusYears(8))
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), proof(minor, responsible = adult))))

        scenario
            .stimulate(Supplier { reservationsApi.undoCheckIn(id) })
            .andWaitForEventOfType(GuestCheckInUndone::class.java)
            .matching { it.reservationId == id.value }
            .toArriveAndVerify { event ->
                event.roomId shouldBe rooms.single()
                event.businessDate shouldBe today
                event.performedBy shouldBe null
            }

        val detail = support.reservation(id.value)["reservation"]
        detail["status"].asString() shouldBe "CONFIRMED"
        detail["checkIn"].isNull shouldBe true
        detail["roomAssignment"]["roomId"].asString() shouldBe rooms.single().toString()
        detail["guests"].values().forEach {
            it["minorAuthorization"].isNull shouldBe true
            it["checkedInAt"].isNull shouldBe true
        }
        minorRow(id.value, minor) shouldBe null
        // A comprovação fica no retrato de antes do histórico.
        val history = with(iam) { call(GET, "/api/v1/reservations/${id.value}/history", support.admin).ok() }
        val undo = history["items"].values().first()
        undo["kind"].asString() shouldBe "UNDO_CHECK_IN"
        undo["before"]["stayGuests"]
            .values()
            .single { it["guestId"].asString() == minor.toString() }["minorAuthorization"]["proofType"]
            .asString() shouldBe "PARENT_PRESENT"
        undo["after"]["checkIn"].isNull shouldBe true

        // De novo, e o check-in de ontem não é mais desfeito.
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), proof(minor, responsible = adult))))
        jdbc
            .sql("UPDATE reservations.reservation SET checked_in_on = checked_in_on - 1 WHERE id = :id")
            .param("id", id.value)
            .update()
        refused { reservationsApi.undoCheckIn(id) }.code.value shouldBe "UNDO_CHECK_IN_EXPIRED"
        support.status(id.value) shouldBe "IN_HOUSE"
        support.historyKinds(id.value) shouldContainExactly listOf("CHECK_IN", "UNDO_CHECK_IN", "CHECK_IN")
    }

    // --- Check-out ------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o check-out no dia da chegada: inventário, day use, alocação, evento e a UH livre
    fun `a check-out on the arrival day gives back every night, keeps the frozen first one and ends the room`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val reservation = created(type, plan, today, today.plusDays(3), adult)
        val id = ReservationId(reservation.id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        (0L..2L).forEach { inventory.row(type, today.plusDays(it))!!.sold shouldBe 1 }

        scenario
            .stimulate(Supplier { reservationsApi.checkOut(id) })
            .andWaitForEventOfType(GuestCheckedOut::class.java)
            .matching { it.reservationId == id.value }
            .toArriveAndVerify { event ->
                event.early shouldBe true
                event.previousDeparture shouldBe today.plusDays(3)
                event.departure shouldBe today
                event.roomId shouldBe rooms.single()
                event.businessDate shouldBe today
            }

        val detail = support.reservation(id.value)["reservation"]
        detail["status"].asString() shouldBe "CHECKED_OUT"
        detail["departure"].asString() shouldBe today.toString()
        detail["dayUse"].asBoolean() shouldBe true
        detail["roomAssignment"].isNull shouldBe true
        detail["checkOut"]["businessDate"].asString() shouldBe today.toString()
        // A noite da chegada fica com o preço congelado; as três unidades voltam ao inventário (o day use não toma).
        detail["nightlyRates"].values().map {
            it["date"].asString() to it["roomAmount"].asString()
        } shouldContainExactly
            listOf(today.toString() to "400.00")
        detail["total"].asString() shouldBe "400.00"
        (0L..2L).forEach { inventory.row(type, today.plusDays(it))!!.sold shouldBe 0 }
        detail["guests"].values().single()["checkedOutAt"].isNull shouldBe false
        // A alocação foi encerrada (motivo CHECKED_OUT) na mesma transação: a UH fica livre para outra reserva.
        val assignment = assignments(id.value).single()
        assignment["releaseReason"].asString() shouldBe "CHECKED_OUT"
        assignment["endsOn"].asString() shouldBe today.toString()
        val next = ReservationId(created(type, plan, today.plusDays(1), today.plusDays(3), guest()).id())
        reservationsApi
            .assignRoom(next, RoomId(rooms.single()))
            .roomAssignment!!
            .roomId.value shouldBe rooms.single()
        support.historyKinds(id.value) shouldContainExactly listOf("CHECK_IN", "CHECK_OUT")
        support.lifecyclePublications(GuestCheckedOut::class.java, id.value, LISTENER) shouldHaveSize 1
        // A encerrada não muda mais.
        refused { reservationsApi.checkOut(id) }.code.value shouldBe "RESERVATION_TRANSITION_NOT_ALLOWED"
        refused { reservationsApi.undoCheckIn(id) }.code.value shouldBe "RESERVATION_TRANSITION_NOT_ALLOWED"
    }

    @Test
    @Suppress("LongMethod") // dois check-outs antecipados no dia seguinte: o comum e o do pick-up
    fun `an early check-out mid-stay gives the next nights back to the inventory or the block, keeping prices`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val start = today
        val adult = guest()
        val common = ReservationId(created(type, plan, start, start.plusDays(3), adult).id())
        val blockId =
            UUID.fromString(
                support
                    .createdBlock(
                        support.blockBody(
                            plan,
                            start,
                            support.allocation(type, start, start.plusDays(2), 1),
                            holderGuest = adult,
                        ),
                    )["id"]
                    .asString(),
            )
        val picked =
            ReservationId(
                UUID.fromString(
                    with(support.billing) {
                        support.pickUp(blockId, support.blockReservation(type, start, start.plusDays(3))).expect(201)
                    }["reservations"][0]["id"].asString(),
                ),
            )
        listOf(common to rooms[0], picked to rooms[1]).forEach { (id, room) ->
            reservationsApi.assignRoom(id, RoomId(room))
            reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        }
        val tomorrow = advanceBusinessDate()

        reservationsApi.checkOut(common).let {
            it.departure shouldBe tomorrow
            it.nightlyRates.map { night -> night.date } shouldContainExactly listOf(start)
            it.nightlyRates
                .single()
                .roomAmount
                .toString() shouldBe "400.00"
        }
        // A noite passada fica vendida (passado); as seguintes voltam ao inventário.
        inventory.row(type, start)!!.sold shouldBe 2
        inventory.row(type, start.plusDays(1))!!.let {
            it.sold shouldBe 1
            it.blocked shouldBe 0
        }
        reservationsApi.checkOut(picked).departure shouldBe tomorrow
        // A do pick-up devolve as noites ao bloqueio ativo: o inventário passa de vendida a bloqueada.
        inventory.row(type, start.plusDays(1))!!.let {
            it.sold shouldBe 0
            it.blocked shouldBe 1
        }
        support.allocationRow(blockId, type, start.plusDays(1)) shouldBe Triple(1, 0, 0)
        support.allocationRow(blockId, type, start) shouldBe Triple(1, 1, 0)
        assignments(common.value).single()["endsOn"].asString() shouldBe tomorrow.toString()
        // A recontagem conta o encerrado como antes: nada a corrigir nas noites dele.
        reservationsApi.recountInventory(tomorrow, start.plusDays(2))
        inventory.row(type, start.plusDays(1))!!.sold shouldBe 0
        inventory.row(type, start.plusDays(2))!!.sold shouldBe 0
    }

    // --- Troca de UH ----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as duas trocas no dia seguinte: tarifa mantida e recalculada, com o evento
    fun `the room move ends the room on the business date and opens another, keeping or recalculating the tariff`(
        scenario: Scenario,
    ) {
        val (type, rooms) = inventory.roomTypeWithRooms(4, today)
        val plan = planFor(type)
        val start = today
        val adult = guest()
        val kept = ReservationId(created(type, plan, start, start.plusDays(3), adult).id())
        val recalculated = ReservationId(created(type, plan, start, start.plusDays(3), adult).id())
        listOf(kept to rooms[0], recalculated to rooms[1]).forEach { (id, room) ->
            reservationsApi.assignRoom(id, RoomId(room))
            reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        }
        // A tarifa muda depois da criação.
        support.setPrices(plan, listOf(type), start, start.plusDays(10), twoAdults = "550.00")
        val tomorrow = advanceBusinessDate()

        scenario
            .stimulate(
                Supplier {
                    reservationsApi.moveRoom(kept, RoomMove(RoomId(rooms[2]), RoomAssignmentReason.GUEST_REQUEST))
                },
            ).andWaitForEventOfType(RoomMoved::class.java)
            .matching { it.reservationId == kept.value }
            .toArriveAndVerify { event ->
                event.fromRoomId shouldBe rooms[0]
                event.toRoomId shouldBe rooms[2]
                event.businessDate shouldBe tomorrow
                event.reason shouldBe RoomAssignmentReason.GUEST_REQUEST
                event.tariff shouldBe RoomMoveTariff.KEEP
            }
        val keptDetail = support.reservation(kept.value)["reservation"]
        keptDetail["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("400.00", "400.00", "400.00")
        keptDetail["roomAssignment"]["roomId"].asString() shouldBe rooms[2].toString()
        keptDetail["roomAssignment"]["startsOn"].asString() shouldBe tomorrow.toString()
        keptDetail["roomAssignment"]["reason"].asString() shouldBe "GUEST_REQUEST"
        assignments(kept.value).map {
            Triple(
                it["roomId"].asString(),
                it["endsOn"].asString(),
                it["releaseReason"]
                    .takeUnless { r ->
                        r.isNull
                    }?.asString(),
            )
        } shouldContainExactly
            listOf(
                Triple(rooms[2].toString(), start.plusDays(3).toString(), null),
                Triple(rooms[0].toString(), tomorrow.toString(), "ROOM_MOVED"),
            )

        val moved =
            reservationsApi.moveRoom(
                recalculated,
                RoomMove(RoomId(rooms[3]), RoomAssignmentReason.ROOM_ISSUE, RoomMoveTariff.RECALCULATE),
            )
        // Só as noites restantes seguem a tarifa nova; a noite passada guarda a congelada (RN-14).
        moved.nightlyRates.map { it.date to it.roomAmount.toString() } shouldContainExactly
            listOf(start to "400.00", tomorrow to "550.00", start.plusDays(2) to "550.00")
        moved.total.toString() shouldBe "1500.00"
        support.historyKinds(recalculated.value) shouldContainExactly listOf("CHECK_IN", "ROOM_MOVE")
        // O inventário não muda (o mesmo tipo de UH).
        inventory.row(type, tomorrow)!!.sold shouldBe 2
    }

    @Test
    fun `a room move to a room taken in the remaining nights is 409, also by a direct insert, and nothing changes`() {
        val (type, rooms) = inventory.roomTypeWithRooms(4, today)
        val (_, otherRooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val id = ReservationId(created(type, plan, today, today.plusDays(3), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms[0]))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        val other = ReservationId(created(type, plan, today.plusDays(2), today.plusDays(4), guest()).id())
        reservationsApi.assignRoom(other, RoomId(rooms[1]))
        roomsApi.openOutOfOrder(RoomId(rooms[2]), OutOfOrderKind.OUT_OF_ORDER, ooo(today.plusDays(1)))
        val move = { room: UUID -> RoomMove(RoomId(room), RoomAssignmentReason.OPERATIONAL) }

        refused { reservationsApi.moveRoom(id, move(rooms[1])) }.code.value shouldBe "ROOM_ALREADY_ASSIGNED"
        refused { reservationsApi.moveRoom(id, move(rooms[0])) }.code.value shouldBe "ROOM_MOVE_SAME_ROOM"
        refused { reservationsApi.moveRoom(id, move(rooms[2])) }.code.value shouldBe "ROOM_OUT_OF_ORDER"
        refused { reservationsApi.moveRoom(id, move(otherRooms.single())) }.code.value shouldBe "ROOM_TYPE_MISMATCH"
        refused { reservationsApi.moveRoom(id, RoomMove(RoomId(rooms[3]), RoomAssignmentReason.INITIAL)) }
            .code.value shouldBe "ROOM_MOVE_INVALID"
        refused { reservationsApi.moveRoom(other, move(rooms[3])) }.code.value shouldBe
            "RESERVATION_TRANSITION_NOT_ALLOWED"
        // Nada mudou: a alocação ativa é a mesma, sem outra linha.
        assignments(id.value).single()["releaseReason"].isNull shouldBe true
        val intruder = ReservationId(created(type, plan, today.plusDays(1), today.plusDays(2), guest()).id())
        // O banco recusa a sobreposição na UH ocupada pelo hospedado mesmo contornando a aplicação (23P01).
        val violation =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc
                        .sql(
                            """
                            INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on,
                                reason, assigned_at)
                            VALUES (:id, :reservation, :room, :startsOn, :endsOn, 'INITIAL', now())
                            """.trimIndent(),
                        ).param("id", UuidV7.generate())
                        .param("reservation", intruder.value)
                        .param("room", rooms[0])
                        .param("startsOn", today.plusDays(1))
                        .param("endsOn", today.plusDays(2))
                        .update()
                }
            }
        val psql = generateSequence<Throwable>(violation) { it.cause }.filterIsInstance<PSQLException>().first()
        psql.sqlState shouldBe "23P01"
        psql.serverErrorMessage?.constraint shouldBe "room_assignment_overlap_ex"
    }

    // --- Alteração do hospedado -----------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // extensão, extensão recusada, redução e as recusas de campo
    fun `an in-house extension takes the inventory and extends the room, a reduction gives back, the rest is fixed`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val adult = guest()
        val reservation = created(type, plan, today, today.plusDays(2), adult)
        val id = reservation.id()
        reservationsApi.assignRoom(ReservationId(id), RoomId(rooms[0]))
        reservationsApi.checkIn(ReservationId(id), CheckIn(listOf(primary(adult))))
        // Outra reserva já está na mesma UH a partir da noite +3.
        val next = ReservationId(created(type, plan, today.plusDays(3), today.plusDays(5), guest()).id())
        reservationsApi.assignRoom(next, RoomId(rooms[0]))

        val extended =
            with(support.billing) {
                val current = support.reservation(id)["reservation"]
                support.change(id, support.changeBody(current, departure = today.plusDays(3))).expect(200)
            }["reservation"]
        extended["status"].asString() shouldBe "IN_HOUSE"
        extended["departure"].asString() shouldBe today.plusDays(3).toString()
        extended["roomAssignment"]["endsOn"].asString() shouldBe today.plusDays(3).toString()
        extended["nightlyRates"].values().map { it["date"].asString() } shouldContainExactly
            listOf(today, today.plusDays(1), today.plusDays(2)).map(LocalDate::toString)
        inventory.row(type, today.plusDays(2))!!.sold shouldBe 1

        // A UH ocupada por outra reserva na noite nova: 409 e nada muda (nem o inventário).
        with(support.billing) {
            val current = support.reservation(id)["reservation"]
            support
                .change(id, support.changeBody(current, departure = today.plusDays(4)))
                .problem(409, "ROOM_ALREADY_ASSIGNED")
            support
                .change(id, support.changeBody(current, arrival = today.minusDays(1)))
                .problem(409, "IN_HOUSE_STAY_FIXED")["fields"]
                .values()
                .map { it.asString() } shouldContainExactly listOf("arrival")
            support.change(id, support.changeBody(current, adults = 1)).problem(409, "IN_HOUSE_STAY_FIXED")
            support
                .change(
                    id,
                    support.changeBody(current, departure = today),
                ).problem(422, "IN_HOUSE_DEPARTURE_INVALID")
        }
        inventory.row(type, today.plusDays(3))!!.sold shouldBe 1
        support.reservation(id)["reservation"]["departure"].asString() shouldBe today.plusDays(3).toString()

        // A redução devolve as noites a partir da saída nova e encurta a UH.
        val reduced =
            with(support.billing) {
                val current = support.reservation(id)["reservation"]
                support.change(id, support.changeBody(current, departure = today.plusDays(1))).expect(200)
            }["reservation"]
        reduced["roomAssignment"]["endsOn"].asString() shouldBe today.plusDays(1).toString()
        reduced["nightlyRates"].size() shouldBe 1
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 0
        inventory.row(type, today.plusDays(2))!!.sold shouldBe 0
        support.historyKinds(id) shouldContainExactly listOf("CHECK_IN", "CHANGE", "CHANGE")
    }

    @Test
    fun `moving an in-house guest with a stale expected version is refused under the lock`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val adult = guest()
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms[0]))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        val version = support.reservation(id.value)["reservation"]["version"].asLong()
        val move = RoomMove(RoomId(rooms[1]), RoomAssignmentReason.GUEST_REQUEST)

        shouldThrow<PreconditionFailedException> {
            transactions.executeWithoutResult { reservationsApi.moveRoom(id, move, expectedVersion = version - 1) }
        }
        assignments(id.value).single()["roomId"].asString() shouldBe rooms[0].toString()
        support.lifecyclePublications(RoomMoved::class.java, id.value, LISTENER).shouldBeEmpty()

        transactions.executeWithoutResult { reservationsApi.moveRoom(id, move, expectedVersion = version) }
        assignments(id.value).map { it["roomId"].asString() }.first() shouldBe rooms[1].toString()
        support.reservation(id.value)["reservation"]["version"].asLong() shouldBe version + 1
    }

    @Test
    fun `checking out an in-house guest with a stale expected version is refused under the lock`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))
        val version = support.reservation(id.value)["reservation"]["version"].asLong()

        shouldThrow<PreconditionFailedException> {
            transactions.executeWithoutResult { reservationsApi.checkOut(id, expectedVersion = version - 1) }
        }
        // Nada mudou: hospedada, com a UH alocada, as noites vendidas e sem GuestCheckedOut.
        support.status(id.value) shouldBe "IN_HOUSE"
        assignments(id.value).single().let {
            it["roomId"].asString() shouldBe rooms.single().toString()
            it["releaseReason"].isNull shouldBe true
        }
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 1
        support.reservation(id.value)["reservation"]["version"].asLong() shouldBe version
        support.lifecyclePublications(GuestCheckedOut::class.java, id.value, LISTENER).shouldBeEmpty()

        transactions.executeWithoutResult { reservationsApi.checkOut(id, expectedVersion = version) }
        support.status(id.value) shouldBe "CHECKED_OUT"
        support.reservation(id.value)["reservation"]["version"].asLong() shouldBe version + 1
    }

    @Test
    fun `an in-house stay is shortened despite sale restrictions, an extension still checks them`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val id = created(type, plan, today, today.plusDays(4), adult).id()
        reservationsApi.assignRoom(ReservationId(id), RoomId(rooms[0]))
        reservationsApi.checkIn(ReservationId(id), CheckIn(listOf(primary(adult))))
        with(iam) {
            restrictions.update(plan, restrictions.body(from = today, set = mapOf("minStay" to 4))).ok()
            listOf(2L, 5L).forEach {
                restrictions
                    .update(
                        plan,
                        restrictions.body(
                            from = today.plusDays(it),
                            set =
                                mapOf("closedToDeparture" to true),
                        ),
                    ).ok()
            }
        }
        val frozen = support.reservation(id)["reservation"]["nightlyRates"].values().map { it["roomAmount"].asString() }

        // A extensão é uma venda: a estadia mínima das noites novas segue conferida.
        with(support.billing) {
            val current = support.reservation(id)["reservation"]
            support
                .change(id, support.changeBody(current, departure = today.plusDays(5)))
                .problem(422, "CLOSED_TO_DEPARTURE")
        }

        val reduced =
            with(support.billing) {
                val current = support.reservation(id)["reservation"]
                support.change(id, support.changeBody(current, departure = today.plusDays(2))).expect(200)
            }["reservation"]
        reduced["departure"].asString() shouldBe today.plusDays(2).toString()
        reduced["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly frozen.take(2)
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 1
        inventory.row(type, today.plusDays(2))!!.sold shouldBe 0
        inventory.row(type, today.plusDays(3))!!.sold shouldBe 0
    }

    // --- Alocação pela API pública, banco e GuestsMerged ----------------------------------------------------------

    @Test
    fun `assignRoom of the ReservationsApi assigns without permission, with the rules of the endpoint`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val (_, otherRooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val id = ReservationId(created(type, plan, today.plusDays(2), today.plusDays(4), adult).id())
        val taken = ReservationId(created(type, plan, today.plusDays(3), today.plusDays(5), guest()).id())
        reservationsApi.assignRoom(taken, RoomId(rooms[1]))

        val assigned = checkNotNull(reservationsApi.assignRoom(id, RoomId(rooms[0]), doNotMove = true).roomAssignment)
        assigned.roomId.value shouldBe rooms[0]
        assigned.doNotMove shouldBe true
        refused { reservationsApi.assignRoom(id, RoomId(otherRooms.single())) }.code.value shouldBe
            "ROOM_TYPE_MISMATCH"
        refused { reservationsApi.assignRoom(id, RoomId(rooms[1])) }.code.value shouldBe "ROOM_ASSIGNMENT_LOCKED"
        refused { reservationsApi.assignRoom(id, RoomId(UuidV7.generate())) }.code.value shouldBe "UNKNOWN_ROOM"
        // Sem ator (o sistema), auditada como a do endpoint.
        support.auditEntries(assigned.id.value).single().let {
            it["action"] shouldBe "reservations.room-assignment.assign"
            it["userId"] shouldBe null
        }
        // A hospedada não é alocada por aqui (a troca de UH).
        val inHouse = ReservationId(created(type, plan, today, today.plusDays(1), adult).id())
        reservationsApi.assignRoom(inHouse, RoomId(rooms[1]))
        reservationsApi.checkIn(inHouse, CheckIn(listOf(primary(adult))))
        refused { reservationsApi.assignRoom(inHouse, RoomId(rooms[0])) }.code.value shouldBe
            "ROOM_ASSIGNMENT_NOT_ALLOWED"
    }

    @Test
    fun `the database keeps an in-house stay with a room and a checked-out one without an active room`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))

        // Soltar a UH de uma hospedada, ou encerrá-la com a UH ativa, é recusado no commit (gatilhos adiados).
        commitRefusal {
            jdbc
                .sql(
                    "UPDATE reservations.room_assignment SET released_at = now(), release_reason = 'UNASSIGNED' " +
                        "WHERE reservation_id = :id AND released_at IS NULL",
                ).param("id", id.value)
                .update()
        } shouldContain "ficou sem UH alocada"
        commitRefusal {
            jdbc
                .sql(
                    "UPDATE reservations.reservation SET status = 'CHECKED_OUT', checked_out_on = checked_in_on, " +
                        "checked_out_at = now() WHERE id = :id",
                ).param("id", id.value)
                .update()
        } shouldContain "ainda tem UH alocada"
        support.status(id.value) shouldBe "IN_HOUSE"
    }

    @Test
    fun `a check-out or a room move in a rolled back transaction leaves nothing and publishes nothing`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val name = "Hóspede Encerrado ${UuidV7.generate().toString().takeLast(8)}"
        val adult = guest(name = name)
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms[0]))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult))))

        transactions.executeWithoutResult {
            reservationsApi.moveRoom(id, RoomMove(RoomId(rooms[1]), RoomAssignmentReason.GUEST_REQUEST))
            it.setRollbackOnly()
        }
        transactions.executeWithoutResult {
            reservationsApi.checkOut(id)
            it.setRollbackOnly()
        }
        support.status(id.value) shouldBe "IN_HOUSE"
        assignments(id.value).single()["roomId"].asString() shouldBe rooms[0].toString()
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 1
        support.lifecyclePublications(RoomMoved::class.java, id.value, LISTENER).shouldBeEmpty()
        support.lifecyclePublications(GuestCheckedOut::class.java, id.value, LISTENER).shouldBeEmpty()

        // Confirmados, cada um publica uma vez, sem nome nem o id do hóspede.
        reservationsApi.moveRoom(id, RoomMove(RoomId(rooms[1]), RoomAssignmentReason.GUEST_REQUEST))
        reservationsApi.checkOut(id)
        listOf(RoomMoved::class.java, GuestCheckedOut::class.java).forEach { type ->
            val published = support.lifecyclePublications(type, id.value, LISTENER).single()
            published shouldNotContain name
            published shouldNotContain adult.toString()
        }
    }

    @Test
    fun `an in-house stay past its departure is extended with the new nights priced, never the frozen past ones`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val start = today
        val adult = guest()
        val overdue = created(type, plan, start, start.plusDays(1), adult).id()
        val dayUse = created(type, plan, start, start, adult).id()
        listOf(overdue to rooms[0], dayUse to rooms[1]).forEach { (id, room) ->
            reservationsApi.assignRoom(ReservationId(id), RoomId(room))
            reservationsApi.checkIn(ReservationId(id), CheckIn(listOf(primary(adult))))
        }
        support.setPrices(plan, listOf(type), start, start.plusDays(PRICED_DAYS), twoAdults = "450.00")
        advanceBusinessDate()
        val now = advanceBusinessDate()
        now shouldBe start.plusDays(2)

        // A saída (start+1) já passou: a extensão precifica as noites novas, inclusive a passada (start+1), pela
        // tarifa atual, mantém a congelada (start) e toma o inventário só da data operacional em diante.
        val extended =
            with(support.billing) {
                val current = support.reservation(overdue)["reservation"]
                support.change(overdue, support.changeBody(current, departure = start.plusDays(3))).expect(200)
            }["reservation"]
        extended["nightlyRates"].values().map {
            it["date"].asString() to it["roomAmount"].asString()
        } shouldContainExactly
            listOf(start to "400.00", start.plusDays(1) to "450.00", now to "450.00").map {
                it.first.toString() to
                    it.second
            }
        extended["roomAssignment"]["endsOn"].asString() shouldBe start.plusDays(3).toString()
        inventory.row(type, now)!!.sold shouldBe 1
        // O day use já passado não vira pernoite: a noite congelada dele seria recalculada (nunca 500).
        with(support.billing) {
            val current = support.reservation(dayUse)["reservation"]
            support
                .change(dayUse, support.changeBody(current, departure = start.plusDays(3)))
                .problem(422, "IN_HOUSE_PAST_NIGHT_FIXED")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly listOf(start.toString())
        }
        support.reservation(dayUse)["reservation"]["departure"].asString() shouldBe start.toString()
    }

    @Test
    fun `a responsible merged into the minor itself drops the proof instead of failing the listener`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val minor = guest(birthDate = today.minusYears(9))
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult).id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), proof(minor, responsible = adult))))

        // Os dois perfis eram a mesma pessoa: o responsável é absorvido pelo menor.
        merge(survivor = minor, merged = adult)
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            support.reservation(id.value)["reservation"]["guests"].size() == 1
        }
        val guests = support.reservation(id.value)["reservation"]["guests"].values().toList()
        guests.single()["guestId"].asString() shouldBe minor.toString()
        guests.single()["primary"].asBoolean() shouldBe true
        guests.single()["minorAuthorization"].isNull shouldBe true
        minorRow(id.value, minor) shouldBe null
        // O listener concluiu (nenhuma publicação de GuestsMerged presa em falha).
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE listener_id LIKE '%GuestsMergedListener%' " +
                    "AND serialized_event LIKE :merged AND completion_date IS NULL",
            ).param("merged", "%$adult%")
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `GuestsMerged repoints the responsible of the minor proof, and the recount counts the in-house stay`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type)
        val adult = guest()
        val duplicate = guest()
        val minor = guest(birthDate = today.minusYears(12))
        val id = ReservationId(created(type, plan, today, today.plusDays(2), adult, adults = 2).id())
        reservationsApi.assignRoom(id, RoomId(rooms.single()))
        reservationsApi.checkIn(id, CheckIn(listOf(primary(adult), proof(minor, responsible = adult))))

        // O responsável é mesclado noutro perfil: a comprovação passa a citar o sobrevivente.
        merge(survivor = duplicate, merged = adult)
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { minorRow(id.value, minor)?.second == duplicate }
        support
            .reservation(
                id.value,
            )["reservation"]["guests"]
            .values()
            .map { it["guestId"].asString() } shouldContainExactly
            listOf(duplicate.toString(), minor.toString())

        reservationsApi.recountInventory(today, today.plusDays(1))
        inventory.row(type, today)!!.sold shouldBe 1
        inventory.row(type, today.plusDays(1))!!.sold shouldBe 1
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun merge(
        survivor: UUID,
        merged: UUID,
    ) = with(iam) {
        call(
            POST,
            "/api/v1/guests/$survivor/merge",
            asAdmin(),
            mapOf("mergedGuestId" to merged, "takeFromMerged" to emptyList<String>()),
            ifMatch = support.guests.etagOf("/api/v1/guests/$survivor"),
        ).ok()
    }

    private fun planFor(type: UUID): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return plan.id
    }

    @Suppress("LongParameterList") // a reserva, com padrões
    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        holder: UUID,
        adults: Int = 2,
    ): JsonNode =
        support.created(
            support.booking(holder, support.reservation(type, plan, arrival, departure, adults = adults)),
        )["reservations"][0]

    private fun guest(
        name: String = "Hóspede ${UuidV7.generate().toString().takeLast(8)}",
        birthDate: LocalDate? = LocalDate.of(1990, 5, 20),
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile = support.guests.profileBody(fullName = name, extra = mapOf("birthDate" to birthDate)),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun primary(guest: UUID) =
        CheckInGuest(GuestId(guest), primary = true, travelReason = "LAZER", transportMode = "AUTOMOVEL")

    private fun plain(guest: UUID) = CheckInGuest(GuestId(guest))

    private fun proof(
        minor: UUID,
        responsible: UUID,
    ) = CheckInGuest(
        GuestId(minor),
        minorAuthorization = MinorAuthorization(MinorProofType.PARENT_PRESENT, GuestId(responsible), "MAE"),
    )

    private fun refused(block: () -> Any?): DomainException = shouldThrow<DomainException> { block() }

    /** A comprovação gravada do menor: (tipo, responsável, parentesco); `null` sem comprovação. */
    private fun minorRow(
        reservation: UUID,
        minor: UUID,
    ): Triple<String, UUID, String>? =
        jdbc
            .sql(
                "SELECT minor_proof_type, minor_responsible_guest_id, minor_relationship " +
                    "FROM reservations.reservation_guest WHERE reservation_id = :reservation AND guest_id = :guest",
            ).param("reservation", reservation)
            .param("guest", minor)
            .query { rs, _ ->
                rs.getString("minor_proof_type")?.let {
                    Triple(
                        it,
                        rs.getObject("minor_responsible_guest_id", UUID::class.java),
                        rs.getString("minor_relationship"),
                    )
                }
            }.optional()
            .orElse(null)

    private fun assignments(reservation: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/reservations/$reservation/room-assignments", support.admin).ok() }["items"]
            .values()
            .toList()

    private fun retype(
        room: UUID,
        type: UUID,
    ) {
        val path = "/api/v1/rooms/$room"
        val current = with(iam) { call(GET, path, support.admin).ok() }
        with(iam) {
            call(
                PUT,
                path,
                support.admin,
                inventory.rooms.roomBody(type, number = current["number"].asString()),
                ifMatch = inventory.rooms.etagOf(path),
            ).ok()
        }
    }

    private fun ooo(date: LocalDate) =
        OutOfOrderRequest(date, date, "Infiltração", "Manutenção", HousekeepingStatus.DIRTY)

    /** Avança a data operacional um dia (como o fechamento do M6 fará) e devolve a nova. */
    private fun advanceBusinessDate(): LocalDate = hotelApi.advanceBusinessDate(today).current

    /** Roda [write] numa transação e devolve as mensagens da recusa no commit. */
    private fun commitRefusal(write: () -> Unit): String =
        generateSequence<Throwable>(
            shouldThrow<Exception> { transactions.executeWithoutResult { write() } },
        ) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    /** Roda [block] com o ator [userId] no contexto de segurança (fora do `MockMvc`). */
    private fun <T> actingAs(
        userId: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private companion object {
        const val LISTENER = "StayTransitionsProbeListener"
        const val PRICED_DAYS = 10L
        const val TIMEOUT_SECONDS = 15L

        fun definition(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }
    }
}
