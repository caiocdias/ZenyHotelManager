package com.zeny.hotel.reporting

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.matchers.doubles.plusOrMinus
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
import java.time.LocalDate
import java.util.UUID

/**
 * `GET /api/v1/reports/day-panel` (M6 E09, D3): chegadas, saídas, hospedados e ocupação de hoje e dos próximos dias, e
 * as UHs de hoje. O banco é compartilhado, então todo total do hotel é afirmado **por diferença**: lê antes, semeia
 * reservas, UHs e bloqueios próprios, espera os listeners do inventário e lê depois. A data operacional é a do painel
 * (`businessDate`), e as linhas são achadas pela data.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class DayPanelApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val seed = ReportingTestSupport(iam, support)
    private val inventory = support.inventory
    private val admin = iam.asAdmin()

    private fun panel(
        days: Int = DAYS,
        auth: RequestPostProcessor = admin,
    ) = iam.call(GET, "/api/v1/reports/day-panel?days=$days", auth)

    private fun read(days: Int = DAYS): JsonNode = with(iam) { panel(days).ok() }

    private fun JsonNode.day(date: LocalDate): JsonNode =
        this["days"].values().single {
            it["date"].asString() ==
                "$date"
        }

    private fun JsonNode.count(
        date: LocalDate,
        field: String,
    ): Long = day(date)[field].asLong()

    private fun JsonNode.occupancy(date: LocalDate): Double = day(date)["occupancy"].asString().toDouble()

    /** A capacidade (UHs ativas menos as fora de ordem) de todos os tipos na data, como o inventário a publica. */
    private fun capacity(date: LocalDate): Long =
        jdbc
            .sql(
                "SELECT coalesce(sum(total - out_of_order), 0) FROM reservations.report_inventory WHERE stay_date = :d",
            ).param("d", date)
            .query(Long::class.java)
            .single()

    private fun plan(
        type: UUID,
        category: String,
        from: LocalDate,
    ) = seed.plan(type, category, from)

    private fun book(
        holder: UUID,
        body: Map<String, Any?>,
    ) = seed.book(holder, body)

    @Test
    @Suppress("LongMethod") // o cenário e as conferências de cada data
    fun `arrivals, departures, guests and occupancy change as expected, courtesy counting and house use not`() {
        val base = read()["businessDate"].asString().let(LocalDate::parse)
        val (type, rooms) = inventory.roomTypeWithRooms(ROOMS, base)
        val normal = plan(type, "NORMAL", base)
        val courtesy = plan(type, "COURTESY", base)
        val houseUse = plan(type, "HOUSE_USE", base)
        val marker = support.guests.suffix().uppercase()
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = "Hóspede Painel $marker"),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        InventoryListenersQuiescence.await(jdbc)
        val before = read()
        val d0 = base
        val d1 = base.plusDays(1)
        val d2 = base.plusDays(2)
        val d3 = base.plusDays(3)
        val capacities = listOf(d0, d1, d2, d3).associateWith(::capacity)

        // Chega hoje, sai em d2 e já está hospedada: chegada de hoje e hospedada em d0 e d1.
        val inHouse = book(holder, support.reservation(type, normal, d0, d2, adults = 1))
        support.forceInHouse(inHouse, rooms[0])
        // Futura (CONFIRMED), chega em d1 e sai em d3, dentro da janela.
        val future = book(holder, support.reservation(type, normal, d1, d3, adults = 1))
        // Cortesia: conta na ocupação. Uso da casa e day use: não contam.
        book(holder, support.reservation(type, courtesy, d1, d2, adults = 1))
        book(holder, support.reservation(type, houseUse, d1, d2, adults = 1))
        book(holder, support.reservation(type, normal, d1, d1, adults = 1))
        // Cancelada: não conta em nada.
        val cancelled = book(holder, support.reservation(type, normal, d1, d2, adults = 1))
        with(support.billing) { support.cancel(cancelled, notes = "Desistiu $marker").expect(200) }
        InventoryListenersQuiescence.await(jdbc)
        support.status(future) shouldBe "CONFIRMED"

        val after = read()

        // d0: uma chegada (a hospedada) e uma hospedada.
        after.count(d0, "arrivals") - before.count(d0, "arrivals") shouldBe 1
        after.count(d0, "departures") - before.count(d0, "departures") shouldBe 0
        after.count(d0, "hosted") - before.count(d0, "hosted") shouldBe 1
        // d1: chegam a futura, a cortesia, o uso da casa e o day use (4); o day use também sai; hospedam a hospedada,
        // a futura, a cortesia e o uso da casa (4), sem o day use.
        after.count(d1, "arrivals") - before.count(d1, "arrivals") shouldBe 4
        after.count(d1, "departures") - before.count(d1, "departures") shouldBe 1
        after.count(d1, "hosted") - before.count(d1, "hosted") shouldBe 4
        // d2: saem a hospedada, a cortesia e o uso da casa; hospeda a futura.
        after.count(d2, "arrivals") - before.count(d2, "arrivals") shouldBe 0
        after.count(d2, "departures") - before.count(d2, "departures") shouldBe 3
        after.count(d2, "hosted") - before.count(d2, "hosted") shouldBe 1
        // d3: sai a futura, que está dentro da janela de saídas.
        after.count(d3, "departures") - before.count(d3, "departures") shouldBe 1
        after.count(d3, "hosted") - before.count(d3, "hosted") shouldBe 0

        // Ocupação: a variação é as noites que contam sobre a capacidade (que não mudou desde antes). Em d0 conta 1
        // noite; em d1, 3 (hospedada, futura e cortesia; não o uso da casa nem o day use); em d2, 1 (a futura).
        mapOf(d0 to 1, d1 to 3, d2 to 1).forEach { (date, nights) ->
            capacity(date) shouldBe capacities.getValue(date)
            val gain = after.occupancy(date) - before.occupancy(date)
            gain shouldBe (100.0 * nights / capacities.getValue(date)).plusOrMinus(OCCUPANCY_TOLERANCE)
        }
        // d3 não ganha noite: a ocupação não muda.
        after.occupancy(d3) shouldBe before.occupancy(d3)
        after.toString() shouldNotContain marker
    }

    @Test
    fun `house use and day use alone do not move the occupancy, and the courtesy does`() {
        val base = read()["businessDate"].asString().let(LocalDate::parse)
        val (type, _) = inventory.roomTypeWithRooms(ROOMS, base)
        val courtesy = plan(type, "COURTESY", base)
        val houseUse = plan(type, "HOUSE_USE", base)
        val normal = plan(type, "NORMAL", base)
        val holder = support.guest()
        val date = base.plusDays(3)
        InventoryListenersQuiescence.await(jdbc)
        val before = read()
        val capacity = capacity(date)

        book(holder, support.reservation(type, houseUse, date, date.plusDays(1), adults = 1))
        book(holder, support.reservation(type, normal, date, date, adults = 1))
        InventoryListenersQuiescence.await(jdbc)
        val noise = read()
        book(holder, support.reservation(type, courtesy, date, date.plusDays(1), adults = 1))
        InventoryListenersQuiescence.await(jdbc)
        val withCourtesy = read()

        noise.occupancy(date) shouldBe before.occupancy(date)
        noise.count(date, "hosted") - before.count(date, "hosted") shouldBe 1
        (withCourtesy.occupancy(date) - noise.occupancy(date)) shouldBe
            (100.0 / capacity).plusOrMinus(OCCUPANCY_TOLERANCE)
    }

    @Test
    fun `the rooms of today are counted by status and the open blocks in force, not the future or released ones`() {
        val base = read()["businessDate"].asString().let(LocalDate::parse)
        val before = read()["rooms"]
        val (_, created) = inventory.roomTypeWithRooms(ROOMS, base)
        val oooRoom = created[0]
        val oosRoom = created[1]
        val releasedRoom = created[2]
        val futureRoom = created[3]

        fun open(
            room: UUID,
            kind: String,
            from: LocalDate,
            to: LocalDate,
        ): String =
            with(iam) {
                call(
                    POST,
                    "/api/v1/rooms/$room/out-of-order",
                    admin,
                    mapOf(
                        "kind" to kind,
                        "startsOn" to "$from",
                        "endsOn" to "$to",
                        "reason" to "Teste do painel",
                        "responsible" to "Equipe",
                        "returnStatus" to "DIRTY",
                    ),
                ).expectStatus(201)!!["id"].asString()
            }
        open(oooRoom, "OUT_OF_ORDER", base, base.plusDays(2))
        open(oosRoom, "OUT_OF_SERVICE", base, base.plusDays(2))
        val released = open(releasedRoom, "OUT_OF_ORDER", base, base.plusDays(2))
        with(iam) { call(POST, "/api/v1/rooms/$releasedRoom/out-of-order/$released/release", admin).ok() }
        open(futureRoom, "OUT_OF_ORDER", base.plusDays(3), base.plusDays(4))
        InventoryListenersQuiescence.await(jdbc)
        val statusOfCreated =
            created.map { room ->
                jdbc
                    .sql("SELECT housekeeping_status FROM rooms.report_room_status WHERE room_id = :id")
                    .param("id", room)
                    .query(String::class.java)
                    .single()
            }

        val after = read()["rooms"]

        fun delta(field: String) = after[field].asLong() - before[field].asLong()
        delta("active") shouldBe ROOMS.toLong()
        delta("vacant") shouldBe ROOMS.toLong()
        delta("occupied") shouldBe 0
        delta("dirty") shouldBe statusOfCreated.count { it == "DIRTY" }.toLong()
        delta("clean") shouldBe statusOfCreated.count { it == "CLEAN" }.toLong()
        delta("inspected") shouldBe statusOfCreated.count { it == "INSPECTED" }.toLong()
        delta("outOfOrder") shouldBe 1
        delta("outOfService") shouldBe 1
    }

    @Test
    fun `a block on an inactive room does not count as out of order, like the rest of the room counts`() {
        val base = read()["businessDate"].asString().let(LocalDate::parse)
        val before = read()["rooms"]
        val (_, created) = inventory.roomTypeWithRooms(ROOMS, base)

        fun open(
            room: UUID,
            kind: String,
        ) = with(iam) {
            call(
                POST,
                "/api/v1/rooms/$room/out-of-order",
                admin,
                mapOf(
                    "kind" to kind,
                    "startsOn" to "$base",
                    "endsOn" to "${base.plusDays(2)}",
                    "reason" to "Teste do painel",
                    "responsible" to "Equipe",
                    "returnStatus" to "DIRTY",
                ),
            ).expectStatus(201)
        }
        open(created[0], "OUT_OF_ORDER")
        open(created[1], "OUT_OF_SERVICE")
        open(created[2], "OUT_OF_ORDER")
        InventoryListenersQuiescence.await(jdbc)
        // A UH inativa some das contagens de hoje, inclusive a do bloqueio dela.
        jdbc.sql("UPDATE rooms.room SET active = false WHERE id = :id").param("id", created[2]).update()

        val after = read()["rooms"]

        fun delta(field: String) = after[field].asLong() - before[field].asLong()
        delta("outOfOrder") shouldBe 1
        delta("outOfService") shouldBe 1
    }

    @Test
    fun `days outside 1 to 30 are refused with 400, and the default is 7 days starting today`() {
        with(iam) {
            panel(0).problem(400, "VALIDATION_FAILED")
            panel(31).problem(400, "VALIDATION_FAILED")
            iam.call(GET, "/api/v1/reports/day-panel?days=abc", admin).problem(400, "VALIDATION_FAILED")
            panel(1).ok()["days"].size() shouldBe 1
            panel(30).ok()["days"].size() shouldBe 30
            val default = call(GET, "/api/v1/reports/day-panel", admin).ok()
            default["days"].size() shouldBe DEFAULT_DAYS
            default["days"][0]["date"].asString() shouldBe default["businessDate"].asString()
        }
    }

    @Test
    fun `without the permission the panel is refused with 403, and with only it the panel is served`() {
        val none = iam.asTestUser(iam.createUser())
        val onlyHousekeeping =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single {
                            it.code ==
                                ReportingPermissions.HOUSEKEEPING_READ
                        },
                        ALL,
                    ),
                ),
            )
        val reader =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single { it.code == ReportingPermissions.DASHBOARD_READ },
                        ALL,
                    ),
                ),
            )

        with(iam) {
            panel(auth = none).problem(403, "PERMISSION_DENIED")
            panel(auth = onlyHousekeeping).problem(403, "PERMISSION_DENIED")
            panel(auth = reader).ok()
        }
    }

    @Test
    fun `the response is a typed object made of counts only`() {
        val body = read()

        body.propertyNames().toSet() shouldBe setOf("businessDate", "days", "rooms")
        body["days"].values().forEach {
            it.propertyNames().toSet() shouldBe setOf("date", "arrivals", "departures", "hosted", "occupancy")
            it["arrivals"].isIntegralNumber shouldBe true
        }
        body["rooms"].propertyNames().toSet() shouldBe
            setOf(
                "active",
                "occupied",
                "vacant",
                "dirty",
                "clean",
                "inspected",
                "inspectionPending",
                "outOfOrder",
                "outOfService",
            )
    }

    private companion object {
        const val DAYS = 7
        const val DEFAULT_DAYS = 7
        const val ROOMS = 6
        const val OCCUPANCY_TOLERANCE = 0.011
    }
}
