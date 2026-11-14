package com.zeny.hotel.fnrh

import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * A migração do no-show (M6 E14, D-E14-6): a fila aceita a operação e a falha novas e a reserva remota ganha o estado
 * no-show, nunca junto com o cancelamento. SQL cru, com ids próprios (o banco é compartilhado).
 */
@IntegrationTest
class FnrhNoShowMigrationTest(
    @Autowired private val jdbc: JdbcClient,
) {
    private val submissions = mutableListOf<UUID>()
    private val remotes = mutableListOf<UUID>()

    // O banco é compartilhado: as linhas da fila e da reserva remota não podem sobrar para os outros testes.
    @AfterEach
    fun cleanUp() {
        submissions.forEach { jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", it).update() }
        remotes.forEach {
            jdbc.sql("DELETE FROM fnrh.remote_reservation WHERE reservation_id = :id").param("id", it).update()
        }
    }

    @Test
    fun `the queue accepts NO_SHOW and REMOTE_NO_SHOW`() {
        insertSubmission(operation = "NO_SHOW", lastFailure = "REMOTE_NO_SHOW")

        shouldThrow<DataIntegrityViolationException> { insertSubmission(operation = "BOGUS", lastFailure = null) }
        shouldThrow<DataIntegrityViolationException> {
            insertSubmission(operation = "NO_SHOW", lastFailure = "BOGUS_FAILURE")
        }
    }

    @Test
    fun `a remote reservation is not no-show by default`() {
        val plain = insertRemote(noShow = null, cancelled = false)
        val noShow = insertRemote(noShow = true, cancelled = false)

        noShowOf(plain) shouldBe false
        noShowOf(noShow) shouldBe true
    }

    @Test
    fun `a remote reservation is never cancelled and no-show at once`() {
        shouldThrow<DataIntegrityViolationException> { insertRemote(noShow = true, cancelled = true) }
    }

    @Test
    fun `the new migration header documents the view contract`() {
        val sql =
            checkNotNull(
                javaClass.classLoader.getResource("db/migration/fnrh/V2026_11_14_1000__add_fnrh_no_show.sql"),
            ).readText()
        val header = sql.lineSequence().takeWhile { it.startsWith("--") || it.isBlank() }.joinToString("\n")

        header shouldContain "report_submissions"
        header shouldContain "NO_SHOW"
        header shouldContain "REMOTE_NO_SHOW"
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
                VALUES (:id, :reservation, 'R-NOSHOW', :operation, 6, 'e1', 1, 'FAILED', 1, now(), :failure, false,
                    now(), now(), 0)
                """.trimIndent(),
            ).param("id", UuidV7.generate().also { submissions += it })
            .param("reservation", UuidV7.generate())
            .param("operation", operation)
            .param("failure", lastFailure)
            .update()
    }

    private fun insertRemote(
        noShow: Boolean?,
        cancelled: Boolean,
    ): UUID {
        val id = UuidV7.generate().also { remotes += it }
        val column = if (noShow == null) "" else ", no_show"
        val value = if (noShow == null) "" else ", :noShow"
        jdbc
            .sql(
                "INSERT INTO fnrh.remote_reservation (reservation_id, environment, remote_id, cancelled$column) " +
                    "VALUES (:id, 'HOMOLOGATION', 'r-1', :cancelled$value)",
            ).param("id", id)
            .param("cancelled", cancelled)
            .apply { if (noShow != null) param("noShow", noShow) }
            .update()
        return id
    }

    private fun noShowOf(reservationId: UUID): Boolean? =
        jdbc
            .sql("SELECT no_show FROM fnrh.remote_reservation WHERE reservation_id = :id")
            .param("id", reservationId)
            .query(Boolean::class.javaObjectType)
            .single()
}
