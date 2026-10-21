package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.reservations.ReallocationMark
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.RoomAssignmentEndReason
import com.zeny.hotel.reservations.RoomAssignmentId
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.application.ReallocationRow
import com.zeny.hotel.reservations.application.RoomAssignmentStore
import com.zeny.hotel.reservations.domain.AssignmentPeriod
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.UserId
import org.postgresql.util.PSQLException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As alocações de UH pelo `JdbcClient`, só no schema `reservations` e sempre parametrizado. O que a alocação ocupa na
 * UH é a coluna gerada `slots` (meios-dias, a da restrição `room_assignment_overlap_ex`, a regra de
 * [AssignmentPeriod.conflictsWith]); as consultas por período usam a coluna gerada `stay` (as datas: de `startsOn` à
 * véspera de `endsOn`, ou a própria data no day use), que traz um superconjunto dos conflitos e cruza com os OOO;
 * quem chama filtra por `conflictsWith`.
 */
@Component
@Suppress("TooManyFunctions") // a porta inteira
class JdbcRoomAssignmentStore(
    private val jdbc: JdbcClient,
) : RoomAssignmentStore {
    override fun lockRooms(roomIds: Collection<RoomId>) {
        roomIds.map { it.value.toString() }.distinct().sorted().forEach { id ->
            jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtext('reservations.room'), hashtext(:id))")
                .param("id", id)
                .query { _, _ -> 0 }
                .list()
        }
    }

    override fun active(reservationId: ReservationId): RoomAssignmentSummary? =
        jdbc
            .sql("SELECT * FROM reservations.room_assignment WHERE reservation_id = :id AND released_at IS NULL")
            .param("id", reservationId.value)
            .query { rs, _ -> assignment(rs) }
            .optional()
            .orElse(null)

    override fun history(reservationId: ReservationId): List<RoomAssignmentSummary> =
        jdbc
            .sql(
                "SELECT * FROM reservations.room_assignment WHERE reservation_id = :id " +
                    "ORDER BY assigned_at DESC, id DESC",
            ).param("id", reservationId.value)
            .query { rs, _ -> assignment(rs) }
            .list()

    override fun activeOverlapping(
        period: AssignmentPeriod,
        roomId: RoomId?,
    ): List<RoomAssignmentSummary> =
        jdbc
            .sql(
                """
                SELECT * FROM reservations.room_assignment
                WHERE released_at IS NULL
                  AND stay && daterange(CAST(:from AS date), CAST(:until AS date), '[)')
                  AND (CAST(:room AS uuid) IS NULL OR room_id = CAST(:room AS uuid))
                ORDER BY starts_on, id
                """.trimIndent(),
            ).param("from", period.startsOn)
            .param("until", period.lastDate.plusDays(1))
            .param("room", roomId?.value)
            .query { rs, _ -> assignment(rs) }
            .list()

    override fun tryInsert(assignment: RoomAssignmentSummary): Boolean =
        unlessOverlap {
            jdbc
                .sql(
                    """
                    INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                        do_not_move, assigned_at, assigned_by)
                    VALUES (:id, :reservationId, :roomId, :startsOn, :endsOn, :reason, :doNotMove, :assignedAt,
                        :assignedBy)
                    """.trimIndent(),
                ).param("id", assignment.id.value)
                .param("reservationId", assignment.reservationId.value)
                .param("roomId", assignment.roomId.value)
                .param("startsOn", assignment.startsOn)
                .param("endsOn", assignment.endsOn)
                .param("reason", assignment.reason.name)
                .param("doNotMove", assignment.doNotMove)
                .param("assignedAt", assignment.assignedAt.utc())
                .param("assignedBy", assignment.assignedBy?.value)
                .update()
        }

    override fun release(
        id: RoomAssignmentId,
        now: Instant,
        by: UserId?,
        reason: RoomAssignmentEndReason,
    ): RoomAssignmentSummary? =
        jdbc
            .sql(
                """
                UPDATE reservations.room_assignment
                SET released_at = :now, released_by = :by, release_reason = :reason
                WHERE id = :id AND released_at IS NULL
                RETURNING *
                """.trimIndent(),
            ).param("now", now.utc())
            .param("by", by?.value)
            .param("reason", reason.name)
            .param("id", id.value)
            .query { rs, _ -> assignment(rs) }
            .optional()
            .orElse(null)

    override fun setDoNotMove(
        id: RoomAssignmentId,
        doNotMove: Boolean,
    ) {
        val updated =
            jdbc
                .sql(
                    "UPDATE reservations.room_assignment SET do_not_move = :value " +
                        "WHERE id = :id AND released_at IS NULL",
                ).param("value", doNotMove)
                .param("id", id.value)
                .update()
        check(updated == 1) { "Alocação $id não está ativa" }
    }

    override fun movePeriod(
        id: RoomAssignmentId,
        period: AssignmentPeriod,
    ): Boolean =
        unlessOverlap {
            val updated =
                jdbc
                    .sql(
                        """
                        UPDATE reservations.room_assignment
                        SET starts_on = :startsOn, ends_on = :endsOn, reallocation_required_at = NULL,
                            reallocation_out_of_order_id = NULL
                        WHERE id = :id AND released_at IS NULL
                        """.trimIndent(),
                    ).param("startsOn", period.startsOn)
                    .param("endsOn", period.endsOn)
                    .param("id", id.value)
                    .update()
            check(updated == 1) { "Alocação $id não está ativa" }
        }

    override fun mark(
        id: RoomAssignmentId,
        outOfOrderId: OutOfOrderId,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE reservations.room_assignment
                SET reallocation_required_at = :now, reallocation_out_of_order_id = :ooo
                WHERE id = :id AND released_at IS NULL AND reallocation_required_at IS NULL
                """.trimIndent(),
            ).param("now", now.utc())
            .param("ooo", outOfOrderId.value)
            .param("id", id.value)
            .update() == 1

    override fun remark(
        id: RoomAssignmentId,
        from: OutOfOrderId,
        to: OutOfOrderId,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE reservations.room_assignment SET reallocation_out_of_order_id = :to
                WHERE id = :id AND released_at IS NULL AND reallocation_out_of_order_id = :from
                """.trimIndent(),
            ).param("to", to.value)
            .param("from", from.value)
            .param("id", id.value)
            .update() == 1

    override fun unmark(id: RoomAssignmentId): Boolean =
        jdbc
            .sql(
                """
                UPDATE reservations.room_assignment
                SET reallocation_required_at = NULL, reallocation_out_of_order_id = NULL
                WHERE id = :id AND released_at IS NULL AND reallocation_required_at IS NOT NULL
                """.trimIndent(),
            ).param("id", id.value)
            .update() == 1

    override fun reallocationRequired(
        after: Pair<LocalDate, RoomAssignmentId>?,
        limit: Int,
    ): List<ReallocationRow> =
        jdbc
            .sql(
                """
                SELECT a.*, r.code AS reservation_code, r.status AS reservation_status, r.arrival, r.departure
                FROM reservations.room_assignment a
                JOIN reservations.reservation r ON r.id = a.reservation_id
                WHERE a.released_at IS NULL AND a.reallocation_required_at IS NOT NULL
                  AND (CAST(:afterOn AS date) IS NULL
                       OR (a.starts_on, a.id) > (CAST(:afterOn AS date), CAST(:afterId AS uuid)))
                ORDER BY a.starts_on, a.id
                LIMIT :limit
                """.trimIndent(),
            ).param("afterOn", after?.first)
            .param("afterId", after?.second?.value)
            .param("limit", limit)
            .query { rs, _ ->
                ReallocationRow(
                    assignment = assignment(rs),
                    reservationCode = DocumentNumber.parse(rs.getString("reservation_code")),
                    status = ReservationStatus.valueOf(rs.getString("reservation_status")),
                    arrival = rs.getObject("arrival", LocalDate::class.java),
                    departure = rs.getObject("departure", LocalDate::class.java),
                )
            }.list()

    override fun touchReservation(
        reservationId: ReservationId,
        now: Instant,
    ) {
        jdbc
            .sql(
                "UPDATE reservations.reservation SET version = version + 1, updated_at = :now WHERE id = :id",
            ).param("now", now.utc())
            .param("id", reservationId.value)
            .update()
    }

    /** A violação de `room_assignment_overlap_ex` (`23P01`, reconhecida pelo nome da restrição) vira `false`. */
    private fun unlessOverlap(write: () -> Unit): Boolean =
        try {
            write()
            true
        } catch (e: DataAccessException) {
            val overlap =
                generateSequence<Throwable>(e) { it.cause }
                    .filterIsInstance<PSQLException>()
                    .any { it.sqlState == EXCLUSION_VIOLATION && it.serverErrorMessage?.constraint == OVERLAP }
            if (!overlap) throw e
            false
        }

    private fun assignment(rs: ResultSet): RoomAssignmentSummary {
        val markedAt = rs.instant("reallocation_required_at")
        return RoomAssignmentSummary(
            id = RoomAssignmentId(rs.uuid("id")),
            reservationId = ReservationId(rs.uuid("reservation_id")),
            roomId = RoomId(rs.uuid("room_id")),
            startsOn = rs.getObject("starts_on", LocalDate::class.java),
            endsOn = rs.getObject("ends_on", LocalDate::class.java),
            reason = RoomAssignmentReason.valueOf(rs.getString("reason")),
            doNotMove = rs.getBoolean("do_not_move"),
            reallocation =
                markedAt?.let { ReallocationMark(OutOfOrderId(rs.uuid("reallocation_out_of_order_id")), it) },
            assignedAt = checkNotNull(rs.instant("assigned_at")),
            assignedBy = rs.getObject("assigned_by", UUID::class.java)?.let(::UserId),
            releasedAt = rs.instant("released_at"),
            releasedBy = rs.getObject("released_by", UUID::class.java)?.let(::UserId),
            releaseReason = rs.getString("release_reason")?.let(RoomAssignmentEndReason::valueOf),
        )
    }

    private companion object {
        const val OVERLAP = "room_assignment_overlap_ex"
        const val EXCLUSION_VIOLATION = "23P01"
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
