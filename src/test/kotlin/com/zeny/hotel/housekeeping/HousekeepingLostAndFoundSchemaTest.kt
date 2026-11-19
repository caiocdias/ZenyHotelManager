package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A tabela `housekeeping.lost_and_found_item` (M6 E06, Task 1, D2): um local só (UH ou área), o destino coerente com o
 * status (a quem só na devolução, o motivo só no descarte e na doação, quando e por quem fora de `STORED`), a origem do
 * checklist com a execução e o item, e a recusa de DELETE, também para o dono do schema.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingLostAndFoundSchemaTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    fun `the row has one location, a destination coherent with the status and a coherent checklist origin`() {
        val room = UUID.randomUUID()
        val area = UUID.randomUUID()
        val refused =
            listOf(
                Row(room = room, area = area),
                Row(room = null, area = null),
                Row(status = "LOST"),
                Row(source = "PHONE"),
                Row(status = "RETURNED"),
                Row(status = "RETURNED", returnedTo = "Fulano"),
                Row(status = "RETURNED", returnedTo = "Fulano", resolved = true, reason = "Motivo"),
                Row(status = "DISCARDED", resolved = true),
                Row(status = "DONATED", resolved = true, reason = "Bazar", returnedTo = "Fulano"),
                Row(returnedTo = "Fulano"),
                Row(resolved = true),
                Row(source = "CHECKLIST"),
                Row(execution = UUID.randomUUID(), itemKey = "item"),
                Row(source = "CHECKLIST", execution = UUID.randomUUID()),
                Row(description = "   "),
            )
        refused.forEach { row ->
            withClue(row) {
                val error = shouldThrow<DataAccessException> { insert(row) }
                (error.mostSpecificCause as SQLException).sqlState shouldBe CHECK_VIOLATION
            }
        }
        listOf(
            Row(),
            Row(room = null, area = area),
            Row(status = "RETURNED", returnedTo = "Fulano", resolved = true),
            Row(status = "DISCARDED", reason = "Vencido", resolved = true),
            Row(status = "DONATED", reason = "Bazar", resolved = true),
            Row(source = "CHECKLIST", execution = UUID.randomUUID(), itemKey = "item"),
        ).forEach { row -> withClue(row) { insert(row) shouldBe 1 } }
    }

    @Test
    fun `the database refuses DELETE on lost and found items, also for the owner`() {
        val id = UUID.randomUUID()
        insert(Row(id = id))

        val appError =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM housekeeping.lost_and_found_item WHERE id = :id").param("id", id).update()
                }
            }
        (appError.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        val ownerError =
            shouldThrow<DataAccessException> {
                owner.sql("DELETE FROM housekeeping.lost_and_found_item WHERE id = :id").param("id", id).update()
            }
        (ownerError.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION
    }

    private data class Row(
        val id: UUID = UUID.randomUUID(),
        val room: UUID? = UUID.randomUUID(),
        val area: UUID? = null,
        val description: String = "Carteira",
        val status: String = "STORED",
        val source: String = "MANUAL",
        val execution: UUID? = null,
        val itemKey: String? = null,
        val returnedTo: String? = null,
        val reason: String? = null,
        val resolved: Boolean = false,
    )

    private fun insert(row: Row): Int =
        owner
            .sql(
                """
                INSERT INTO housekeeping.lost_and_found_item (id, room_id, area_id, description, storage_place,
                    found_at, reported_by, source, checklist_execution_id, checklist_item_key, status, returned_to,
                    disposal_reason, resolved_at, resolved_by, created_at, updated_at, version)
                VALUES (:id, :room, :area, :description, NULL, now(), :user, :source, :execution, :itemKey, :status,
                    :returnedTo, :reason, :resolvedAt, :resolvedBy, now(), now(), 0)
                """.trimIndent(),
            ).param("id", row.id)
            .param("room", row.room)
            .param("area", row.area)
            .param("description", row.description)
            .param("user", iam.adminId())
            .param("source", row.source)
            .param("execution", row.execution)
            .param("itemKey", row.itemKey)
            .param("status", row.status)
            .param("returnedTo", row.returnedTo)
            .param("reason", row.reason)
            .param("resolvedAt", if (row.resolved) OffsetDateTime.now() else null)
            .param("resolvedBy", if (row.resolved) iam.adminId() else null)
            .update()

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
        const val CHECK_VIOLATION = "23514"
    }
}
