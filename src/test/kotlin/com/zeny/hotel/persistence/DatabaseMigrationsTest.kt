package com.zeny.hotel.persistence

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Aplica todas as migrações do zero num PostgreSQL 18 real (Testcontainers) e confere o que
 * a fundação precisa: `btree_gist`, a tabela do Event Publication Registry, a do ShedLock e a
 * das chaves de idempotência, todas no schema `shared`, e um schema por pasta de módulo.
 */
@IntegrationTest
class DatabaseMigrationsTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val flyway: Flyway,
) {
    private val moduleFolders =
        Path
            .of("src", "main", "resources", "db", "migration")
            .listDirectoryEntries()
            .filter { it.isDirectory() }

    @Test
    fun `runs on PostgreSQL 18`() {
        val version = jdbc.sql("SELECT current_setting('server_version_num')::int").query(Int::class.java).single()

        version shouldBeInRange 180_000..189_999
    }

    @Test
    fun `applies every migration file and nothing is pending`() {
        val info = flyway.info()
        val expected =
            moduleFolders.flatMap { folder ->
                folder.listDirectoryEntries("*.sql").mapNotNull { MigrationConventions.versionOf(it.name) }
            }

        info.pending().toList().shouldBeEmpty()
        info.applied().map { it.state }.toSet() shouldBe setOf(MigrationState.SUCCESS)
        info.applied().mapNotNull { it.version?.version } shouldContainAll expected
    }

    @Test
    fun `keeps the migration history in the shared schema`() {
        flyway.configuration.defaultSchema shouldBe "shared"

        // O histórico é só do dono dos schemas (o Flyway); o papel da aplicação não o lê (DatabaseRolesTest).
        val failed =
            JdbcClient
                .create(flyway.configuration.dataSource)
                .sql("SELECT count(*) FROM shared.flyway_schema_history WHERE NOT success")
                .query(Int::class.java)
                .single()
        failed shouldBe 0
    }

    @Test
    fun `creates one schema per module folder`() {
        val folders = moduleFolders.map { it.name }
        val existing =
            jdbc
                .sql("SELECT nspname FROM pg_namespace")
                .query(String::class.java)
                .list()

        flyway.configuration.schemas.toList() shouldContainExactly folders.sortedWith(sharedFirst)
        existing shouldContainAll folders
    }

    @Test
    fun `btree_gist is installed and backs a no-double-booking exclusion constraint`() {
        val extensions = jdbc.sql("SELECT extname FROM pg_extension").query(String::class.java).list()
        extensions shouldContain "btree_gist"

        // Sem btree_gist o PostgreSQL recusa `uuid WITH =` num índice GiST (ARCHITECTURE §11). A tabela
        // temporária é criada com o dono: o papel da aplicação não cria tabelas, nem temporárias.
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val ownerTransactions = TransactionTemplate(DataSourceTransactionManager(flyway.configuration.dataSource))
        val error =
            shouldThrow<DataAccessException> {
                ownerTransactions.executeWithoutResult {
                    owner
                        .sql(
                            """
                            CREATE TEMP TABLE allocation_probe (
                                room_id UUID NOT NULL,
                                stay DATERANGE NOT NULL,
                                EXCLUDE USING gist (room_id WITH =, stay WITH &&)
                            ) ON COMMIT DROP
                            """.trimIndent(),
                        ).update()
                    val room = "'0199d5a0-0000-7000-8000-000000000001'::uuid"
                    owner.sql("INSERT INTO allocation_probe VALUES ($room, '[2026-10-10,2026-10-12)')").update()
                    // Diária encostada (saída no dia da entrada) não conflita.
                    owner.sql("INSERT INTO allocation_probe VALUES ($room, '[2026-10-12,2026-10-14)')").update()
                    // Sobreposição na mesma UH viola a restrição.
                    owner.sql("INSERT INTO allocation_probe VALUES ($room, '[2026-10-13,2026-10-15)')").update()
                }
            }
        (error.rootCause as SQLException).sqlState shouldBe EXCLUSION_VIOLATION
    }

    @Test
    fun `creates the event publication registry table in shared`() {
        columnsOf("shared", "event_publication") shouldContainExactly
            mapOf(
                "id" to "uuid",
                "listener_id" to "text",
                "event_type" to "text",
                "serialized_event" to "text",
                "publication_date" to "timestamp with time zone",
                "completion_date" to "timestamp with time zone",
                "status" to "text",
                "completion_attempts" to "integer",
                "last_resubmission_date" to "timestamp with time zone",
            )
    }

    @Test
    fun `creates the ShedLock table in shared`() {
        columnsOf("shared", "shedlock") shouldContainExactly
            mapOf(
                "name" to "character varying",
                "lock_until" to "timestamp without time zone",
                "locked_at" to "timestamp without time zone",
                "locked_by" to "character varying",
            )
    }

    @Test
    fun `creates the idempotency key table in shared`() {
        columnsOf("shared", "idempotency_key") shouldContainExactly
            mapOf(
                "id" to "uuid",
                "scope" to "character varying",
                "idempotency_key" to "character varying",
                "request_hash" to "character",
                "status" to "character varying",
                "response_status" to "integer",
                "response_headers" to "text",
                "response_body" to "text",
                "created_at" to "timestamp with time zone",
                "completed_at" to "timestamp with time zone",
                "expires_at" to "timestamp with time zone",
            )
    }

    private fun columnsOf(
        schema: String,
        table: String,
    ): Map<String, String> =
        jdbc
            .sql(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = :schema AND table_name = :table
                ORDER BY ordinal_position
                """.trimIndent(),
            ).param("schema", schema)
            .param("table", table)
            .query { rs, _ -> rs.getString("column_name") to rs.getString("data_type") }
            .list()
            .toMap()

    private companion object {
        const val EXCLUSION_VIOLATION = "23P01"
        val sharedFirst = compareBy<String>({ it != "shared" }, { it })
    }
}
