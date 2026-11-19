package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.DAMAGES
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.actingAs
import com.zeny.hotel.housekeeping.application.DamageReportDraft
import com.zeny.hotel.housekeeping.application.HousekeepingDamages
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Ataque aos danos (M6 E06, lacunas das revisões de fatia): 403 antes de estado para a camareira dona, 428 e 412 por
 * HTTP nas três ações, isolamento `OWN`, a Governanta (ALL sem `damages.manage`) sem a reserva, código desativado,
 * revisão de dano já cobrado, custo estimado fora da faixa e o `record` sem a reserva na resposta.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, governança, reservas e billing
class HousekeepingDamageAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val damages: HousekeepingDamages,
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
    fun `the owner attendant gets 403 on review, charge and waive of her own damage, before 404 and 412`(): Unit =
        with(iam) {
            val owner = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val own = asTestUser(owner)
            val id = support.registered(own)
            listOf(
                "review" to support.reviewBody(support.createCode("CHARGE")),
                "charge" to mapOf("folioId" to UUID.randomUUID()),
                "waive" to mapOf("reason" to "Minha"),
            ).forEach { (action, body) ->
                withClue(action) {
                    support.act(id, action, own, body).problem(403, "PERMISSION_DENIED")
                    support.act(id, action, own, body, "\"999\"").problem(403, "PERMISSION_DENIED")
                    support.act(UUID.randomUUID(), action, own, body, "\"0\"").problem(403, "PERMISSION_DENIED")
                }
            }
            support.damage(id)["status"].asString() shouldBe "REPORTED"
        }

    @Test
    fun `review, charge and waive answer 428 without If-Match and 412 with a stale one, over HTTP`(): Unit =
        with(iam) {
            val id = support.registered()
            listOf(
                "review" to support.reviewBody(support.createCode("CHARGE")),
                "charge" to mapOf("folioId" to UUID.randomUUID()),
                "waive" to mapOf("reason" to "Cortesia"),
            ).forEach { (action, body) ->
                withClue(action) {
                    support.act(id, action, asAdmin(), body, null).status shouldBe 428
                    support.act(id, action, asAdmin(), body, "\"999\"").status shouldBe 412
                }
            }
            support.damage(id)["status"].asString() shouldBe "REPORTED"
            support.postingsOf(id).shouldBeEmpty()
        }

    @Test
    fun `an attendant sees only her own damages, the other one is 404 and out of the list`(): Unit =
        with(iam) {
            val a = asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
            val b = asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
            val ofA = support.registered(a)
            val ofB = support.registered(b)

            call(GET, "$DAMAGES/$ofA", a).ok()["id"].asString() shouldBe ofA.toString()
            call(GET, "$DAMAGES/$ofB", a).problem(404, "HOUSEKEEPING_DAMAGE_NOT_FOUND")
            val seenByA = housekeeping.rooms.listAll(DAMAGES, a).map { it["id"].asString() }
            seenByA.contains(ofA.toString()) shouldBe true
            seenByA.contains(ofB.toString()) shouldBe false
            // O mesmo 404 do inexistente.
            call(GET, "$DAMAGES/${UUID.randomUUID()}", a).problem(404, "HOUSEKEEPING_DAMAGE_NOT_FOUND")
        }

    @Test
    fun `the housekeeper reads every damage without the reservation, the folio or the posting`(): Unit =
        with(iam) {
            val room = occupiedRoom()
            val attendant = asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            val id = support.registered(attendant, support.body(roomId = room))

            support.damage(id)["reservationId"].isNull shouldBe false
            val seen = call(GET, "$DAMAGES/$id", housekeeper).ok()
            seen["reservationId"].isNull shouldBe true
            seen["folioId"]?.isNull ?: true shouldBe true
            seen["postingId"]?.isNull ?: true shouldBe true
            housekeeping.rooms
                .listAll(DAMAGES, housekeeper)
                .single { it["id"].asString() == id.toString() }["reservationId"]
                .isNull shouldBe true
        }

    @Test
    fun `a deactivated transaction code is refused on review with 409 and the damage stays reported`(): Unit =
        with(iam) {
            val id = support.registered()
            val code = support.createCode("CHARGE")
            call(org.springframework.http.HttpMethod.POST, "/api/v1/transaction-codes/$code/deactivate", asAdmin())
                .expectStatus(200)

            support
                .act(id, "review", asAdmin(), support.reviewBody(code))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID")
            support.damage(id)["status"].asString() shouldBe "REPORTED"
        }

    @Test
    fun `reviewing a charged damage is a 409 transition and changes nothing`(): Unit =
        with(iam) {
            val room = occupiedRoom()
            val id = support.registered(body = support.body(roomId = room))
            val reservation = UUID.fromString(support.damage(id)["reservationId"].asString())
            val folio =
                reservationsApi
                    .reservation(
                        com.zeny.hotel.reservations
                            .ReservationId(reservation),
                    ).shouldNotBeNull()
                    .folioId.value
            val code = support.createCode("CHARGE")
            support.act(id, "review", asAdmin(), support.reviewBody(code, "80.00", reservation)).ok()
            support.act(id, "charge", asAdmin(), mapOf("folioId" to folio)).ok()["status"].asString() shouldBe "CHARGED"

            support
                .act(id, "review", asAdmin(), support.reviewBody(support.createCode("CHARGE"), "999.00"))
                .problem(409, "HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED")
            val after = support.damage(id)
            after["finalAmount"].asString() shouldBe "80.00"
            after["transactionCodeId"].asString() shouldBe code.toString()
            support.postingsOf(id).size shouldBe 1
        }

    @Test
    fun `an estimated cost negative, above 99999,99 or with 3 decimals is a 400 and registers nothing`(): Unit =
        with(iam) {
            val room = housekeeping.createRoom()
            listOf("-0.01", "-1", "100000.00", "99999.991", "1.234").forEach { cost ->
                withClue(cost) {
                    support.register(asAdmin(), support.body(roomId = room, estimatedCost = cost)).status shouldBe 400
                }
            }
            support.register(asAdmin(), support.body(roomId = room, estimatedCost = "99999.99")).expectStatus(201)
            support.register(asAdmin(), support.body(roomId = room, estimatedCost = "0.00")).expectStatus(201)
        }

    @Test
    fun `record returns the damage without the reservation even when one is linked`() {
        val room = occupiedRoom()
        val view =
            actingAs(iam.adminId()) {
                damages.record(
                    DamageReportDraft(
                        id = DamageReportId.new(),
                        roomId = RoomId(room),
                        areaId = null,
                        description = "Dano via record",
                        estimatedCost = Money.of("10.00"),
                        occurredAt = Instant.now(),
                        reportedBy = UserId(iam.adminId()),
                        source = OccurrenceSource.OFFLINE,
                    ),
                )
            }

        view.reservationId shouldBe null
        view.folioId shouldBe null
        view.postingId shouldBe null
        support.damage(view.id.value)["reservationId"].isNull shouldBe false
    }

    private fun occupiedRoom(): UUID {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
        stays.inHouse(type, stays.planFor(type), rooms[0], hotel.currentBusinessDate().plusDays(2), "Hóspede Atk")
        return rooms[0]
    }
}
