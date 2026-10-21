package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.BlockAllocationSummary
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.RoomBlockStatus
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.reservations.application.RoomBlockStore
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os bloqueios de grupo pelo `JdbcClient`, só no schema `reservations` e sempre parametrizado. As unidades entram e
 * mudam num comando (`jsonb_to_recordset` com `ON CONFLICT … DO UPDATE`).
 */
@Component
@Suppress("TooManyFunctions") // gravação, leituras, travas, recontagem, reapontamento e liberação
class JdbcRoomBlockStore(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : RoomBlockStore {
    override fun insert(block: RoomBlockSummary) {
        jdbc
            .sql(
                """
                INSERT INTO reservations.room_block (id, code, name, holder_guest_id, holder_company_id, rate_plan_id,
                    cutoff_date, status, folio_id, version, created_at, created_by, updated_at)
                VALUES (:id, :code, :name, :holderGuest, :holderCompany, :ratePlanId, :cutoff, :status, :folioId,
                    :version, :createdAt, :createdBy, :createdAt)
                """.trimIndent(),
            ).param("id", block.id.value)
            .param("code", block.code.toString())
            .param("name", block.name)
            .param("holderGuest", block.holderGuestId?.value)
            .param("holderCompany", block.holderCompanyId?.value)
            .param("ratePlanId", block.ratePlanId.value)
            .param("cutoff", block.cutoffDate)
            .param("status", block.status.name)
            .param("folioId", block.folioId.value)
            .param("version", block.version)
            .param("createdAt", block.createdAt.utc())
            .param("createdBy", block.createdBy?.value)
            .update()
        saveAllocations(block.id, block.allocations)
    }

    override fun block(id: RoomBlockId): RoomBlockSummary? {
        val allocations =
            jdbc
                .sql(
                    "SELECT * FROM reservations.block_allocation WHERE block_id = :id " +
                        "ORDER BY CAST(room_type_id AS text), stay_date",
                ).param("id", id.value)
                .query { rs, _ -> allocation(rs) }
                .list()
        return jdbc
            .sql("SELECT * FROM reservations.room_block WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> block(rs, allocations) }
            .optional()
            .orElse(null)
    }

    override fun blockForUpdate(id: RoomBlockId): RoomBlockSummary? {
        val exists =
            jdbc
                .sql("SELECT id FROM reservations.room_block WHERE id = :id FOR UPDATE")
                .param("id", id.value)
                .query { rs, _ -> rs.uuid("id") }
                .optional()
                .isPresent
        return if (exists) block(id) else null
    }

    override fun update(
        block: RoomBlockSummary,
        saved: List<BlockAllocationSummary>,
        removed: List<InventoryKey>,
        now: Instant,
    ) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE reservations.room_block SET name = :name, cutoff_date = :cutoff, status = :status,
                        released_at = :releasedAt, released_on = :releasedOn, released_by = :releasedBy,
                        version = :version, updated_at = :now
                    WHERE id = :id AND version = :version - 1
                    """.trimIndent(),
                ).param("id", block.id.value)
                .param("name", block.name)
                .param("cutoff", block.cutoffDate)
                .param("status", block.status.name)
                .param("releasedAt", block.releasedAt?.utc())
                .param("releasedOn", block.releasedOn)
                .param("releasedBy", block.releasedBy?.value)
                .param("version", block.version)
                .param("now", now.utc())
                .update()
        check(updated == 1) { "Bloqueio ${block.id} não gravado: versão ${block.version - 1} não confere" }
        removed.forEach { key ->
            jdbc
                .sql(
                    "DELETE FROM reservations.block_allocation " +
                        "WHERE block_id = :id AND room_type_id = :type AND stay_date = :date",
                ).param("id", block.id.value)
                .param("type", key.roomTypeId.value)
                .param("date", key.date)
                .update()
        }
        saveAllocations(block.id, saved)
    }

    private fun saveAllocations(
        id: RoomBlockId,
        rows: List<BlockAllocationSummary>,
    ) {
        if (rows.isEmpty()) return
        jdbc
            .sql(
                """
                INSERT INTO reservations.block_allocation (block_id, room_type_id, stay_date, quantity, picked_up,
                    released, rate)
                SELECT :id, a.room_type_id, a.stay_date, a.quantity, a.picked_up, a.released, a.rate
                FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS a(room_type_id uuid, stay_date date, quantity integer,
                    picked_up integer, released integer, rate numeric)
                ON CONFLICT (block_id, room_type_id, stay_date) DO UPDATE SET quantity = excluded.quantity,
                    picked_up = excluded.picked_up, released = excluded.released, rate = excluded.rate
                """.trimIndent(),
            ).param("id", id.value)
            .param(
                "rows",
                jsonMapper.writeValueAsString(
                    rows.map {
                        mapOf(
                            "room_type_id" to it.roomTypeId.toString(),
                            "stay_date" to it.date.toString(),
                            "quantity" to it.quantity,
                            "picked_up" to it.pickedUp,
                            "released" to it.released,
                            "rate" to it.rate.amount.toPlainString(),
                        )
                    },
                ),
            ).update()
    }

    override fun page(
        status: RoomBlockStatus?,
        after: RoomBlockId?,
        limit: Int,
    ): List<RoomBlockId> =
        jdbc
            .sql(
                """
                SELECT id FROM reservations.room_block
                WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                  AND (CAST(:after AS uuid) IS NULL OR id < CAST(:after AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("status", status?.name)
            .param("after", after?.value)
            .param("limit", limit)
            .query { rs, _ -> RoomBlockId(rs.uuid("id")) }
            .list()

    override fun blockedUnits(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT a.room_type_id, a.stay_date, sum(a.quantity - a.picked_up - a.released) AS blocked
                FROM reservations.block_allocation a
                JOIN reservations.room_block b ON b.id = a.block_id
                WHERE b.status = 'ACTIVE'
                  AND a.room_type_id = ANY (CAST(:types AS uuid[]))
                  AND a.stay_date BETWEEN :from AND :to
                GROUP BY a.room_type_id, a.stay_date
                """.trimIndent(),
            ).param("types", roomTypeIds.joinToString(",", "{", "}") { it.value.toString() })
            .param("from", from)
            .param("to", to)
            .query { rs, _ ->
                InventoryKey(RoomTypeId(rs.uuid("room_type_id")), rs.getObject("stay_date", LocalDate::class.java)) to
                    InventoryUsage(0, rs.getInt("blocked"))
            }.list()
            .toMap()
    }

    override fun repointHolder(
        merged: GuestId,
        target: GuestId,
        now: Instant,
    ): List<RoomBlockId> {
        if (merged == target) return emptyList()
        return jdbc
            .sql(
                """
                UPDATE reservations.room_block SET holder_guest_id = :target, version = version + 1, updated_at = :now
                WHERE holder_guest_id = :merged
                RETURNING id
                """.trimIndent(),
            ).param("target", target.value)
            .param("now", now.utc())
            .param("merged", merged.value)
            .query { rs, _ -> RoomBlockId(rs.uuid("id")) }
            .list()
            .sortedBy { it.value.toString() }
    }

    override fun blocksHeldBy(guestIds: Collection<GuestId>): List<RoomBlockId> {
        if (guestIds.isEmpty()) return emptyList()
        return jdbc
            .sql(
                "SELECT id FROM reservations.room_block WHERE holder_guest_id = ANY (CAST(:ids AS uuid[])) ORDER BY id",
            ).param("ids", guestIds.joinToString(",", "{", "}") { it.value.toString() })
            .query { rs, _ -> RoomBlockId(rs.uuid("id")) }
            .list()
    }

    override fun dueForRelease(businessDate: LocalDate): List<RoomBlockId> =
        jdbc
            .sql(
                "SELECT id FROM reservations.room_block WHERE status = 'ACTIVE' AND cutoff_date <= :date " +
                    "ORDER BY cutoff_date, id",
            ).param("date", businessDate)
            .query { rs, _ -> RoomBlockId(rs.uuid("id")) }
            .list()

    private fun block(
        rs: ResultSet,
        allocations: List<BlockAllocationSummary>,
    ) = RoomBlockSummary(
        id = RoomBlockId(rs.uuid("id")),
        code = DocumentNumber.parse(rs.getString("code")),
        name = rs.getString("name"),
        holderGuestId = rs.getObject("holder_guest_id", UUID::class.java)?.let(::GuestId),
        holderCompanyId = rs.getObject("holder_company_id", UUID::class.java)?.let(::CompanyId),
        ratePlanId = RatePlanId(rs.uuid("rate_plan_id")),
        cutoffDate = rs.getObject("cutoff_date", LocalDate::class.java),
        status = RoomBlockStatus.valueOf(rs.getString("status")),
        folioId = FolioId(rs.uuid("folio_id")),
        allocations = allocations,
        releasedAt = rs.getObject("released_at", OffsetDateTime::class.java)?.toInstant(),
        releasedOn = rs.getObject("released_on", LocalDate::class.java),
        releasedBy = rs.getObject("released_by", UUID::class.java)?.let(::UserId),
        version = rs.getLong("version"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
    )

    private fun allocation(rs: ResultSet) =
        BlockAllocationSummary(
            roomTypeId = RoomTypeId(rs.uuid("room_type_id")),
            date = rs.getObject("stay_date", LocalDate::class.java),
            quantity = rs.getInt("quantity"),
            pickedUp = rs.getInt("picked_up"),
            released = rs.getInt("released"),
            rate = Money.of(rs.getBigDecimal("rate")),
        )
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
