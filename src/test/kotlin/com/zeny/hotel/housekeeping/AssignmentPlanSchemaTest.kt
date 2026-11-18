package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
import kotlin.random.Random

/**
 * O plano do dia da distribuição (M6 E03, Task 2): `housekeeping.assignment_plan`, um por data, que nunca é apagado, e
 * `housekeeping.assignment_plan_item`, uma tarefa no máximo uma vez por plano, com a camareira (nula: sem camareira) e
 * a posição, que o ajuste e a nova sugestão apagam. Os planos deste teste ficam em datas longe da janela (1900–1999).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlanSchemaTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    fun `the plan and item tables have only the plan columns`() {
        columns("assignment_plan") shouldContainExactlyInAnyOrder
            listOf(
                "id",
                "business_date",
                "status",
                "version",
                "created_at",
                "updated_at",
                "published_at",
                "published_by",
            )
        columns("assignment_plan_item") shouldContainExactlyInAnyOrder
            listOf("plan_id", "task_id", "employee_id", "position")
        owner
            .sql(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'housekeeping' " +
                    "AND table_name = 'assignment_plan_item' AND column_name = 'employee_id'",
            ).query(String::class.java)
            .single() shouldBe "YES"
    }

    @Test
    fun `one plan per date, one item per task in a plan, and the foreign keys hold`() {
        val date = farDate()
        val plan = insertPlan(date)
        val duplicate = shouldThrow<DataAccessException> { insertPlan(date) }
        (duplicate.mostSpecificCause as SQLException).sqlState shouldBe UNIQUE_VIOLATION

        val task = insertTask()
        insertItem(plan, task, employee = null, position = 1)
        val twice = shouldThrow<DataAccessException> { insertItem(plan, task, employee = UUID.randomUUID(), 2) }
        (twice.mostSpecificCause as SQLException).sqlState shouldBe UNIQUE_VIOLATION
        // A mesma tarefa pode estar no plano de outra data.
        insertItem(insertPlan(farDate()), task, employee = UUID.randomUUID(), position = 1)

        val unknownPlan = shouldThrow<DataAccessException> { insertItem(UUID.randomUUID(), insertTask(), null, 1) }
        (unknownPlan.mostSpecificCause as SQLException).sqlState shouldBe FOREIGN_KEY_VIOLATION
        val unknownTask = shouldThrow<DataAccessException> { insertItem(plan, UUID.randomUUID(), null, 1) }
        (unknownTask.mostSpecificCause as SQLException).sqlState shouldBe FOREIGN_KEY_VIOLATION
        val badStatus = shouldThrow<DataAccessException> { insertPlan(farDate(), status = "ARCHIVED") }
        (badStatus.mostSpecificCause as SQLException).sqlState shouldBe CHECK_VIOLATION
        val badPosition = shouldThrow<DataAccessException> { insertItem(plan, insertTask(), null, 0) }
        (badPosition.mostSpecificCause as SQLException).sqlState shouldBe CHECK_VIOLATION
    }

    @Test
    fun `the database refuses DELETE on plans, also for the owner, and the application deletes items`() {
        val plan = insertPlan(farDate())
        val task = insertTask()
        insertItem(plan, task, employee = UUID.randomUUID(), position = 1)

        val appError =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM housekeeping.assignment_plan WHERE id = :id").param("id", plan).update()
                }
            }
        (appError.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        val ownerError =
            shouldThrow<DataAccessException> {
                owner.sql("DELETE FROM housekeeping.assignment_plan WHERE id = :id").param("id", plan).update()
            }
        (ownerError.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION

        transactions.executeWithoutResult {
            jdbc
                .sql("DELETE FROM housekeeping.assignment_plan_item WHERE plan_id = :plan AND task_id = :task")
                .param("plan", plan)
                .param("task", task)
                .update() shouldBe 1
        }
    }

    private fun columns(table: String): List<String> =
        owner
            .sql(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'housekeeping' AND table_name = :table",
            ).param("table", table)
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Uma data do século passado, longe da janela dos planos (o banco é compartilhado e o plano não se apaga). */
    private fun farDate(): LocalDate = LocalDate.of(1900, 1, 1).plusDays(Random.nextLong(FAR_DAYS))

    private fun insertPlan(
        date: LocalDate,
        status: String = "DRAFT",
    ): UUID {
        val id = UUID.randomUUID()
        owner
            .sql(
                """
                INSERT INTO housekeeping.assignment_plan (id, business_date, status, version, created_at, updated_at)
                VALUES (:id, :date, :status, 0, now(), now())
                """.trimIndent(),
            ).param("id", id)
            .param("date", date)
            .param("status", status)
            .update()
        return id
    }

    private fun insertItem(
        plan: UUID,
        task: UUID,
        employee: UUID?,
        position: Int,
    ) {
        jdbc
            .sql(
                "INSERT INTO housekeeping.assignment_plan_item (plan_id, task_id, employee_id, position) " +
                    "VALUES (:plan, :task, :employee, :position)",
            ).param("plan", plan)
            .param("task", task)
            .param("employee", employee)
            .param("position", position)
            .update()
    }

    /**
     * Uma tarefa gravada pelo dono numa área inexistente (sem FK entre schemas), longe da data corrente. Fica
     * `CANCELLED` para não entrar no conteúdo de nenhum plano operacional (D2-E03): o schema não depende do status.
     */
    private fun insertTask(): UUID {
        val id = UUID.randomUUID()
        owner
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                    source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                    created_at, updated_at, version)
                VALUES (:id, NULL, :area, DATE '2001-01-01', :type, 'AD_HOC', 'CANCELLED', 3, 0, FALSE, NULL, NULL,
                    NULL, now(), now(), 0)
                """.trimIndent(),
            ).param("id", id)
            .param("area", UUID.randomUUID())
            .param("type", TOUCH_UP_TYPE)
            .update()
        return id
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val FAR_DAYS = 36_000L

        /** O tipo `TOUCH_UP` da carga (D5), só referenciado. */
        val TOUCH_UP_TYPE: UUID = UUID.fromString("01a1e8f2-f902-7002-8000-000000000003")
    }
}
