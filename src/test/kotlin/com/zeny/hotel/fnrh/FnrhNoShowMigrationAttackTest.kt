package com.zeny.hotel.fnrh

import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Ataque à migração do no-show (M6 E14, D-E14-6): as listas exatas das CHECKs, as quatro combinações do
 * remote_reservation_closed_ck, o DEFAULT false e a view e os privilégios intactos. SQL cru; o banco é compartilhado e
 * os enums Kotlin do no-show só nascem na Task 2, então toda linha com NO_SHOW ou REMOTE_NO_SHOW é apagada no fim.
 */
@IntegrationTest
class FnrhNoShowMigrationAttackTest(
    @Autowired private val jdbc: JdbcClient,
) {
    private val submissions = mutableListOf<UUID>()
    private val remotes = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        submissions.forEach { jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", it).update() }
        remotes.forEach {
            jdbc.sql("DELETE FROM fnrh.remote_reservation WHERE reservation_id = :id").param("id", it).update()
        }
    }

    @Test
    fun `the operation check lists exactly the six old operations plus NO_SHOW`() {
        literals("submission_operation_ck") shouldContainExactlyInAnyOrder
            listOf(
                "REGISTER_RESERVATION",
                "UPDATE_RESERVATION",
                "CANCEL_RESERVATION",
                "CHECK_IN",
                "UNDO_CHECK_IN",
                "CHECK_OUT",
                "NO_SHOW",
            )
    }

    @Test
    fun `the last failure check lists exactly the ten old codes plus REMOTE_NO_SHOW`() {
        literals("submission_last_failure_ck") shouldContainExactlyInAnyOrder
            listOf(
                "VALIDATION",
                "UNAUTHORIZED",
                "FORBIDDEN",
                "NOT_FOUND",
                "SERVER_ERROR",
                "UNAVAILABLE",
                "UNKNOWN_OUTCOME",
                "INTERNAL_ERROR",
                "REMOTE_CANCELLED",
                "NO_REMOTE_OPERATION",
                "REMOTE_NO_SHOW",
            )
    }

    @Test
    fun `every old operation still inserts and near-miss operations are refused`() {
        listOf("REGISTER_RESERVATION", "UPDATE_RESERVATION", "CANCEL_RESERVATION", "CHECK_IN", "UNDO_CHECK_IN")
            .plus("CHECK_OUT")
            .forEach { insertSubmission(it, null) }
        listOf("no_show", "NO_SHOW ", "NOSHOW", "", "NO-SHOW", "NO_SHOWS").forEach {
            shouldThrow<DataIntegrityViolationException> { insertSubmission(it, null) }
        }
    }

    @Test
    fun `every old failure code still inserts, null is allowed and near-miss codes are refused`() {
        listOf("VALIDATION", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "SERVER_ERROR", "UNAVAILABLE")
            .plus(listOf("UNKNOWN_OUTCOME", "INTERNAL_ERROR", "REMOTE_CANCELLED", "NO_REMOTE_OPERATION"))
            .plus(listOf(null))
            .forEach { insertSubmission("CHECK_IN", it) }
        listOf("remote_no_show", "NO_SHOW", "REMOTE_NO_SHOW ", "REMOTE_NOSHOW", "").forEach {
            shouldThrow<DataIntegrityViolationException> { insertSubmission("NO_SHOW", it) }
        }
    }

    @Test
    fun `the closed check allows only the three consistent combinations of cancelled and no-show`() {
        insertRemote(cancelled = false, noShow = false)
        insertRemote(cancelled = true, noShow = false)
        insertRemote(cancelled = false, noShow = true)
        shouldThrow<DataIntegrityViolationException> { insertRemote(cancelled = true, noShow = true) }
    }

    @Test
    fun `no-show is boolean not null defaulting to false and refuses an explicit null`() {
        val column =
            jdbc
                .sql(
                    "SELECT data_type, is_nullable, column_default FROM information_schema.columns " +
                        "WHERE table_schema = 'fnrh' AND table_name = 'remote_reservation' AND column_name = 'no_show'",
                ).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
                .single()
        column shouldBe Triple("boolean", "NO", "false")

        val id = UuidV7.generate().also { remotes += it }
        shouldThrow<DataIntegrityViolationException> {
            jdbc
                .sql(
                    "INSERT INTO fnrh.remote_reservation " +
                        "(reservation_id, environment, remote_id, cancelled, no_show) " +
                        "VALUES (:id, 'HOMOLOGATION', 'r-1', false, NULL)",
                ).param("id", id)
                .update()
        }
    }

    @Test
    fun `the report view keeps its definition and does not expose the new state`() {
        val definition =
            jdbc
                .sql("SELECT pg_get_viewdef('fnrh.report_submissions'::regclass)")
                .query(String::class.java)
                .single()
        definition shouldNotContain "no_show"
        val columns =
            jdbc
                .sql(
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'fnrh' AND table_name = 'report_submissions' ORDER BY ordinal_position",
                ).query(String::class.java)
                .list()
        columns shouldBe
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

    @Test
    fun `privileges are unchanged, the app role reads the view only and the tables stay read-write`() {
        privileges("fnrh.report_submissions") shouldBe
            mapOf(
                "SELECT" to true,
                "INSERT" to false,
                "UPDATE" to false,
                "DELETE" to false,
                "TRUNCATE" to false,
                "REFERENCES" to false,
                "TRIGGER" to false,
            )
        listOf("fnrh.submission", "fnrh.remote_reservation").forEach {
            privileges(it) shouldBe
                mapOf(
                    "SELECT" to true,
                    "INSERT" to true,
                    "UPDATE" to true,
                    "DELETE" to true,
                    "TRUNCATE" to false,
                    "REFERENCES" to false,
                    "TRIGGER" to false,
                )
        }
        jdbc
            .sql("SELECT has_column_privilege(current_user, 'fnrh.remote_reservation', 'no_show', 'UPDATE')")
            .query(Boolean::class.javaObjectType)
            .single() shouldBe true
        // PUBLIC não ganhou acesso à tabela nem à view.
        jdbc
            .sql(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, " +
                    "aclexplode(c.relacl) a WHERE n.nspname = 'fnrh' AND c.relname IN " +
                    "('report_submissions', 'submission', 'remote_reservation') AND a.grantee = 0",
            ).query(Long::class.javaObjectType)
            .single() shouldBe 0L
    }

    private fun privileges(table: String): Map<String, Boolean> =
        listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER").associateWith {
            jdbc
                .sql("SELECT has_table_privilege(current_user, :table, :privilege)")
                .param("table", table)
                .param("privilege", it)
                .query(Boolean::class.javaObjectType)
                .single()
        }

    /** Os literais entre aspas simples da definição da CHECK, como o banco a guarda. */
    private fun literals(constraint: String): List<String> {
        val definition =
            jdbc
                .sql(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :name " +
                        "AND conrelid = 'fnrh.submission'::regclass",
                ).param("name", constraint)
                .query(String::class.java)
                .single()
        return Regex("'([^']*)'::text").findAll(definition).map { it.groupValues[1] }.toList()
    }

    private fun insertSubmission(
        operation: String,
        lastFailure: String?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO fnrh.submission (id, reservation_id, reservation_code, operation, operation_rank, event_key,
                    causal_version, status, attempts, next_attempt_at, last_failure, in_flight, occurred_at, created_at,
                    version)
                VALUES (:id, :reservation, 'R-NOSHOW-ATK', :operation, 6, 'e1', 1, 'FAILED', 1, now(), :failure, false,
                    now(), now(), 0)
                """.trimIndent(),
            ).param("id", UuidV7.generate().also { submissions += it })
            .param("reservation", UuidV7.generate())
            .param("operation", operation)
            .param("failure", lastFailure)
            .update()
    }

    private fun insertRemote(
        cancelled: Boolean,
        noShow: Boolean,
    ) {
        jdbc
            .sql(
                "INSERT INTO fnrh.remote_reservation (reservation_id, environment, remote_id, cancelled, no_show) " +
                    "VALUES (:id, 'HOMOLOGATION', 'r-1', :cancelled, :noShow)",
            ).param("id", UuidV7.generate().also { remotes += it })
            .param("cancelled", cancelled)
            .param("noShow", noShow)
            .update()
    }
}
