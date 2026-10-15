package com.zeny.hotel.rooms.infrastructure

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaKind
import com.zeny.hotel.rooms.Occupancy
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.application.AreaView
import com.zeny.hotel.rooms.application.RoomFeatureView
import com.zeny.hotel.rooms.application.RoomTypeView
import com.zeny.hotel.rooms.application.RoomView
import com.zeny.hotel.rooms.application.RoomsDirectory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Leituras de `rooms` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas. */
@Component
class JdbcRoomsDirectory(
    private val jdbc: JdbcClient,
) : RoomsDirectory {
    override fun features(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomFeatureView> =
        jdbc
            .sql(
                """
                SELECT * FROM rooms.room_feature
                WHERE (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:afterCode AS text) IS NULL
                       OR (code, id) > (CAST(:afterCode AS text), CAST(:afterId AS uuid)))
                ORDER BY code, id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterCode", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                RoomFeatureView(
                    RoomFeatureId(rs.uuid("id")),
                    rs.getString("system_key")?.let(RoomFeatureKey::valueOf),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getBoolean("active"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun roomTypes(
        active: Boolean?,
        after: Pair<Long, UUID>?,
        limit: Int,
    ): List<RoomTypeView> =
        jdbc
            .sql(
                """
                SELECT t.*, ARRAY(SELECT l.feature_id FROM rooms.room_type_feature_link l
                                  WHERE l.room_type_id = t.id ORDER BY l.feature_id) AS feature_ids
                FROM rooms.room_type t
                WHERE (CAST(:active AS boolean) IS NULL OR t.active = CAST(:active AS boolean))
                  AND (CAST(:afterOrder AS bigint) IS NULL
                       OR (t.display_order, t.id) > (CAST(:afterOrder AS bigint), CAST(:afterId AS uuid)))
                ORDER BY t.display_order, t.id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterOrder", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                RoomTypeView(
                    RoomTypeId(rs.uuid("id")),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("description"),
                    Occupancy(
                        rs.getInt("standard_adults"),
                        rs.getInt("standard_children"),
                        rs.getInt("max_adults"),
                        rs.getInt("max_children"),
                    ),
                    rs.getString("bed_setup"),
                    rs.getInt("display_order"),
                    rs.featureIds(),
                    rs.getBoolean("active"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun rooms(
        roomTypeId: RoomTypeId?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomView> =
        jdbc
            .sql(
                """
                SELECT r.*, ARRAY(SELECT l.feature_id FROM rooms.room_feature_link l
                                  WHERE l.room_id = r.id ORDER BY l.feature_id) AS feature_ids
                FROM rooms.room r
                WHERE (CAST(:type AS uuid) IS NULL OR r.room_type_id = CAST(:type AS uuid))
                  AND (CAST(:active AS boolean) IS NULL OR r.active = CAST(:active AS boolean))
                  AND (CAST(:afterNumber AS text) IS NULL
                       OR (lower(r.number), r.id) > (CAST(:afterNumber AS text), CAST(:afterId AS uuid)))
                ORDER BY lower(r.number), r.id LIMIT :limit
                """.trimIndent(),
            ).param("type", roomTypeId?.value)
            .param("active", active)
            .param("afterNumber", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                RoomView(
                    RoomId(rs.uuid("id")),
                    rs.getString("number"),
                    rs.getString("floor"),
                    RoomTypeId(rs.uuid("room_type_id")),
                    rs.featureIds(),
                    rs.getString("notes"),
                    rs.getBoolean("active"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun areas(
        kind: AreaKind?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<AreaView> =
        jdbc
            .sql(
                """
                SELECT * FROM rooms.area
                WHERE (CAST(:kind AS text) IS NULL OR kind = CAST(:kind AS text))
                  AND (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:afterName AS text) IS NULL
                       OR (lower(name), id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
                ORDER BY lower(name), id LIMIT :limit
                """.trimIndent(),
            ).param("kind", kind?.name)
            .param("active", active)
            .param("afterName", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                AreaView(
                    AreaId(rs.uuid("id")),
                    rs.getString("name"),
                    AreaKind.valueOf(rs.getString("kind")),
                    rs.getString("description"),
                    rs.getBoolean("active"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

/** A coluna `feature_ids` (`uuid[]`) como conjunto de ids. */
private fun ResultSet.featureIds(): Set<RoomFeatureId> =
    (getArray("feature_ids").array as Array<*>).map { RoomFeatureId(it as UUID) }.toSet()
