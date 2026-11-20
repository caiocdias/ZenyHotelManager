package com.zeny.hotel.testsupport

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Conferências comuns dos testes das views `report_*` (ARCHITECTURE §11): as colunas do contrato com o tipo, a
 * ausência de coluna de texto livre e o papel da aplicação só com `SELECT`.
 */
object ReportViewAssertions {
    private val FREE_TEXT_NAMES =
        setOf(
            "notes",
            "note",
            "description",
            "reason",
            "responsible",
            "comment",
            "completion_notes",
            "cancellation_reason",
            "hold_reason",
            "refusal_reason",
            "third_party_name",
            "name",
        )
    private const val MAX_LABEL_LENGTH = 40
    private val ALL_PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")

    /** As colunas da view, em ordem, como `nome:tipo`. */
    fun columns(
        jdbc: JdbcClient,
        schema: String,
        view: String,
    ): List<String> =
        jdbc
            .sql(
                "SELECT column_name || ':' || data_type FROM information_schema.columns " +
                    "WHERE table_schema = :schema AND table_name = :view ORDER BY ordinal_position",
            ).param("schema", schema)
            .param("view", view)
            .query { rs, _ -> rs.getString(1) }
            .list()
            .filterNotNull()

    fun shouldHaveColumns(
        jdbc: JdbcClient,
        schema: String,
        view: String,
        expected: List<String>,
    ) = withClue("$schema.$view") { columns(jdbc, schema, view) shouldContainExactly expected }

    /** Nenhuma coluna de texto livre: nome de campo livre, texto sem limite curto, array ou JSON. */
    fun shouldHaveNoFreeText(
        jdbc: JdbcClient,
        schema: String,
        view: String,
    ) {
        val columns =
            jdbc
                .sql(
                    "SELECT column_name, data_type, character_maximum_length FROM information_schema.columns " +
                        "WHERE table_schema = :schema AND table_name = :view",
                ).param("schema", schema)
                .param("view", view)
                .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getObject(3) as Int?) }
                .list()
        columns.size shouldNotBe 0
        columns.forEach { (name, type, length) ->
            withClue("$schema.$view.$name") {
                (name in FREE_TEXT_NAMES) shouldBe false
                (type in setOf("ARRAY", "json", "jsonb", "text")) shouldBe false
                if (type == "character varying") (length != null && length <= MAX_LABEL_LENGTH) shouldBe true
            }
        }
    }

    /** O papel da aplicação só tem `SELECT` na view. */
    fun shouldBeReadOnlyForTheApplication(
        jdbc: JdbcClient,
        schema: String,
        view: String,
    ) {
        val granted =
            ALL_PRIVILEGES.filter {
                jdbc
                    .sql("SELECT has_table_privilege(current_user, :relation, :privilege)")
                    .param("relation", "$schema.$view")
                    .param("privilege", it)
                    .query(Boolean::class.java)
                    .single()
            }
        withClue("$schema.$view") { granted shouldContainExactly listOf("SELECT") }
    }
}
