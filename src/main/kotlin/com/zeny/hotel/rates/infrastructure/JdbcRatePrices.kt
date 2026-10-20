package com.zeny.hotel.rates.infrastructure

import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.application.DerivedPlan
import com.zeny.hotel.rates.application.RatePriceStore
import com.zeny.hotel.rates.domain.ChildPrice
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os preços do `rates` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizados e só no schema `rates`. Uma edição
 * em massa grava todas as linhas num só comando, com as listas de tipos e datas passadas como vetores
 * (`unnest(CAST(:x AS uuid[]), CAST(:y AS date[]))`).
 */
@Component
class JdbcRatePrices(
    private val jdbc: JdbcClient,
) : RatePriceStore {
    override fun lockDerivations() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('$DERIVATION_LOCK'))").query { _, _ -> 0 }.list()
    }

    override fun lockDerivationsShared() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext('$DERIVATION_LOCK'))").query { _, _ -> 0 }.list()
    }

    override fun prices(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<PriceKey, PriceSet> {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT p.room_type_id, p.stay_date, p.one_adult, p.two_adults, p.extra_adult,
                           c.min_age, c.max_age, c.amount
                    FROM rates.rate_price p
                    LEFT JOIN rates.rate_price_child c
                           ON c.rate_plan_id = p.rate_plan_id AND c.room_type_id = p.room_type_id
                          AND c.stay_date = p.stay_date
                    WHERE p.rate_plan_id = :plan
                      AND p.room_type_id = ANY (CAST(:types AS uuid[]))
                      AND p.stay_date BETWEEN :from AND :to
                    ORDER BY p.stay_date, p.room_type_id, c.min_age
                    """.trimIndent(),
                ).param("plan", planId.value)
                .param("types", array(roomTypeIds.map { it.value }))
                .param("from", from)
                .param("to", to)
                .query { rs, _ ->
                    val key =
                        PriceKey(
                            RoomTypeId(rs.getObject("room_type_id", UUID::class.java)),
                            rs.getObject("stay_date", LocalDate::class.java),
                        )
                    val base =
                        PriceSet(
                            Money.of(rs.getBigDecimal("one_adult")),
                            money(rs.getBigDecimal("two_adults")),
                            money(rs.getBigDecimal("extra_adult")),
                            emptyList(),
                        )
                    val child =
                        rs.getBigDecimal("amount")?.let {
                            ChildPrice(rs.getInt("min_age"), rs.getInt("max_age"), Money.of(it))
                        }
                    Triple(key, base, child)
                }.list()
        return rows
            .groupBy({ it.first }, { it })
            .mapValues { (_, group) -> group.first().second.copy(children = group.mapNotNull { it.third }) }
    }

    override fun upsert(
        planId: RatePlanId,
        keys: List<PriceKey>,
        prices: PriceSet,
        now: Instant,
    ) {
        if (keys.isEmpty()) return
        val types = array(keys.map { it.roomTypeId.value })
        val dates = array(keys.map { it.date })
        jdbc
            .sql(
                """
                INSERT INTO rates.rate_price
                    (rate_plan_id, room_type_id, stay_date, one_adult, two_adults, extra_adult, updated_at)
                SELECT :plan, k.room_type_id, k.stay_date, :oneAdult, CAST(:twoAdults AS numeric),
                       CAST(:extraAdult AS numeric), :now
                FROM unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                ON CONFLICT (rate_plan_id, room_type_id, stay_date) DO UPDATE
                    SET one_adult = EXCLUDED.one_adult, two_adults = EXCLUDED.two_adults,
                        extra_adult = EXCLUDED.extra_adult, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("oneAdult", checkNotNull(prices.oneAdult).amount)
            .param("twoAdults", prices.twoAdults?.amount)
            .param("extraAdult", prices.extraAdult?.amount)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("types", types)
            .param("dates", dates)
            .update()
        jdbc
            .sql(
                """
                DELETE FROM rates.rate_price_child c
                USING unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                WHERE c.rate_plan_id = :plan AND c.room_type_id = k.room_type_id AND c.stay_date = k.stay_date
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("types", types)
            .param("dates", dates)
            .update()
        if (prices.children.isEmpty()) return
        jdbc
            .sql(
                """
                INSERT INTO rates.rate_price_child (rate_plan_id, room_type_id, stay_date, min_age, max_age, amount)
                SELECT :plan, k.room_type_id, k.stay_date, b.min_age, b.max_age, b.amount
                FROM unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                CROSS JOIN unnest(CAST(:mins AS int[]), CAST(:maxs AS int[]), CAST(:amounts AS numeric[]))
                    AS b(min_age, max_age, amount)
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("types", types)
            .param("dates", dates)
            .param("mins", array(prices.children.map { it.minAge }))
            .param("maxs", array(prices.children.map { it.maxAge }))
            .param("amounts", array(prices.children.map { it.amount.amount.toPlainString() }))
            .update()
    }

    override fun remove(
        planId: RatePlanId,
        keys: List<PriceKey>,
    ) {
        if (keys.isEmpty()) return
        jdbc
            .sql(
                """
                DELETE FROM rates.rate_price p
                USING unnest(CAST(:types AS uuid[]), CAST(:dates AS date[])) AS k(room_type_id, stay_date)
                WHERE p.rate_plan_id = :plan AND p.room_type_id = k.room_type_id AND p.stay_date = k.stay_date
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("types", array(keys.map { it.roomTypeId.value }))
            .param("dates", array(keys.map { it.date }))
            .update()
    }

    override fun hasPricesFrom(
        planId: RatePlanId,
        date: LocalDate,
    ): Boolean =
        jdbc
            .sql(
                "SELECT EXISTS (SELECT 1 FROM rates.rate_price WHERE rate_plan_id = :plan AND stay_date >= :date)",
            ).param("plan", planId.value)
            .param("date", date)
            .query(Boolean::class.java)
            .single()

    override fun descendants(planId: RatePlanId): List<DerivedPlan> =
        jdbc
            .sql(
                """
                WITH RECURSIVE derived (id, depth, active) AS (
                    SELECT id, 1, active FROM rates.rate_plan WHERE base_rate_plan_id = :plan
                    UNION ALL
                    SELECT p.id, d.depth + 1, p.active
                    FROM rates.rate_plan p JOIN derived d ON p.base_rate_plan_id = d.id
                    WHERE d.depth < :maxDepth
                )
                SELECT id, depth, active FROM derived ORDER BY depth, id
                """.trimIndent(),
            ).param("plan", planId.value)
            .param("maxDepth", MAX_WALK)
            .query { rs, _ ->
                DerivedPlan(
                    RatePlanId(rs.getObject("id", UUID::class.java)),
                    rs.getInt("depth"),
                    rs.getBoolean("active"),
                )
            }.list()

    private fun money(value: BigDecimal?): Money? = value?.let(Money::of)

    /** Um vetor do PostgreSQL em texto (`{a,b}`), convertido pelo `CAST` do comando; os valores nunca têm vírgula. */
    private fun array(values: Collection<Any>): String = values.joinToString(",", "{", "}")

    private companion object {
        const val DERIVATION_LOCK = "rates.derivations"

        /** Teto da busca dos derivados (acima do teto da árvore, só para proteger de um dado corrompido). */
        const val MAX_WALK = 10
    }
}
