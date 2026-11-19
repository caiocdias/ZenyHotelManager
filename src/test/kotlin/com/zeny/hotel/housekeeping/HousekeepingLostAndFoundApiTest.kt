package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.LostAndFoundTestSupport.Companion.ITEMS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
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
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Achados e perdidos (M6 E06, Task 1, D2; RF-GOV-08): o registro online, a listagem com o escopo `OWN` da camareira e
 * o filtro `expired`, a devolução (a quem, quando e por quem), o descarte e a doação só do item vencido (pela data
 * operacional e o fuso do hotel), as recusas de transição (Review Focus 5) e a resposta à camareira sem dado de
 * hóspede nem o "a quem" (Review Focus 4).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, governança e reservas (UH ocupada)
class HousekeepingLostAndFoundApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired staffApi: StaffApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = LostAndFoundTestSupport(iam, jdbc)
    private val housekeeping = support.housekeeping
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    @Test
    fun `the attendant registers an item found in an occupied room, stored, without guest data in it`(): Unit =
        with(iam) {
            val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, hotel.currentBusinessDate())
            val guestName = "Hóspede Achado ${UUID.randomUUID().toString().takeLast(8)}"
            val (reservation, guest) =
                stays.inHouse(type, stays.planFor(type), rooms[0], hotel.currentBusinessDate().plusDays(2), guestName)
            val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val before = Instant.now()

            val response =
                support.register(
                    asTestUser(attendant),
                    support.body(roomId = rooms[0], description = "  Relógio dourado  ", storagePlace = "Gaveta 3"),
                )
            val item = response.expectStatus(201)!!
            val after = Instant.now()

            val id = item["id"].asString()
            response.getHeader("Location") shouldBe "/api/v1/lost-and-found-items/$id"
            support.etagHeader(response) shouldBe "\"0\""
            item["roomId"].asString() shouldBe rooms[0].toString()
            item["areaId"].isNull shouldBe true
            item["description"].asString() shouldBe "Relógio dourado"
            item["storagePlace"].asString() shouldBe "Gaveta 3"
            item["status"].asString() shouldBe "STORED"
            item["source"].asString() shouldBe "MANUAL"
            item["reportedBy"].asString() shouldBe attendant.id.toString()
            val foundAt = Instant.parse(item["foundAt"].asString())
            withClue("foundAt $foundAt") { (foundAt >= before.minusSeconds(1) && foundAt <= after) shouldBe true }
            item["expired"].asBoolean() shouldBe false
            noGuestData(item, guestName, reservation.value, guest)
            noGuestData(call(GET, "$ITEMS/$id", asTestUser(attendant)).ok(), guestName, reservation.value, guest)
            ownItems(asTestUser(attendant)).single { it["id"].asString() == id }.let {
                noGuestData(it, guestName, reservation.value, guest)
            }

            // A trilha: só o tamanho da descrição e do local de guarda, nunca o texto.
            housekeeping.auditActions(ENTITY, UUID.fromString(id)) shouldContainExactly listOf("$ENTITY.register")
            val state = auditState(UUID.fromString(id), "$ENTITY.register")
            state shouldNotContain "Relógio"
            state shouldNotContain "Gaveta"
        }

    @Test
    fun `an item is registered in an area, and the location is exactly one room or area that exists`(): Unit =
        with(iam) {
            val area = housekeeping.rooms.createArea()
            val item = support.register(asAdmin(), support.body(areaId = area)).expectStatus(201)!!
            item["areaId"].asString() shouldBe area.toString()
            item["roomId"].isNull shouldBe true

            val room = housekeeping.createRoom()
            support
                .register(asAdmin(), support.body(roomId = room, areaId = area))
                .problem(400, "HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID")
            support.register(asAdmin(), support.body()).problem(400, "HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID")
            support
                .register(asAdmin(), support.body(roomId = UUID.randomUUID()))
                .problem(400, "HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION")["errors"][0]["field"]
                .asString() shouldBe "roomId"
            support
                .register(asAdmin(), support.body(areaId = UUID.randomUUID()))
                .problem(400, "HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION")["errors"][0]["field"]
                .asString() shouldBe "areaId"
        }

    @Test
    fun `the description is required up to 500 characters and the storage place is optional up to 100`(): Unit =
        with(iam) {
            val room = housekeeping.createRoom()
            support.register(asAdmin(), support.body(roomId = room, description = "   ")).status shouldBe 400
            support.register(asAdmin(), support.body(roomId = room, description = null)).status shouldBe 400
            support
                .register(asAdmin(), support.body(roomId = room, description = "a".repeat(501)))
                .problem(400, "HOUSEKEEPING_DATA_INVALID")
            support
                .register(asAdmin(), support.body(roomId = room, description = "a\u0000b"))
                .problem(400, "HOUSEKEEPING_DATA_INVALID")
            support
                .register(asAdmin(), support.body(roomId = room, storagePlace = "b".repeat(101)))
                .problem(400, "HOUSEKEEPING_DATA_INVALID")

            support.register(asAdmin(), support.body(roomId = room, description = "😀".repeat(500))).expectStatus(201)
            val withoutPlace = support.register(asAdmin(), support.body(roomId = room, storagePlace = "  "))
            withoutPlace.expectStatus(201)!!["storagePlace"].isNull shouldBe true
        }

    @Test
    fun `with OWN the attendant lists and reads only her own items, with ALL the reception lists all`(): Unit =
        with(iam) {
            val mine = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val other = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val reception = asTestUser(execution.userWithTemplate(RoleTemplate.RECEPTIONIST))
            val myItem = support.registered(asTestUser(mine)).toString()
            val otherItem = support.registered(asTestUser(other)).toString()

            val myIds = ownItems(asTestUser(mine)).map { it["id"].asString() }
            myIds shouldContain myItem
            myIds shouldNotContain otherItem
            call(GET, "$ITEMS/$myItem", asTestUser(mine)).ok()["id"].asString() shouldBe myItem
            call(GET, "$ITEMS/$otherItem", asTestUser(mine)).problem(404, "HOUSEKEEPING_LOST_FOUND_NOT_FOUND")

            val all = ownItems(reception).map { it["id"].asString() }
            all shouldContain myItem
            all shouldContain otherItem
            call(GET, "$ITEMS/$otherItem", reception).ok()["id"].asString() shouldBe otherItem
            call(GET, "$ITEMS/${UUID.randomUUID()}", reception).problem(404, "HOUSEKEEPING_LOST_FOUND_NOT_FOUND")
        }

    @Test
    fun `the return records to whom, when and by whom, visible only to who manages, and out of the trail`(): Unit =
        with(iam) {
            val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val receptionist = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
            val id = support.registered(asTestUser(attendant))
            val before = Instant.now()

            val response = support.act(id, "return", asTestUser(receptionist), mapOf("returnedTo" to " Maria Souza "))
            val returned = response.ok()
            support.etagHeader(response) shouldBe "\"1\""
            returned["status"].asString() shouldBe "RETURNED"
            returned["returnedTo"].asString() shouldBe "Maria Souza"
            returned["resolvedBy"].asString() shouldBe receptionist.id.toString()
            (Instant.parse(returned["resolvedAt"].asString()) >= before.minusSeconds(1)) shouldBe true
            returned["disposalReason"].isNull shouldBe true
            returned["expired"].asBoolean() shouldBe false

            // A camareira dona vê o item devolvido, mas não a quem (Review Focus 4).
            val seen = call(GET, "$ITEMS/$id", asTestUser(attendant)).ok()
            seen["status"].asString() shouldBe "RETURNED"
            seen["returnedTo"].isNull shouldBe true
            seen.toString() shouldNotContain "Maria"
            ownItems(asTestUser(attendant)).single { it["id"].asString() == id.toString() }.toString() shouldNotContain
                "Maria"

            housekeeping.auditActions(ENTITY, id) shouldContainExactly listOf("$ENTITY.register", "$ENTITY.return")
            auditState(id, "$ENTITY.return") shouldNotContain "Maria"
        }

    @Test
    fun `a returned or disposed item refuses a new destination with 409 and does not change`(): Unit =
        with(iam) {
            val returned = support.registered()
            support.act(returned, "return", asAdmin(), mapOf("returnedTo" to "João")).ok()
            val discarded = expiredItem()
            support.act(discarded, "discard", asAdmin(), mapOf("reason" to "Prazo vencido")).ok()
            val donated = expiredItem()
            support.act(donated, "donate", asAdmin(), mapOf("reason" to "Bazar")).ok()
            support.foundAt(returned, expiredFoundAt())

            listOf(returned, discarded, donated).forEach { id ->
                val before = support.item(id)
                listOf(
                    "return" to mapOf("returnedTo" to "Outra pessoa"),
                    "discard" to mapOf("reason" to "Outro motivo"),
                    "donate" to mapOf("reason" to "Outro motivo"),
                ).forEach { (action, body) ->
                    withClue("$id $action") {
                        val problem =
                            support
                                .act(id, action, asAdmin(), body)
                                .problem(409, "HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED")
                        problem["itemStatus"].asString() shouldBe before["status"].asString()
                    }
                }
                support.item(id) shouldBe before
            }
        }

    @Test
    fun `discard and donate of an expired item record the reason and the user`(): Unit =
        with(iam) {
            val manager = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
            val discarded = expiredItem()
            support.item(discarded)["expired"].asBoolean() shouldBe true
            val response = support.act(discarded, "discard", asTestUser(manager), mapOf("reason" to " Prazo vencido "))
            val item = response.ok()
            item["status"].asString() shouldBe "DISCARDED"
            item["disposalReason"].asString() shouldBe "Prazo vencido"
            item["resolvedBy"].asString() shouldBe manager.id.toString()
            item["resolvedAt"].isNull shouldBe false
            item["returnedTo"].isNull shouldBe true
            item["expired"].asBoolean() shouldBe false

            val donated = expiredItem()
            val gift = support.act(donated, "donate", asTestUser(manager), mapOf("reason" to "Doado ao bazar")).ok()
            gift["status"].asString() shouldBe "DONATED"
            gift["disposalReason"].asString() shouldBe "Doado ao bazar"
            gift["resolvedBy"].asString() shouldBe manager.id.toString()

            housekeeping.auditActions(ENTITY, discarded) shouldContainExactly
                listOf("$ENTITY.register", "$ENTITY.discard")
            housekeeping.auditActions(ENTITY, donated) shouldContainExactly listOf("$ENTITY.register", "$ENTITY.donate")
            auditState(discarded, "$ENTITY.discard") shouldNotContain "Prazo"

            // O motivo é obrigatório.
            val third = expiredItem()
            support.act(third, "discard", asAdmin(), mapOf("reason" to "  ")).status shouldBe 400
            support.act(third, "donate", asAdmin(), mapOf<String, Any?>("reason" to null)).status shouldBe 400
            support.item(third)["status"].asString() shouldBe "STORED"
        }

    @Test
    fun `discard or donate before the retention deadline is 409 NOT_EXPIRED, and the item stays stored`(): Unit =
        with(iam) {
            val fresh = support.registered()
            support.act(fresh, "discard", asAdmin(), mapOf("reason" to "Cedo")).problem(
                409,
                "HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED",
            )
            support.act(fresh, "donate", asAdmin(), mapOf("reason" to "Cedo")).problem(
                409,
                "HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED",
            )
            support.item(fresh)["status"].asString() shouldBe "STORED"
            support.etag(fresh) shouldBe "\"0\""
            // A devolução vale a qualquer tempo.
            support.act(fresh, "return", asAdmin(), mapOf("returnedTo" to "Dono")).ok()["status"].asString() shouldBe
                "RETURNED"
        }

    @Test
    fun `expiry is by the local date found in the hotel timezone plus the retention against the business date`(): Unit =
        with(iam) {
            val zone = hotel.timezone()
            val retention = hotel.operationalSettings().lostAndFoundRetentionDays.toLong()
            val lastExpiredDay = hotel.currentBusinessDate().minusDays(retention)
            // O último instante do último dia vencido (hora local) e o primeiro do dia seguinte.
            val expired = support.registered()
            support.foundAt(expired, lastExpiredDay.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant())
            val notYet = support.registered()
            support.foundAt(notYet, lastExpiredDay.plusDays(1).atStartOfDay(zone).toInstant())

            support.item(expired)["expired"].asBoolean() shouldBe true
            support.item(notYet)["expired"].asBoolean() shouldBe false

            val expiredIds = housekeeping.listAll("$ITEMS?expired=true").map { it["id"].asString() }
            expiredIds shouldContain expired.toString()
            expiredIds shouldNotContain notYet.toString()
            val notExpiredIds = housekeeping.listAll("$ITEMS?expired=false").map { it["id"].asString() }
            notExpiredIds shouldContain notYet.toString()
            notExpiredIds shouldNotContain expired.toString()

            support.act(notYet, "discard", asAdmin(), mapOf("reason" to "Cedo")).problem(
                409,
                "HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED",
            )
            support.act(expired, "discard", asAdmin(), mapOf("reason" to "Vencido")).ok()

            // Devolvido ou descartado não é mais "vencido", nem na listagem.
            housekeeping
                .listAll("$ITEMS?expired=true")
                .map { it["id"].asString() } shouldNotContain expired.toString()
        }

    /** Um item novo achado no último dia já vencido pelo prazo de guarda. */
    private fun expiredItem(): UUID = support.registered().also { support.foundAt(it, expiredFoundAt()) }

    private fun expiredFoundAt(): Instant {
        val retention = hotel.operationalSettings().lostAndFoundRetentionDays.toLong()
        return hotel
            .currentBusinessDate()
            .minusDays(retention)
            .atTime(LocalTime.NOON)
            .atZone(hotel.timezone())
            .toInstant()
    }

    private fun ownItems(auth: RequestPostProcessor): List<JsonNode> = housekeeping.rooms.listAll(ITEMS, auth)

    private fun noGuestData(
        item: JsonNode,
        guestName: String,
        reservation: UUID,
        guest: UUID,
    ) {
        val text = item.toString()
        text shouldNotContain guestName
        text shouldNotContain reservation.toString()
        text shouldNotContain guest.toString()
        item.propertyNames().toList().forEach { name ->
            withClue(name) {
                listOf("guest", "reservation", "holder").none { name.contains(it, ignoreCase = true) } shouldBe true
            }
        }
        item["returnedTo"].isNull shouldBe true
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
        const val ENTITY = "housekeeping.lost-found-item"
    }
}
