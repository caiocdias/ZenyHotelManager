package com.zeny.hotel.rooms.infrastructure

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusField
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.application.HousekeepingObservationView
import com.zeny.hotel.rooms.application.RoomDiscrepancyKind
import com.zeny.hotel.rooms.application.RoomDiscrepancyView
import com.zeny.hotel.rooms.application.RoomStatusChangeView
import com.zeny.hotel.rooms.application.RoomStatusRecords
import com.zeny.hotel.rooms.application.RoomStatusView
import com.zeny.hotel.rooms.domain.RoomStatusFieldChange
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * O histórico de status, as observações da governança e as leituras do status pelo `JdbcClient`
 * (ARCHITECTURE §11), sempre parametrizadas. O histórico e as observações só recebem `INSERT`: o papel
 * da aplicação não tem `UPDATE` nem `DELETE` nelas ([RoomStatusTablesConfiguration]).
 */
@Component
class JdbcRoomStatusRecords(
    private val jdbc: JdbcClient,
) : RoomStatusRecords {
    override fun appendChanges(
        roomId: RoomId,
        changes: List<RoomStatusFieldChange>,
        changedAt: Instant,
        changedBy: UserId?,
        source: RoomStatusSource,
        roomVersion: Long,
    ) {
        changes.forEach { change ->
            jdbc
                .sql(
                    """
                    INSERT INTO rooms.room_status_change
                        (id, room_id, field, from_value, to_value, changed_at, changed_by, source, room_version)
                    VALUES (:id, :roomId, :field, :from, :to, :changedAt, :changedBy, :source, :roomVersion)
                    """.trimIndent(),
                ).param("id", UuidV7.generate())
                .param("roomId", roomId.value)
                .param("field", change.field.name)
                .param("from", change.from)
                .param("to", change.to)
                .param("changedAt", utc(changedAt))
                .param("changedBy", changedBy?.value)
                .param("source", source.name)
                .param("roomVersion", roomVersion)
                .update()
        }
    }

    override fun appendObservation(observation: HousekeepingObservationView) {
        jdbc
            .sql(
                """
                INSERT INTO rooms.housekeeping_observation
                    (id, room_id, occupancy, guest_count, observed_at, observed_by)
                VALUES (:id, :roomId, :occupancy, :guestCount, :observedAt, :observedBy)
                """.trimIndent(),
            ).param("id", observation.id)
            .param("roomId", observation.roomId.value)
            .param("occupancy", observation.occupancy.name)
            .param("guestCount", observation.guestCount)
            .param("observedAt", utc(observation.observedAt))
            .param("observedBy", observation.observedBy.value)
            .update()
    }

    override fun board(
        occupancy: OccupancyStatus?,
        housekeepingStatus: HousekeepingStatus?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomStatusView> =
        jdbc
            .sql(
                """
                SELECT * FROM rooms.room
                WHERE active
                  AND (CAST(:occupancy AS text) IS NULL OR occupancy = CAST(:occupancy AS text))
                  AND (CAST(:hk AS text) IS NULL OR housekeeping_status = CAST(:hk AS text))
                  AND (CAST(:afterNumber AS text) IS NULL
                       OR (lower(number), id) > (CAST(:afterNumber AS text), CAST(:afterId AS uuid)))
                ORDER BY lower(number), id LIMIT :limit
                """.trimIndent(),
            ).param("occupancy", occupancy?.name)
            .param("hk", housekeepingStatus?.name)
            .param("afterNumber", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.statusView() }
            .list()

    override fun history(
        roomId: RoomId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<RoomStatusChangeView> =
        jdbc
            .sql(
                """
                SELECT * FROM rooms.room_status_change
                WHERE room_id = :roomId
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (changed_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY changed_at DESC, id DESC LIMIT :limit
                """.trimIndent(),
            ).param("roomId", roomId.value)
            .param("afterAt", after?.first?.let(::utc))
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                RoomStatusChangeView(
                    rs.uuid("id"),
                    RoomId(rs.uuid("room_id")),
                    RoomStatusField.valueOf(rs.getString("field")),
                    rs.getString("from_value"),
                    rs.getString("to_value"),
                    rs.instant("changed_at"),
                    rs.getObject("changed_by", UUID::class.java)?.let(::UserId),
                    RoomStatusSource.valueOf(rs.getString("source")),
                    rs.getLong("room_version"),
                )
            }.list()

    override fun discrepancies(
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomDiscrepancyView> =
        jdbc
            .sql(
                """
                SELECT r.id, r.number, r.occupancy, r.guest_count,
                       o.id AS observation_id, o.occupancy AS observed_occupancy,
                       o.guest_count AS observed_guest_count, o.observed_at, o.observed_by
                FROM rooms.room r
                JOIN LATERAL (SELECT * FROM rooms.housekeeping_observation h
                              WHERE h.room_id = r.id
                              ORDER BY h.observed_at DESC, h.id DESC LIMIT 1) o ON TRUE
                WHERE r.active
                  AND o.observed_at >= r.occupancy_changed_at
                  AND (r.occupancy <> o.occupancy
                       OR (r.occupancy = 'OCCUPIED' AND o.guest_count IS NOT NULL AND o.guest_count <> r.guest_count))
                  AND (CAST(:afterNumber AS text) IS NULL
                       OR (lower(r.number), r.id) > (CAST(:afterNumber AS text), CAST(:afterId AS uuid)))
                ORDER BY lower(r.number), r.id LIMIT :limit
                """.trimIndent(),
            ).param("afterNumber", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                val roomId = RoomId(rs.uuid("id"))
                val frontDesk = OccupancyStatus.valueOf(rs.getString("occupancy"))
                val observation =
                    HousekeepingObservationView(
                        rs.uuid("observation_id"),
                        roomId,
                        OccupancyStatus.valueOf(rs.getString("observed_occupancy")),
                        rs.intOrNull("observed_guest_count"),
                        rs.instant("observed_at"),
                        UserId(rs.uuid("observed_by")),
                    )
                RoomDiscrepancyView(
                    roomId,
                    rs.getString("number"),
                    when {
                        frontDesk == OccupancyStatus.OCCUPIED && observation.occupancy == OccupancyStatus.VACANT -> {
                            RoomDiscrepancyKind.OCCUPIED_BUT_FOUND_VACANT
                        }

                        frontDesk == OccupancyStatus.VACANT && observation.occupancy == OccupancyStatus.OCCUPIED -> {
                            RoomDiscrepancyKind.VACANT_BUT_FOUND_OCCUPIED
                        }

                        else -> {
                            RoomDiscrepancyKind.GUEST_COUNT_MISMATCH
                        }
                    },
                    frontDesk,
                    rs.intOrNull("guest_count"),
                    observation,
                )
            }.list()
}

/** O histórico e as observações são *append-only* para o papel da aplicação; a partida confere (DatabaseRoleGuard). */
@Configuration(proxyBeanMethods = false)
class RoomStatusTablesConfiguration {
    @Bean
    fun roomsAppendOnlyTables(): AppendOnlyTables =
        AppendOnlyTables { setOf(ROOM_STATUS_CHANGE_TABLE, HOUSEKEEPING_OBSERVATION_TABLE) }

    companion object {
        const val ROOM_STATUS_CHANGE_TABLE = "rooms.room_status_change"
        const val HOUSEKEEPING_OBSERVATION_TABLE = "rooms.housekeeping_observation"
    }
}

private fun ResultSet.statusView() =
    RoomStatusView(
        RoomId(uuid("id")),
        getString("number"),
        getString("floor"),
        RoomTypeId(uuid("room_type_id")),
        getBoolean("active"),
        OccupancyStatus.valueOf(getString("occupancy")),
        intOrNull("guest_count"),
        HousekeepingStatus.valueOf(getString("housekeeping_status")),
        getBoolean("inspection_pending"),
        GuestServiceFlag.valueOf(getString("service_flag")),
        instant("status_changed_at"),
        getLong("version"),
    )

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
