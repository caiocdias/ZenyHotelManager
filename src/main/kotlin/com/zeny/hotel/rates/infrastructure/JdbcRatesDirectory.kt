package com.zeny.hotel.rates.infrastructure

import com.zeny.hotel.rates.application.RatePlanFilter
import com.zeny.hotel.rates.application.RatesDirectory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/** Páginas do `rates` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas e só no schema `rates`. */
@Component
class JdbcRatesDirectory(
    private val jdbc: JdbcClient,
) : RatesDirectory {
    override fun ratePlans(
        filter: RatePlanFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> =
        jdbc
            .sql(
                """
                SELECT p.code AS sort_key, p.id FROM rates.rate_plan p
                WHERE (CAST(:active AS boolean) IS NULL OR p.active = CAST(:active AS boolean))
                  AND (CAST(:category AS text) IS NULL OR p.category = CAST(:category AS text))
                  AND (CAST(:channel AS text) IS NULL OR EXISTS (
                        SELECT 1 FROM rates.rate_plan_channel c
                        WHERE c.rate_plan_id = p.id AND c.channel = CAST(:channel AS text)))
                  AND (CAST(:roomTypeId AS uuid) IS NULL OR EXISTS (
                        SELECT 1 FROM rates.rate_plan_room_type t
                        WHERE t.rate_plan_id = p.id AND t.room_type_id = CAST(:roomTypeId AS uuid)))
                  AND (CAST(:afterKey AS text) IS NULL
                       OR (p.code, p.id) > (CAST(:afterKey AS text), CAST(:afterId AS uuid)))
                ORDER BY p.code, p.id LIMIT :limit
                """.trimIndent(),
            ).param("active", filter.active)
            .param("category", filter.category?.name)
            .param("channel", filter.channel?.name)
            .param("roomTypeId", filter.roomTypeId?.value)
            .param("afterKey", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.getString("sort_key") to rs.getObject("id", UUID::class.java) }
            .list()

    override fun cancellationPolicies(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> = byName("rates.cancellation_policy", active, after, limit)

    override fun guaranteePolicies(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> = byName("rates.guarantee_policy", active, after, limit)

    override fun ratePackages(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> = page("rates.rate_package", "code", active, after, limit)

    private fun byName(
        table: String,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ) = page(table, "lower(name)", active, after, limit)

    /** Uma página de [table] (nome fixo deste arquivo, nunca da requisição) em ordem de [sortKey] e id. */
    private fun page(
        table: String,
        sortKey: String,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> =
        jdbc
            .sql(
                """
                SELECT $sortKey AS sort_key, id FROM $table
                WHERE (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:afterKey AS text) IS NULL
                       OR ($sortKey, id) > (CAST(:afterKey AS text), CAST(:afterId AS uuid)))
                ORDER BY $sortKey, id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterKey", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.getString("sort_key") to rs.getObject("id", UUID::class.java) }
            .list()
}
