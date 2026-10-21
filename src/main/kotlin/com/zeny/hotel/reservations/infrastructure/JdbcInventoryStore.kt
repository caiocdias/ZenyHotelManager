package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.reservations.application.InventoryStore
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.rooms.RoomTypeId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * O inventário por tipo e data pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizado e só no schema
 * `reservations`. As travas de linha saem de um só `SELECT … ORDER BY room_type_id, stay_date FOR UPDATE`: o
 * PostgreSQL trava as linhas na ordem da consulta (o `LockRows` fica acima do `Sort`), então quaisquer duas transações
 * travam as linhas que têm em comum na mesma ordem e nunca esperam uma pela outra em círculo. Os vetores (`unnest`) vão
 * em texto (`{a,b}`) convertido pelo `CAST`: ids, datas e inteiros nunca têm vírgula nem aspas.
 */
@Component
class JdbcInventoryStore(
    private val jdbc: JdbcClient,
) : InventoryStore {
    override fun lockRoomTypes(roomTypeIds: Collection<RoomTypeId>) {
        roomTypeIds.map { it.value.toString() }.distinct().sorted().forEach { id ->
            jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtext('reservations.inventory'), hashtext(:id))")
                .param("id", id)
                .query { _, _ -> 0 }
                .list()
        }
    }

    override fun createMissing(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
    ): Set<InventoryKey> =
        jdbc
            .sql(
                """
                INSERT INTO reservations.room_type_inventory (room_type_id, stay_date, updated_at)
                SELECT t.id, CAST(d AS date), :now
                FROM unnest(CAST(:types AS uuid[])) AS t(id)
                CROSS JOIN generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') AS d
                ORDER BY t.id, d
                ON CONFLICT ON CONSTRAINT room_type_inventory_pk DO NOTHING
                RETURNING room_type_id, stay_date
                """.trimIndent(),
            ).param("types", array(roomTypeIds.map { it.value }))
            .param("from", from)
            .param("to", to)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .query { rs, _ -> key(rs) }
            .set()

    override fun lockRows(keys: Collection<InventoryKey>): Map<InventoryKey, InventoryCounters> {
        if (keys.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT i.* FROM reservations.room_type_inventory i
                JOIN unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                  ON i.room_type_id = k.room_type_id AND i.stay_date = k.stay_date
                ORDER BY i.room_type_id, i.stay_date
                FOR UPDATE OF i
                """.trimIndent(),
            ).param("types", array(keys.map { it.roomTypeId.value }))
            .param("dates", array(keys.map { it.date }))
            .query { rs, _ -> key(rs) to counters(rs) }
            .list()
            .toMap()
    }

    override fun lockRows(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryCounters> =
        jdbc
            .sql(
                """
                SELECT * FROM reservations.room_type_inventory
                WHERE room_type_id = ANY (CAST(:types AS uuid[])) AND stay_date BETWEEN :from AND :to
                ORDER BY room_type_id, stay_date
                FOR UPDATE
                """.trimIndent(),
            ).param("types", array(roomTypeIds.map { it.value }))
            .param("from", from)
            .param("to", to)
            .query { rs, _ -> key(rs) to counters(rs) }
            .list()
            .toMap()

    override fun rows(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryCounters> =
        jdbc
            .sql(
                """
                SELECT * FROM reservations.room_type_inventory
                WHERE room_type_id = :type AND stay_date BETWEEN :from AND :to
                ORDER BY stay_date
                """.trimIndent(),
            ).param("type", roomTypeId.value)
            .param("from", from)
            .param("to", to)
            .query { rs, _ -> key(rs) to counters(rs) }
            .list()
            .toMap()

    override fun rows(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryCounters> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT * FROM reservations.room_type_inventory
                WHERE room_type_id = ANY (CAST(:types AS uuid[])) AND stay_date BETWEEN :from AND :to
                ORDER BY room_type_id, stay_date
                """.trimIndent(),
            ).param("types", array(roomTypeIds.map { it.value }))
            .param("from", from)
            .param("to", to)
            .query { rs, _ -> key(rs) to counters(rs) }
            .list()
            .toMap()
    }

    override fun update(
        rows: Map<InventoryKey, InventoryCounters>,
        now: Instant,
    ) {
        if (rows.isEmpty()) return
        val keys = rows.keys.toList()
        val values = keys.map { rows.getValue(it) }
        jdbc
            .sql(
                """
                UPDATE reservations.room_type_inventory i
                SET total = r.total, sold = r.sold, blocked = r.blocked, out_of_order = r.out_of_order,
                    overbooking_limit = r.overbooking_limit, updated_at = :now
                FROM unnest(CAST(:types AS uuid[]), CAST(:dates AS date[]), CAST(:total AS int[]),
                            CAST(:sold AS int[]), CAST(:blocked AS int[]), CAST(:ooo AS int[]),
                            CAST(:overbooking AS int[]))
                    AS r(room_type_id, stay_date, total, sold, blocked, out_of_order, overbooking_limit)
                WHERE i.room_type_id = r.room_type_id AND i.stay_date = r.stay_date
                """.trimIndent(),
            ).param("now", now.atOffset(ZoneOffset.UTC))
            .param("types", array(keys.map { it.roomTypeId.value }))
            .param("dates", array(keys.map { it.date }))
            .param("total", array(values.map { it.total }))
            .param("sold", array(values.map { it.sold }))
            .param("blocked", array(values.map { it.blocked }))
            .param("ooo", array(values.map { it.outOfOrder }))
            .param("overbooking", array(values.map { it.overbookingLimit }))
            .update()
            .also { check(it == rows.size) { "Linhas do inventário ausentes: $it de ${rows.size}" } }
    }

    override fun roomTypesFrom(from: LocalDate): Set<RoomTypeId> =
        jdbc
            .sql("SELECT DISTINCT room_type_id FROM reservations.room_type_inventory WHERE stay_date >= :from")
            .param("from", from)
            .query { rs, _ -> RoomTypeId(rs.getObject("room_type_id", UUID::class.java)) }
            .set()

    private fun key(rs: ResultSet) =
        InventoryKey(
            RoomTypeId(rs.getObject("room_type_id", UUID::class.java)),
            rs.getObject("stay_date", LocalDate::class.java),
        )

    private fun counters(rs: ResultSet) =
        InventoryCounters(
            rs.getInt("total"),
            rs.getInt("sold"),
            rs.getInt("blocked"),
            rs.getInt("out_of_order"),
            rs.getInt("overbooking_limit"),
        )

    private fun array(values: Collection<Any>): String = values.joinToString(",", "{", "}")
}
