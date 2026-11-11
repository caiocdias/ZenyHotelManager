package com.zeny.hotel.fnrh

import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.SQLException
import java.util.UUID

/**
 * A view `fnrh.report_submissions` (M5 E08, Task 12; ARCHITECTURE §11): o contrato das colunas, só `SELECT` para o
 * papel da aplicação, a linha de uma submissão sem texto livre nem dado de hóspede e a leitura por um papel que só lê
 * a view (as tabelas do schema, não).
 */
@IntegrationTest
class FnrhReportViewsTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val flyway: Flyway,
) {
    @Test
    fun `the view is the published contract, only readable by the application role`() {
        columnsOf() shouldContainExactly COLUMNS
        PRIVILEGES.filter { privilegeOn(it) } shouldBe listOf("SELECT")
        jdbc
            .sql(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE n.nspname = 'fnrh' AND c.relkind = 'v'",
            ).query(String::class.java)
            .list() shouldContainExactly listOf("report_submissions")
    }

    @Test
    fun `the view reads one row per submission, with opaque ids and no code nor text`() {
        val reservationId = UuidV7.generate()
        val id = insertSubmission(reservationId)
        withViewOnlyRole { reader ->
            val rows =
                reader
                    .sql("SELECT * FROM fnrh.report_submissions WHERE submission_id = :id")
                    .param("id", id)
                    .query()
                    .listOfRows()
            rows shouldHaveSize 1
            val row = rows.single()
            row["reservation_id"] shouldBe reservationId
            row["operation"] shouldBe "CHECK_IN"
            row["status"] shouldBe "FAILED"
            row["attempts"] shouldBe 3
            row["last_failure"] shouldBe "SERVER_ERROR"
            row["sent_at"] shouldBe null

            listOf("fnrh.submission", "fnrh.remote_reservation", "fnrh.remote_guest", "fnrh.submission_error")
                .forEach { table ->
                    val failure =
                        shouldThrow<DataAccessException> { reader.sql("SELECT 1 FROM $table").query().listOfRows() }
                    (failure.rootCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
                }
        }
    }

    private fun insertSubmission(reservationId: UUID): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO fnrh.submission (id, reservation_id, reservation_code, operation, operation_rank, event_key,
                    causal_version, status, attempts, next_attempt_at, last_failure, in_flight, occurred_at, created_at,
                    version)
                VALUES (:id, :reservation, 'R-REPORT', 'CHECK_IN', 3, 'e1', 1, 'FAILED', 3, now(), 'SERVER_ERROR',
                    false, now(), now(), 0)
                """.trimIndent(),
            ).param("id", id)
            .param("reservation", reservationId)
            .update()
        return id
    }

    private fun columnsOf(): List<String> =
        jdbc
            .sql(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'fnrh' AND table_name = 'report_submissions' ORDER BY ordinal_position",
            ).query(String::class.java)
            .list()
            .filterNotNull()

    private fun privilegeOn(privilege: String): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, 'fnrh.report_submissions', :privilege)")
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    /** Roda [block] com `SET ROLE` para um papel novo, sem login, só com `USAGE` no schema e `SELECT` na view. */
    private fun withViewOnlyRole(block: (JdbcClient) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "fnrh_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            owner.sql("GRANT USAGE ON SCHEMA fnrh TO $role").update()
            owner.sql("GRANT SELECT ON fnrh.report_submissions TO $role").update()
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                block(JdbcClient.create(SingleConnectionDataSource(connection, true)))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private companion object {
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
        val COLUMNS =
            listOf(
                "submission_id",
                "reservation_id",
                "operation",
                "status",
                "attempts",
                "last_failure",
                "created_at",
                "sent_at",
                "next_attempt_at",
            )
    }
}
