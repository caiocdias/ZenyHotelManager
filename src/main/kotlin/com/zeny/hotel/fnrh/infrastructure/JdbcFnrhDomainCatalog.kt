package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhDomainValueStore
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainCatalog
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/** O cache dos domínios oficiais (`fnrh.domain_value`) por JDBC, na transação corrente. */
@Component
class JdbcFnrhDomainCatalog(
    private val jdbc: JdbcClient,
) : FnrhDomainCatalog,
    FnrhDomainValueStore {
    override fun contains(
        domain: FnrhDomain,
        code: String,
    ): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM fnrh.domain_value WHERE domain = :domain AND code = :code)")
            .param("domain", domain.name)
            .param("code", code)
            .query(Boolean::class.java)
            .single()

    override fun values(domain: FnrhDomain): List<FnrhDomainValue> =
        jdbc
            .sql("SELECT code, label FROM fnrh.domain_value WHERE domain = :domain ORDER BY code")
            .param("domain", domain.name)
            .query { rs, _ -> FnrhDomainValue(rs.getString("code"), rs.getString("label")) }
            .list()

    override fun lockForRefresh() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", REFRESH_LOCK_KEY)
            .query()
            .listOfRows()
    }

    override fun counts(): Map<FnrhDomain, Int> {
        val counted =
            jdbc
                .sql("SELECT domain, count(*) FROM fnrh.domain_value GROUP BY domain")
                .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
                .list()
                .toMap()
        return FnrhDomain.entries.associateWith { counted[it.name] ?: 0 }
    }

    override fun replaceAll(
        values: Map<FnrhDomain, List<FnrhDomainValue>>,
        refreshedAt: Instant,
    ) {
        jdbc.sql("DELETE FROM fnrh.domain_value").update()
        for ((domain, domainValues) in values) {
            for (value in domainValues) {
                jdbc
                    .sql(
                        "INSERT INTO fnrh.domain_value (domain, code, label, refreshed_at) " +
                            "VALUES (:domain, :code, :label, :refreshedAt)",
                    ).param("domain", domain.name)
                    .param("code", value.code)
                    .param("label", value.label)
                    .param("refreshedAt", refreshedAt.atOffset(ZoneOffset.UTC))
                    .update()
            }
        }
    }

    private companion object {
        /** Chave fixa do `fnrh` para o lock de aviso do `refresh` (o ASCII de "FNRHDOM1"). */
        const val REFRESH_LOCK_KEY = 0x464E5248444F4D31L
    }
}
