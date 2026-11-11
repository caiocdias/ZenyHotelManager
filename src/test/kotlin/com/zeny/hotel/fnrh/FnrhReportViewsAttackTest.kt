package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ataque a `fnrh.report_submissions` (M5 E08, Task 12): tipos exatos, nenhum privilégio de escrita nem para `PUBLIC`,
 * o papel que só lê a view negado em todas as tabelas do schema, uma linha por submissão com todas as situações e a
 * coerência com a submissão depois de falha, reenvio e encerramento. Os ids são próprios (o banco é compartilhado).
 */
@IntegrationTest
class FnrhReportViewsAttackTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val flyway: Flyway,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val tx: TransactionTemplate,
) {
    private val now: Instant = Instant.parse("2026-11-10T12:00:00Z")

    @Test
    fun `the columns have the exact types and nullability of the contract`() {
        val columns =
            jdbc
                .sql(
                    "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                        "WHERE table_schema = 'fnrh' AND table_name = 'report_submissions' ORDER BY ordinal_position",
                ).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
                .list()
        columns shouldContainExactly
            listOf(
                Triple("submission_id", "uuid", "YES"),
                Triple("reservation_id", "uuid", "YES"),
                Triple("operation", "text", "YES"),
                Triple("status", "text", "YES"),
                Triple("attempts", "integer", "YES"),
                Triple("last_failure", "text", "YES"),
                Triple("created_at", "timestamp with time zone", "YES"),
                Triple("sent_at", "timestamp with time zone", "YES"),
                Triple("next_attempt_at", "timestamp with time zone", "YES"),
            )
    }

    @Test
    fun `the application role cannot write through the view and PUBLIC has no access to it`() {
        listOf(
            "INSERT INTO fnrh.report_submissions (submission_id, reservation_id, operation, status, attempts, " +
                "created_at, next_attempt_at) VALUES (gen_random_uuid(), gen_random_uuid(), 'CHECK_IN', 'PENDING', " +
                "0, now(), now())",
            "UPDATE fnrh.report_submissions SET attempts = 99 WHERE false",
            "DELETE FROM fnrh.report_submissions WHERE false",
        ).forEach { statement ->
            withClue(statement) {
                val failure = shouldThrow<DataAccessException> { jdbc.sql(statement).update() }
                (failure.rootCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
            }
        }
        val publicGrants =
            jdbc
                .sql(
                    "SELECT a.privilege_type FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, " +
                        "aclexplode(c.relacl) a WHERE n.nspname = 'fnrh' AND c.relname = 'report_submissions' " +
                        "AND a.grantee = 0",
                ).query(String::class.java)
                .list()
        publicGrants.shouldBeEmpty()
    }

    @Test
    fun `the view only role reads the view and is denied in every table of the fnrh schema`() {
        val tables =
            jdbc
                .sql(
                    "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'fnrh' AND c.relkind IN ('r', 'p', 'm', 'f')",
                ).query(String::class.java)
                .list()
        tables shouldContain "settings"
        tables shouldContain "requester"
        tables shouldContain "integration_call"
        tables shouldContain "domain_value"
        tables shouldContain "submission_error"
        val id = record(UuidV7.generate(), FnrhOperation.CHECK_IN, "e1")
        withViewOnlyRole { reader ->
            reader
                .sql("SELECT count(*) FROM fnrh.report_submissions WHERE submission_id = :id")
                .param("id", id)
                .query(Long::class.java)
                .single() shouldBe 1L
            tables.forEach { table ->
                withClue(table) {
                    val failure =
                        shouldThrow<DataAccessException> {
                            reader.sql("SELECT 1 FROM fnrh.$table").query().listOfRows()
                        }
                    (failure.rootCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
                }
            }
        }
    }

    @Test
    fun `a submission with several validation errors is still one row and all statuses show up`() {
        val guest = UuidV7.generate()
        val ids = mutableMapOf<FnrhSubmissionStatus, UUID>()
        ids[FnrhSubmissionStatus.PENDING] = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, "p")
        ids[FnrhSubmissionStatus.SENT] =
            record(
                UuidV7.generate(),
                FnrhOperation.UPDATE_RESERVATION,
                "s",
            ).also { change(it) { s -> s.markSent(now) } }
        ids[FnrhSubmissionStatus.FAILED] =
            record(UuidV7.generate(), FnrhOperation.CHECK_OUT, "f").also {
                change(it) { s -> s.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now) }
            }
        ids[FnrhSubmissionStatus.INVALID] =
            record(UuidV7.generate(), FnrhOperation.CHECK_IN, "i").also {
                change(it) { s ->
                    s.markInvalid(
                        listOf(
                            FnrhSubmissionError(guest, "birthDate", "REQUIRED"),
                            FnrhSubmissionError(guest, "birthDate", "INVALID"),
                            FnrhSubmissionError(guest, "nationality", "REQUIRED"),
                        ),
                        now,
                    )
                }
            }
        ids[FnrhSubmissionStatus.CANCELLED] =
            record(UuidV7.generate(), FnrhOperation.CANCEL_RESERVATION, "c").also { change(it) { s -> s.cancel(now) } }
        ids[FnrhSubmissionStatus.DISMISSED] =
            record(UuidV7.generate(), FnrhOperation.UNDO_CHECK_IN, "d").also {
                change(it) { s ->
                    s.fail(FnrhSubmissionFailure.REMOTE_CANCELLED, now)
                    s.dismiss(now)
                }
            }

        val rows =
            jdbc
                .sql("SELECT submission_id, status FROM fnrh.report_submissions WHERE submission_id IN (:ids)")
                .param("ids", ids.values.toList())
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
                .list()
        rows shouldHaveSize ids.size
        rows.associate { it.first to it.second } shouldBe
            ids.mapValues { (status, _) -> status.name }.mapKeys { ids[it.key] }
        jdbc
            .sql("SELECT count(*) FROM fnrh.submission_error WHERE submission_id = :id")
            .param("id", ids.getValue(FnrhSubmissionStatus.INVALID))
            .query(Long::class.java)
            .single() shouldBe 3L
    }

    @Test
    fun `the view follows the submission through failure, retry and dismiss`() {
        val reservation = UuidV7.generate()
        val id = record(reservation, FnrhOperation.CHECK_IN, "e1")
        viewRow(id).let {
            it.status shouldBe "PENDING"
            it.attempts shouldBe 0
            it.lastFailure shouldBe null
            it.sentAt shouldBe null
            it.reservationId shouldBe reservation
            it.operation shouldBe "CHECK_IN"
            it.createdAt shouldBe now
        }

        change(id) { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy(maxAttempts = 1)) }
        viewRow(id).let {
            it.status shouldBe "FAILED"
            it.attempts shouldBe 1
            it.lastFailure shouldBe "SERVER_ERROR"
        }
        matchesTable(id) shouldBe true

        val later = now.plusSeconds(600)
        change(id) { it.retry(later) }
        viewRow(id).let {
            it.status shouldBe "PENDING"
            it.attempts shouldBe 0
            it.lastFailure shouldBe null
            it.nextAttemptAt shouldBe later
        }
        matchesTable(id) shouldBe true

        change(id) { it.recordFailure(FnrhSubmissionFailure.FORBIDDEN, later, FnrhRetryPolicy()) }
        change(id) { it.dismiss(later) }
        viewRow(id).let {
            it.status shouldBe "DISMISSED"
            it.attempts shouldBe 1
            it.lastFailure shouldBe "FORBIDDEN"
            it.sentAt shouldBe null
        }
        matchesTable(id) shouldBe true

        val sent = record(UuidV7.generate(), FnrhOperation.CHECK_OUT, "e2")
        change(sent) { it.markSent(later) }
        viewRow(sent).let {
            it.status shouldBe "SENT"
            it.sentAt shouldBe later
        }
        matchesTable(sent) shouldBe true
    }

    @Test
    fun `the view definition leaves out the code, the link, the errors and the internal columns`() {
        val definition =
            jdbc
                .sql("SELECT pg_get_viewdef('fnrh.report_submissions'::regclass)")
                .query(String::class.java)
                .single()
        definition shouldContain "fnrh.submission"
        listOf("reservation_code", "precheckin", "remote_", "submission_error", "performed_by", "event_key", "version")
            .forEach { withClue(it) { definition shouldNotContainIgnoringCase it } }
    }

    @Test
    fun `the migration comment names every published column, operation and status and what stays out`() {
        val migration =
            checkNotNull(
                javaClass.classLoader.getResource("db/migration/fnrh/V2026_11_10_1100__create_fnrh_report_views.sql"),
            ).readText()
        val comment = migration.lines().takeWhile { it.startsWith("--") }.joinToString("\n")
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
        ).forEach { withClue(it) { comment shouldContain it } }
        FnrhOperation.entries.forEach { withClue(it.name) { comment shouldContain it.name } }
        FnrhSubmissionStatus.entries.forEach { withClue(it.name) { comment shouldContain it.name } }
        listOf("reservation_code", "submission_error", "remote_reservation").forEach {
            withClue(it) { comment shouldContain it }
        }
        columnNames() shouldContainExactlyInAnyOrder COLUMNS
    }

    private fun columnNames(): List<String> =
        jdbc
            .sql(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'fnrh' AND table_name = 'report_submissions'",
            ).query(String::class.java)
            .list()
            .filterNotNull()

    private data class ViewRow(
        val reservationId: UUID,
        val operation: String,
        val status: String,
        val attempts: Int,
        val lastFailure: String?,
        val createdAt: Instant,
        val sentAt: Instant?,
        val nextAttemptAt: Instant,
    )

    private fun viewRow(id: UUID): ViewRow =
        jdbc
            .sql(
                "SELECT reservation_id, operation, status, attempts, last_failure, created_at, sent_at, " +
                    "next_attempt_at FROM fnrh.report_submissions WHERE submission_id = :id",
            ).param("id", id)
            .query { rs, _ ->
                ViewRow(
                    rs.getObject("reservation_id", UUID::class.java),
                    rs.getString("operation"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("last_failure"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("sent_at", OffsetDateTime::class.java)?.toInstant(),
                    rs.getObject("next_attempt_at", OffsetDateTime::class.java).toInstant(),
                )
            }.single()

    private fun matchesTable(id: UUID): Boolean =
        jdbc
            .sql(
                "SELECT count(*) FROM fnrh.report_submissions v JOIN fnrh.submission s ON s.id = v.submission_id " +
                    "WHERE s.id = :id AND v.reservation_id = s.reservation_id AND v.operation = s.operation " +
                    "AND v.status = s.status AND v.attempts = s.attempts " +
                    "AND v.last_failure IS NOT DISTINCT FROM s.last_failure AND v.created_at = s.created_at " +
                    "AND v.sent_at IS NOT DISTINCT FROM s.sent_at AND v.next_attempt_at = s.next_attempt_at",
            ).param("id", id)
            .query(Long::class.java)
            .single() == 1L

    private fun record(
        reservationId: UUID,
        operation: FnrhOperation,
        key: String,
    ): UUID {
        val submission =
            FnrhSubmission.create(
                reservationId = reservationId,
                reservationCode = "R-ATTACK",
                operation = operation,
                eventKey = key,
                causalVersion = 1,
                performedBy = null,
                occurredAt = now,
                now = now,
            )
        store.recordIfAbsent(submission) shouldBe true
        return submission.id
    }

    private fun change(
        id: UUID,
        block: (FnrhSubmission) -> Unit,
    ) = tx.executeWithoutResult {
        val loaded = checkNotNull(store.find(id))
        block(loaded)
        store.save(loaded)
    }

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
        const val MIGRATION = "V2026_11_10_1100__create_fnrh_report_views.sql"
        const val INSUFFICIENT_PRIVILEGE = "42501"
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
