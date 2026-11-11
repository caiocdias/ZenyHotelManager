package com.zeny.hotel.printing

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Ataques ao esquema do `printing` (M5 E10, Task 1): INSERT e UPDATE diretos que o domínio nunca faria, contra as
 * restrições de D-E10-2, D-E10-4 e D-E10-5, e os privilégios do papel da aplicação.
 * Dados próprios, apagados no fim.
 */
@IntegrationTest
class PrintingSchemaAttackTest(
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val tag = "attack-" + UUID.randomUUID()

    @AfterEach
    fun cleanUp() {
        val agents = "SELECT id FROM printing.print_agent WHERE name = '$tag'"
        val printers = "SELECT id FROM printing.printer WHERE agent_id IN ($agents)"
        jdbc.update("DELETE FROM printing.print_job WHERE printer_id IN ($printers)")
        jdbc.update("DELETE FROM printing.printer WHERE agent_id IN ($agents)")
        jdbc.update("DELETE FROM printing.print_agent WHERE name = '$tag'")
    }

    private fun hash(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .repeat(2)

    private fun agent(
        active: Boolean = true,
        tokenHash: String? = null,
        generation: Int = 0,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO printing.print_agent (id, name, token_hash, token_generation, active, created_at, version) " +
                "VALUES (?, ?, ?, ?, ?, now(), 0)",
            id,
            tag,
            tokenHash,
            generation,
            active,
        )
        return id
    }

    private fun printer(
        agentId: UUID,
        kind: String = "A4_PDF",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO printing.printer (id, name, kind, address, agent_id, active, version) " +
                "VALUES (?, 'p', ?, 'addr', ?, true, 0)",
            id,
            kind,
            agentId,
        )
        return id
    }

    @Suppress("LongParameterList")
    private fun job(
        printerId: UUID,
        status: String = "PENDING",
        attempts: Int = 0,
        content: String? = "'\\x01'",
        purgedAt: String? = null,
        sentAt: String? = null,
        deliveredTo: UUID? = null,
        finishedAt: String? = null,
        position: String = "nextval('printing.print_job_position_seq')",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO printing.print_job (id, printer_id, document_type, status, position, attempts, content, " +
                "content_purged_at, media_type, created_at, sent_at, delivered_to, finished_at, version) " +
                "VALUES (?, ?, 'x.y', ?, $position, ?, ${content ?: "NULL"}, ${purgedAt ?: "NULL"}, " +
                "'application/pdf', now(), ${sentAt ?: "NULL"}, ?, ${finishedAt ?: "NULL"}, 0)",
            id,
            printerId,
            status,
            attempts,
            deliveredTo,
        )
        return id
    }

    private fun rejected(block: () -> Unit) {
        shouldThrow<DataIntegrityViolationException> { block() }
    }

    private fun denied(sql: String) {
        val e = shouldThrow<DataAccessException> { jdbc.execute(sql) }
        e.mostSpecificCause.message.orEmpty().lowercase().let {
            (it.contains("permission denied") || it.contains("must be owner")) shouldBe true
        }
    }

    // --- agente: token_hash (D-E10-2)

    @Test
    fun `any number of agents may have no token hash`() {
        agent(active = false)
        agent(active = false)
        agent(active = true)
    }

    @Test
    fun `deactivating an agent without clearing its hash is rejected`() {
        val id = agent(tokenHash = hash())

        rejected { jdbc.update("UPDATE printing.print_agent SET active = false WHERE id = ?", id) }
        jdbc.update("UPDATE printing.print_agent SET active = false, token_hash = NULL WHERE id = ?", id) shouldBe 1
    }

    @Test
    fun `giving a token hash to an inactive agent by update is rejected`() {
        val id = agent(active = false)

        rejected { jdbc.update("UPDATE printing.print_agent SET token_hash = ? WHERE id = ?", hash(), id) }
    }

    @Test
    fun `an update that copies another agent's hash is rejected`() {
        val first = hash()
        agent(tokenHash = first)
        val second = agent(tokenHash = hash())

        rejected { jdbc.update("UPDATE printing.print_agent SET token_hash = ? WHERE id = ?", first, second) }
    }

    @Test
    fun `a hash longer than 64 characters is rejected`() {
        shouldThrow<DataAccessException> { agent(tokenHash = hash() + "0") }
    }

    @Test
    fun `a negative token generation or a missing name is rejected`() {
        rejected { agent(generation = -1) }
        rejected {
            jdbc.update(
                "INSERT INTO printing.print_agent (id, name, active, created_at, version) " +
                    "VALUES (?, NULL, true, now(), 0)",
                UUID.randomUUID(),
            )
        }
    }

    // --- impressora

    @Test
    fun `kind is closed, case sensitive and not null`() {
        val agent = agent()

        for (kind in listOf("thermal_escpos", "a4_pdf", "", "PDF", "THERMAL_ESCPOS ")) {
            rejected { printer(agent, kind) }
        }
        rejected {
            jdbc.update(
                "INSERT INTO printing.printer (id, name, kind, address, agent_id, active, version) " +
                    "VALUES (?, 'p', NULL, 'a', ?, true, 0)",
                UUID.randomUUID(),
                agent,
            )
        }
        printer(agent, "THERMAL_ESCPOS")
        printer(agent, "A4_PDF")
    }

    @Test
    fun `changing the kind of a printer to an unknown value by update is rejected`() {
        val printer = printer(agent())

        rejected { jdbc.update("UPDATE printing.printer SET kind = 'LASER' WHERE id = ?", printer) }
    }

    @Test
    fun `a printer needs an existing agent and an agent with printers cannot be deleted`() {
        rejected { printer(UUID.randomUUID()) }
        val agent = agent()
        printer(agent)

        rejected { jdbc.update("DELETE FROM printing.print_agent WHERE id = ?", agent) }
        rejected {
            jdbc.update(
                "UPDATE printing.printer SET agent_id = ? WHERE agent_id = ?",
                UUID.randomUUID(),
                agent,
            )
        }
    }

    // --- job: chaves estrangeiras

    @Test
    fun `a job needs an existing printer and a printer with jobs cannot be deleted`() {
        rejected { job(UUID.randomUUID()) }
        val printer = printer(agent())
        job(printer)

        rejected { jdbc.update("DELETE FROM printing.printer WHERE id = ?", printer) }
    }

    @Test
    fun `delivered_to has no foreign key, so history survives the agent change`() {
        val agent = agent()
        val printer = printer(agent)

        job(printer, "SENT", sentAt = "now()", deliveredTo = UUID.randomUUID())
    }

    @Test
    fun `no foreign key in the printing schema points outside it`() {
        jdbc
            .queryForList(
                """
                SELECT c.conname FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                JOIN pg_class t ON t.oid = c.confrelid
                JOIN pg_namespace tn ON tn.oid = t.relnamespace
                WHERE c.contype = 'f' AND n.nspname = 'printing' AND tn.nspname <> 'printing'
                """.trimIndent(),
                String::class.java,
            ).shouldBeEmpty()
        jdbc
            .queryForList(
                """
                SELECT c.conname FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE c.contype = 'f' AND n.nspname = 'printing'
                """.trimIndent(),
                String::class.java,
            ).toSet() shouldBe setOf("printer_agent_fk", "print_job_printer_fk")
    }

    // --- job: status, attempts, finished_at, SENT, conteúdo

    @Test
    fun `status is closed, case sensitive and updates cannot leave the set`() {
        val printer = printer(agent())

        for (status in listOf("pending", "DONE", "", "PENDING ", "CANCELLED")) {
            rejected { job(printer, status) }
        }
        val id = job(printer)
        rejected { jdbc.update("UPDATE printing.print_job SET status = 'CANCELLED' WHERE id = ?", id) }
    }

    @Test
    fun `attempts cannot be negative or null`() {
        val printer = printer(agent())

        rejected { job(printer, attempts = -1) }
        job(printer, attempts = 0)
        job(printer, attempts = 3)
        val id = job(printer)
        rejected { jdbc.update("UPDATE printing.print_job SET attempts = attempts - 1 WHERE id = ?", id) }
        rejected { jdbc.update("UPDATE printing.print_job SET attempts = NULL WHERE id = ?", id) }
    }

    @Test
    fun `finished_at exists exactly when the status is PRINTED or FAILED`() {
        val printer = printer(agent())
        val agent = agent()

        rejected { job(printer, "FAILED") }
        rejected { job(printer, "PRINTED") }
        rejected { job(printer, "PENDING", finishedAt = "now()") }
        rejected { job(printer, "SENT", sentAt = "now()", deliveredTo = agent, finishedAt = "now()") }
        job(printer, "FAILED", finishedAt = "now()")
        job(printer, "PRINTED", finishedAt = "now()")
    }

    @Test
    fun `a finished job cannot be reopened or closed by update without fixing finished_at`() {
        val printer = printer(agent())
        val finished = job(printer, "FAILED", finishedAt = "now()")
        val pending = job(printer)

        rejected { jdbc.update("UPDATE printing.print_job SET status = 'PENDING' WHERE id = ?", finished) }
        rejected { jdbc.update("UPDATE printing.print_job SET status = 'PRINTED' WHERE id = ?", pending) }
        rejected { jdbc.update("UPDATE printing.print_job SET finished_at = NULL WHERE id = ?", finished) }
        jdbc.update(
            "UPDATE printing.print_job SET status = 'PENDING', finished_at = NULL WHERE id = ?",
            finished,
        ) shouldBe 1
    }

    @Test
    fun `a sent job needs both delivered_to and sent_at, also by update`() {
        val agent = agent()
        val printer = printer(agent)
        val pending = job(printer)

        rejected { jdbc.update("UPDATE printing.print_job SET status = 'SENT' WHERE id = ?", pending) }
        rejected {
            jdbc.update("UPDATE printing.print_job SET status = 'SENT', sent_at = now() WHERE id = ?", pending)
        }
        rejected {
            jdbc.update("UPDATE printing.print_job SET status = 'SENT', delivered_to = ? WHERE id = ?", agent, pending)
        }
        jdbc.update(
            "UPDATE printing.print_job SET status = 'SENT', sent_at = now(), delivered_to = ? WHERE id = ?",
            agent,
            pending,
        ) shouldBe 1
        rejected { jdbc.update("UPDATE printing.print_job SET delivered_to = NULL WHERE id = ?", pending) }
        rejected { jdbc.update("UPDATE printing.print_job SET sent_at = NULL WHERE id = ?", pending) }
    }

    @Test
    fun `content and content_purged_at are never both set, and the purge is allowed`() {
        val printer = printer(agent())

        rejected { job(printer, content = "'\\x01'", purgedAt = "now()") }
        job(printer, content = null, purgedAt = "now()")
        val id = job(printer, "PRINTED", finishedAt = "now()")
        rejected { jdbc.update("UPDATE printing.print_job SET content_purged_at = now() WHERE id = ?", id) }
        jdbc.update(
            "UPDATE printing.print_job SET content = NULL, content_purged_at = now() WHERE id = ?",
            id,
        ) shouldBe 1
        rejected { jdbc.update("UPDATE printing.print_job SET content = '\\x02' WHERE id = ?", id) }
    }

    @Test
    fun `not null columns of the job reject null and overlong codes`() {
        val printer = printer(agent())
        val id = job(printer, "FAILED", finishedAt = "now()")

        for (column in listOf("document_type", "media_type", "position", "created_at", "version", "attempts")) {
            rejected { jdbc.update("UPDATE printing.print_job SET $column = NULL WHERE id = ?", id) }
        }
        shouldThrow<DataAccessException> {
            jdbc.update("UPDATE printing.print_job SET failure_code = ? WHERE id = ?", "A".repeat(51), id)
        }
        jdbc.update("UPDATE printing.print_job SET failure_code = ? WHERE id = ?", "A".repeat(50), id) shouldBe 1
    }

    // --- posição e sequence

    @Test
    fun `position is unique and has no default`() {
        val printer = printer(agent())
        val position = jdbc.queryForObject("SELECT nextval('printing.print_job_position_seq')", Long::class.java)!!

        job(printer, position = "$position")
        rejected { job(printer, position = "$position") }
        val other = job(printer)
        rejected { jdbc.update("UPDATE printing.print_job SET position = ? WHERE id = ?", position, other) }
        jdbc
            .queryForObject(
                "SELECT column_default FROM information_schema.columns " +
                    "WHERE table_schema = 'printing' AND table_name = 'print_job' AND column_name = 'position'",
                String::class.java,
            ) shouldBe null
    }

    @Test
    fun `the sequence increases strictly and starts above zero`() {
        val first = jdbc.queryForObject("SELECT nextval('printing.print_job_position_seq')", Long::class.java)!!
        val second = jdbc.queryForObject("SELECT nextval('printing.print_job_position_seq')", Long::class.java)!!

        (first > 0) shouldBe true
        (second > first) shouldBe true
    }

    // --- índices

    @Test
    fun `the four job indexes exist with the expected columns and the purge predicate`() {
        val defs =
            jdbc
                .queryForList(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'printing' AND tablename = 'print_job'",
                    String::class.java,
                ).filterNotNull()
                .map { it.replace("\"", "") }

        defs.any { it.contains("(printer_id, status, position)") } shouldBe true
        defs.any { it.contains("(status, sent_at)") } shouldBe true
        defs.any { it.contains("(status, created_at)") } shouldBe true
        val purge = defs.single { it.contains("(status, finished_at)") }
        purge shouldContain "WHERE (content IS NOT NULL)"
        defs.filter { it.contains("UNIQUE") }.any { it.contains("(position)") } shouldBe true
    }

    @Test
    fun `unique indexes cover the token hash and the job position`() {
        val unique =
            jdbc
                .queryForList(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'printing' AND indexdef LIKE '%UNIQUE%'",
                    String::class.java,
                ).filterNotNull()
                .map { it.replace("\"", "") }

        unique.any { it.contains("print_agent") && it.contains("(token_hash)") } shouldBe true
        unique.any { it.contains("print_job") && it.contains("(position)") } shouldBe true
    }

    // --- privilégios do papel da aplicação

    @Test
    fun `the app role owns nothing in printing and has no DDL, TRUNCATE or schema CREATE`() {
        jdbc
            .queryForList(
                """
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'printing' AND pg_get_userbyid(c.relowner) = current_user
                """.trimIndent(),
                String::class.java,
            ).shouldBeEmpty()
        jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'printing', 'CREATE')",
            Boolean::class.java,
        ) shouldBe false
        jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'printing', 'USAGE')",
            Boolean::class.java,
        ) shouldBe true
        for (table in listOf("print_agent", "printer", "print_job")) {
            for (privilege in listOf("TRUNCATE", "REFERENCES", "TRIGGER")) {
                jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, 'printing.$table', '$privilege')",
                    Boolean::class.java,
                ) shouldBe false
            }
        }
        jdbc.queryForObject(
            "SELECT has_sequence_privilege(current_user, 'printing.print_job_position_seq', 'UPDATE')",
            Boolean::class.java,
        ) shouldBe false
    }

    @Test
    fun `the app role is refused DDL and TRUNCATE on the printing tables`() {
        denied("CREATE TABLE printing.attack_table (id int)")
        denied("ALTER TABLE printing.print_job DROP CONSTRAINT print_job_status_ck")
        denied("ALTER TABLE printing.print_job ADD COLUMN attack int")
        denied("DROP TABLE printing.print_job")
        denied("TRUNCATE printing.print_job")
        denied("DROP INDEX printing.print_job_purge_idx")
        denied("ALTER SEQUENCE printing.print_job_position_seq RESTART")
        denied("DROP SEQUENCE printing.print_job_position_seq")
        jdbc.queryForObject("SELECT 1 FROM printing.print_job_position_seq", Int::class.java)
    }
}
