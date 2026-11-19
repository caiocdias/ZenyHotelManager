package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.LostAndFoundStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.application.LostAndFoundPageKey
import com.zeny.hotel.housekeeping.application.LostAndFoundQuery
import com.zeny.hotel.housekeeping.application.LostAndFoundStore
import com.zeny.hotel.housekeeping.domain.LostAndFoundItem
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os achados e perdidos (D2-E06) por `JdbcClient`, sempre parametrizados e só em `housekeeping.lost_and_found_item`:
 * `INSERT`, a leitura (com a linha travada, `FOR UPDATE`, nas ações), o `UPDATE` do destino com a versão e a página da
 * listagem, com o escopo e o filtro de vencidos na própria consulta. Nunca apaga.
 */
@Component
class JdbcLostAndFoundStore(
    private val jdbc: JdbcClient,
) : LostAndFoundStore {
    override fun insert(item: LostAndFoundItem) {
        jdbc
            .sql(
                """
                INSERT INTO $LOST_AND_FOUND_TABLE (id, room_id, area_id, description, storage_place, found_at,
                    reported_by, source, checklist_execution_id, checklist_item_key, status, returned_to,
                    disposal_reason, resolved_at, resolved_by, created_at, updated_at, version)
                VALUES (:id, :room, :area, :description, :storagePlace, :foundAt, :reportedBy, :source, :execution,
                    :itemKey, :status, :returnedTo, :reason, :resolvedAt, :resolvedBy, :createdAt, :updatedAt,
                    :version)
                """.trimIndent(),
            ).param("id", item.id.value)
            .param("room", item.roomId?.value)
            .param("area", item.areaId?.value)
            .param("description", item.description)
            .param("storagePlace", item.storagePlace)
            .param("foundAt", utc(item.foundAt))
            .param("reportedBy", item.reportedBy.value)
            .param("source", item.source.name)
            .param("execution", item.checklistExecutionId?.value)
            .param("itemKey", item.checklistItemKey)
            .param("status", item.status.name)
            .param("returnedTo", item.returnedTo)
            .param("reason", item.disposalReason)
            .param("resolvedAt", item.resolvedAt?.let(::utc))
            .param("resolvedBy", item.resolvedBy?.value)
            .param("createdAt", utc(item.createdAt))
            .param("updatedAt", utc(item.updatedAt))
            .param("version", item.version)
            .update()
    }

    override fun find(id: LostAndFoundItemId): LostAndFoundItem? = byId(id, lock = false)

    override fun findForUpdate(id: LostAndFoundItemId): LostAndFoundItem? = byId(id, lock = true)

    override fun update(item: LostAndFoundItem) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE $LOST_AND_FOUND_TABLE SET status = :status, storage_place = :storagePlace,
                        returned_to = :returnedTo,
                        disposal_reason = :reason, resolved_at = :resolvedAt, resolved_by = :resolvedBy,
                        updated_at = :updatedAt, version = version + 1
                    WHERE id = :id AND version = :version
                    """.trimIndent(),
                ).param("id", item.id.value)
                .param("status", item.status.name)
                .param("storagePlace", item.storagePlace)
                .param("returnedTo", item.returnedTo)
                .param("reason", item.disposalReason)
                .param("resolvedAt", item.resolvedAt?.let(::utc))
                .param("resolvedBy", item.resolvedBy?.value)
                .param("updatedAt", utc(item.updatedAt))
                .param("version", item.version)
                .update()
        check(updated == 1) { "O item ${item.id} mudou sem trava (versão ${item.version})" }
        item.version += 1
    }

    override fun page(
        query: LostAndFoundQuery,
        after: LostAndFoundPageKey?,
        limit: Int,
    ): List<LostAndFoundItem> {
        val conditions = mutableListOf<String>()
        if (query.ownerId != null) conditions += "reported_by = :owner"
        when (query.expired) {
            true -> conditions += EXPIRED
            false -> conditions += "NOT ($EXPIRED)"
            null -> Unit
        }
        if (after != null) conditions += "(found_at, id) < (:afterAt, :afterId)"
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        var statement =
            jdbc
                .sql("SELECT * FROM $LOST_AND_FOUND_TABLE $where ORDER BY found_at DESC, id DESC LIMIT :limit")
                .param("limit", limit)
        if (query.ownerId != null) statement = statement.param("owner", query.ownerId)
        if (query.expired != null) statement = statement.param("cutoff", utc(query.expiryCutoff))
        if (after != null) statement = statement.param("afterAt", utc(after.foundAt)).param("afterId", after.id)
        return statement.query { rs, _ -> item(rs) }.list()
    }

    private fun byId(
        id: LostAndFoundItemId,
        lock: Boolean,
    ): LostAndFoundItem? =
        jdbc
            .sql("SELECT * FROM $LOST_AND_FOUND_TABLE WHERE id = :id" + if (lock) " FOR UPDATE" else "")
            .param("id", id.value)
            .query { rs, _ -> item(rs) }
            .optional()
            .orElse(null)

    private fun item(rs: ResultSet): LostAndFoundItem =
        LostAndFoundItem.restore(
            LostAndFoundItemId(rs.getObject("id", UUID::class.java)),
            rs.getObject("room_id", UUID::class.java)?.let(::RoomId),
            rs.getObject("area_id", UUID::class.java)?.let(::AreaId),
            rs.getString("description"),
            rs.getString("storage_place"),
            checkNotNull(instant(rs, "found_at")),
            UserId(rs.getObject("reported_by", UUID::class.java)),
            OccurrenceSource.valueOf(rs.getString("source")),
            rs.getObject("checklist_execution_id", UUID::class.java)?.let(::ChecklistExecutionId),
            rs.getString("checklist_item_key"),
            LostAndFoundStatus.valueOf(rs.getString("status")),
            rs.getString("returned_to"),
            rs.getString("disposal_reason"),
            instant(rs, "resolved_at"),
            rs.getObject("resolved_by", UUID::class.java)?.let(::UserId),
            checkNotNull(instant(rs, "created_at")),
            checkNotNull(instant(rs, "updated_at")),
            rs.getLong("version"),
        )

    private fun instant(
        rs: ResultSet,
        column: String,
    ): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        /** Vencido (D2-E06): guardado e achado antes do corte do prazo de guarda. */
        const val EXPIRED = "status = 'STORED' AND found_at < :cutoff"
    }
}

/** Os achados e perdidos (D2-E06): alterados pelo destino, nunca apagados. */
internal const val LOST_AND_FOUND_TABLE = "housekeeping.lost_and_found_item"
