package com.zeny.hotel.persistence

import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.infrastructure.persistence.DatabaseRoleGuard
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.PostgresContainer
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/**
 * Os dois papéis do banco (H4 do roadmap do M1, ARCHITECTURE §11): a aplicação conecta com o
 * papel restrito, criado fora das migrações pelo script de inicialização (o mesmo do compose), e
 * o Flyway com o dono dos schemas. O papel da aplicação lê e escreve linhas, mas não cria, altera,
 * remove nem é dono de nada; a partida recusa o papel dono ([DatabaseRoleGuard]).
 */
@IntegrationTest
class DatabaseRolesTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val guard: DatabaseRoleGuard,
    @Autowired private val appendOnly: List<AppendOnlyTables>,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val schemas = flyway.configuration.schemas.toList()

    @Test
    fun `the application connects with the restricted role and Flyway with the schema owner`() {
        val appUser = jdbc.sql("SELECT current_user").query(String::class.java).single()
        val migrationUser = owner.sql("SELECT current_user").query(String::class.java).single()

        appUser shouldBe PostgresContainer.APP_USERNAME
        migrationUser shouldBe PostgresContainer.ownerUsername
        appUser shouldNotBe migrationUser
        jdbc
            .sql(
                "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls " +
                    "FROM pg_roles WHERE rolname = current_user",
            ).query(Boolean::class.java)
            .single() shouldBe false
    }

    @Test
    fun `the schema owner applied every migration and owns every schema and table`() {
        val installedBy =
            owner
                .sql("SELECT DISTINCT installed_by FROM shared.flyway_schema_history")
                .query(String::class.java)
                .list()
        installedBy shouldBe listOf(PostgresContainer.ownerUsername)

        val foreignOwners =
            owner
                .sql(
                    """
                    SELECT n.nspname || '.' || c.relname || ' (' || pg_get_userbyid(c.relowner) || ')'
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname IN (:schemas) AND pg_get_userbyid(c.relowner) <> :owner
                    """.trimIndent(),
                ).param("schemas", schemas)
                .param("owner", PostgresContainer.ownerUsername)
                .query(String::class.java)
                .list()
        foreignOwners.shouldBeEmpty()
    }

    @Test
    fun `the application role cannot run DDL in any schema`() {
        val statements =
            schemas.flatMap { schema ->
                listOf(
                    "CREATE TABLE $schema.ddl_probe (id uuid)",
                    "CREATE SEQUENCE $schema.ddl_probe_seq",
                    "CREATE VIEW $schema.ddl_probe_view AS SELECT 1 AS one",
                    "CREATE FUNCTION $schema.ddl_probe() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                )
            } +
                listOf(
                    "CREATE TABLE public.ddl_probe (id uuid)",
                    "CREATE SCHEMA ddl_probe",
                    "CREATE TEMP TABLE ddl_probe_temp (id uuid)",
                    "CREATE TEMPORARY TABLE ddl_probe_temp AS SELECT 1 AS one",
                    "ALTER TABLE iam.user_account ADD COLUMN ddl_probe text",
                    "ALTER TABLE shared.idempotency_key DROP COLUMN response_body",
                    "DROP TABLE shared.shedlock",
                    "TRUNCATE iam.rotated_refresh_token",
                    "DROP TRIGGER user_account_authorization_version ON iam.user_account",
                    "CREATE INDEX ddl_probe_idx ON iam.user_account (name)",
                    "SELECT count(*) FROM shared.flyway_schema_history",
                )

        assertSoftly {
            statements.forEach { sql ->
                withClue(sql) {
                    val error =
                        shouldThrow<DataAccessException> {
                            transactions.executeWithoutResult {
                                jdbc
                                    .sql(
                                        sql,
                                    ).update()
                            }
                        }
                    (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
                }
            }
        }
    }

    @Test
    fun `the application role reads and writes every module table, except the append-only ones`() {
        val tables =
            owner
                .sql(
                    """
                    SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname IN (:schemas) AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                    """.trimIndent(),
                ).param("schemas", schemas)
                .query { rs, _ -> rs.getString(1) }
                .list()
        val appendOnlyTables = appendOnly.flatMap { it.tables() }
        tables shouldContainAll appendOnlyTables
        appendOnlyTables shouldContain "audit.audit_entry"
        appendOnlyTables shouldContain "iam.approval"
        appendOnlyTables shouldContain "iam.approval_use"

        assertSoftly {
            tables.forEach { table ->
                val expected =
                    if (table in
                        appendOnlyTables
                    ) {
                        setOf("SELECT", "INSERT")
                    } else {
                        setOf("SELECT", "INSERT", "UPDATE", "DELETE")
                    }
                withClue(table) { privilegesOn(table) shouldBe expected }
            }
        }
    }

    /**
     * As views de módulo são as `report_*` (ARCHITECTURE §11, M2 E08), a API de leitura para relatórios: o papel da
     * aplicação só as lê (o `ALTER DEFAULT PRIVILEGES` da pasta daria também DML; a migração da view o retira).
     */
    @Test
    fun `the application role only reads the module views, all of them report views`() {
        val views =
            owner
                .sql(
                    """
                    SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname IN (:schemas) AND c.relkind IN ('v', 'm')
                    """.trimIndent(),
                ).param("schemas", schemas)
                .query { rs, _ -> rs.getString(1) }
                .list()
        views shouldContain "checklists.report_execution_compliance"

        assertSoftly {
            views.forEach { view ->
                withClue(view) {
                    view.substringAfter('.') shouldStartWith "report_"
                    privilegesOn(view) shouldBe setOf("SELECT")
                }
            }
        }
    }

    @Test
    fun `the startup guard accepts the restricted role and refuses the schema owner`() {
        guard.problems().shouldBeEmpty()
        guard.run(DefaultApplicationArguments())

        val ownerGuard = DatabaseRoleGuard(owner, flyway, appendOnly)
        val problems = ownerGuard.problems()

        problems.shouldNotBeEmpty()
        problems.joinToString() shouldContain "superusuário"
        problems shouldContain "tem UPDATE em audit.audit_entry"
        problems shouldContain "tem DELETE em audit.audit_entry"
        shouldThrow<IllegalStateException> { ownerGuard.run(DefaultApplicationArguments()) }.message!! shouldContain
            "ZENY_DB_MIGRATION"
    }

    @Test
    fun `the startup guard names each privilege that would let a non-owner role change the trail or create objects`() {
        val database = PostgresContainer.instance.databaseName
        val cases =
            listOf(
                null to emptyList(),
                "GRANT UPDATE ON audit.audit_entry TO %s" to listOf("tem UPDATE em audit.audit_entry"),
                "GRANT DELETE ON audit.audit_entry TO %s" to listOf("tem DELETE em audit.audit_entry"),
                "GRANT TRUNCATE ON audit.audit_entry TO %s" to listOf("tem TRUNCATE em audit.audit_entry"),
                "GRANT UPDATE (after_state) ON audit.audit_entry TO %s" to
                    listOf("tem UPDATE em colunas de audit.audit_entry"),
                "GRANT TEMPORARY ON DATABASE \"$database\" TO %s" to listOf("pode criar tabelas temporárias no banco"),
                "GRANT CREATE ON DATABASE \"$database\" TO %s" to listOf("pode criar schemas no banco"),
                "GRANT CREATE ON SCHEMA iam TO %s" to listOf("pode criar objetos no schema iam"),
            )

        assertSoftly {
            cases.forEach { (grant, expected) ->
                withClue(grant ?: "só os privilégios da aplicação") {
                    withProbeRole(grant) { probeGuard -> probeGuard.problems() shouldBe expected }
                }
            }
        }
    }

    /**
     * Um papel de login novo, não dono e sem atributos, com os privilégios da aplicação nas tabelas
     * *append-only* (`SELECT, INSERT`: a trilha e as aprovações do `iam`) e mais [extraGrant] (`%s` =
     * o papel), e a trava ligada a ele. Removido no fim.
     */
    private fun withProbeRole(
        extraGrant: String?,
        block: (DatabaseRoleGuard) -> Unit,
    ) {
        val role = "guard_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        val password = UuidV7.generate().toString()
        val database = PostgresContainer.instance.databaseName
        owner.sql("CREATE ROLE $role LOGIN PASSWORD '$password'").update()
        try {
            owner.sql("GRANT CONNECT ON DATABASE \"$database\" TO $role").update()
            appendOnly.flatMap { it.tables() }.forEach { table ->
                owner.sql("GRANT USAGE ON SCHEMA ${table.substringBefore('.')} TO $role").update()
                owner.sql("GRANT SELECT, INSERT ON $table TO $role").update()
            }
            extraGrant?.let { owner.sql(it.replace("%s", role)).update() }
            val dataSource = DriverManagerDataSource(PostgresContainer.instance.jdbcUrl, role, password)
            block(DatabaseRoleGuard(JdbcClient.create(dataSource), flyway, appendOnly))
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private fun privilegesOn(table: String): Set<String> =
        PRIVILEGES
            .filter {
                jdbc
                    .sql("SELECT has_table_privilege(current_user, :table, :privilege)")
                    .param("table", table)
                    .param("privilege", it)
                    .query(Boolean::class.java)
                    .single()
            }.toSet()

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val PROBE_SUFFIX = 12
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
    }
}
