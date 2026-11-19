package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * A tabela `housekeeping.housekeeping_task` (M6 E02, Task 1): só as colunas da tarefa, sem nome, documento, contato nem
 * id de hóspede ou de reserva (RN-13); a unicidade por (local, data, tipo) com `NULLS NOT DISTINCT` (D5); os CHECKs de
 * exatamente um local, de `status` e de `source`; e a recusa de DELETE, também para o dono. O `HousekeepingSchemaTest`
 * da E01 é do testador; este teste fica ao lado dele.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskSchemaTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    fun `the task table has only the task columns, no guest or reservation data (RN-13)`() {
        val columns =
            owner
                .sql(
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'housekeeping' AND table_name = 'housekeeping_task'",
                ).query(String::class.java)
                .list()

        columns shouldContainExactlyInAnyOrder
            listOf(
                "id",
                "room_id",
                "area_id",
                "business_date",
                "cleaning_type_id",
                "source",
                "status",
                "priority",
                "credits",
                "arrival_expected",
                "scheduled_cleaning_id",
                "notes",
                "created_by",
                "created_at",
                "updated_at",
                "version",
                "assigned_employee_id",
                "assignment_position",
                "started_at",
                "finished_at",
                "worked_seconds",
                "in_progress_since",
                "last_transition_at",
                "cleaned_by",
                "refusal_reason",
            )
    }

    @Test
    fun `the assignee goes with the position, never on a PENDING task, and the position starts at 1 (M6 E03)`() {
        val room = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val cases =
            listOf(
                "PENDING with assignee" to { insert(room = room, employee = employee, position = 1) },
                "assignee without position" to { insert(room = room, status = "ASSIGNED", employee = employee) },
                "position without assignee" to { insert(room = room, status = "ASSIGNED", position = 1) },
                "position 0" to { insert(room = room, status = "ASSIGNED", employee = employee, position = 0) },
            )
        cases.forEach { (case, write) ->
            withClue(case) {
                val error = shouldThrow<DataAccessException> { write() }
                (error.mostSpecificCause as SQLException).sqlState shouldBe CHECK_VIOLATION
            }
        }

        val assigned = insert(room = room, status = "ASSIGNED", employee = employee, position = 2)
        insert(room = UUID.randomUUID(), status = "IN_PROGRESS", employee = employee, position = 1)
        val pending = insert(room = UUID.randomUUID())

        // A ASSIGNED e a PENDING de 2001-01-01 entrariam em todo plano operacional do banco compartilhado (D2-E03).
        listOf(assigned, pending).forEach { cancel(it) }
    }

    /** Fecha a tarefa (`CANCELLED`, sem camareira), para ela sair do conteúdo dos planos. */
    private fun cancel(task: UUID) {
        owner
            .sql(
                "UPDATE housekeeping.housekeeping_task SET status = 'CANCELLED', assigned_employee_id = NULL, " +
                    "assignment_position = NULL WHERE id = :id",
            ).param("id", task)
            .update() shouldBe 1
    }

    @Test
    fun `the task is unique per location, date and type, with nulls not distinct`() {
        val definition =
            owner
                .sql(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c " +
                        "JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace " +
                        "WHERE n.nspname = 'housekeeping' AND t.relname = 'housekeeping_task' AND c.contype = 'u'",
                ).query(String::class.java)
                .single()
        definition shouldContain "UNIQUE NULLS NOT DISTINCT (room_id, area_id, business_date, cleaning_type_id)"

        val area = UUID.randomUUID()
        val date = LocalDate.of(2001, 2, 3)
        insert(area = area, date = date)
        val duplicate = shouldThrow<DataAccessException> { insert(area = area, date = date) }
        (duplicate.mostSpecificCause as SQLException).sqlState shouldBe UNIQUE_VIOLATION
        insert(area = area, date = date.plusDays(1))
    }

    @Test
    fun `the checks refuse both locations, none, an unknown status or source and a schedule outside SCHEDULED`() {
        val room = UUID.randomUUID()
        val cases =
            listOf(
                "both locations" to { insert(room = room, area = UUID.randomUUID()) },
                "no location" to { insert() },
                "status" to { insert(room = room, status = "LOST") },
                "source" to { insert(room = room, source = "GUEST") },
                "priority" to { insert(room = room, priority = 6) },
                "credits" to { insert(room = room, credits = -1) },
                "schedule outside SCHEDULED" to { insert(room = room, schedule = UUID.randomUUID()) },
                "SCHEDULED without schedule" to { insert(room = room, source = "SCHEDULED") },
            )
        cases.forEach { (case, write) ->
            withClue(case) {
                val error = shouldThrow<DataAccessException> { write() }
                (error.mostSpecificCause as SQLException).sqlState shouldBe CHECK_VIOLATION
            }
        }
    }

    @Test
    fun `the database refuses DELETE on tasks, also for the owner`() {
        val id = insert(room = UUID.randomUUID())

        val appError =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM housekeeping.housekeeping_task WHERE id = :id").param("id", id).update()
                }
            }
        (appError.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        val ownerError =
            shouldThrow<DataAccessException> {
                owner.sql("DELETE FROM housekeeping.housekeeping_task WHERE id = :id").param("id", id).update()
            }
        (ownerError.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION
        // O papel da aplicação altera (desfeito no fim).
        transactions.executeWithoutResult { status ->
            jdbc
                .sql("UPDATE housekeeping.housekeeping_task SET status = 'ASSIGNED' WHERE id = :id")
                .param("id", id)
                .update() shouldBe 1
            status.setRollbackOnly()
        }
    }

    /** Uma tarefa gravada pelo dono, num local inexistente (sem FK entre schemas) e numa data longe da corrente. */
    @Suppress("LongParameterList")
    private fun insert(
        room: UUID? = null,
        area: UUID? = null,
        date: LocalDate = LocalDate.of(2001, 1, 1),
        status: String = "PENDING",
        source: String = "AD_HOC",
        priority: Int = 3,
        credits: Int = 0,
        schedule: UUID? = null,
        employee: UUID? = null,
        position: Int? = null,
    ): UUID {
        val id = UUID.randomUUID()
        owner
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                    source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                    created_at, updated_at, version, assigned_employee_id, assignment_position)
                VALUES (:id, :room, :area, :date, :type, :source, :status, :priority, :credits, FALSE, :schedule, NULL,
                    NULL, now(), now(), 0, :employee, :position)
                """.trimIndent(),
            ).param("id", id)
            .param("room", room)
            .param("area", area)
            .param("date", date)
            .param("type", TOUCH_UP_TYPE)
            .param("source", source)
            .param("status", status)
            .param("priority", priority)
            .param("credits", credits)
            .param("schedule", schedule)
            .param("employee", employee)
            .param("position", position)
            .update()
        return id
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"

        /** O tipo `TOUCH_UP` da carga (D5), só referenciado. */
        val TOUCH_UP_TYPE: UUID = UUID.fromString("01a1e8f2-f902-7002-8000-000000000003")
    }
}
