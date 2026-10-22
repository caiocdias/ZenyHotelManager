package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * As views `report_*` do `reservations` e do `rates` (M4 E12, ARCHITECTURE §11, SPEC Q-15): o contrato das colunas,
 * só `SELECT` para o papel da aplicação, os fatos de um cenário (reservas em planos normal, cortesia e uso da casa, um
 * day use, uma cancelada, uma alocação, um bloqueio de grupo e os preços), a regra de Q-15 nas noites, nenhum texto
 * livre nem dado de hóspede nos valores e a consulta por um papel que só lê as views (as tabelas, não). Cada teste cria
 * os próprios tipos de UH, planos e hóspedes (o banco é compartilhado).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class ReservationReportViewsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val flyway: Flyway,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `the reservations and rates views are the published contract, only readable by the application role`() {
        VIEW_COLUMNS.keys.groupBy { it.substringBefore('.') }.forEach { (schema, views) ->
            withClue(schema) { viewsOf(schema) shouldContainExactlyInAnyOrder views.map { it.substringAfter('.') } }
        }
        VIEW_COLUMNS.forEach { (view, columns) ->
            withClue(view) {
                columnsOf(view) shouldContainExactly columns
                columnsOf(view).filter { column ->
                    FORBIDDEN.any { column.contains(it) } && "$view.$column" !in CONFIGURATION_NAMES
                } shouldBe emptyList()
                PRIVILEGES.filter { privilegeOn(view, it) } shouldBe listOf("SELECT")
            }
        }
    }

    @Test
    @Suppress("LongMethod") // o cenário e as conferências de cada view
    fun `the views carry the reservation facts, apply Q-15 to the nights and hold no free text nor guest data`() {
        val (type, rooms) = inventory.roomTypeWithRooms(4, today)
        val marker = support.guests.suffix().uppercase()
        val normal = plan(type, "NORMAL")
        val courtesy = plan(type, "COURTESY")
        val houseUse = plan(type, "HOUSE_USE")
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = "Hóspede Relatório $marker"),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val arrival = today.plusDays(4)
        val stay =
            reservation(
                holder,
                support.reservation(
                    type,
                    normal,
                    arrival,
                    arrival.plusDays(2),
                    specialRequests = "Berço e flores $marker",
                    nightlyRates = listOf(arrival to "360.00"),
                ),
            )
        val courtesyStay = reservation(holder, support.reservation(type, courtesy, arrival, arrival.plusDays(1)))
        val houseStay = reservation(holder, support.reservation(type, houseUse, arrival, arrival.plusDays(1)))
        val dayUse = reservation(holder, support.reservation(type, normal, arrival, arrival))
        val cancelled = reservation(holder, support.reservation(type, normal, arrival.plusDays(1), arrival.plusDays(2)))
        with(support.billing) { support.cancel(cancelled, notes = "Desistiu $marker").expect(200) }
        with(iam) {
            call(POST, "/api/v1/reservations/$stay/assign-room", support.admin, mapOf("roomId" to rooms[0])).ok()
        }
        val block =
            support
                .createdBlock(
                    support.blockBody(
                        normal,
                        today.plusDays(2),
                        support.allocation(type, arrival.plusDays(5), arrival.plusDays(6), 1),
                        holderGuest = holder,
                        name = "Casamento $marker",
                    ),
                )["id"]
                .asString()
                .let(UUID::fromString)

        withClue("report_reservations: uma linha por reserva, com a categoria copiada") {
            val rows =
                rowsIn(
                    "reservations.report_reservations",
                    "reservation_id",
                    listOf(stay, courtesyStay, houseStay, dayUse, cancelled),
                )
            rows.associate { it["reservation_id"] to it["rate_plan_category"] } shouldBe
                mapOf(
                    stay to "NORMAL",
                    courtesyStay to "COURTESY",
                    houseStay to "HOUSE_USE",
                    dayUse to "NORMAL",
                    cancelled to "NORMAL",
                )
            val main = rows.single { it["reservation_id"] == stay }
            main["nights"] shouldBe 2
            main["day_use"] shouldBe false
            main["holder_guest_id"] shouldBe holder
            main["primary_guest_id"] shouldBe holder
            main["status"] shouldBe "CONFIRMED"
            main["total"].toString() shouldBe "760.00"
            rows.single { it["reservation_id"] == dayUse }["nights"] shouldBe 0
            val gone = rows.single { it["reservation_id"] == cancelled }
            gone["status"] shouldBe "CANCELLED"
            gone["cancellation_reason"] shouldBe "GUEST_REQUEST"
            gone["cancellation_previous_status"] shouldBe "CONFIRMED"
        }

        withClue("report_room_nights: as noites com a regra de Q-15 e o preço congelado") {
            val nights =
                rowsIn(
                    "reservations.report_room_nights",
                    "reservation_id",
                    listOf(stay, courtesyStay, houseStay, dayUse, cancelled),
                )

            fun flags(id: UUID) =
                nights.filter { it["reservation_id"] == id }.map {
                    Triple(it["sold"], it["counts_in_occupancy"], it["counts_in_adr"])
                }
            flags(stay) shouldBe List(2) { Triple(true, true, true) }
            flags(courtesyStay) shouldBe listOf(Triple(true, true, false))
            flags(houseStay) shouldBe listOf(Triple(true, false, false))
            flags(dayUse) shouldBe listOf(Triple(true, false, false))
            flags(cancelled) shouldBe listOf(Triple(false, true, true))
            val first =
                nights.single {
                    it["reservation_id"] == stay && it["stay_date"].toString() == arrival.toString()
                }
            first["room_amount"].toString() shouldBe "360.00"
            first["calculated_room_amount"].toString() shouldBe "400.00"
            first["overridden"] shouldBe true
            first["discount_percent"].toString() shouldBe "10.0000"
            first["accommodation_amount"].toString() shouldBe "360.00"
            first["channel"] shouldBe "DIRECT"
            first["rate_plan_id"] shouldBe normal
        }

        withClue("report_inventory: os contadores do tipo na data") {
            val row =
                jdbc
                    .sql("SELECT * FROM reservations.report_inventory WHERE room_type_id = :type AND stay_date = :date")
                    .param("type", type)
                    .param("date", arrival)
                    .query()
                    .listOfRows()
                    .single()
            row["total"] shouldBe 4
            // A normal, a cortesia e o uso da casa pernoitam na chegada (o day use não toma; a cancelada chega depois).
            row["sold"] shouldBe 3
            row["available"] shouldBe 1
        }

        withClue("report_room_assignments: a alocação ativa") {
            val assignment = rowsIn("reservations.report_room_assignments", "reservation_id", listOf(stay)).single()
            assignment["room_id"] shouldBe rooms[0]
            assignment["active"] shouldBe true
            assignment["reason"] shouldBe "INITIAL"
            assignment["starts_on"].toString() shouldBe arrival.toString()
        }

        withClue("report_block_allocations: as unidades do bloqueio por noite, sem o nome do grupo") {
            val allocations = rowsIn("reservations.report_block_allocations", "block_id", listOf(block))
            allocations shouldHaveSize 2
            allocations.forEach {
                it["quantity"] shouldBe 1
                it["remaining"] shouldBe 1
                it["block_status"] shouldBe "ACTIVE"
                it["holder_guest_id"] shouldBe holder
            }
        }

        withClue("rates: o plano com a categoria e os preços gravados") {
            val plans = rowsIn("rates.report_rate_plans", "rate_plan_id", listOf(normal, courtesy, houseUse))
            plans.associate { it["rate_plan_id"] to it["category"] } shouldBe
                mapOf(normal to "NORMAL", courtesy to "COURTESY", houseUse to "HOUSE_USE")
            val prices = rowsIn("rates.report_rate_prices", "rate_plan_id", listOf(courtesy))
            prices shouldHaveSize (PRICED_DAYS + 1).toInt()
            prices.forEach {
                it["room_type_id"] shouldBe type
                it["one_adult"].toString() shouldBe "300.00"
                it["two_adults"].toString() shouldBe "400.00"
            }
        }

        withClue("nenhum valor das views tem texto livre nem dado de hóspede") {
            val text =
                listOf(
                    json("reservations.report_reservations", "reservation_id", listOf(stay, cancelled)),
                    json("reservations.report_room_nights", "reservation_id", listOf(stay, cancelled)),
                    json("reservations.report_block_allocations", "block_id", listOf(block)),
                    json("rates.report_rate_plans", "rate_plan_id", listOf(normal)),
                ).joinToString("\n")
            text shouldNotContain marker
            text shouldNotContain "Hóspede Relatório"
            text shouldNotContain "Tarifa de balcão com café"
        }

        withClue("um papel que só lê as views consulta-as; as tabelas internas, não (42501)") {
            withViewOnlyRole { client ->
                VIEW_COLUMNS.keys.forEach { view ->
                    client.sql("SELECT count(*) FROM $view").query(Long::class.java).single()
                }
                client
                    .sql("SELECT rate_plan_category FROM reservations.report_reservations WHERE reservation_id = :id")
                    .param("id", courtesyStay)
                    .query(String::class.java)
                    .single() shouldBe "COURTESY"
                for (table in TABLES) {
                    val error =
                        shouldThrow<DataAccessException> {
                            client.sql("SELECT count(*) FROM $table").query(Long::class.java).single()
                        }
                    (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
                }
            }
        }
    }

    @Test
    fun `the plan category follows a change of plan and comes from the block plan in the pick-up`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val normal = plan(type, "NORMAL")
        val courtesy = plan(type, "COURTESY")
        val arrival = today.plusDays(6)
        val id = reservation(support.guest(), support.reservation(type, normal, arrival, arrival.plusDays(2)))
        support.reservation(id)["reservation"]["ratePlanCategory"].asString() shouldBe "NORMAL"
        categoryOf(id) shouldBe "NORMAL"

        val current = support.reservation(id)["reservation"]
        val changed =
            with(support.billing) {
                support.change(id, support.changeBody(current, plan = courtesy)).expect(200)
            }["reservation"]
        changed["ratePlanId"].asString() shouldBe courtesy.toString()
        changed["ratePlanCategory"].asString() shouldBe "COURTESY"
        categoryOf(id) shouldBe "COURTESY"
        rowsIn("reservations.report_reservations", "reservation_id", listOf(id)).single()["rate_plan_category"] shouldBe
            "COURTESY"
        val nights = rowsIn("reservations.report_room_nights", "reservation_id", listOf(id))
        nights shouldHaveSize 2
        nights.forEach {
            it["rate_plan_id"] shouldBe courtesy
            it["rate_plan_category"] shouldBe "COURTESY"
            it["counts_in_occupancy"] shouldBe true
            it["counts_in_adr"] shouldBe false
        }

        // O pick-up de um bloqueio num plano de cortesia: a reserva nasce com a categoria do plano do bloqueio.
        val block =
            UUID.fromString(
                support
                    .createdBlock(
                        support.blockBody(
                            courtesy,
                            today.plusDays(2),
                            support.allocation(type, arrival.plusDays(3), arrival.plusDays(3), 1),
                            holderGuest = support.guest(),
                        ),
                    )["id"]
                    .asString(),
            )
        val picked =
            with(support.billing) {
                support
                    .pickUp(block, support.blockReservation(type, arrival.plusDays(3), arrival.plusDays(4)))
                    .expect(201)
            }["reservations"][0]
        picked["ratePlanCategory"].asString() shouldBe "COURTESY"
        categoryOf(UUID.fromString(picked.id())) shouldBe "COURTESY"
    }

    // --- Apoio -----------------------------------------------------------------------------------------

    /** Um plano da [category] com o tipo, políticas novas sem garantia e preços de hoje a [PRICED_DAYS] dias. */
    private fun plan(
        type: UUID,
        category: String,
    ): UUID {
        val rates = support.rates
        val cancellation = rates.createCancellationPolicy(rates.cancellationBody())
        val guarantee =
            rates.createGuaranteePolicy(rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null))
        val body = rates.planBody(listOf(type), cancellation, guarantee, category = category)
        val id =
            with(iam) {
                UUID.fromString(
                    call(POST, "/api/v1/rate-plans", support.admin, body).expectStatus(201)!!["id"].asString(),
                )
            }
        support.setPrices(id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return id
    }

    private fun reservation(
        holder: UUID,
        body: Map<String, Any?>,
    ): UUID = UUID.fromString(support.created(support.booking(holder, body))["reservations"][0].id())

    private fun JsonNode.id(): String = this["id"].asString()

    private fun categoryOf(id: UUID): String =
        jdbc
            .sql("SELECT rate_plan_category FROM reservations.reservation WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun viewsOf(schema: String): List<String> =
        jdbc
            .sql(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE n.nspname = :schema AND c.relkind = 'v'",
            ).param("schema", schema)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun columnsOf(view: String): List<String> =
        jdbc
            .sql(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = :schema AND table_name = :view ORDER BY ordinal_position",
            ).param("schema", view.substringBefore('.'))
            .param("view", view.substringAfter('.'))
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun privilegeOn(
        relation: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, :relation, :privilege)")
            .param("relation", relation)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun rowsIn(
        view: String,
        column: String,
        ids: List<UUID>,
    ): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM $view WHERE $column = ANY (CAST(:ids AS uuid[]))")
            .param("ids", ids.joinToString(",", "{", "}"))
            .query()
            .listOfRows()

    private fun json(
        view: String,
        column: String,
        ids: List<UUID>,
    ): String =
        jdbc
            .sql(
                "SELECT coalesce(string_agg(row_to_json(v)::text, ' '), '') FROM $view v " +
                    "WHERE $column = ANY (CAST(:ids AS uuid[]))",
            ).param("ids", ids.joinToString(",", "{", "}"))
            .query(String::class.java)
            .single()

    /**
     * Roda [block] numa conexão do dono com `SET ROLE` para um papel novo, sem login, que só tem `USAGE` nos schemas e
     * `SELECT` nas views (a view roda com os privilégios do dono dela). Removido no fim.
     */
    private fun withViewOnlyRole(block: (JdbcClient) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "reservations_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            owner.sql("GRANT USAGE ON SCHEMA reservations, rates TO $role").update()
            VIEW_COLUMNS.keys.forEach { owner.sql("GRANT SELECT ON $it TO $role").update() }
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                block(JdbcClient.create(SingleConnectionDataSource(connection, true)))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private companion object {
        const val PRICED_DAYS = 20L
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")

        /** Nada de texto livre nem de dado de hóspede (nome, documento, contato) nas colunas. */
        val FORBIDDEN =
            listOf(
                "name",
                "note",
                "special",
                "description",
                "document",
                "email",
                "phone",
                "external",
            )

        /** Nomes de configuração do gestor, que podem ir (o nome do plano tarifário). */
        val CONFIGURATION_NAMES = setOf("rates.report_rate_plans.name")

        val TABLES =
            listOf(
                "reservations.reservation",
                "reservations.booking",
                "reservations.nightly_rate",
                "reservations.reservation_guest",
                "reservations.room_block",
                "reservations.room_type_inventory",
                "rates.rate_plan",
                "rates.rate_price",
            )

        val VIEW_COLUMNS =
            mapOf(
                "reservations.report_reservations" to
                    listOf(
                        "reservation_id",
                        "reservation_code",
                        "booking_id",
                        "booking_code",
                        "holder_guest_id",
                        "primary_guest_id",
                        "origin",
                        "channel",
                        "status",
                        "room_type_id",
                        "rate_plan_id",
                        "rate_plan_category",
                        "block_id",
                        "arrival",
                        "departure",
                        "day_use",
                        "nights",
                        "adults",
                        "children",
                        "segment",
                        "room_total",
                        "additional_packages_total",
                        "total",
                        "folio_id",
                        "created_at",
                        "created_by",
                        "discount_approval_id",
                        "cancellation_policy_id",
                        "cancellation_policy_version",
                        "guarantee_policy_id",
                        "guarantee_policy_version",
                        "guarantee_type",
                        "guarantee_method",
                        "guaranteed_at",
                        "guarantee_deposit_required",
                        "guarantee_deposit_received",
                        "provisional_until",
                        "cancellation_reason",
                        "cancellation_previous_status",
                        "cancelled_at",
                        "cancelled_by",
                        "penalty_amount",
                        "penalty_waived",
                        "penalty_posting_id",
                        "penalties_charged",
                    ),
                "reservations.report_room_nights" to
                    listOf(
                        "reservation_id",
                        "stay_date",
                        "room_type_id",
                        "rate_plan_id",
                        "rate_plan_category",
                        "status",
                        "sold",
                        "counts_in_occupancy",
                        "counts_in_adr",
                        "day_use",
                        "channel",
                        "origin",
                        "segment",
                        "block_id",
                        "adults",
                        "children",
                        "calculated_room_amount",
                        "room_amount",
                        "included_packages_amount",
                        "accommodation_amount",
                        "additional_packages_amount",
                        "amount",
                        "overridden",
                        "discount_percent",
                    ),
                "reservations.report_inventory" to
                    listOf(
                        "room_type_id",
                        "stay_date",
                        "total",
                        "sold",
                        "blocked",
                        "out_of_order",
                        "overbooking_limit",
                        "available",
                        "updated_at",
                    ),
                "reservations.report_room_assignments" to
                    listOf(
                        "assignment_id",
                        "reservation_id",
                        "room_id",
                        "starts_on",
                        "ends_on",
                        "day_use",
                        "reason",
                        "do_not_move",
                        "reallocation_required_at",
                        "reallocation_out_of_order_id",
                        "assigned_at",
                        "assigned_by",
                        "active",
                        "released_at",
                        "released_by",
                        "release_reason",
                    ),
                "reservations.report_block_allocations" to
                    listOf(
                        "block_id",
                        "block_code",
                        "block_status",
                        "holder_guest_id",
                        "holder_company_id",
                        "rate_plan_id",
                        "cutoff_date",
                        "folio_id",
                        "released_on",
                        "room_type_id",
                        "stay_date",
                        "quantity",
                        "picked_up",
                        "released",
                        "remaining",
                        "rate",
                    ),
                "rates.report_rate_plans" to
                    listOf(
                        "rate_plan_id",
                        "code",
                        "name",
                        "meal_plan",
                        "category",
                        "active",
                        "base_rate_plan_id",
                        "derivation_type",
                        "derivation_percent",
                        "derivation_amount",
                        "derivation_per_person",
                        "cancellation_policy_id",
                        "guarantee_policy_id",
                        "created_at",
                        "updated_at",
                    ),
                "rates.report_rate_prices" to
                    listOf(
                        "rate_plan_id",
                        "room_type_id",
                        "stay_date",
                        "one_adult",
                        "two_adults",
                        "extra_adult",
                        "updated_at",
                    ),
            )
    }
}
