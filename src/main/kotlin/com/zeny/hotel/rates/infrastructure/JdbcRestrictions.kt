package com.zeny.hotel.rates.infrastructure

import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.application.RestrictionStore
import com.zeny.hotel.rates.domain.RestrictionKey
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rooms.RoomTypeId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * As restrições de venda do `rates` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas e só no schema `rates`.
 * Uma edição em massa grava todas as linhas num só comando, com cada coluna passada como vetor (`unnest`), e a linha
 * geral (tipo de UH nulo) usa a mesma chave única (`rate_restriction_uk`, `NULLS NOT DISTINCT`).
 */
@Component
class JdbcRestrictions(
    private val jdbc: JdbcClient,
) : RestrictionStore {
    override fun restrictions(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<RestrictionKey, RestrictionValues> =
        jdbc
            .sql(
                """
                SELECT room_type_id, stay_date, closed, closed_to_arrival, closed_to_departure, min_stay, max_stay,
                       min_advance_days, max_advance_days
                FROM rates.rate_restriction
                WHERE rate_plan_id = :plan
                  AND stay_date BETWEEN :from AND :to
                  AND (room_type_id IS NULL OR room_type_id = ANY (CAST(:types AS uuid[])))
                ORDER BY stay_date, room_type_id NULLS FIRST
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("types", array(roomTypeIds.map { it.value }))
            .param("from", from)
            .param("to", to)
            .query { rs, _ ->
                val key =
                    RestrictionKey(
                        rs.getObject("room_type_id", UUID::class.java)?.let(::RoomTypeId),
                        rs.getObject("stay_date", LocalDate::class.java),
                    )
                key to
                    RestrictionValues(
                        rs.nullableBoolean("closed"),
                        rs.nullableBoolean("closed_to_arrival"),
                        rs.nullableBoolean("closed_to_departure"),
                        rs.nullableInt("min_stay"),
                        rs.nullableInt("max_stay"),
                        rs.nullableInt("min_advance_days"),
                        rs.nullableInt("max_advance_days"),
                    )
            }.list()
            .toMap()

    override fun upsert(
        planId: RatePlanId,
        rows: Map<RestrictionKey, RestrictionValues>,
        now: Instant,
    ) {
        if (rows.isEmpty()) return
        val keys = rows.keys.toList()
        val values = keys.map { rows.getValue(it) }
        jdbc
            .sql(
                """
                INSERT INTO rates.rate_restriction
                    (rate_plan_id, room_type_id, stay_date, closed, closed_to_arrival, closed_to_departure, min_stay,
                     max_stay, min_advance_days, max_advance_days, updated_at)
                SELECT :plan, r.room_type_id, r.stay_date, r.closed, r.closed_to_arrival, r.closed_to_departure,
                       r.min_stay, r.max_stay, r.min_advance_days, r.max_advance_days, :now
                FROM unnest(CAST(:types AS uuid[]), CAST(:dates AS date[]), CAST(:closed AS boolean[]),
                            CAST(:cta AS boolean[]), CAST(:ctd AS boolean[]), CAST(:minStay AS int[]),
                            CAST(:maxStay AS int[]), CAST(:minAdvance AS int[]), CAST(:maxAdvance AS int[]))
                    AS r(room_type_id, stay_date, closed, closed_to_arrival, closed_to_departure, min_stay, max_stay,
                         min_advance_days, max_advance_days)
                ON CONFLICT ON CONSTRAINT rate_restriction_uk DO UPDATE
                    SET closed = EXCLUDED.closed, closed_to_arrival = EXCLUDED.closed_to_arrival,
                        closed_to_departure = EXCLUDED.closed_to_departure, min_stay = EXCLUDED.min_stay,
                        max_stay = EXCLUDED.max_stay, min_advance_days = EXCLUDED.min_advance_days,
                        max_advance_days = EXCLUDED.max_advance_days, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("types", array(keys.map { it.roomTypeId?.value }))
            .param("dates", array(keys.map { it.date }))
            .param("closed", array(values.map { it.closed }))
            .param("cta", array(values.map { it.closedToArrival }))
            .param("ctd", array(values.map { it.closedToDeparture }))
            .param("minStay", array(values.map { it.minStay }))
            .param("maxStay", array(values.map { it.maxStay }))
            .param("minAdvance", array(values.map { it.minAdvanceDays }))
            .param("maxAdvance", array(values.map { it.maxAdvanceDays }))
            .update()
    }

    override fun remove(
        planId: RatePlanId,
        keys: List<RestrictionKey>,
    ) {
        if (keys.isEmpty()) return
        jdbc
            .sql(
                """
                DELETE FROM rates.rate_restriction r
                USING unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                WHERE r.rate_plan_id = :plan AND r.room_type_id IS NOT DISTINCT FROM k.room_type_id
                  AND r.stay_date = k.stay_date
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("types", array(keys.map { it.roomTypeId?.value }))
            .param("dates", array(keys.map { it.date }))
            .update()
    }

    private fun ResultSet.nullableBoolean(column: String): Boolean? = getBoolean(column).takeUnless { wasNull() }

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }

    /**
     * Um vetor do PostgreSQL em texto (`{a,NULL,b}`), convertido pelo `CAST` do comando; os valores nunca têm vírgula
     * nem aspas (ids, datas, booleanos e inteiros), e o nulo vira `NULL`.
     */
    private fun array(values: Collection<Any?>): String =
        values.joinToString(",", "{", "}") { it?.toString() ?: "NULL" }
}
