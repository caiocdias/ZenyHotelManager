package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.ReportViewAssertions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.util.UUID

/**
 * As views `rooms.report_room_status` e `rooms.report_out_of_order` (M6 E09, D1): os dados semeados aparecem com as
 * colunas do contrato, nenhuma coluna é de texto livre e o papel da aplicação só lê.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class RoomsReportViewsTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val rooms = RoomsTestSupport(iam)

    @Test
    fun `room status has one row per room with the contract columns`() {
        val type = rooms.createRoomType()
        val active = rooms.createRoom(type)
        val inactive = rooms.createRoom(type)
        jdbc.sql("UPDATE rooms.room SET active = false WHERE id = :id").param("id", inactive).update()
        jdbc
            .sql(
                "UPDATE rooms.room SET floor = '3', occupancy = 'OCCUPIED', guest_count = 2, " +
                    "housekeeping_status = 'CLEAN', inspection_pending = true WHERE id = :id",
            ).param("id", active)
            .update()

        val row =
            jdbc
                .sql("SELECT * FROM rooms.report_room_status WHERE room_id = :id")
                .param("id", active)
                .query { rs, _ ->
                    listOf(
                        rs.getObject("room_type_id", UUID::class.java),
                        rs.getString("floor"),
                        rs.getBoolean("active"),
                        rs.getString("occupancy"),
                        rs.getString("housekeeping_status"),
                        rs.getBoolean("inspection_pending"),
                        rs.getTimestamp("status_changed_at") != null,
                    )
                }.single()
        row shouldBe listOf(type, "3", true, "OCCUPIED", "CLEAN", true, true)
        // A UH inativa também é uma linha: quem consulta filtra.
        jdbc
            .sql("SELECT active FROM rooms.report_room_status WHERE room_id = :id")
            .param("id", inactive)
            .query(Boolean::class.java)
            .single() shouldBe false
        ReportViewAssertions.shouldHaveColumns(
            jdbc,
            "rooms",
            "report_room_status",
            listOf(
                "room_id:uuid",
                "room_type_id:uuid",
                "floor:character varying",
                "active:boolean",
                "occupancy:character varying",
                "housekeeping_status:character varying",
                "inspection_pending:boolean",
                "status_changed_at:timestamp with time zone",
            ),
        )
    }

    @Test
    fun `out of order has one row per period, released or not, without the reason or the responsible`() {
        val room = rooms.createRoom(rooms.createRoomType())
        val open = UuidV7.generate()
        val released = UuidV7.generate()
        val start = LocalDate.of(2031, 3, 10)
        insertPeriod(open, room, "OUT_OF_ORDER", start, start.plusDays(5))
        val otherRoom = rooms.createRoom(rooms.createRoomType())
        insertPeriod(released, otherRoom, "OUT_OF_SERVICE", start, start.plusDays(5))
        jdbc
            .sql("UPDATE rooms.out_of_order SET released_on = :day, released_at = now() WHERE id = :id")
            .param("day", start.plusDays(2))
            .param("id", released)
            .update()

        fun row(id: UUID) =
            jdbc
                .sql("SELECT * FROM rooms.report_out_of_order WHERE out_of_order_id = :id")
                .param("id", id)
                .query { rs, _ ->
                    listOf(
                        rs.getObject("room_id", UUID::class.java),
                        rs.getString("kind"),
                        rs.getObject("starts_on", LocalDate::class.java),
                        rs.getObject("ends_on", LocalDate::class.java),
                        rs.getObject("released_on", LocalDate::class.java),
                    )
                }.single()
        row(open) shouldBe listOf(room, "OUT_OF_ORDER", start, start.plusDays(5), null)
        row(released) shouldBe
            listOf(otherRoom, "OUT_OF_SERVICE", start, start.plusDays(5), start.plusDays(2))
        ReportViewAssertions.shouldHaveColumns(
            jdbc,
            "rooms",
            "report_out_of_order",
            listOf(
                "out_of_order_id:uuid",
                "room_id:uuid",
                "kind:character varying",
                "starts_on:date",
                "ends_on:date",
                "released_on:date",
            ),
        )
    }

    @Test
    fun `no column is free text and the application role only reads the views`() {
        listOf("report_room_status", "report_out_of_order").forEach {
            ReportViewAssertions.shouldHaveNoFreeText(jdbc, "rooms", it)
            ReportViewAssertions.shouldBeReadOnlyForTheApplication(jdbc, "rooms", it)
        }
    }

    private fun insertPeriod(
        id: UUID,
        room: UUID,
        kind: String,
        start: LocalDate,
        end: LocalDate,
    ) {
        jdbc
            .sql(
                "INSERT INTO rooms.out_of_order (id, room_id, kind, starts_on, ends_on, reason, responsible, " +
                    "return_status, opened_at, updated_at, version) VALUES (:id, :room, :kind, :start, :end, " +
                    "'Motivo livre', 'Responsável livre', 'DIRTY', now(), now(), 0)",
            ).param("id", id)
            .param("room", room)
            .param("kind", kind)
            .param("start", start)
            .param("end", end)
            .update()
    }
}
