package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.HousekeepingTaskStore
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * O histórico de posse da tarefa (M6 E19, Task 2, D3): a publicação do plano grava uma linha em
 * `housekeeping.task_assignment` a cada vez que a tarefa passa a uma camareira (não ao mudar só a posição, nem na
 * retirada); as tarefas atribuídas antes da migração ganham a linha da camareira atual; a tabela é só de inserção
 * (o papel da aplicação não altera nem apaga) e declarada em [AppendOnlyTables].
 *
 * O banco é compartilhado: a publicação usa uma data futura da janela ([DAYS_AHEAD], tolerando o plano alheio) e o
 * teste afirma só sobre as próprias tarefas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlanOwnershipTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val store: HousekeepingTaskStore,
    @Autowired private val appendOnly: List<AppendOnlyTables>,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired staffApi: StaffApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    private val date: LocalDate get() = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)

    @Test
    fun `each assignment to an attendant is recorded, not a position change nor a withdrawal`() {
        val ana = plans.attendant(date)
        val bia = plans.attendant(date)
        val first = roomTask()
        val second = roomTask()

        publish(listOf(ana, bia), first to ana, second to ana)
        owners(first) shouldBe listOf(ana)
        owners(second) shouldBe listOf(ana)

        publish(listOf(ana, bia), second to ana, first to ana)
        withClue("só a posição mudou") { owners(first) shouldBe listOf(ana) }

        publish(listOf(ana, bia), first to bia, second to ana)
        publish(listOf(ana, bia), first to ana, second to ana)
        owners(first) shouldBe listOf(ana, bia, ana)

        publish(listOf(ana, bia), second to ana)
        withClue("a retirada não grava posse") { owners(first) shouldBe listOf(ana, bia, ana) }
        owners(second) shouldBe listOf(ana)

        store.wasAssignedTo(HousekeepingTaskId(first), EmployeeId(bia)) shouldBe true
        store.wasAssignedTo(HousekeepingTaskId(second), EmployeeId(bia)) shouldBe false
    }

    @Test
    fun `a task assigned before the migration gets the row of its current attendant`() {
        val ana = plans.attendant()
        // Uma tarefa atribuída sem a publicação, como estavam as tarefas antes desta etapa (sem linha de posse).
        val task = plans.insertTask(date, area = UUID.randomUUID(), status = "ASSIGNED", employee = ana, position = 1)
        val backfill =
            checkNotNull(javaClass.classLoader.getResource(BACKFILL)) { "migração $BACKFILL ausente" }.readText()

        val rows =
            transactions.execute { status ->
                status.setRollbackOnly()
                owners(task) shouldBe emptyList()
                jdbc.sql(backfill).update()
                jdbc
                    .sql(
                        "SELECT a.employee_id, a.assigned_at = t.updated_at AS same_instant " +
                            "FROM housekeeping.task_assignment a JOIN housekeeping.housekeeping_task t ON t.id = a.task_id " +
                            "WHERE a.task_id = :task",
                    ).param("task", task)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getBoolean(2) }
                    .list()
            }

        rows shouldBe listOf(ana to true)
    }

    @Test
    fun `the application role cannot update nor delete an ownership row, and the table is declared append-only`() {
        val ana = plans.attendant(date)
        val task = roomTask()
        publish(listOf(ana), task to ana)

        listOf(
            "UPDATE housekeeping.task_assignment SET assigned_at = :now WHERE task_id = :task",
            "DELETE FROM housekeeping.task_assignment WHERE task_id = :task",
        ).forEach { sql ->
            withClue(sql) {
                val refused =
                    shouldThrow<DataAccessException> {
                        jdbc
                            .sql(sql)
                            .apply { if (":now" in sql) param("now", OffsetDateTime.now()) }
                            .param("task", task)
                            .update()
                    }
                (refused.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
            }
        }
        owners(task) shouldBe listOf(ana)
        appendOnly.flatMap { it.tables() } shouldContain "housekeeping.task_assignment"
    }

    // --- Apoio -------------------------------------------------------------------------------------------------------

    private fun roomTask(): UUID =
        UUID.fromString(
            housekeeping
                .requestTask(
                    housekeeping.taskBody(
                        AssignmentPlanTestSupport.TOUCH_UP_TYPE,
                        roomId = housekeeping.createRoom(),
                        date = "$date",
                    ),
                )["id"]
                .asString(),
        )

    private fun publish(
        employees: List<UUID>,
        vararg items: Pair<UUID, UUID>,
    ) {
        plans.draft(date, employees, items.toList())
        plans.published(date)
    }

    /** As camareiras que já tiveram a tarefa, na ordem da posse. */
    private fun owners(task: UUID): List<UUID> =
        jdbc
            .sql("SELECT employee_id FROM housekeeping.task_assignment WHERE task_id = :task ORDER BY assigned_at, id")
            .param("task", task)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .list()

    private companion object {
        const val DAYS_AHEAD = 6L
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val BACKFILL = "db/migration/housekeeping/V2026_11_19_1102__backfill_task_assignment.sql"
    }
}
