package com.zeny.hotel.guests.infrastructure

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.application.CompanyFilter
import com.zeny.hotel.guests.application.DuplicatePairRow
import com.zeny.hotel.guests.application.GuestFilter
import com.zeny.hotel.guests.application.GuestsDirectory
import com.zeny.hotel.guests.application.SearchRow
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * As buscas de `guests` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas. Devolvem só o id e a chave
 * de ordenação: nenhuma coluna cifrada é lida aqui. O nome é procurado por trecho na coluna normalizada
 * (`search_name`, sem acento e em minúsculas), com o índice de trigramas; cada palavra do termo precisa aparecer.
 * As buscas de perfis só devolvem os ativos: um perfil mesclado (RF-HOS-05) só se lê pelo id.
 */
@Component
class JdbcGuestsDirectory(
    private val jdbc: JdbcClient,
) : GuestsDirectory {
    override fun searchGuests(
        filter: GuestFilter,
        after: SearchRow?,
        limit: Int,
    ): List<SearchRow> {
        val terms = terms(filter.name)
        val sql =
            """
            SELECT g.id, g.search_name FROM guests.guest g
            WHERE g.status = 'ACTIVE'
              AND (CAST(:email AS text) IS NULL OR g.email = CAST(:email AS text))
              AND (CAST(:restricted AS boolean) IS NULL OR g.restricted = CAST(:restricted AS boolean))
              AND (CAST(:vip AS boolean) IS NULL OR g.vip = CAST(:vip AS boolean))
              AND (CAST(:companyId AS uuid) IS NULL OR g.company_id = CAST(:companyId AS uuid))
              ${nameConditions("g", terms)}
              AND (CAST(:afterName AS text) IS NULL
                   OR (g.search_name, g.id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
            ORDER BY g.search_name, g.id LIMIT :limit
            """.trimIndent()
        return jdbc
            .sql(sql)
            .param("email", filter.email)
            .param("restricted", filter.restricted)
            .param("vip", filter.vip)
            .param("companyId", filter.companyId?.value)
            .params(termParams(terms))
            .param("afterName", after?.searchName)
            .param("afterId", after?.id)
            .param("limit", limit)
            .query { rs, _ -> SearchRow(rs.getObject("id", UUID::class.java), rs.getString("search_name")) }
            .list()
    }

    override fun guestsWithDocumentIndex(
        hashes: Collection<String>,
        limit: Int,
    ): List<SearchRow> =
        if (hashes.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql(
                    """
                    SELECT g.id, g.search_name FROM guests.guest g
                    WHERE g.status = 'ACTIVE'
                      AND g.id IN (SELECT d.guest_id FROM guests.guest_document d WHERE d.number_index IN (:hashes))
                    ORDER BY g.search_name, g.id LIMIT :limit
                    """.trimIndent(),
                ).param("hashes", hashes.toList())
                .param("limit", limit)
                .query { rs, _ -> SearchRow(rs.getObject("id", UUID::class.java), rs.getString("search_name")) }
                .list()
        }

    override fun duplicatePairs(
        guestId: GuestId?,
        after: Pair<UUID, UUID>?,
        limit: Int,
    ): List<DuplicatePairRow> {
        // Documento igual = o mesmo índice cego (tipo, país e número, com a mesma chave do anel); e-mail igual = a
        // mesma coluna, já gravada em minúsculas. Cada par uma vez (o menor id primeiro), só entre perfis ativos.
        val sql =
            """
            SELECT p.first_id, p.second_id,
                   bool_or(p.document_type IS NULL) AS same_email,
                   string_agg(DISTINCT p.document_type, ',' ORDER BY p.document_type) AS document_types
            FROM (SELECT a.guest_id AS first_id, b.guest_id AS second_id, a.type AS document_type
                  FROM guests.guest_document a
                  JOIN guests.guest_document b ON b.number_index = a.number_index AND a.guest_id < b.guest_id
                  UNION ALL
                  SELECT a.id, b.id, NULL
                  FROM guests.guest a
                  JOIN guests.guest b ON b.email = a.email AND a.id < b.id) p
            JOIN guests.guest f ON f.id = p.first_id AND f.status = 'ACTIVE'
            JOIN guests.guest s ON s.id = p.second_id AND s.status = 'ACTIVE'
            WHERE (CAST(:guestId AS uuid) IS NULL
                   OR p.first_id = CAST(:guestId AS uuid) OR p.second_id = CAST(:guestId AS uuid))
              AND (CAST(:afterFirst AS uuid) IS NULL
                   OR (p.first_id, p.second_id) > (CAST(:afterFirst AS uuid), CAST(:afterSecond AS uuid)))
            GROUP BY p.first_id, p.second_id
            ORDER BY p.first_id, p.second_id LIMIT :limit
            """.trimIndent()
        return jdbc
            .sql(sql)
            .param("guestId", guestId?.value)
            .param("afterFirst", after?.first)
            .param("afterSecond", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                DuplicatePairRow(
                    firstId = rs.getObject("first_id", UUID::class.java),
                    secondId = rs.getObject("second_id", UUID::class.java),
                    sameEmail = rs.getBoolean("same_email"),
                    documentTypes =
                        rs
                            .getString("document_types")
                            ?.split(',')
                            ?.map(DocumentType::valueOf)
                            .orEmpty(),
                )
            }.list()
    }

    override fun absorbedChain(survivorId: GuestId): List<GuestId> =
        jdbc
            .sql(
                """
                WITH RECURSIVE chain(id) AS (
                    SELECT g.id FROM guests.guest g WHERE g.merged_into = :survivor
                    UNION
                    SELECT g.id FROM guests.guest g JOIN chain c ON g.merged_into = c.id
                )
                SELECT id FROM chain ORDER BY id
                """.trimIndent(),
            ).param("survivor", survivorId.value)
            .query { rs, _ -> GuestId(rs.getObject("id", UUID::class.java)) }
            .list()

    override fun survivorChain(id: GuestId): List<GuestId> =
        jdbc
            .sql(
                """
                WITH RECURSIVE up(id, merged_into, depth) AS (
                    SELECT g.id, g.merged_into, 0 FROM guests.guest g WHERE g.id = :id
                    UNION ALL
                    SELECT g.id, g.merged_into, up.depth + 1 FROM guests.guest g JOIN up ON g.id = up.merged_into
                )
                SELECT id FROM up ORDER BY depth
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ -> GuestId(rs.getObject("id", UUID::class.java)) }
            .list()

    override fun searchCompanies(
        filter: CompanyFilter,
        after: SearchRow?,
        limit: Int,
    ): List<SearchRow> {
        val terms = terms(filter.name)
        val sql =
            """
            SELECT c.id, c.search_name FROM guests.company c
            WHERE (CAST(:cnpj AS text) IS NULL OR c.cnpj = CAST(:cnpj AS text))
              AND (CAST(:active AS boolean) IS NULL OR c.active = CAST(:active AS boolean))
              ${nameConditions("c", terms)}
              AND (CAST(:afterName AS text) IS NULL
                   OR (c.search_name, c.id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
            ORDER BY c.search_name, c.id LIMIT :limit
            """.trimIndent()
        return jdbc
            .sql(sql)
            .param("cnpj", filter.cnpj?.value)
            .param("active", filter.active)
            .params(termParams(terms))
            .param("afterName", after?.searchName)
            .param("afterId", after?.id)
            .param("limit", limit)
            .query { rs, _ -> SearchRow(rs.getObject("id", UUID::class.java), rs.getString("search_name")) }
            .list()
    }

    /** As palavras do termo já normalizado (até [MAX_TERMS]). */
    private fun terms(name: String?): List<String> =
        name
            ?.split(' ')
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.take(MAX_TERMS)
            .orEmpty()

    /**
     * Uma condição `LIKE` por palavra; o texto entra só como parâmetro (os nomes `:term0`… são fixos), com `%`, `_`
     * e o próprio escape (`!`, sem sentido especial no SQL nem no parser de parâmetros nomeados) escapados.
     */
    private fun nameConditions(
        alias: String,
        terms: List<String>,
    ): String = terms.indices.joinToString("") { " AND $alias.search_name LIKE :term$it ESCAPE '!'" }

    private fun termParams(terms: List<String>): Map<String, Any> =
        terms.withIndex().associate { (index, term) -> "term$index" to "%${escapeLike(term)}%" }

    private fun escapeLike(term: String): String = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private companion object {
        const val MAX_TERMS = 5
    }
}
