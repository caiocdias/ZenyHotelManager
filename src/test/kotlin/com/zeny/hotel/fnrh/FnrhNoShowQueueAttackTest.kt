package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionFailureView
import com.zeny.hotel.fnrh.application.FnrhSubmissionOperationView
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ataques do testador ao no-show na fila e no contrato (M6 E14, Task 2; D-E14-2 e 5): a ordem causal de `NO_SHOW` na
 * mesma versão e em versões diferentes, a view de relatório como texto, o gauge e os enums do `api/openapi.yaml`.
 * Ids próprios (banco compartilhado); as linhas gravadas são apagadas no fim.
 */
@IntegrationTest
class FnrhNoShowQueueAttackTest(
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val registry: MeterRegistry,
) {
    private val now: Instant = Instant.parse("2026-11-14T12:00:00Z")
    private val later: Instant = now.plus(Duration.ofDays(30))
    private val recorded = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        recorded.forEach { jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", it).update() }
    }

    private fun record(
        reservation: UUID,
        operation: FnrhOperation,
        version: Long,
        key: String = "${operation.name}:v$version",
    ): UUID {
        val s = FnrhSubmission.create(reservation, "R-NOSHOW-ATK", operation, key, version, null, now, now)
        recorded += s.id
        store.recordIfAbsent(s) shouldBe true
        return s.id
    }

    private fun heads(reservation: UUID) = store.dueHeads(later, 100, reservation)

    private fun markSent(id: UUID) {
        jdbc.sql("UPDATE fnrh.submission SET status = 'SENT' WHERE id = :id").param("id", id).update()
    }

    @Test
    fun `a NO_SHOW never passes the registration of version 0 even when stored first`() {
        val r = UuidV7.generate()
        val noShow = record(r, FnrhOperation.NO_SHOW, 2)
        val register = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        heads(r) shouldContainExactly listOf(register)
        markSent(register)
        heads(r) shouldContainExactly listOf(noShow)
    }

    @Test
    fun `on the same version the NO_SHOW comes after every other operation`() {
        val r = UuidV7.generate()
        val noShow = record(r, FnrhOperation.NO_SHOW, 5)
        val others = FnrhOperation.entries.filter { it != FnrhOperation.NO_SHOW }.map { record(r, it, 5) }

        others.sortedBy { FnrhOperation.valueOf(operationOf(it)).rank }.forEach {
            heads(r) shouldContainExactly listOf(it)
            markSent(it)
        }
        heads(r) shouldContainExactly listOf(noShow)
    }

    @Test
    fun `a lower version NO_SHOW goes before a higher version operation and a higher one goes after`() {
        val r = UuidV7.generate()
        val update = record(r, FnrhOperation.UPDATE_RESERVATION, 3)
        val early = record(r, FnrhOperation.NO_SHOW, 2)
        val reactivation = record(r, FnrhOperation.UPDATE_RESERVATION, 1)

        heads(r) shouldContainExactly listOf(reactivation)
        markSent(reactivation)
        heads(r) shouldContainExactly listOf(early)
        markSent(early)
        heads(r) shouldContainExactly listOf(update)
    }

    @Test
    fun `a FAILED NO_SHOW holds the later operations of its reservation`() {
        val r = UuidV7.generate()
        val noShow = record(r, FnrhOperation.NO_SHOW, 2)
        record(r, FnrhOperation.UPDATE_RESERVATION, 3)
        jdbc
            .sql("UPDATE fnrh.submission SET status = 'FAILED', last_failure = 'REMOTE_NO_SHOW' WHERE id = :id")
            .param("id", noShow)
            .update()

        heads(r).shouldBeEmpty()
    }

    @Test
    fun `the report view shows NO_SHOW and REMOTE_NO_SHOW as text`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.NO_SHOW, 2)
        jdbc
            .sql(
                "UPDATE fnrh.submission SET status = 'FAILED', attempts = 1, last_failure = 'REMOTE_NO_SHOW' " +
                    "WHERE id = :id",
            ).param("id", id)
            .update()

        val row =
            jdbc
                .sql("SELECT operation, last_failure, status FROM fnrh.report_submissions WHERE submission_id = :id")
                .param("id", id)
                .query { rs, _ -> Triple(rs.getString("operation"), rs.getString("last_failure"), rs.getString(3)) }
                .single()
        row shouldBe Triple("NO_SHOW", "REMOTE_NO_SHOW", "FAILED")
        val types =
            jdbc
                .sql(
                    "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'fnrh' " +
                        "AND table_name = 'report_submissions' AND column_name IN ('operation', 'last_failure')",
                ).query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .list()
                .toMap()
        types.values.toSet().all { it == "text" || it == "character varying" } shouldBe true
    }

    @Test
    fun `the failed gauge counts a NO_SHOW submission failed with REMOTE_NO_SHOW`() {
        val before =
            registry
                .get("zeny.fnrh.submissions")
                .tag("status", "failed")
                .gauge()
                .value()
        val id = record(UuidV7.generate(), FnrhOperation.NO_SHOW, 2)
        jdbc
            .sql("UPDATE fnrh.submission SET status = 'FAILED', last_failure = 'REMOTE_NO_SHOW' WHERE id = :id")
            .param("id", id)
            .update()

        registry
            .get("zeny.fnrh.submissions")
            .tag("status", "failed")
            .gauge()
            .value() shouldBeGreaterThanOrEqual
            before + 1
    }

    @Test
    fun `the web mirrors carry exactly the names of the domain enums`() {
        FnrhSubmissionOperationView.entries.map { it.name } shouldContainExactly FnrhOperation.entries.map { it.name }
        FnrhSubmissionFailureView.entries.map { it.name } shouldContainExactly
            FnrhSubmissionFailure.entries.map { it.name }
    }

    @Test
    fun `the contract lists NO_SHOW and REMOTE_NO_SHOW as x-extensible-enum and never as a closed enum`() {
        val schema = schemaBlock("FnrhSubmissionResponse")
        extensibleValues(schema, "lastFailure") shouldContainExactly FnrhSubmissionFailure.entries.map { it.name }
        extensibleValues(schema, "operation") shouldContainExactly FnrhOperation.entries.map { it.name }
        // Um `enum:` fechado faria o cliente gerado recusar o valor novo: o campo só pode ser `x-extensible-enum`.
        schema.lines().none { it.trim() == "enum:" } shouldBe true
    }

    private fun operationOf(id: UUID): String =
        jdbc
            .sql(
                "SELECT operation FROM fnrh.submission WHERE id = :id",
            ).param("id", id)
            .query(String::class.java)
            .single()

    private fun schemaBlock(name: String): String {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val start = lines.indexOfFirst { it == "    $name:" }
        check(start >= 0) { "schema $name ausente do contrato" }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].matches(Regex("^ {4}\\S.*")) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun extensibleValues(
        schema: String,
        property: String,
    ): List<String> {
        val lines = schema.lines()
        val at = lines.indexOfFirst { it == "        $property:" }
        check(at >= 0) { "propriedade $property ausente" }
        val key = (at until lines.size).first { lines[it].trim() == "x-extensible-enum:" }
        return lines
            .drop(key + 1)
            .takeWhile { it.trim().startsWith("- ") }
            .map { it.trim().removePrefix("- ") }
    }
}
