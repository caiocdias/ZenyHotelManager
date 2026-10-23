package com.zeny.hotel.frontdesk

import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * O early check-in cobrado no check-in e no walk-in (M5 E05; RN-11, H13, decisão D6): lançado só pelo valor cotado
 * e ainda vigente, conferido antes de qualquer escrita, uma vez só na repetição com a mesma `Idempotency-Key`, mantido
 * quando o check-in é desfeito e, no walk-in, na mesma transação. Mais o walk-in com o id de um hóspede mesclado
 * (pendência da E03: aceito, com o sobrevivente em todo lugar). A política do hotel é compartilhada: cada teste a
 * devolve no fim.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class FrontdeskEarlyCheckInApiTest(
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
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    @Suppress("LongMethod") // cotado, sem o campo, valor mudado, não oferecido e o formato do valor
    fun `an early check-in is charged only as quoted and still current`() {
        val original = frontdesk.policy()
        try {
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "60.00")))
            val stay = frontdesk.stay(rooms = 3)

            // Cotado: lançado como cotado, na conta da reserva, no código da diária, origem MANUAL.
            val holder = frontdesk.guest()
            val quoted = stay.book(holder, room = stay.rooms[0])
            stay.ready(stay.rooms[0])
            val fee = quote(quoted.id)["earlyCheckIn"]["fee"].asString()
            fee shouldBe "60.00"
            val response = checkIn(quoted.id, holder, fee).expect(200)
            response["earlyCheckIn"]["amount"].asString() shouldBe "60.00"
            response["earlyCheckIn"]["folioId"].asString() shouldBe quoted.folio.toString()
            response["earlyCheckIn"]["transactionCodeId"].asString() shouldBe billing.codeId("ROOM_CHARGE").toString()
            response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
            support.postings(quoted.folio).single().let {
                it["key"] shouldBe "ROOM_CHARGE"
                it["source"] shouldBe "MANUAL"
                it["amount"] shouldBe "60.00"
                it["description"] shouldBe "Early check-in"
            }

            // Sem o campo, nada é lançado.
            val plainHolder = frontdesk.guest()
            val plain = stay.book(plainHolder, room = stay.rooms[1])
            stay.ready(stay.rooms[1])
            checkIn(plain.id, plainHolder).expect(200)["earlyCheckIn"].isNull shouldBe true
            support.postings(plain.folio).shouldBeEmpty()

            // A política mudou entre a cotação e o check-in: 409 com o valor vigente e nada gravado.
            val changedHolder = frontdesk.guest()
            val changed = stay.book(changedHolder, room = stay.rooms[2])
            stay.ready(stay.rooms[2])
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "70.00")))
            checkIn(changed.id, changedHolder, "60.00")
                .problem(409, "EARLY_LATE_FEE_CHANGED")["currentFee"]
                .asString() shouldBe "70.00"
            nothingWritten(changed, stay.rooms[2])

            // Sem early na política e o valor no pedido: 422.
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to null))
            checkIn(changed.id, changedHolder, "60.00").problem(422, "EARLY_LATE_NOT_OFFERED")
            nothingWritten(changed, stay.rooms[2])

            // O valor: positivo e com até duas casas.
            for (invalid in listOf("0.00", "-60.00", "60.001")) {
                withClue(invalid) {
                    checkIn(changed.id, changedHolder, invalid)
                        .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                        .asString() shouldBe "earlyCheckInFee"
                }
            }
            nothingWritten(changed, stay.rooms[2])
        } finally {
            frontdesk.putPolicy(original)
        }
    }

    @Test
    fun `a repeated check-in with the same key does not charge the early twice`() {
        val original = frontdesk.policy()
        try {
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "60.00")))
            val stay = frontdesk.stay()
            val holder = frontdesk.guest()
            val booked = stay.book(holder)
            stay.ready()
            val key = UuidV7.generate().toString()

            val firstBody = with(billing) { checkInRaw(booked.id, holder, "60.00", key).expect(200) }
            val secondBody = with(billing) { checkInRaw(booked.id, holder, "60.00", key).expect(200) }

            secondBody["earlyCheckIn"]["postingId"] shouldBe firstBody["earlyCheckIn"]["postingId"]
            support.postings(booked.folio).count { it["description"] == "Early check-in" } shouldBe 1
        } finally {
            frontdesk.putPolicy(original)
        }
    }

    @Test
    @Suppress("LongMethod") // o sucesso e as recusas, com os contadores do que não se grava
    fun `a walk-in charges the early check-in in the same transaction`() {
        val original = frontdesk.policy()
        try {
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "60.00")))
            val stay = frontdesk.stay(rooms = 2)
            stay.ready(stay.rooms[0])
            stay.ready(stay.rooms[1])

            // Valor velho: 409 e nenhuma reserva, conta, unidade, alocação ou lançamento.
            val before = counts(stay)
            walkIn(walkInBody(stay, stay.rooms[0], frontdesk.guest(), "50.00"))
                .problem(409, "EARLY_LATE_FEE_CHANGED")["currentFee"]
                .asString() shouldBe "60.00"
            withClue("nada gravado pelo walk-in recusado") { counts(stay) shouldBe before }

            // Sem early na política: 422 e nada gravado.
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to null))
            walkIn(walkInBody(stay, stay.rooms[0], frontdesk.guest(), "60.00")).problem(422, "EARLY_LATE_NOT_OFFERED")
            withClue("nada gravado pelo walk-in recusado") { counts(stay) shouldBe before }

            // Vigente: o early vai na resposta e um lançamento na conta da reserva criada.
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "60.00")))
            val response = walkIn(walkInBody(stay, stay.rooms[0], frontdesk.guest(), "60.00")).expect(201)
            val folio = UUID.fromString(response["reservation"]["folioId"].asString())
            response["earlyCheckIn"]["amount"].asString() shouldBe "60.00"
            response["earlyCheckIn"]["folioId"].asString() shouldBe folio.toString()
            response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
            support.postings(folio).single().let {
                it["key"] shouldBe "ROOM_CHARGE"
                it["source"] shouldBe "MANUAL"
                it["description"] shouldBe "Early check-in"
            }

            // Sem o campo, nada é lançado.
            val plain = walkIn(walkInBody(stay, stay.rooms[1], frontdesk.guest(), null)).expect(201)
            plain["earlyCheckIn"].isNull shouldBe true
            support.postings(UUID.fromString(plain["reservation"]["folioId"].asString())).shouldBeEmpty()
        } finally {
            frontdesk.putPolicy(original)
        }
    }

    @Test
    fun `undoing a check-in keeps the early posting`() {
        val original = frontdesk.policy()
        try {
            frontdesk.putPolicy(original + mapOf("earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "60.00")))
            val stay = frontdesk.stay()
            val holder = frontdesk.guest()
            val booked = stay.book(holder)
            stay.ready()
            checkIn(booked.id, holder, "60.00").expect(200)

            val undone =
                with(billing) {
                    call(
                        POST,
                        "/api/v1/reservations/${booked.id}/undo-check-in",
                        iam.asAdmin(),
                        idempotencyKey = null,
                    ).expect(200)
                }
            undone["reservation"]["status"].asString() shouldBe "CONFIRMED"
            support.postings(booked.folio).single()["description"] shouldBe "Early check-in"
        } finally {
            frontdesk.putPolicy(original)
        }
    }

    @Test
    @Suppress("LongMethod") // a mesclagem, o walk-in com o id antigo e o que ficou gravado
    fun `a walk-in with a merged guest id never leaves a partial stay`() {
        val stay = frontdesk.stay()
        stay.ready()
        // A mesclagem exige o mesmo CPF nos dois perfis (senão `409 GUEST_MERGE_DOCUMENT_CONFLICT`).
        val cpf = newCpf()
        val survivor = guestWith(cpf)
        val merged = guestWith(cpf)
        with(iam) {
            call(
                POST,
                "/api/v1/guests/$survivor/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged),
                ifMatch = support.guests.etagOf("/api/v1/guests/$survivor"),
            ).ok()
        }

        // Observado: o walk-in com o id antigo é aceito, com o sobrevivente como hóspede da estadia e titular.
        val response = walkIn(walkInBody(stay, stay.room, merged, null)).expect(201)
        response["holderGuestId"].asString() shouldBe survivor.toString()
        response["reservation"]["guests"]
            .values()
            .map { it["guestId"].asString() } shouldBe listOf(survivor.toString())
        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        response["room"]["occupancy"].asString() shouldBe "OCCUPIED"
        val reservationId = UUID.fromString(response["reservation"]["id"].asString())
        support
            .reservation(reservationId)["reservation"]["guests"]
            .values()
            .map { it["guestId"].asString() } shouldBe listOf(survivor.toString())
        with(iam) {
            call(
                GET,
                "/api/v1/bookings/${response["bookingId"].asString()}",
                asAdmin(),
            ).ok()["holderGuestId"].asString()
        } shouldBe survivor.toString()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** O que um check-in recusado não grava: a reserva continua confirmada, a UH vaga, sem lançamento nem caução. */
    private fun nothingWritten(
        booked: FrontdeskTestSupport.Booked,
        room: UUID,
    ) {
        support.status(booked.id) shouldBe "CONFIRMED"
        roomsApi.status(RoomId(room))!!.occupancy.name shouldBe "VACANT"
        support.postings(booked.folio).shouldBeEmpty()
        frontdesk.preAuthorizations(booked.folio).shouldBeEmpty()
    }

    private fun counts(stay: FrontdeskTestSupport.Stay): Map<String, Int> =
        mapOf(
            "reservas do tipo" to support.bookingCount(stay.type),
            "unidades vendidas hoje" to inventory.row(stay.type, today)!!.sold,
            "contas de reserva" to support.reservationFolioCount(),
            "lançamentos" to
                jdbc
                    .sql("SELECT count(*) FROM billing.posting")
                    .query(Int::class.java)
                    .single(),
            "alocações do tipo" to
                jdbc
                    .sql("SELECT count(*) FROM reservations.room_assignment WHERE room_id = ANY (:rooms)")
                    .param("rooms", stay.rooms.toTypedArray())
                    .query(Int::class.java)
                    .single(),
        )

    /** Um hóspede brasileiro adulto com o CPF [cpf] (os dados mínimos do check-in, H11). */
    private fun guestWith(cpf: String): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                    ),
                )["id"]
                .asString(),
        )

    private fun quote(id: UUID): JsonNode =
        with(iam) { call(GET, "/api/v1/reservations/$id/early-late-quote", asAdmin()).ok() }

    private fun guestBody(
        guest: UUID,
        fee: String?,
    ): Pair<List<Map<String, Any?>>, String?> =
        listOf(
            mapOf(
                "guestId" to guest,
                "primary" to true,
                "travelReason" to "LAZER",
                "transportMode" to "AUTOMOVEL",
            ),
        ) to fee

    private fun checkInRaw(
        id: UUID,
        guest: UUID,
        fee: String?,
        key: String = UuidV7.generate().toString(),
    ) = billing.call(
        POST,
        "/api/v1/reservations/$id/check-in",
        iam.asAdmin(),
        mapOf("guests" to guestBody(guest, fee).first, "earlyCheckInFee" to fee),
        idempotencyKey = key,
    )

    private fun checkIn(
        id: UUID,
        guest: UUID,
        fee: String? = null,
    ) = frontdesk.Response(checkInRaw(id, guest, fee))

    private fun walkInBody(
        stay: FrontdeskTestSupport.Stay,
        room: UUID,
        guest: UUID,
        fee: String?,
    ): Map<String, Any?> =
        mapOf(
            "roomId" to room,
            "ratePlanId" to stay.plan,
            "departure" to today.plusDays(2),
            "adults" to 1,
            "childAges" to emptyList<Int>(),
            "segment" to "LEISURE",
            "guests" to guestBody(guest, fee).first,
            "earlyCheckInFee" to fee,
        )

    private fun walkInRaw(body: Map<String, Any?>) =
        billing.call(POST, "/api/v1/walk-ins", iam.asAdmin(), body, idempotencyKey = UuidV7.generate().toString())

    private fun walkIn(body: Map<String, Any?>) = frontdesk.Response(walkInRaw(body))
}
