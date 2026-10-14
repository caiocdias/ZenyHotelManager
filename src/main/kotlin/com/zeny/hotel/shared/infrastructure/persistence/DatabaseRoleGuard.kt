package com.zeny.hotel.shared.infrastructure.persistence

import com.zeny.hotel.shared.AppendOnlyTables
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Confere na partida, antes de qualquer outra tarefa de boot, que a aplicação está conectada com
 * o **papel restrito** (H4, ARCHITECTURE §8.3 e §11) e não com o dono dos schemas, que é o do
 * Flyway (`ZENY_DB_MIGRATION_*`):
 *
 * - o papel não é superusuário nem cria papéis, bancos ou ignora RLS, e não cria schemas nem
 *   tabelas temporárias no banco;
 * - não cria objetos (DDL) em nenhum schema de módulo nem é dono (ou membro do dono) de nenhuma
 *   tabela ou sequence deles;
 * - não tem `UPDATE` (na tabela ou em alguma coluna), `DELETE` nem `TRUNCATE` em nenhuma tabela
 *   *append-only* declarada ([AppendOnlyTables]).
 *
 * Qualquer violação impede a partida: com o papel dono, a trilha de auditoria deixaria de ser
 * imutável.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DatabaseRoleGuard(
    private val jdbc: JdbcClient,
    private val flyway: Flyway,
    private val appendOnly: List<AppendOnlyTables>,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val problems = problems().joinToString("; ")
        check(problems.isEmpty()) {
            "O papel do banco da aplicação (ZENY_DB_USERNAME) não é o papel restrito: $problems. " +
                "Use o papel da aplicação, sem DDL, e deixe o dono dos schemas para o Flyway (ZENY_DB_MIGRATION_*)."
        }
        log.info("Papel do banco da aplicação conferido: sem DDL e sem alterar as tabelas append-only")
    }

    /** As violações encontradas; vazia com o papel restrito. */
    fun problems(): List<String> {
        val schemas = flyway.configuration.schemas.toList()
        return buildList {
            val attributes =
                jdbc
                    .sql(
                        """
                        SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls
                        FROM pg_roles WHERE rolname = current_user
                        """.trimIndent(),
                    ).query(Boolean::class.java)
                    .single()
            if (attributes) add("é superusuário ou tem CREATEROLE, CREATEDB ou BYPASSRLS")
            if (databasePrivilege("CREATE")) add("pode criar schemas no banco")
            if (databasePrivilege("TEMPORARY")) add("pode criar tabelas temporárias no banco")
            schemas
                .filter { schemaPrivilege(it, "CREATE") }
                .forEach { add("pode criar objetos no schema $it") }
            ownedObjects(schemas).forEach { add("é dono de $it") }
            appendOnly.flatMap { it.tables() }.flatMap(::mutationsOn).forEach(::add)
        }
    }

    /**
     * O que o papel pode mudar numa tabela *append-only*: `UPDATE` na tabela ou em qualquer coluna
     * (`GRANT UPDATE (coluna)`), `DELETE` e `TRUNCATE`.
     */
    private fun mutationsOn(table: String): List<String> =
        buildList {
            if (tablePrivilege(table, "UPDATE")) {
                add("tem UPDATE em $table")
            } else if (anyColumnPrivilege(table, "UPDATE")) {
                add("tem UPDATE em colunas de $table")
            }
            TABLE_MUTATIONS.filter { tablePrivilege(table, it) }.forEach { add("tem $it em $table") }
        }

    private fun databasePrivilege(privilege: String): Boolean =
        jdbc
            .sql("SELECT has_database_privilege(current_user, current_database(), :privilege)")
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun anyColumnPrivilege(
        table: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_any_column_privilege(current_user, :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun schemaPrivilege(
        schema: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_schema_privilege(current_user, :schema, :privilege)")
            .param("schema", schema)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun tablePrivilege(
        table: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    private fun ownedObjects(schemas: List<String>): List<String> =
        jdbc
            .sql(
                """
                SELECT n.nspname || '.' || c.relname
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN (:schemas) AND pg_has_role(current_user, c.relowner, 'USAGE')
                UNION ALL
                SELECT n.nspname FROM pg_namespace n
                WHERE n.nspname IN (:schemas) AND pg_has_role(current_user, n.nspowner, 'USAGE')
                ORDER BY 1
                """.trimIndent(),
            ).param("schemas", schemas)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private companion object {
        val TABLE_MUTATIONS = listOf("DELETE", "TRUNCATE")
    }
}
