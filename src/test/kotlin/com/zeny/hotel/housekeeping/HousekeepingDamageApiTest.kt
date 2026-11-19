package com.zeny.hotel.housekeeping

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.DAMAGES
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.actingAs
import com.zeny.hotel.housekeeping.application.DamageReportDraft
import com.zeny.hotel.housekeeping.application.DamageReportView
import com.zeny.hotel.housekeeping.application.HousekeepingDamages
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Danos (M6 E06, Task 2, D4; RF-GOV-09): o registro com o vínculo sugerido à reserva pelo instante do fato (Review
 * Focus 6), visível só a quem tem `housekeeping.damages.manage` (Review Focus 4), a revisão (valor, código e vínculo),
 * a cobrança única na conta da reserva sem taxa de serviço (Review Focus 1), a dispensa e o dano sem reserva (Review
 * Focus 2).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, governança, reservas e billing
class HousekeepingDamageApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val billing: BillingApi,
    @Autowired private val damages: HousekeepingDamages,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired staffApi: StaffApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = DamageReportTestSupport(iam, jdbc)
    private val housekeeping = support.housekeeping
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    @Test
    fun `a damage in an occupied room links the reservation, seen only by who manages damages`(): Unit =
        with(iam) {
            val occupied = occupiedRoom()
            val (room, reservation, guest) = occupied
            val guestName = occupied.guestName
            val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val reception = asTestUser(execution.userWithTemplate(RoleTemplate.RECEPTIONIST))
            val before = Instant.now()

            val response =
                support.register(asTestUser(attendant), support.body(roomId = room, description = "  Abajur  "))
            val damage = response.expectStatus(201)!!
            val id = damage["id"].asString()
            response.getHeader("Location") shouldBe "$DAMAGES/$id"
            damage["status"].asString() shouldBe "REPORTED"
            damage["source"].asString() shouldBe "MANUAL"
            damage["description"].asString() shouldBe "Abajur"
            damage["estimatedCost"].asString() shouldBe "120.00"
            damage["reportedBy"].asString() shouldBe attendant.id.toString()
            (Instant.parse(damage["occurredAt"].asString()) >= before.minusSeconds(1)) shouldBe true
            noGuestData(damage, guestName, reservation.value, guest)
            noGuestData(call(GET, "$DAMAGES/$id", asTestUser(attendant)).ok(), guestName, reservation.value, guest)
            listed(asTestUser(attendant), id).let { noGuestData(it, guestName, reservation.value, guest) }

            call(GET, "$DAMAGES/$id", reception).ok()["reservationId"].asString() shouldBe reservation.toString()
            listed(reception, id)["reservationId"].asString() shouldBe reservation.toString()

            // Sem damages.manage, as ações dão 403 antes do 404.
            support
                .act(UUID.randomUUID(), "waive", asTestUser(attendant), mapOf("reason" to "x"), "\"0\"")
                .problem(403, "PERMISSION_DENIED")

            housekeeping.auditActions(ENTITY, UUID.fromString(id)) shouldContainExactly listOf("$ENTITY.register")
            auditState(UUID.fromString(id), "$ENTITY.register") shouldNotContain "Abajur"
        }

    @Test
    fun `a fact before the check-in, before the arrival by room move, or from a checkout task does not link`(): Unit =
        with(iam) {
            val (type, rooms) = reservations.inventory.roomTypeWithRooms(2, hotel.currentBusinessDate())
            val plan = stays.planFor(type)
            val (moving, _) = stays.inHouse(type, plan, rooms[0], hotel.currentBusinessDate().plusDays(2))
            val checkedInAt =
                reservationsApi
                    .reservation(moving)
                    .shouldNotBeNull()
                    .checkIn
                    .shouldNotBeNull()
                    .at

            recorded(rooms[0], checkedInAt.minusSeconds(60)).linked() shouldBe null
            recorded(rooms[0], checkedInAt.plusMillis(1)).linked() shouldBe moving.value.toString()

            reservationsApi.moveRoom(moving, RoomMove(RoomId(rooms[1]), RoomAssignmentReason.GUEST_REQUEST))
            val movedAt =
                reservationsApi
                    .reservation(moving)
                    .shouldNotBeNull()
                    .roomAssignment
                    .shouldNotBeNull()
                    .assignedAt
            recorded(rooms[1], movedAt.minusMillis(1)).linked() shouldBe null
            recorded(rooms[1], movedAt.plusMillis(1)).linked() shouldBe moving.value.toString()
            recorded(rooms[1], Instant.now(), TaskSource.CHECKOUT).linked() shouldBe null
            recorded(rooms[1], Instant.now(), TaskSource.ROOM_MOVE).linked() shouldBe null

            // A revisão troca e retira o vínculo.
            val code = support.createCode("CHARGE")
            val (_, other) = occupiedRoom()
            val unlinked = recorded(rooms[1], movedAt.minusMillis(1)).id.value
            support
                .act(unlinked, "review", asAdmin(), support.reviewBody(code, reservationId = other.value))
                .ok()["reservationId"]
                .asString() shouldBe other.toString()
            support
                .act(unlinked, "review", asAdmin(), support.reviewBody(code, reservationId = null))
                .ok()["reservationId"]
                .isNull shouldBe true
        }

    @Test
    fun `charge needs a review, the code is a charge or penalty, and the final amount is positive`(): Unit =
        with(iam) {
            val (room, reservation) = occupiedRoom()
            val id = support.registered(body = support.body(roomId = room))
            val folio =
                reservationsApi
                    .reservation(reservation)
                    .shouldNotBeNull()
                    .folioId.value

            support
                .act(id, "charge", asAdmin(), mapOf("folioId" to folio))
                .problem(409, "HOUSEKEEPING_DAMAGE_NOT_REVIEWED")
            support
                .act(id, "review", asAdmin(), support.reviewBody(support.createCode("CREDIT")))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID")
            support
                .act(id, "review", asAdmin(), support.reviewBody(UUID.randomUUID()))
                .problem(400, "HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE")["errors"][0]
                .let {
                    it["field"].asString() shouldBe "transactionCodeId"
                    it["code"].asString() shouldBe "HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE"
                }
            // A reserva da troca: inexistente 400 com o campo; existente e não hospedada 409.
            val code = support.createCode("CHARGE")
            support
                .act(id, "review", asAdmin(), support.reviewBody(code, reservationId = UUID.randomUUID()))
                .problem(400, "HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION")["errors"][0]
                .let {
                    it["field"].asString() shouldBe "reservationId"
                    it["code"].asString() shouldBe "HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION"
                }
            val (type, _) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
            val (confirmed, _) = stays.reservation(type, stays.planFor(type))
            support
                .act(id, "review", asAdmin(), support.reviewBody(code, reservationId = confirmed.value))
                .problem(409, "HOUSEKEEPING_DAMAGE_RESERVATION_INVALID")
            // Sem damages.manage, o 403 vem antes do 400 de id inexistente (que depende de consulta).
            val attendant = asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
            support
                .act(id, "review", attendant, support.reviewBody(UUID.randomUUID(), reservationId = UUID.randomUUID()))
                .problem(403, "PERMISSION_DENIED")
            support
                .act(id, "charge", attendant, mapOf("folioId" to UUID.randomUUID()))
                .problem(403, "PERMISSION_DENIED")
            support.act(id, "review", asAdmin(), support.reviewBody(code, finalAmount = "0")).status shouldBe 400
            support.act(id, "review", asAdmin(), support.reviewBody(code, finalAmount = "1.001")).status shouldBe 400
            support.act(id, "review", asAdmin(), support.reviewBody(code, finalAmount = "100000.00")).status shouldBe
                400
            support.damage(id)["status"].asString() shouldBe "REPORTED"
            support.postingsOf(id).shouldBeEmpty()
        }

    @Test
    fun `a reviewed damage is charged once to the reservation folio, without service charge`(): Unit =
        with(iam) {
            val (room, reservation) = occupiedRoom()
            val number = roomsApi.room(RoomId(room)).shouldNotBeNull().number
            val manager = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
            val id = support.registered(body = support.body(roomId = room, description = "Espelho trincado"))
            val folio =
                reservationsApi
                    .reservation(reservation)
                    .shouldNotBeNull()
                    .folioId.value
            val code = support.createCode("CHARGE")

            val reviewed =
                support
                    .act(id, "review", asTestUser(manager), support.reviewBody(code, "150.00", reservation.value))
                    .ok()
            reviewed["status"].asString() shouldBe "REVIEWED"
            reviewed["finalAmount"].asString() shouldBe "150.00"
            reviewed["transactionCodeId"].asString() shouldBe code.toString()
            reviewed["reviewedBy"].asString() shouldBe manager.id.toString()

            val charged = support.act(id, "charge", asTestUser(manager), mapOf("folioId" to folio)).ok()
            charged["status"].asString() shouldBe "CHARGED"
            charged["folioId"].asString() shouldBe folio.toString()
            charged["resolvedBy"].asString() shouldBe manager.id.toString()

            val posting = support.postingsOf(id).single()
            posting["id"].toString() shouldBe charged["postingId"].asString()
            posting["folio_id"] shouldBe folio
            (posting["amount"] as BigDecimal).compareTo(BigDecimal("150.00")) shouldBe 0
            posting["transaction_code_id"] shouldBe code
            posting["description"] shouldBe "Dano na UH $number"
            posting["source"] shouldBe "MANUAL"
            support.serviceChargesOf(posting["id"] as UUID) shouldBe 0

            // A segunda cobrança: 409 de transição, sem lançar de novo.
            support
                .act(id, "charge", asAdmin(), mapOf("folioId" to folio))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED")["damageStatus"]
                .asString() shouldBe "CHARGED"
            support.postingsOf(id) shouldHaveSize 1
            housekeeping.auditActions(ENTITY, id) shouldContainExactly
                listOf("$ENTITY.register", "$ENTITY.review", "$ENTITY.charge")
            auditState(id, "$ENTITY.charge") shouldNotContain "Espelho"
        }

    @Test
    fun `waive records the reason, and a damage without reservation is only registered and waived`(): Unit =
        with(iam) {
            val code = support.createCode("PENALTY")
            val reported = support.registered()
            val waived = support.act(reported, "waive", asAdmin(), mapOf("reason" to " Desgaste natural ")).ok()
            waived["status"].asString() shouldBe "WAIVED"
            waived["waiverReason"].asString() shouldBe "Desgaste natural"
            waived["resolvedBy"].asString() shouldBe iam.adminId().toString()
            support
                .act(reported, "waive", asAdmin(), mapOf("reason" to "De novo"))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED")
            // A transição vem antes do 400 de id inexistente.
            support
                .act(reported, "review", asAdmin(), support.reviewBody(UUID.randomUUID()))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED")

            // Sem reserva (UH vazia ou área): só registro e dispensa.
            val area = housekeeping.rooms.createArea()
            val noReservation = support.registered(body = support.body(areaId = area))
            support.damage(noReservation)["reservationId"].isNull shouldBe true
            support.act(noReservation, "review", asAdmin(), support.reviewBody(code)).ok()
            support
                .act(noReservation, "charge", asAdmin(), mapOf("folioId" to UUID.randomUUID()))
                .problem(409, "HOUSEKEEPING_DAMAGE_NO_RESERVATION")
            support
                .act(
                    noReservation,
                    "waive",
                    asAdmin(),
                    mapOf("reason" to "Sem hóspede"),
                ).ok()["status"]
                .asString() shouldBe
                "WAIVED"
            support.postingsOf(noReservation).shouldBeEmpty()
            support.postingsOf(reported).shouldBeEmpty()
        }

    @Test
    fun `the charge folio must exist, be open and be of the linked reservation, and a reviewed one waives`(): Unit =
        with(iam) {
            val code = support.createCode("PENALTY")
            val (room, reservation) = occupiedRoom()
            val (_, other) = occupiedRoom()
            val linked = support.registered(body = support.body(roomId = room))
            support.act(linked, "review", asAdmin(), support.reviewBody(code, reservationId = reservation.value)).ok()
            val otherFolio =
                reservationsApi
                    .reservation(other)
                    .shouldNotBeNull()
                    .folioId.value
            val closed = billing.openFolio(NewFolio(FolioOwnerType.RESERVATION, reservation.value))
            billing.closeFolio(closed.id)
            listOf(otherFolio, closed.id.value).forEach { folio ->
                withClue(folio) {
                    support
                        .act(linked, "charge", asAdmin(), mapOf("folioId" to folio))
                        .problem(409, "HOUSEKEEPING_DAMAGE_FOLIO_INVALID")
                }
            }
            support
                .act(linked, "charge", asAdmin(), mapOf("folioId" to UUID.randomUUID()))
                .problem(400, "HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO")["errors"][0]
                .let {
                    it["field"].asString() shouldBe "folioId"
                    it["code"].asString() shouldBe "HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO"
                }
            support.damage(linked)["status"].asString() shouldBe "REVIEWED"
            support.postingsOf(linked).shouldBeEmpty()
            // Dispensa de REVIEWED.
            support.act(linked, "waive", asAdmin(), mapOf("reason" to "Cortesia")).ok()["status"].asString() shouldBe
                "WAIVED"
            support.postingsOf(linked).shouldBeEmpty()
        }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private data class Occupied(
        val room: UUID,
        val reservation: ReservationId,
        val guest: UUID,
        val guestName: String,
    )

    /** Uma UH nova ocupada por um check-in real, com o titular de nome único. */
    private fun occupiedRoom(): Occupied {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
        val name = "Hóspede Dano ${UUID.randomUUID().toString().takeLast(8)}"
        val (reservation, guest) =
            stays.inHouse(type, stays.planFor(type), rooms[0], hotel.currentBusinessDate().plusDays(2), name)
        return Occupied(rooms[0], reservation, guest, name)
    }

    /** Registra direto pelo caso de uso (a entrada do offline e do checklist), com o instante do fato [at]. */
    private fun recorded(
        room: UUID,
        at: Instant,
        taskSource: TaskSource? = null,
    ) = actingAs(iam.adminId()) {
        damages.record(
            DamageReportDraft(
                id = DamageReportId.new(),
                roomId = RoomId(room),
                areaId = null,
                description = "Dano registrado",
                estimatedCost = Money.of("10.00"),
                occurredAt = at,
                reportedBy = UserId(iam.adminId()),
                source = OccurrenceSource.OFFLINE,
                taskSource = taskSource,
            ),
        )
    }

    /** A reserva vinculada ao dano, como quem gere os danos a vê (o caso de uso não a devolve no registro). */
    private fun DamageReportView.linked(): String? =
        support.damage(id.value)["reservationId"].takeUnless { it.isNull }?.asString()

    private fun listed(
        auth: RequestPostProcessor,
        id: String,
    ): JsonNode = housekeeping.rooms.listAll(DAMAGES, auth).single { it["id"].asString() == id }

    private fun noGuestData(
        node: JsonNode,
        guestName: String,
        reservation: UUID,
        guest: UUID,
    ) {
        node["reservationId"].isNull shouldBe true
        val text = node.toString()
        text shouldNotContain guestName
        text shouldNotContain reservation.toString()
        text shouldNotContain guest.toString()
    }

    private fun auditState(
        id: UUID,
        action: String,
    ): String =
        jdbc
            .sql(
                "SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') FROM audit.audit_entry " +
                    "WHERE entity_type = :type AND entity_id = :id AND action = :action",
            ).param("type", ENTITY)
            .param("id", id.toString())
            .param("action", action)
            .query(String::class.java)
            .single()

    private companion object {
        const val ENTITY = "housekeeping.damage-report"
    }
}
