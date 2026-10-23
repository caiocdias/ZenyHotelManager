package com.zeny.hotel.frontdesk.infrastructure

import com.zeny.hotel.frontdesk.TraceId
import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.frontdesk.application.TraceFilter
import com.zeny.hotel.frontdesk.application.TraceStore
import com.zeny.hotel.frontdesk.domain.Trace
import com.zeny.hotel.frontdesk.domain.TraceLink
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os recados por SQL (M5 E06): `frontdesk.trace`. Um trigger recusa o `DELETE`; a lista pagina por `(due_on, id)`.
 */
@Component
class JdbcTraceStore(
    private val jdbc: JdbcClient,
) : TraceStore {
    override fun add(trace: Trace) {
        jdbc
            .sql(
                """
                INSERT INTO frontdesk.trace (id, department_id, due_on, text, reservation_id, room_id, assignee_user_id,
                    created_at, created_by, updated_at, resolved_at, resolved_by, version)
                VALUES (:id, :departmentId, :dueOn, :text, :reservationId, :roomId, :assignee, :createdAt, :createdBy,
                    :updatedAt, :resolvedAt, :resolvedBy, :version)
                """.trimIndent(),
            ).param("id", trace.id.value)
            .param("departmentId", trace.departmentId.value)
            .param("dueOn", trace.dueOn)
            .param("text", trace.text)
            .param("reservationId", (trace.link as? TraceLink.Reservation)?.id?.value, java.sql.Types.OTHER)
            .param("roomId", (trace.link as? TraceLink.Room)?.id?.value, java.sql.Types.OTHER)
            .param("assignee", trace.assigneeUserId?.value, java.sql.Types.OTHER)
            .param("createdAt", trace.createdAt.atOffset(ZoneOffset.UTC))
            .param("createdBy", trace.createdBy.value)
            .param("updatedAt", trace.updatedAt.atOffset(ZoneOffset.UTC))
            .param("resolvedAt", trace.resolvedAt?.atOffset(ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .param("resolvedBy", trace.resolvedBy?.value, java.sql.Types.OTHER)
            .param("version", trace.version)
            .update()
    }

    override fun update(trace: Trace): Trace {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE frontdesk.trace
                    SET department_id = :departmentId, due_on = :dueOn, text = :text, assignee_user_id = :assignee,
                        updated_at = :updatedAt, resolved_at = :resolvedAt, resolved_by = :resolvedBy,
                        version = version + 1
                    WHERE id = :id AND version = :version
                    """.trimIndent(),
                ).param("id", trace.id.value)
                .param("departmentId", trace.departmentId.value)
                .param("dueOn", trace.dueOn)
                .param("text", trace.text)
                .param("assignee", trace.assigneeUserId?.value, java.sql.Types.OTHER)
                .param("updatedAt", trace.updatedAt.atOffset(ZoneOffset.UTC))
                .param("resolvedAt", trace.resolvedAt?.atOffset(ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("resolvedBy", trace.resolvedBy?.value, java.sql.Types.OTHER)
                .param("version", trace.version)
                .update()
        check(updated == 1) { "Recado ${trace.id} gravado fora da trava (versão inesperada)" }
        return trace.copy(version = trace.version + 1)
    }

    override fun find(id: TraceId): Trace? =
        jdbc
            .sql("SELECT * FROM frontdesk.trace WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.trace() }
            .optional()
            .orElse(null)

    override fun forUpdate(id: TraceId): Trace? =
        jdbc
            .sql("SELECT * FROM frontdesk.trace WHERE id = :id FOR UPDATE")
            .param("id", id.value)
            .query { rs, _ -> rs.trace() }
            .optional()
            .orElse(null)

    override fun page(
        filter: TraceFilter,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<Trace> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        filter.departmentId?.let {
            conditions += "department_id = :departmentId"
            params["departmentId"] = it.value
        }
        filter.dueOn?.let {
            conditions += "due_on = :dueOn"
            params["dueOn"] = it
        }
        filter.status?.let {
            conditions += if (it == TraceStatus.OPEN) "resolved_at IS NULL" else "resolved_at IS NOT NULL"
        }
        filter.reservationId?.let {
            conditions += "reservation_id = :reservationId"
            params["reservationId"] = it.value
        }
        filter.roomId?.let {
            conditions += "room_id = :roomId"
            params["roomId"] = it.value
        }
        after?.let {
            conditions += "(due_on, id) > (:afterDueOn, :afterId)"
            params["afterDueOn"] = it.first
            params["afterId"] = it.second
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return jdbc
            .sql("SELECT * FROM frontdesk.trace $where ORDER BY due_on, id LIMIT :limit")
            .params(params)
            .param("limit", limit)
            .query { rs, _ -> rs.trace() }
            .list()
    }

    private fun ResultSet.trace(): Trace {
        val reservation = getObject("reservation_id", UUID::class.java)
        val room = getObject("room_id", UUID::class.java)
        return Trace(
            TraceId(getObject("id", UUID::class.java)),
            DepartmentId(getObject("department_id", UUID::class.java)),
            getObject("due_on", LocalDate::class.java),
            getString("text"),
            if (reservation !=
                null
            ) {
                TraceLink.Reservation(ReservationId(reservation))
            } else {
                TraceLink.Room(RoomId(room))
            },
            getObject("assignee_user_id", UUID::class.java)?.let(::UserId),
            getObject("created_at", OffsetDateTime::class.java).toInstant(),
            UserId(getObject("created_by", UUID::class.java)),
            getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            getObject("resolved_at", OffsetDateTime::class.java)?.toInstant(),
            getObject("resolved_by", UUID::class.java)?.let(::UserId),
            getLong("version"),
        )
    }
}
