package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.CheckInRendezvousProbe
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.RoomStatusFailureProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A troca de UH do hospedado na recepção (M5 E05; RF-REC-04, RN-09, RN-14, ARCHITECTURE §6.1): a transação única (a
 * alocação nova, a origem Vaga e Suja, o destino ocupado com o mesmo número de hóspedes, `RoomMoved` e a trilha; a
 * falha forçada no fim, que não deixa nada), a tarifa mantida e a recalculada só nas noites restantes com a diferença
 * (D8, Review Focus 3), as recusas sem nada mudado, a corrida pela mesma UH com outra troca ou com um check-in (Review
 * Focus 1), o `If-Match` conferido com a reserva travada (D5, Review Focus 2), a idempotência e a permissão. O contexto
 * dos testes das reservas com o relógio mutável (o mesmo do `FrontdeskCheckOutApiTest`).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass") // os cenários da troca de ponta a ponta
class FrontdeskRoomMoveApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Sucesso --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length") // a troca inteira; o nome é o do plano
    fun `a move to a ready room of the same type leaves the origin vacant and dirty, the destination occupied and publishes RoomMoved`() {
        val stay = frontdesk.stay(rooms = 2)
        val (origin, destination) = stay.rooms
        val booked = stay.checkedIn()
        // O hóspede declarou dois na UH: o destino recebe o mesmo número.
        roomsApi.markOccupied(RoomId(origin), 2, RoomStatusSource.MANUAL)
        stay.ready(destination)
        val nights = nightlyAmounts(booked.id)

        val raw = moveRaw(booked.id, destination, reason = "GUEST_REQUEST")
        val response = with(billing) { raw.expect(200) }

        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        response["reservation"]["roomId"].asString() shouldBe destination.toString()
        raw.getHeader("ETag") shouldBe support.etag(booked.id)
        raw.getHeader("ETag") shouldBe "\"${response["reservation"]["version"].asLong()}\""
        response["fromRoom"].let {
            it["roomId"].asString() shouldBe origin.toString()
            it["occupancy"].asString() shouldBe "VACANT"
            it["housekeepingStatus"].asString() shouldBe "DIRTY"
            it["guestCount"].isNull shouldBe true
        }
        response["toRoom"].let {
            it["roomId"].asString() shouldBe destination.toString()
            it["occupancy"].asString() shouldBe "OCCUPIED"
            it["guestCount"].asInt() shouldBe 2
        }
        response["tariff"].asString() shouldBe "KEEP"
        response["remainingBefore"].asString() shouldBe "800.00"
        response["remainingAfter"].asString() shouldBe "800.00"
        response["difference"].asString() shouldBe "0.00"

        roomsApi.status(RoomId(origin))!!.let {
            it.occupancy.name shouldBe "VACANT"
            it.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        }
        roomsApi.status(RoomId(destination))!!.guestCount shouldBe 2
        assignments(booked.id) shouldContainExactly
            listOf(
                Assignment(origin, "INITIAL", "ROOM_MOVED"),
                Assignment(destination, "GUEST_REQUEST", null),
            )
        nightlyAmounts(booked.id) shouldBe nights
        support.historyKinds(booked.id) shouldContainExactly listOf("CHECK_IN", "ROOM_MOVE")
        support.lifecyclePublications(RoomMoved::class.java, booked.id, LISTENER) shouldHaveSize 1
        val trail = support.auditEntries(booked.id)
        trail.map { it["action"] }.filter { it in MOVE_ACTIONS } shouldContainExactly MOVE_ACTIONS
        trail.single { it["action"] == "frontdesk.stay.room-move" }["after"].toString().let { after ->
            withClue(after) {
                after.contains("\"fromRoomId\": \"$origin\"") shouldBe true
                after.contains("\"toRoomId\": \"$destination\"") shouldBe true
                after.contains("\"reason\": \"GUEST_REQUEST\"") shouldBe true
                after.contains("\"difference\": \"0.00\"") shouldBe true
            }
        }
    }

    @Test
    @Suppress("LongMethod") // o hospedado desde ontem, o preço novo e o que cada noite guarda
    fun `a recalculated move reprices only the remaining nights and returns the difference`() {
        val stay = frontdesk.stay(rooms = 2)
        val (origin, destination) = stay.rooms
        val yesterday = today.minusDays(1)
        val booked = stayingSinceYesterday(stay, origin)
        // A tarifa do tipo sobe depois da reserva.
        support.setPrices(stay.plan, listOf(stay.type), today, today.plusDays(PRICED_DAYS), twoAdults = "550.00")
        stay.ready(destination)

        val response = move(booked.id, destination, reason = "ROOM_ISSUE", tariff = "RECALCULATE").expect(200)

        response["tariff"].asString() shouldBe "RECALCULATE"
        response["remainingBefore"].asString() shouldBe "800.00"
        response["remainingAfter"].asString() shouldBe "1100.00"
        response["difference"].asString() shouldBe "300.00"
        // A noite passada guarda o preço congelado (RN-14); as restantes seguem a tarifa nova.
        nightlyAmounts(booked.id) shouldContainExactly
            listOf(yesterday to "400.00", today to "550.00", today.plusDays(1) to "550.00")
        support.reservation(booked.id)["reservation"]["total"].asString() shouldBe "1500.00"
        response["toRoom"]["guestCount"].asInt() shouldBe 2
    }

    // --- Recusas --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cada recusa e o que não pode mudar com ela
    fun `a move to a room that is not ready, of another type or the same room is refused with nothing changed`() {
        val stay = frontdesk.stay(rooms = 4)
        val origin = stay.rooms[0]
        val dirty = stay.rooms[1]
        val outOfOrder = stay.rooms[2]
        val taken = stay.rooms[3]
        val booked = stay.checkedIn()
        stay.ready(outOfOrder)
        roomsApi.openOutOfOrder(
            RoomId(outOfOrder),
            OutOfOrderKind.OUT_OF_ORDER,
            OutOfOrderRequest(today, today, "Infiltração", "Manutenção", HousekeepingStatus.DIRTY),
        )
        stay.ready(taken)
        stay.book(frontdesk.guest(), departure = today.plusDays(3), room = taken)
        val other = frontdesk.stay()
        other.ready()
        val rooms = listOf(origin, dirty, outOfOrder, taken, other.room)
        val before = snapshot(booked.id, rooms)

        move(booked.id, dirty).problem(409, "ROOM_NOT_READY").let {
            it["roomId"].asString() shouldBe dirty.toString()
            it["housekeepingStatus"].asString() shouldBe "DIRTY"
        }
        move(booked.id, outOfOrder).problem(409, "ROOM_NOT_READY")["outOfOrder"].asString() shouldBe "OUT_OF_ORDER"
        // Outro tipo de UH ainda não é aceito (D1).
        move(booked.id, other.room).problem(422, "ROOM_TYPE_MISMATCH")
        move(booked.id, origin).problem(422, "ROOM_MOVE_SAME_ROOM")
        move(booked.id, taken).problem(409, "ROOM_ALREADY_ASSIGNED")
        move(booked.id, taken, reason = "INITIAL").problem(400, "ROOM_MOVE_INVALID")
        move(booked.id, UuidV7.generate()).problem(400, "UNKNOWN_ROOM")["errors"][0]["field"].asString() shouldBe
            "roomId"

        snapshot(booked.id, rooms) shouldBe before
        support.historyKinds(booked.id) shouldContainExactly listOf("CHECK_IN")
        support.lifecyclePublications(RoomMoved::class.java, booked.id, LISTENER).shouldBeEmpty()
        support
            .auditEntries(booked.id)
            .map { it["action"] }
            .filter { it in MOVE_ACTIONS }
            .shouldBeEmpty()
    }

    @Test
    fun `a forced failure at the end leaves nothing`() {
        val stay = frontdesk.stay(rooms = 2)
        val (origin, destination) = stay.rooms
        val booked = stay.checkedIn()
        stay.ready(destination)
        val before = snapshot(booked.id, stay.rooms)
        RoomStatusFailureProbe.armed += destination
        try {
            move(booked.id, destination).problem(409, "TEST_FORCED_FAILURE")
        } finally {
            RoomStatusFailureProbe.armed -= destination
        }

        snapshot(booked.id, stay.rooms) shouldBe before
        roomsApi.status(RoomId(origin))!!.occupancy.name shouldBe "OCCUPIED"
        support.historyKinds(booked.id) shouldContainExactly listOf("CHECK_IN")
        support.lifecyclePublications(RoomMoved::class.java, booked.id, LISTENER).shouldBeEmpty()

        // Sem a falha, a mesma troca passa.
        move(booked.id, destination).expect(200)
        support.lifecyclePublications(RoomMoved::class.java, booked.id, LISTENER) shouldHaveSize 1
    }

    // --- Concorrência (Review Focus 1) ----------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as rodadas das duas corridas e o que o perdedor não pode deixar
    fun `two concurrent moves or a move and a check-in into the same room never both pass`() {
        repeat(RACE_ROUNDS) { round ->
            withClue("duas trocas, rodada $round") {
                // Um pernoite e um day use trocando para a mesma UH hoje: as alocações não conflitam (H5), a ocupação
                // sim. As travas da alocação enfileiram as duas trocas; a segunda vê a UH ocupada.
                val stay = frontdesk.stay(rooms = 3)
                val (first, second, target) = stay.rooms
                val overnight = stay.checkedIn(room = first)
                val dayUse = stay.checkedIn(departure = today, room = second)
                stay.ready(target)
                val etags = listOf(overnight, dayUse).associateWith { support.etag(it.id) }
                val start = CyclicBarrier(2)
                val results =
                    race(
                        listOf(overnight, dayUse).map { booked ->
                            {
                                start.await(CheckInRendezvousProbe.WAIT_SECONDS, TimeUnit.SECONDS)
                                moveRaw(booked.id, target, ifMatch = etags.getValue(booked))
                            }
                        },
                    )

                results.map { it.status }.sorted() shouldContainExactly listOf(200, 409)
                val lost = results.indexOfFirst { it.status == 409 }
                with(billing) { results[lost].problem(409, "ROOM_NOT_READY") }
                val (winner, loser) = if (lost == 0) dayUse to overnight else overnight to dayUse
                support.reservation(winner.id)["reservation"]["roomAssignment"]["roomId"].asString() shouldBe
                    target.toString()
                movedNothing(loser, if (lost == 0) first else second)
                roomsApi.status(RoomId(target))!!.occupancy.name shouldBe "OCCUPIED"
            }
        }
        repeat(RACE_ROUNDS) { round ->
            withClue("troca e check-in, rodada $round") {
                // O hospedado troca para a UH em que um day use de hoje faz o check-in: os dois passam a conferência
                // sem trava (o encontro no CheckInRendezvousProbe) e só um ocupa a UH.
                val stay = frontdesk.stay(rooms = 2)
                val (origin, target) = stay.rooms
                val moving = stay.checkedIn(room = origin)
                val holder = frontdesk.guest()
                val arriving = stay.book(holder, departure = today, room = target)
                stay.ready(target)
                val etag = support.etag(moving.id)
                CheckInRendezvousProbe.armed[target] = CyclicBarrier(2)
                val results =
                    try {
                        race(
                            listOf(
                                { moveRaw(moving.id, target, ifMatch = etag) },
                                { frontdesk.checkIn(arriving.id, holder).raw },
                            ),
                        )
                    } finally {
                        CheckInRendezvousProbe.armed.remove(target)
                    }

                results.map { it.status }.sorted() shouldContainExactly listOf(200, 409)
                with(billing) { results.single { it.status == 409 }.problem(409, "ROOM_NOT_READY") }
                if (results[0].status == 200) {
                    support.status(arriving.id) shouldBe "CONFIRMED"
                    support.historyKinds(arriving.id).shouldBeEmpty()
                    support.lifecyclePublications(GuestCheckedIn::class.java, arriving.id, LISTENER).shouldBeEmpty()
                    roomsApi.status(RoomId(origin))!!.occupancy.name shouldBe "VACANT"
                } else {
                    support.status(arriving.id) shouldBe "IN_HOUSE"
                    movedNothing(moving, origin)
                }
                roomsApi.status(RoomId(target))!!.guestCount shouldBe 1
            }
        }
    }

    // --- Concorrência otimista, idempotência e permissão ----------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cada cabeçalho, a repetição, a permissão e a situação
    fun `the move needs If-Match, an Idempotency-Key and its permission`() {
        val stay = frontdesk.stay(rooms = 2)
        val (origin, destination) = stay.rooms
        val booked = stay.checkedIn()
        stay.ready(destination)

        move(booked.id, destination, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
        // A reserva mudou depois da leitura do cliente: a versão velha é recusada com a reserva travada (D5).
        val read = support.etag(booked.id)
        val current = support.reservation(booked.id)["reservation"]
        with(billing) {
            support.change(booked.id, support.changeBody(current, specialRequests = "Andar alto")).expect(200)
        }
        move(booked.id, destination, ifMatch = read).problem(412, "PRECONDITION_FAILED")
        move(booked.id, destination, key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        val receptionist = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN))
        move(booked.id, destination, auth = iam.asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")
        assignments(booked.id).single().roomId shouldBe origin

        val operator = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_MOVE))
        val key = UuidV7.generate().toString()
        val etag = support.etag(booked.id)
        val first = move(booked.id, destination, auth = iam.asTestUser(operator), key = key, ifMatch = etag).expect(200)
        val replayed = moveRaw(booked.id, destination, auth = iam.asTestUser(operator), key = key, ifMatch = etag)
        replayed.getHeader("Idempotent-Replayed") shouldBe "true"
        with(billing) { replayed.expect(200) } shouldBe first
        support.historyKinds(booked.id).filter { it == "ROOM_MOVE" } shouldHaveSize 1
        support.auditEntries(booked.id).single { it["action"] == "frontdesk.stay.room-move" }["userId"] shouldBe
            operator.id.value

        val confirmed = stay.book(frontdesk.guest(), room = origin, departure = today.plusDays(4))
        move(confirmed.id, destination).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED").let {
            it["reservationStatus"].asString() shouldBe "CONFIRMED"
            it["action"].asString() shouldBe "MOVE_ROOM"
        }
        move(UuidV7.generate(), destination, ifMatch = "\"0\"").problem(404, "RESERVATION_NOT_FOUND")
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    @Suppress("LongParameterList") // o pedido e os cabeçalhos, com padrões
    private fun moveRaw(
        id: UUID,
        roomId: UUID,
        reason: String = "OPERATIONAL",
        tariff: String? = null,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String? = UuidV7.generate().toString(),
        ifMatch: String? = support.etag(id),
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/reservations/$id/room-moves",
            auth,
            mapOf("roomId" to roomId, "reason" to reason) + tariff?.let { mapOf("tariff" to it) }.orEmpty(),
            idempotencyKey = key,
            ifMatch = ifMatch,
        )

    @Suppress("LongParameterList") // o pedido e os cabeçalhos, com padrões
    private fun move(
        id: UUID,
        roomId: UUID,
        reason: String = "OPERATIONAL",
        tariff: String? = null,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String? = UuidV7.generate().toString(),
        ifMatch: String? = support.etag(id),
    ) = frontdesk.Response(moveRaw(id, roomId, reason, tariff, auth, key, ifMatch))

    /** Roda as [calls] ao mesmo tempo, cada uma numa thread, e devolve as respostas na ordem delas. */
    private fun race(calls: List<() -> MockHttpServletResponse>): List<MockHttpServletResponse> {
        val pool = Executors.newFixedThreadPool(calls.size)
        return try {
            calls
                .map { call -> pool.submit<MockHttpServletResponse> { call() } }
                .map { it.get(CheckInRendezvousProbe.WAIT_SECONDS * 2, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Uma reserva hospedada desde ontem na UH [room] (a API não cria um hospedado no passado), com dois na UH. */
    private fun stayingSinceYesterday(
        stay: FrontdeskTestSupport.Stay,
        room: UUID,
    ): FrontdeskTestSupport.Booked {
        val booked = stay.book(frontdesk.guest(), departure = today.plusDays(2), room = room)
        with(iam) { call(POST, "/api/v1/reservations/${booked.id}/unassign-room", asAdmin()).ok() }
        val yesterday = today.minusDays(1)
        // A noite de ontem, ao preço congelado da de hoje, e a chegada nela.
        jdbc
            .sql(
                """
                INSERT INTO reservations.nightly_rate (reservation_id, stay_date, rate_plan_id, calculated_room_amount,
                    room_amount, included_packages_amount, additional_packages_amount, amount, overridden,
                    discount_percent, packages)
                SELECT reservation_id, :yesterday, rate_plan_id, calculated_room_amount, room_amount,
                    included_packages_amount, additional_packages_amount, amount, overridden, discount_percent, packages
                FROM reservations.nightly_rate WHERE reservation_id = :id AND stay_date = :today
                """.trimIndent(),
            ).param("yesterday", yesterday)
            .param("today", today)
            .param("id", booked.id)
            .update() shouldBe 1
        jdbc
            .sql(
                "UPDATE reservations.reservation SET arrival = :yesterday, room_total = room_total + 400, " +
                    "total = total + 400 WHERE id = :id",
            ).param("yesterday", yesterday)
            .param("id", booked.id)
            .update() shouldBe 1
        support.forceInHouse(booked.id, room)
        jdbc
            .sql("UPDATE reservations.reservation_guest SET checked_in_at = now() WHERE reservation_id = :id")
            .param("id", booked.id)
            .update()
        roomsApi.markOccupied(RoomId(room), 2, RoomStatusSource.MANUAL)
        return booked
    }

    private data class Assignment(
        val roomId: UUID,
        val reason: String,
        val releaseReason: String?,
    )

    /** As alocações da reserva, da mais antiga à mais recente. */
    private fun assignments(reservation: UUID): List<Assignment> =
        jdbc
            .sql(
                "SELECT room_id, reason, release_reason FROM reservations.room_assignment " +
                    "WHERE reservation_id = :id ORDER BY assigned_at, id",
            ).param("id", reservation)
            .query { rs, _ ->
                Assignment(
                    rs.getObject("room_id", UUID::class.java),
                    rs.getString("reason"),
                    rs.getString("release_reason"),
                )
            }.list()

    /** As noites da reserva: a data e o valor. */
    private fun nightlyAmounts(reservation: UUID): List<Pair<LocalDate, String>> =
        support.reservation(reservation)["reservation"]["nightlyRates"].values().map {
            LocalDate.parse(it["date"].asString()) to it["amount"].asString()
        }

    /** O que uma troca recusada não pode mudar: as alocações, a versão da reserva e o status de cada UH. */
    private fun snapshot(
        reservation: UUID,
        rooms: List<UUID>,
    ): Triple<List<Assignment>, String, List<Pair<String, Long>>> =
        Triple(
            assignments(reservation),
            support.etag(reservation),
            rooms.map { room -> roomsApi.status(RoomId(room))!!.let { it.occupancy.name to it.version } },
        )

    /** O perdedor da corrida ficou como estava: na UH de origem ocupada, sem troca no histórico, trilha nem evento. */
    private fun movedNothing(
        booked: FrontdeskTestSupport.Booked,
        origin: UUID,
    ) {
        support.status(booked.id) shouldBe "IN_HOUSE"
        assignments(booked.id).single().let {
            it.roomId shouldBe origin
            it.releaseReason shouldBe null
        }
        roomsApi.status(RoomId(origin))!!.occupancy.name shouldBe "OCCUPIED"
        support.historyKinds(booked.id) shouldContainExactly listOf("CHECK_IN")
        support.lifecyclePublications(RoomMoved::class.java, booked.id, LISTENER).shouldBeEmpty()
        support
            .auditEntries(booked.id)
            .map { it["action"] }
            .filter { it in MOVE_ACTIONS }
            .shouldBeEmpty()
    }

    private companion object {
        const val LISTENER = "StayTransitionsProbeListener"
        const val PRICED_DAYS = 10L
        const val RACE_ROUNDS = 3
        val MOVE_ACTIONS = listOf("reservations.reservation.move-room", "frontdesk.stay.room-move")
    }
}
