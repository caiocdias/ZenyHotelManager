package com.zeny.hotel.printing

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Esquema do `printing` (M5 E10, Task 1): privilégios do papel da aplicação e as restrições das três tabelas. */
@IntegrationTest
class PrintingSchemaTest(
    @Autowired private val jdbc: JdbcTemplate,
) {
    private fun insertAgent(
        active: Boolean = true,
        tokenHash: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO printing.print_agent (id, name, token_hash, active, created_at, version) " +
                "VALUES (?, 'agent', ?, ?, now(), 0)",
            id,
            tokenHash,
            active,
        )
        return id
    }

    private fun insertPrinter(agentId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO printing.printer (id, name, kind, address, agent_id, active, version) " +
                "VALUES (?, 'printer', 'THERMAL_ESCPOS', 'usb://1', ?, true, 0)",
            id,
            agentId,
        )
        return id
    }

    private fun insertJob(
        printerId: UUID,
        status: String,
        finishedAtSql: String = "NULL",
        sentAtSql: String = "NULL",
        deliveredTo: UUID? = null,
    ) = jdbc.update(
        "INSERT INTO printing.print_job (id, printer_id, document_type, status, position, attempts, content, " +
            "media_type, created_at, sent_at, delivered_to, finished_at, version) " +
            "VALUES (?, ?, 'frontdesk.registration-card', ?, nextval('printing.print_job_position_seq'), 0, " +
            "'\\x00', 'application/octet-stream', now(), $sentAtSql, ?, $finishedAtSql, 0)",
        UUID.randomUUID(),
        printerId,
        status,
        deliveredTo,
    )

    private fun hash(seed: String): String = seed.padEnd(64, '0')

    @Test
    fun `the app role has DML on the three tables and usage on the position sequence`() {
        for (table in listOf("print_agent", "printer", "print_job")) {
            for (privilege in listOf("SELECT", "INSERT", "UPDATE", "DELETE")) {
                jdbc
                    .queryForObject(
                        "SELECT has_table_privilege(current_user, 'printing.$table', '$privilege')",
                        Boolean::class.java,
                    ) shouldBe true
            }
        }
        jdbc.queryForObject(
            "SELECT has_sequence_privilege(current_user, 'printing.print_job_position_seq', 'USAGE')",
            Boolean::class.java,
        ) shouldBe true
    }

    @Test
    fun `a repeated token hash violates the unique constraint`() {
        val token = hash(UUID.randomUUID().toString().replace("-", ""))
        insertAgent(tokenHash = token)

        shouldThrow<DataIntegrityViolationException> { insertAgent(tokenHash = token) }
    }

    @Test
    fun `an inactive agent cannot keep a token hash`() {
        shouldThrow<DataIntegrityViolationException> {
            insertAgent(active = false, tokenHash = hash(UUID.randomUUID().toString().replace("-", "")))
        }
    }

    @Test
    fun `a pending job cannot have a finish time`() {
        val printer = insertPrinter(insertAgent())

        shouldThrow<DataIntegrityViolationException> { insertJob(printer, "PENDING", finishedAtSql = "now()") }
    }

    @Test
    fun `a sent job needs the delivery agent and the send time`() {
        val agent = insertAgent()
        val printer = insertPrinter(agent)

        shouldThrow<DataIntegrityViolationException> { insertJob(printer, "SENT", sentAtSql = "now()") }
        shouldThrow<DataIntegrityViolationException> { insertJob(printer, "SENT", deliveredTo = agent) }
        insertJob(printer, "SENT", sentAtSql = "now()", deliveredTo = agent) shouldBe 1
    }

    @Test
    fun `a finished job needs a finish time and keeps either content or purge time, never both`() {
        val printer = insertPrinter(insertAgent())

        shouldThrow<DataIntegrityViolationException> { insertJob(printer, "PRINTED") }
        insertJob(printer, "PRINTED", finishedAtSql = "now()") shouldBe 1
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE printing.print_job SET content_purged_at = now() WHERE printer_id = ?", printer)
        }
    }

    @Test
    fun `an unknown kind or status is rejected`() {
        val agent = insertAgent()

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO printing.printer (id, name, kind, address, agent_id, active, version) " +
                    "VALUES (?, 'p', 'LASER', 'x', ?, true, 0)",
                UUID.randomUUID(),
                agent,
            )
        }
        shouldThrow<DataIntegrityViolationException> { insertJob(insertPrinter(agent), "DONE") }
    }
}
