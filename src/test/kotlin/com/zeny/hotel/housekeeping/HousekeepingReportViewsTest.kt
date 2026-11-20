package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.HousekeepingReportSeed
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.ReportViewAssertions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As views `housekeeping.report_tasks` e `housekeeping.report_inspections` (M6 E09, D1): os dados semeados aparecem
 * com as colunas do contrato (o tempo trabalhado sem as pausas, a camareira atribuída), só a inspeção concluída
 * entra, nenhuma coluna é de texto livre e o papel da aplicação só lê.
 */
@IntegrationTest
class HousekeepingReportViewsTest(
    @Autowired private val jdbc: JdbcClient,
) {
    private val seed = HousekeepingReportSeed(jdbc)
    private val day = LocalDate.of(2031, 4, 12)
    private val startedAt = Instant.parse("2031-04-12T14:00:00Z")
    private val finishedAt = Instant.parse("2031-04-12T15:00:00Z")

    @Test
    fun `tasks have one row each with the assignee, the credits and the worked time without pauses`() {
        val type = seed.cleaningType()
        val room = UuidV7.generate()
        val area = UuidV7.generate()
        val employee = UuidV7.generate()
        val done = seed.task(type, day, room = room, status = "DONE", employee = employee, worked = 1800)
        val pending = seed.task(type, day, area = area, status = "PENDING", notes = "Texto livre da recepção")

        fun row(id: UUID) =
            jdbc
                .sql("SELECT * FROM housekeeping.report_tasks WHERE task_id = :id")
                .param("id", id)
                .query { rs, _ ->
                    listOf(
                        rs.getObject("business_date", LocalDate::class.java),
                        rs.getObject("room_id", UUID::class.java),
                        rs.getObject("area_id", UUID::class.java),
                        rs.getObject("cleaning_type_id", UUID::class.java),
                        rs.getString("source"),
                        rs.getString("status"),
                        rs.getInt("credits"),
                        rs.getObject("assigned_employee_id", UUID::class.java),
                        rs.getTimestamp("started_at")?.toInstant(),
                        rs.getTimestamp("finished_at")?.toInstant(),
                        rs.getLong("worked_seconds"),
                    )
                }.single()
        row(done) shouldBe
            listOf(day, room, null, type, "CHECKOUT", "DONE", 3, employee, startedAt, finishedAt, 1800L)
        row(pending) shouldBe listOf(day, null, area, type, "CHECKOUT", "PENDING", 3, null, null, null, 0L)
        ReportViewAssertions.shouldHaveColumns(
            jdbc,
            "housekeeping",
            "report_tasks",
            listOf(
                "task_id:uuid",
                "business_date:date",
                "room_id:uuid",
                "area_id:uuid",
                "cleaning_type_id:uuid",
                "source:character varying",
                "status:character varying",
                "credits:integer",
                "assigned_employee_id:uuid",
                "started_at:timestamp with time zone",
                "finished_at:timestamp with time zone",
                "worked_seconds:bigint",
            ),
        )
    }

    @Test
    fun `inspections list only the completed ones, with a nullable task for the voluntary one`() {
        val type = seed.cleaningType()
        val room = UuidV7.generate()
        val task = seed.task(type, day, room = room, status = "AWAITING_INSPECTION")
        val rejected = seed.inspection(task, room, "REJECTED")
        val voluntary = seed.inspection(null, room, "APPROVED")
        val inProgress = UuidV7.generate()
        jdbc
            .sql(
                "INSERT INTO housekeeping.inspection (id, task_id, room_id, status, inspector_id, started_at) " +
                    "VALUES (:id, :task, :room, 'IN_PROGRESS', :inspector, now())",
            ).param("id", inProgress)
            .param("task", task)
            .param("room", room)
            .param("inspector", UuidV7.generate())
            .update()

        fun row(id: UUID) =
            jdbc
                .sql("SELECT * FROM housekeeping.report_inspections WHERE inspection_id = :id")
                .param("id", id)
                .query { rs, _ ->
                    listOf(
                        rs.getObject("task_id", UUID::class.java),
                        rs.getObject("room_id", UUID::class.java),
                        rs.getString("result"),
                        rs.getTimestamp("completed_at") != null,
                        rs.getObject("inspector_id", UUID::class.java),
                    )
                }.list()
        row(rejected).single() shouldBe listOf(task, room, "REJECTED", true, inspectorOf(rejected))
        row(voluntary).single() shouldBe listOf(null, room, "APPROVED", true, inspectorOf(voluntary))
        row(inProgress) shouldBe emptyList()
        ReportViewAssertions.shouldHaveColumns(
            jdbc,
            "housekeeping",
            "report_inspections",
            listOf(
                "inspection_id:uuid",
                "task_id:uuid",
                "room_id:uuid",
                "result:character varying",
                "completed_at:timestamp with time zone",
                "inspector_id:uuid",
            ),
        )
    }

    @Test
    fun `no column is free text and the application role only reads the views`() {
        listOf("report_tasks", "report_inspections").forEach {
            ReportViewAssertions.shouldHaveNoFreeText(jdbc, "housekeeping", it)
            ReportViewAssertions.shouldBeReadOnlyForTheApplication(jdbc, "housekeeping", it)
        }
    }

    private fun inspectorOf(id: UUID): UUID =
        jdbc
            .sql("SELECT inspector_id FROM housekeeping.inspection WHERE id = :id")
            .param("id", id)
            .query(UUID::class.java)
            .single()
}
