package com.zeny.hotel.frontdesk

import com.zeny.hotel.files.TestImages
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationCreated
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.RoomStatusFailureProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * O walk-in da recepção (M5 E03; RF-REC-02, H24): a reserva criada (origem `FRONT_DESK`, preço calculado), a UH alocada
 * e o check-in da E02 numa transação só. O sucesso com a caução e um menor (a reserva `IN_HOUSE`, a UH Ocupada, a
 * unidade tomada, `ReservationCreated` e `GuestCheckedIn`), a repetição idempotente, e as recusas que não deixam nada:
 * sem unidade, UH não pronta, menor sem comprovação, a falha forçada depois da caução, a venda recusada, a garantia de
 * depósito, sem `Idempotency-Key` e sem as permissões. O mesmo contexto dos testes das reservas e do check-in.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass") // os cenários do walk-in de ponta a ponta
class FrontdeskWalkInApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Sucesso --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o walk-in inteiro: resposta, reserva, conta, inventário, UH, caução, eventos e repetição
    fun `a walk-in creates the reservation, allocates the room and checks in, all in one transaction`() {
        val stay = stay()
        val adultCpf = newCpf()
        val adult = guest(cpf = adultCpf)
        val minor = guest(birthDate = today.minusYears(16))
        val proof = minorPhoto(minor)
        stay.ready()
        val operator = operator()
        val key = UuidV7.generate().toString()
        val body =
            walkInBody(
                stay,
                listOf(primary(adult), written(minor, responsible = adult, attachment = proof)),
                preAuthorization = preAuthorization(),
            )

        val raw = walkInRaw(body, operator.auth, key)
        val response = with(billing) { raw.expect(201) }
        val reservationId = UUID.fromString(response["reservation"]["id"].asString())
        val bookingId = UUID.fromString(response["bookingId"].asString())
        raw.getHeader("Location") shouldBe "/api/v1/reservations/$reservationId"
        raw.getHeader("ETag") shouldBe "\"${response["reservation"]["version"].asLong()}\""

        response["holderGuestId"].asString() shouldBe adult.toString()
        response["total"].asString() shouldBe "800.00"
        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        response["reservation"]["arrival"].asString() shouldBe today.toString()
        response["reservation"]["roomId"].asString() shouldBe stay.room.toString()
        response["reservation"]["checkIn"]["by"].asString() shouldBe
            operator.user.id.value
                .toString()
        response["reservation"]["guests"]
            .values()
            .single { it["guestId"].asString() == "$minor" }["minorAuthorization"]["proofType"]
            .asString() shouldBe "WRITTEN_AUTHORIZATION"
        response["room"]["occupancy"].asString() shouldBe "OCCUPIED"
        response["room"]["guestCount"].asInt() shouldBe 2
        response["preAuthorization"]["status"].asString() shouldBe "ACTIVE"
        response["restrictionAlerts"].values().toList().shouldBeEmpty()
        response.toString() shouldNotContain adultCpf

        // A reserva agrupadora do balcão, o preço calculado (sem diária editada) e a conta aberta.
        val booking = with(iam) { call(GET, "/api/v1/bookings/$bookingId", iam.asAdmin()).ok() }
        booking["origin"].asString() shouldBe "FRONT_DESK"
        booking["channel"].asString() shouldBe "DIRECT"
        booking["createdBy"].asString() shouldBe
            operator.user.id.value
                .toString()
        val reservation = support.reservation(reservationId)["reservation"]
        reservation["nightlyRates"].values().forEach {
            it["overridden"].asBoolean() shouldBe false
            it["roomAmount"].asString() shouldBe it["calculatedRoomAmount"].asString()
            it["roomAmount"].asString() shouldBe "400.00"
        }
        val folio = UUID.fromString(reservation["folioId"].asString())
        folio(folio)["status"].asString() shouldBe "OPEN"
        preAuthorizations(folio) shouldHaveSize 1
        // Uma unidade tomada em cada noite e a UH ocupada.
        inventory.row(stay.type, today)!!.sold shouldBe 1
        inventory.row(stay.type, today.plusDays(1))!!.sold shouldBe 1
        roomsApi.status(RoomId(stay.room))!!.guestCount shouldBe 2
        assignmentsOf(stay.room) shouldBe 1
        support.historyKinds(reservationId) shouldContain "CHECK_IN"
        // A trilha: a ação do walk-in e as dos módulos de baixo, em nome do operador.
        val trail = support.auditEntries(reservationId)
        trail.map { it["action"] } shouldContainAll WALK_IN_ACTIONS
        trail.single { it["action"] == "frontdesk.stay.walk-in" }["userId"] shouldBe operator.user.id.value
        trail.joinToString { "${it["before"]} ${it["after"]}" } shouldNotContain adultCpf
        // `ReservationCreated` e `GuestCheckedIn` pelo outbox, uma vez cada.
        support.createdPublications(bookingId) shouldHaveSize 1
        support.lifecyclePublications(GuestCheckedIn::class.java, reservationId, LISTENER) shouldHaveSize 1

        // A repetição com a mesma chave devolve a guardada, sem nova reserva, unidade nem caução.
        val replayed = walkInRaw(body, operator.auth, key)
        replayed.getHeader("Idempotent-Replayed") shouldBe "true"
        with(billing) { replayed.expect(201) }["reservation"]["id"].asString() shouldBe reservationId.toString()
        support.bookingCount(stay.type) shouldBe 1
        inventory.row(stay.type, today)!!.sold shouldBe 1
        preAuthorizations(folio) shouldHaveSize 1
    }

    @Test
    fun `the holder defaults to the primary guest, a separate holder is alerted and a day use takes no unit`() {
        val stay = stay()
        val holder = guest()
        val adult = guest()
        with(iam) { call(POST, "/api/v1/guests/$holder/restrict", asAdmin(), mapOf("reason" to "Calote")).ok() }
        stay.ready()

        val response =
            walkIn(walkInBody(stay, listOf(primary(adult)), holder = holder, departure = today, adults = 1))
                .expect(201)

        response["holderGuestId"].asString() shouldBe holder.toString()
        response["restrictionAlerts"].values().map { it["guestId"].asString() } shouldBe listOf(holder.toString())
        response["reservation"]["dayUse"].asBoolean() shouldBe true
        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        inventory.row(stay.type, today)!!.sold shouldBe 0

        // Sem titular, o principal.
        val other = stay()
        other.ready()
        val principal = guest()
        walkIn(walkInBody(other, listOf(primary(principal)))).expect(201)["holderGuestId"].asString() shouldBe
            principal.toString()
    }

    @Test
    fun `a plan with a card guarantee is confirmed at the desk, a deposit guarantee is refused with nothing written`() {
        val card = stay(guarantee = "CARD")
        card.ready()
        val response = walkIn(walkInBody(card, listOf(primary(guest())))).expect(201)
        val reservationId = UUID.fromString(response["reservation"]["id"].asString())
        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        support.reservation(reservationId)["reservation"]["guarantee"]["method"].asString() shouldBe "CARD"
        support.historyKinds(reservationId) shouldBe listOf("CONFIRM", "CHECK_IN")

        val deposit = stay(guarantee = "DEPOSIT")
        deposit.ready()
        val before = snapshotOf(deposit)
        walkIn(walkInBody(deposit, listOf(primary(guest())))).problem(422, "DEPOSIT_INSUFFICIENT")
        before.shouldBeUnchanged()
    }

    // --- Nada fica gravado ----------------------------------------------------------------------------------------

    @Test
    fun `without a unit of the room type nothing is written`() {
        val stay = stay()
        stay.ready()
        // A única unidade do tipo já vendida a outra reserva, sem UH alocada.
        support.created(
            support.booking(guest(), support.reservation(stay.type, stay.plan, today, today.plusDays(2))),
        )
        inventory.row(stay.type, today)!!.sold shouldBe 1
        val before = snapshotOf(stay)

        walkIn(walkInBody(stay, listOf(primary(guest())), preAuthorization = preAuthorization()))
            .problem(409, "ROOM_TYPE_UNAVAILABLE")

        before.shouldBeUnchanged()
        inventory.row(stay.type, today)!!.sold shouldBe 1
    }

    @Test
    fun `a room that is not ready leaves no reservation, folio, unit, assignment or hold`() {
        val stay = stay()
        // A UH nasce suja.
        val before = snapshotOf(stay)

        val problem =
            walkIn(walkInBody(stay, listOf(primary(guest())), preAuthorization = preAuthorization()))
                .problem(409, "ROOM_NOT_READY")

        problem["housekeepingStatus"].asString() shouldBe "DIRTY"
        before.shouldBeUnchanged()
    }

    @Test
    fun `a minor without proof leaves nothing written`() {
        val stay = stay()
        stay.ready()
        val adult = guest()
        val minor = guest(birthDate = today.minusYears(16))
        val before = snapshotOf(stay)

        walkIn(walkInBody(stay, listOf(primary(adult), plain(minor)), preAuthorization = preAuthorization()))
            .problem(422, "MINOR_AUTHORIZATION_REQUIRED")

        before.shouldBeUnchanged()
    }

    @Test
    fun `a forced failure after the deposit hold leaves nothing written`() {
        val stay = stay()
        stay.ready()
        val before = snapshotOf(stay)
        RoomStatusFailureProbe.armed += stay.room
        try {
            walkIn(walkInBody(stay, listOf(primary(guest())), preAuthorization = preAuthorization()))
                .problem(409, "TEST_FORCED_FAILURE")
        } finally {
            RoomStatusFailureProbe.armed -= stay.room
        }

        before.shouldBeUnchanged()
        // Sem a falha, o mesmo walk-in passa.
        walkIn(walkInBody(stay, listOf(primary(guest())), preAuthorization = preAuthorization())).expect(201)
        support.bookingCount(stay.type) shouldBe 1
    }

    @Test
    fun `the sale is checked in the direct channel and the refusals name the walk-in fields, writing nothing`() {
        val website = stay(channels = listOf("WEBSITE"))
        website.ready()
        val before = snapshotOf(website)
        val refused =
            walkIn(walkInBody(website, listOf(primary(guest())))).problem(422, "SALES_CHANNEL_NOT_ALLOWED")
        refused["refusals"].values().map { it["code"].asString() } shouldContain "SALES_CHANNEL_NOT_ALLOWED"
        before.shouldBeUnchanged()

        val stay = stay()
        stay.ready()
        val unknownPlan = walkIn(walkInBody(stay, listOf(primary(guest())), plan = UuidV7.generate()))
        unknownPlan.problem(400, "UNKNOWN_RATE_PLAN")["errors"].values().map { it["field"].asString() } shouldBe
            listOf("ratePlanId")
        val unknownRoom = walkIn(walkInBody(stay, listOf(primary(guest())), room = UuidV7.generate()))
        unknownRoom.problem(400, "UNKNOWN_ROOM")["errors"].values().map { it["field"].asString() } shouldBe
            listOf("roomId")
        walkIn(walkInBody(stay, listOf(primary(guest())), departure = today.minusDays(1)))
            .problem(400, "STAY_INVALID")["errors"]
            .values()
            .map { it["field"].asString() } shouldBe listOf("departure")
        support.bookingCount(stay.type) shouldBe 0
    }

    @Test
    fun `the idempotency key and the three permissions are required, with nothing written`() {
        val stay = stay()
        stay.ready()
        val before = snapshotOf(stay)
        val body = walkInBody(stay, listOf(primary(guest())))

        Response(billing.call(POST, "/api/v1/walk-ins", iam.asAdmin(), body, idempotencyKey = null))
            .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        val withoutManage = ReservationsTestSupport.Operator(iam.createUserWith(checkIn(), assignments()))
        walkIn(body, withoutManage.auth).problem(403, "PERMISSION_DENIED")
        val withoutAssignments = ReservationsTestSupport.Operator(iam.createUserWith(checkIn(), manage()))
        walkIn(body, withoutAssignments.auth).problem(403, "PERMISSION_DENIED")
        val withoutCheckIn = ReservationsTestSupport.Operator(iam.createUserWith(manage(), assignments()))
        walkIn(body, withoutCheckIn.auth).problem(403, "PERMISSION_DENIED")
        before.shouldBeUnchanged()

        walkIn(body, operator().auth).expect(201)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Um tipo de UH novo com uma UH e um plano com preço de hoje em diante. */
    private inner class Stay(
        val type: UUID,
        val room: UUID,
        val plan: UUID,
    ) {
        /** A UH limpa e inspecionada (nasce suja). */
        fun ready() {
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
        }
    }

    private fun stay(
        guarantee: String = "NONE",
        channels: List<String> = listOf("DIRECT", "WEBSITE"),
    ): Stay {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = guarantee, channels = channels)
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return Stay(type, rooms.single(), plan.id)
    }

    /** O que um walk-in recusado não deixa: reserva, conta, unidade, alocação, caução, ocupação, evento, trilha. */
    private inner class Snapshot(
        val stay: Stay,
    ) {
        private val before = counts()

        fun counts(): Map<String, Int> =
            mapOf(
                "reservas do tipo" to support.bookingCount(stay.type),
                "unidades vendidas hoje" to inventory.row(stay.type, today)!!.sold,
                "contas de reserva" to support.reservationFolioCount(),
                "cauções" to count("SELECT count(*) FROM billing.card_pre_authorization"),
                "alocações da UH" to assignmentsOf(stay.room),
                "ReservationCreated do tipo" to createdOf(stay.type),
                "walk-ins na trilha" to
                    count("SELECT count(*) FROM audit.audit_entry WHERE action = 'frontdesk.stay.walk-in'"),
            )

        fun shouldBeUnchanged() {
            withClue("nada gravado pelo walk-in recusado") {
                counts() shouldBe before
                roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "VACANT"
            }
        }
    }

    private fun snapshotOf(stay: Stay) = Snapshot(stay)

    private fun count(sql: String): Int =
        jdbc
            .sql(sql)
            .query(Int::class.java)
            .single()

    private fun assignmentsOf(room: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM reservations.room_assignment WHERE room_id = :room")
            .param("room", room)
            .query(Int::class.java)
            .single()

    private fun createdOf(type: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :roomType",
            ).param("type", ReservationCreated::class.java.name)
            .param("roomType", "%\"roomTypeId\":\"$type\"%")
            .query(Int::class.java)
            .single()

    private fun guest(
        cpf: String = newCpf(),
        birthDate: LocalDate = LocalDate.of(1988, 3, 14),
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to birthDate, "nationality" to "BR"),
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                    ),
                )["id"]
                .asString(),
        )

    private fun checkInGuest(
        guest: UUID,
        primary: Boolean = false,
        minorAuthorization: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to minorAuthorization,
        )

    private fun primary(guest: UUID) = checkInGuest(guest, primary = true)

    private fun plain(guest: UUID) = checkInGuest(guest)

    private fun written(
        minor: UUID,
        responsible: UUID,
        attachment: UUID,
    ) = checkInGuest(
        minor,
        minorAuthorization =
            mapOf(
                "proofType" to "WRITTEN_AUTHORIZATION",
                "attachmentId" to attachment,
                "responsibleGuestId" to responsible,
                "relationship" to "PAI",
            ),
    )

    private fun preAuthorization(): Map<String, Any?> =
        mapOf(
            "amount" to "500.00",
            "expiresAt" to Instant.now().plus(5, ChronoUnit.DAYS),
            "authorizationCode" to "A1B2C3",
            "nsu" to "004512",
            "cardBrand" to "VISA",
            "cardLast4" to "1234",
        )

    @Suppress("LongParameterList") // o pedido inteiro, com padrões
    private fun walkInBody(
        stay: Stay,
        guests: List<Map<String, Any?>>,
        holder: UUID? = null,
        departure: LocalDate = today.plusDays(2),
        adults: Int = guests.size,
        preAuthorization: Map<String, Any?>? = null,
        plan: UUID = stay.plan,
        room: UUID = stay.room,
    ): Map<String, Any?> =
        mapOf(
            "holderGuestId" to holder,
            "roomId" to room,
            "ratePlanId" to plan,
            "departure" to departure,
            "adults" to adults,
            "childAges" to emptyList<Int>(),
            "segment" to "LEISURE",
            "guests" to guests,
            "preAuthorization" to preAuthorization,
        )

    private fun walkInRaw(
        body: Map<String, Any?>,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String = UuidV7.generate().toString(),
    ) = billing.call(POST, "/api/v1/walk-ins", auth, body, idempotencyKey = key)

    private fun walkIn(
        body: Map<String, Any?>,
        auth: RequestPostProcessor = iam.asAdmin(),
    ) = Response(walkInRaw(body, auth))

    /** A resposta com os atalhos do `BillingTestSupport`. */
    private inner class Response(
        val raw: MockHttpServletResponse,
    ) {
        fun expect(status: Int): JsonNode = with(billing) { raw.expect(status) }

        fun problem(
            status: Int,
            code: String,
        ): JsonNode = with(billing) { raw.problem(status, code) }
    }

    private fun folio(id: UUID): JsonNode = with(iam) { call(GET, "/api/v1/folios/$id", asAdmin()).ok() }

    private fun preAuthorizations(folio: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/folios/$folio/card-pre-authorizations", asAdmin()).ok() }["items"]
            .values()
            .toList()

    /** A foto da autorização de menor enviada e vinculada ao perfil [owner]. */
    private fun minorPhoto(owner: UUID): UUID {
        val response =
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "autorizacao.png", "image/png", TestImages.png(64, 48)))
                    part(MockPart("purpose", MINOR_AUTHORIZATION_PHOTO.toByteArray(Charsets.UTF_8)))
                    with(iam.asAdmin())
                }.andReturn()
                .response
        val file = UUID.fromString(with(iam) { response.expectStatus(201)!! }["id"].asString())
        with(iam) {
            call(POST, "/api/v1/guests/$owner/attachments", asAdmin(), mapOf("fileId" to file)).expectStatus(201)
        }
        return file
    }

    private fun operator() = ReservationsTestSupport.Operator(iam.createUserWith(checkIn(), manage(), assignments()))

    private fun checkIn() = grant(FrontdeskPermissions.STAYS_CHECK_IN)

    private fun manage() = grant(ReservationsPermissions.RESERVATIONS_MANAGE)

    private fun assignments() = grant(ReservationsPermissions.ASSIGNMENTS_MANAGE)

    private fun grant(code: PermissionCode): TestGrant {
        val definition =
            (FrontdeskPermissions.permissions + ReservationsPermissions.permissions).single { it.code == code }
        return TestGrant(definition, ALL)
    }

    private companion object {
        const val LISTENER = "StayTransitionsProbeListener"
        const val PRICED_DAYS = 10L
        const val MINOR_AUTHORIZATION_PHOTO = "guests.minor-authorization-photo"
        val WALK_IN_ACTIONS =
            listOf(
                "reservations.booking.create",
                "reservations.reservation.check-in",
                "frontdesk.stay.walk-in",
            )
    }
}
