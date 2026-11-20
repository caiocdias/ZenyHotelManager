package com.zeny.hotel.maintenance

import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.ReportViewAssertions
import com.zeny.hotel.testsupport.WorkOrderNumbers
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A view `maintenance.report_work_orders` (M6 E09, D1): uma linha por ordem, com as colunas do contrato e os custos,
 * sem a descrição, os motivos nem o terceiro; nenhuma coluna é de texto livre e o papel da aplicação só lê.
 */
@IntegrationTest
class MaintenanceReportViewsTest(
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `a work order is one row with the contract columns and the costs`() {
        val done = insert(area = UuidV7.generate(), status = "DONE", closedAt = Instant.parse("2031-05-02T10:00:00Z"))

        jdbc
            .sql("SELECT * FROM maintenance.report_work_orders WHERE work_order_id = :id")
            .param("id", done)
            .query { rs, _ ->
                listOf(
                    rs.getString("category"),
                    rs.getString("priority"),
                    rs.getString("origin"),
                    rs.getString("status"),
                    rs.getTimestamp("opened_at").toInstant(),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("closed_at").toInstant(),
                    rs.getBigDecimal("labor_cost"),
                    rs.getBigDecimal("third_party_cost"),
                    rs.getBigDecimal("parts_cost"),
                    rs.getObject("room_id"),
                )
            }.single() shouldBe
            listOf(
                "PLUMBING",
                "HIGH",
                "RECEPTION",
                "DONE",
                Instant.parse("2031-05-01T08:00:00Z"),
                Instant.parse("2031-05-01T09:00:00Z"),
                Instant.parse("2031-05-02T10:00:00Z"),
                BigDecimal("10.50"),
                BigDecimal("20.00"),
                BigDecimal("5.25"),
                null,
            )
        ReportViewAssertions.shouldHaveColumns(
            jdbc,
            "maintenance",
            "report_work_orders",
            listOf(
                "work_order_id:uuid",
                "number:character varying",
                "room_id:uuid",
                "area_id:uuid",
                "category:character varying",
                "priority:character varying",
                "origin:character varying",
                "status:character varying",
                "opened_at:timestamp with time zone",
                "started_at:timestamp with time zone",
                "closed_at:timestamp with time zone",
                "labor_cost:numeric",
                "third_party_cost:numeric",
                "parts_cost:numeric",
            ),
        )
    }

    @Test
    fun `an open order has no closing and keeps its room`() {
        val room = UuidV7.generate()
        val open = insert(room = room, status = "OPEN")

        jdbc
            .sql(
                "SELECT room_id, closed_at, status FROM maintenance.report_work_orders WHERE work_order_id = :id",
            ).param("id", open)
            .query { rs, _ ->
                listOf(rs.getObject("room_id", UUID::class.java), rs.getTimestamp("closed_at"), rs.getString("status"))
            }.single() shouldBe listOf(room, null, "OPEN")
    }

    @Test
    fun `no column is free text and the application role only reads the view`() {
        ReportViewAssertions.shouldHaveNoFreeText(jdbc, "maintenance", "report_work_orders")
        ReportViewAssertions.shouldBeReadOnlyForTheApplication(jdbc, "maintenance", "report_work_orders")
    }

    private fun insert(
        room: UUID? = null,
        area: UUID? = null,
        status: String,
        closedAt: Instant? = null,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                "INSERT INTO maintenance.work_order (id, number, room_id, area_id, category, priority, description, " +
                    "origin, third_party_name, status, labor_cost, third_party_cost, parts_cost, opened_by, " +
                    "opened_at, started_at, closed_at, completion_notes, updated_at, version) VALUES (:id, :number, " +
                    ":room, :area, 'PLUMBING', 'HIGH', 'Texto livre', 'RECEPTION', 'Terceiro Ltda', :status, " +
                    "10.50, 20.00, 5.25, :by, :openedAt, :startedAt, :closedAt, :notes, now(), 0)",
            ).param("id", id)
            .param("number", WorkOrderNumbers.next())
            .param("room", room)
            .param("area", area)
            .param("status", status)
            .param("by", UuidV7.generate())
            .param("openedAt", Instant.parse("2031-05-01T08:00:00Z").atOffset(java.time.ZoneOffset.UTC))
            .param("startedAt", Instant.parse("2031-05-01T09:00:00Z").atOffset(java.time.ZoneOffset.UTC))
            .param("closedAt", closedAt?.atOffset(java.time.ZoneOffset.UTC))
            .param("notes", closedAt?.let { "Notas livres" })
            .update()
        return id
    }
}
