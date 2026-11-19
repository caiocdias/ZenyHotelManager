package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.DamageReportTestSupport.Companion.DAMAGES
import com.zeny.hotel.housekeeping.LostAndFoundTestSupport.Companion.ITEMS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * O registro offline de achados e danos (M6 E06, Task 3, D6): `housekeeping.report-lost-found` e
 * `housekeeping.report-damage` pela sincronização, com o padrão da E19 (o `commandId` é o id do registro, o
 * `occurredAt` corrigido é o instante do fato, idempotência pelo `commandId`), a permissão
 * `housekeeping.occurrences.report` (sem ela, `REJECTED`), o custo estimado pela entrada estrita (texto decimal, como
 * na API online; 3 casas ou número JSON: `REJECTED COMMAND_PAYLOAD_INVALID`; acima do teto, o código do domínio) e o
 * resultado sem reserva nem hóspede (Review Focus 4).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios de iam, governança e reservas
class HousekeepingOccurrenceOfflineTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired hotel: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired staffApi: StaffApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)
    private val today = { hotel.currentBusinessDate() }

    @Test
    fun `the attendant reports a found item and a damage offline, listed with the command id and the fact time`() {
        val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        val auth = iam.asTestUser(attendant)
        val room = housekeeping.createRoom()
        val now = Instant.now()
        val found =
            command(
                "report-lost-found",
                mapOf("roomId" to room, "description" to "Óculos escuros", "storagePlace" to "Governança"),
                now.minus(MINUTES_10),
                now,
            )
        val damage =
            command(
                "report-damage",
                mapOf("roomId" to room, "description" to "Cortina rasgada", "estimatedCost" to "45.50"),
                now.minus(MINUTES_10),
                now,
            )

        val results = sync(auth, found, damage)

        withClue(results.toString()) {
            results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        }
        val item = listed(ITEMS, auth, found.id)
        val report = listed(DAMAGES, auth, damage.id)
        assertSoftly {
            item["roomId"].asString() shouldBe room.toString()
            item["description"].asString() shouldBe "Óculos escuros"
            item["storagePlace"].asString() shouldBe "Governança"
            item["source"].asString() shouldBe "OFFLINE"
            item["status"].asString() shouldBe "STORED"
            item["reportedBy"].asString() shouldBe attendant.id.toString()
            Instant.parse(item["foundAt"].asString()) shouldBe Instant.parse(results[0]["occurredAt"].asString())
            report["description"].asString() shouldBe "Cortina rasgada"
            report["estimatedCost"].asString() shouldBe "45.50"
            report["source"].asString() shouldBe "OFFLINE"
            report["status"].asString() shouldBe "REPORTED"
            report["reportedBy"].asString() shouldBe attendant.id.toString()
            Instant.parse(report["occurredAt"].asString()) shouldBe Instant.parse(results[1]["occurredAt"].asString())
            housekeeping.auditActions("housekeeping.lost-found-item", found.id) shouldContainExactly
                listOf("housekeeping.lost-found-item.register-offline")
            housekeeping.auditActions("housekeeping.damage-report", damage.id) shouldContainExactly
                listOf("housekeeping.damage-report.register-offline")
        }

        // O mesmo lote de novo: o resultado guardado, sem segundo registro.
        val again = sync(auth, found, damage)

        again.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        count("lost_and_found_item", found.id) shouldBe 1
        count("damage_report", damage.id) shouldBe 1
    }

    @Test
    fun `a device clock beyond the tolerance is applied with a warning and the corrected time`() {
        val auth = iam.asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
        val room = housekeeping.createRoom()
        val deviceClock = Instant.now().minus(HOURS_2)
        val found =
            command("report-lost-found", mapOf("roomId" to room, "description" to "Chave"), deviceClock, deviceClock)
        val damage =
            command("report-damage", mapOf("roomId" to room, "description" to "Mancha"), deviceClock, deviceClock)
        val before = Instant.now()

        val results = sync(auth, found, damage)

        results.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED_WITH_WARNINGS", "APPLIED_WITH_WARNINGS")
        results.flatMap { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
            listOf("CLOCK_SKEW_EXCEEDED", "CLOCK_SKEW_EXCEEDED")
        val foundAt = Instant.parse(listed(ITEMS, auth, found.id)["foundAt"].asString())
        (foundAt >= before.minusSeconds(1)) shouldBe true
        Instant.parse(listed(DAMAGES, auth, damage.id)["occurredAt"].asString()) shouldBe
            Instant.parse(results[1]["occurredAt"].asString())
    }

    @Test
    fun `without the report permission, or with a cost of 3 decimals, the command is rejected and nothing recorded`() {
        val outsider = iam.asTestUser(iam.createUser())
        val auth = iam.asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
        val room = housekeeping.createRoom()
        val now = Instant.now()
        val forbiddenFound = command("report-lost-found", mapOf("roomId" to room, "description" to "Chave"), now, now)
        val forbiddenDamage = command("report-damage", mapOf("roomId" to room, "description" to "Mancha"), now, now)
        val threeDecimals =
            command(
                "report-damage",
                mapOf("roomId" to room, "description" to "Mancha", "estimatedCost" to "12.345"),
                now,
                now,
            )
        val overLimit =
            command(
                "report-damage",
                mapOf("roomId" to room, "description" to "Mancha", "estimatedCost" to "100000.00"),
                now,
                now,
            )

        // O custo é texto decimal, como na API online: o número JSON também é recusado na conversão.
        val number =
            command(
                "report-damage",
                mapOf("roomId" to room, "description" to "Mancha", "estimatedCost" to 12.5),
                now,
                now,
            )

        val forbidden = sync(outsider, forbiddenFound, forbiddenDamage)
        val invalid = sync(auth, threeDecimals, overLimit, number)

        assertSoftly {
            forbidden.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "REJECTED")
            forbidden.map { it["code"].asString() } shouldContainExactly
                listOf("PERMISSION_DENIED", "PERMISSION_DENIED")
            invalid.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "REJECTED", "REJECTED")
            invalid.map { it["code"].asString() } shouldContainExactly
                listOf("COMMAND_PAYLOAD_INVALID", "HOUSEKEEPING_DATA_INVALID", "COMMAND_PAYLOAD_INVALID")
            count("lost_and_found_item", forbiddenFound.id) shouldBe 0
            listOf(forbiddenDamage, threeDecimals, overLimit, number).forEach {
                count("damage_report", it.id) shouldBe 0
            }
        }
    }

    @Test
    fun `a damage reported offline in an occupied room links the reservation, out of the command result`() {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, today())
        val name = "Hóspede Offline ${UUID.randomUUID().toString().takeLast(8)}"
        val (reservation, guest) = stays.inHouse(type, stays.planFor(type), rooms[0], today().plusDays(2), name)
        val attendant = iam.asTestUser(execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
        val now = Instant.now()
        val damage = command("report-damage", mapOf("roomId" to rooms[0], "description" to "Abajur"), now, now)
        val found = command("report-lost-found", mapOf("roomId" to rooms[0], "description" to "Livro"), now, now)

        val results = sync(attendant, damage, found)

        results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        val text = results.toString()
        assertSoftly {
            text shouldNotContain reservation.toString()
            text shouldNotContain guest.toString()
            text shouldNotContain name
            listed(DAMAGES, attendant, damage.id)["reservationId"].isNull shouldBe true
            with(iam) {
                call(GET, "$DAMAGES/${damage.id}", asAdmin()).ok()["reservationId"].asString() shouldBe
                    reservation.toString()
            }
        }
    }

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        occurredAt: Instant,
        deviceClock: Instant,
    ) = SyncCommand(UuidV7.generate(), "housekeeping.$verb", payload, occurredAt, deviceClock)

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

    /** O registro [id] na listagem [path] de quem registrou ([auth], com `OWN`). */
    private fun listed(
        path: String,
        auth: RequestPostProcessor,
        id: UUID,
    ): JsonNode = housekeeping.rooms.listAll(path, auth).single { it["id"].asString() == id.toString() }

    private fun count(
        table: String,
        id: UUID,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.$table WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()

    private companion object {
        val MINUTES_10: Duration = Duration.ofMinutes(10)
        val HOURS_2: Duration = Duration.ofHours(2)
    }
}
