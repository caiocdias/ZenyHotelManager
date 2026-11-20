package com.zeny.hotel.testsupport

import com.zeny.hotel.shared.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Semeia por SQL, direto nas tabelas do `housekeeping`, tipos de limpeza, tarefas e inspeções com os campos que os
 * relatórios leem (camareira, tempo trabalhado, resultado, instantes), sem passar pelos casos de uso.
 */
class HousekeepingReportSeed(
    private val jdbc: JdbcClient,
) {
    fun cleaningType(): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                "INSERT INTO housekeeping.cleaning_type (id, code, name, requires_inspection, default_priority, " +
                    "active, created_at, updated_at, version) VALUES (:id, :code, 'Tipo de teste', true, 3, true, " +
                    "now(), now(), 0)",
            ).param("id", id)
            .param(
                "code",
                "RV" +
                    id
                        .toString()
                        .takeLast(CODE_SUFFIX)
                        .uppercase()
                        .replace("-", "0"),
            ).update()
        return id
    }

    @Suppress("LongParameterList") // os campos que variam nos casos
    fun task(
        type: UUID,
        day: LocalDate,
        room: UUID? = null,
        area: UUID? = null,
        status: String,
        employee: UUID? = null,
        worked: Long = 0,
        credits: Int = 3,
        notes: String? = null,
    ): UUID {
        val id = UuidV7.generate()
        val finished = status in setOf("DONE", "AWAITING_INSPECTION")
        val started = day.atTime(HOUR_START, 0).toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                "INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, " +
                    "cleaning_type_id, source, status, priority, credits, arrival_expected, notes, created_at, " +
                    "updated_at, version, assigned_employee_id, assignment_position, started_at, finished_at, " +
                    "worked_seconds, cleaned_by) VALUES (:id, :room, :area, :day, :type, 'CHECKOUT', :status, 3, " +
                    ":credits, false, :notes, now(), now(), 0, :employee, :position, :started, :finished, " +
                    ":worked, :cleanedBy)",
            ).param("id", id)
            .param("room", room)
            .param("area", area)
            .param("day", day)
            .param("type", type)
            .param("status", status)
            .param("credits", credits)
            .param("notes", notes)
            .param("employee", employee)
            .param("position", employee?.let { 1 })
            .param("started", if (finished) started else null)
            .param("finished", if (finished) started.plusHours(1) else null)
            .param("worked", worked)
            .param("cleanedBy", if (finished) UuidV7.generate() else null)
            .update()
        return id
    }

    fun inspection(
        task: UUID?,
        room: UUID,
        result: String,
        completedAt: Instant = Instant.now(),
    ): UUID {
        val id = UuidV7.generate()
        val completed = completedAt.atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                "INSERT INTO housekeeping.inspection (id, task_id, room_id, status, result, inspector_id, " +
                    "started_at, completed_at) VALUES (:id, :task, :room, 'COMPLETED', :result, :inspector, " +
                    ":startedAt, :completedAt)",
            ).param("id", id)
            .param("task", task)
            .param("room", room)
            .param("result", result)
            .param("inspector", UuidV7.generate())
            .param("startedAt", completed.minusMinutes(1))
            .param("completedAt", completed)
            .update()
        return id
    }

    private companion object {
        const val CODE_SUFFIX = 12
        const val HOUR_START = 14
    }
}
