package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Ataque à situação no-show no banco e no contrato (M6 E11, Task 2): a migração mantém todos os valores anteriores das
 * duas `CHECK`, recusa o desconhecido, o gatilho adiado cobre `NO_SHOW`, as views `report_*` seguem válidas e o
 * contrato publica os valores novos como `x-extensible-enum`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class ReservationNoShowPersistenceAttackTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `the history still accepts every kind from before the migration`() {
        val reservation = reservation()
        val before =
            listOf(
                "CHANGE",
                "CANCEL",
                "REACTIVATE",
                "CONFIRM",
                "GUARANTEE",
                "CHECK_IN",
                "UNDO_CHECK_IN",
                "CHECK_OUT",
                "ROOM_MOVE",
            )

        before.forEach { insertHistory(reservation, it) }

        support.historyKinds(reservation) shouldContainExactlyInAnyOrder before
    }

    @Test
    fun `the history refuses near-miss kinds of the no-show`() {
        val reservation = reservation()

        listOf("no_show", "NO-SHOW", "NOSHOW", "NO_SHOW ", "", "RESERVATION_NO_SHOW").forEach {
            shouldThrow<DataIntegrityViolationException> { insertHistory(reservation, it) }
        }
        support.historyKinds(reservation) shouldBe emptyList()
    }

    @Test
    fun `a released assignment still accepts every reason from before the migration`() {
        val (type, rooms) = inventory.roomTypeWithRooms(6, today)
        val reservation = reservation(type)
        val before =
            listOf(
                "UNASSIGNED",
                "REALLOCATED",
                "RESERVATION_CANCELLED",
                "RESERVATION_CHANGED",
                "CHECKED_OUT",
                "ROOM_MOVED",
            )

        before.forEachIndexed { index, reason -> insertReleasedAssignment(reservation, rooms[index], reason) }

        jdbc
            .sql("SELECT release_reason FROM reservations.room_assignment WHERE reservation_id = :id")
            .param("id", reservation)
            .query(String::class.java)
            .list() shouldContainExactlyInAnyOrder before
    }

    @Test
    fun `the no-show reason keeps the release coherence rules`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val reservation = reservation(type)

        // motivo sem data de soltura
        shouldThrow<DataIntegrityViolationException> {
            insertAssignment(reservation, rooms[0], releasedAt = false, reason = "RESERVATION_NO_SHOW")
        }
        // soltura sem motivo
        shouldThrow<DataIntegrityViolationException> {
            insertAssignment(reservation, rooms[1], releasedAt = true, reason = null)
        }
        // near-misses
        listOf("no_show", "NO_SHOW", "RESERVATION_NOSHOW").forEach {
            shouldThrow<DataIntegrityViolationException> {
                insertReleasedAssignment(reservation, rooms[2], it)
            }
        }
    }

    @Test
    fun `a no-show reservation with an active assignment is refused at commit`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val reservation = reservation(type)
        insertAssignment(reservation, rooms[0], releasedAt = false, reason = null)

        shouldThrow<DataIntegrityViolationException> { tx.executeWithoutResult { markNoShow(reservation) } }

        support.status(reservation) shouldBe "CONFIRMED"
    }

    @Test
    fun `a no-show reservation whose assignment was released with the no-show reason commits`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val reservation = reservation(type)
        insertAssignment(reservation, rooms[0], releasedAt = false, reason = null)

        tx.executeWithoutResult {
            markNoShow(reservation)
            jdbc
                .sql(
                    "UPDATE reservations.room_assignment SET released_at = now(), " +
                        "release_reason = 'RESERVATION_NO_SHOW' WHERE reservation_id = :id AND released_at IS NULL",
                ).param("id", reservation)
                .update()
        }

        support.status(reservation) shouldBe "NO_SHOW"
    }

    @Test
    fun `the report views read the no-show values without error and with the right meaning`() {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val reservation = reservation(type)
        insertAssignment(reservation, rooms[0], releasedAt = false, reason = null)
        tx.executeWithoutResult {
            markNoShow(reservation)
            jdbc
                .sql(
                    "UPDATE reservations.room_assignment SET released_at = now(), " +
                        "release_reason = 'RESERVATION_NO_SHOW' WHERE reservation_id = :id",
                ).param("id", reservation)
                .update()
        }

        jdbc
            .sql("SELECT status FROM reservations.report_reservations WHERE reservation_id = :id")
            .param("id", reservation)
            .query(String::class.java)
            .single() shouldBe "NO_SHOW"
        val nights =
            jdbc
                .sql("SELECT status, sold FROM reservations.report_room_nights WHERE reservation_id = :id")
                .param("id", reservation)
                .query { rs, _ -> rs.getString("status") to rs.getBoolean("sold") }
                .list()
        nights shouldBe listOf("NO_SHOW" to false)
        val assignment =
            jdbc
                .sql(
                    "SELECT release_reason, active FROM reservations.report_room_assignments " +
                        "WHERE reservation_id = :id",
                ).param("id", reservation)
                .query { rs, _ -> rs.getString("release_reason") to rs.getBoolean("active") }
                .list()
        assignment shouldBe listOf("RESERVATION_NO_SHOW" to false)
    }

    @Test
    fun `the published contract lists the no-show values as extensible enum values`() {
        val lines = File("api/openapi.yaml").readLines()

        extensibleEnum(lines, "ReservationHistoryResponse", "kind") shouldContainExactlyInAnyOrder
            listOf(
                "CHANGE",
                "CANCEL",
                "REACTIVATE",
                "CONFIRM",
                "GUARANTEE",
                "CHECK_IN",
                "UNDO_CHECK_IN",
                "CHECK_OUT",
                "ROOM_MOVE",
                "NO_SHOW",
            )
        extensibleEnum(lines, "RoomAssignmentResponse", "releaseReason") shouldContainExactlyInAnyOrder
            listOf(
                "UNASSIGNED",
                "REALLOCATED",
                "RESERVATION_CANCELLED",
                "RESERVATION_CHANGED",
                "CHECKED_OUT",
                "ROOM_MOVED",
                "RESERVATION_NO_SHOW",
            )
    }

    @Test
    fun `the contract does not turn the new values into a closed enum`() {
        val lines = File("api/openapi.yaml").readLines()

        listOf("ReservationHistoryResponse" to "kind", "RoomAssignmentResponse" to "releaseReason").forEach {
            val start = lines.indexOfFirst { l -> l == "    ${it.first}:" }
            val prop = (start until lines.size).first { i -> lines[i] == "        ${it.second}:" }
            val block = lines.drop(prop + 1).takeWhile { l -> l.startsWith("          ") }
            block.none { l -> l.trim() == "enum:" } shouldBe true
        }
    }

    private fun extensibleEnum(
        lines: List<String>,
        schema: String,
        property: String,
    ): List<String> {
        val start = lines.indexOfFirst { it == "    $schema:" }
        check(start >= 0) { "schema $schema não encontrado" }
        val prop = (start until lines.size).first { lines[it] == "        $property:" }
        val block = lines.drop(prop + 1).takeWhile { it.startsWith("          ") }
        val at = block.indexOfFirst { it.trim() == "x-extensible-enum:" }
        check(at >= 0) { "$schema.$property sem x-extensible-enum" }
        return block.drop(at + 1).takeWhile { it.trim().startsWith("- ") }.map { it.trim().removePrefix("- ") }
    }

    private fun markNoShow(reservation: UUID) =
        jdbc
            .sql("UPDATE reservations.reservation SET status = 'NO_SHOW', provisional_until = NULL WHERE id = :id")
            .param("id", reservation)
            .update()

    private fun reservation(type: UUID = inventory.roomTypeWithRooms(1, today).first): UUID {
        val plan = support.plan(listOf(type), "NONE")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(30))
        val created =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(type, plan.id, today, today.plusDays(1), adults = 1),
                ),
            )
        val id = UUID.fromString(created["reservations"][0]["id"].asString())
        jdbc
            .sql("UPDATE reservations.reservation SET status = 'CONFIRMED', provisional_until = NULL WHERE id = :id")
            .param("id", id)
            .update()
        return id
    }

    private fun insertHistory(
        reservation: UUID,
        kind: String,
    ) = jdbc
        .sql(
            """
            INSERT INTO reservations.reservation_history (id, reservation_id, kind, business_date, occurred_at,
                before_state, after_state, version)
            VALUES (:id, :reservation, :kind, :date, now(), '{}'::jsonb, '{}'::jsonb, 1)
            """.trimIndent(),
        ).param("id", UuidV7.generate())
        .param("reservation", reservation)
        .param("kind", kind)
        .param("date", today)
        .update()

    private fun insertReleasedAssignment(
        reservation: UUID,
        room: UUID,
        reason: String,
    ) = insertAssignment(reservation, room, releasedAt = true, reason = reason)

    private fun insertAssignment(
        reservation: UUID,
        room: UUID,
        releasedAt: Boolean,
        reason: String?,
    ) = jdbc
        .sql(
            """
            INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                assigned_at, released_at, release_reason)
            VALUES (:id, :reservation, :room, :from, :to, 'INITIAL', now(),
                CASE WHEN :released THEN now() END, :reason)
            """.trimIndent(),
        ).param("id", UuidV7.generate())
        .param("reservation", reservation)
        .param("room", room)
        .param("from", today)
        .param("to", today.plusDays(1))
        .param("released", releasedAt)
        .param("reason", reason, java.sql.Types.VARCHAR)
        .update()
}
