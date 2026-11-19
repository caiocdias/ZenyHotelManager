package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.itemsOf
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.unassignedOf
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.util.UUID

/**
 * A publicação do plano com tarefas que já terminaram (M6 E04, Task 3; D12 da E04, nota da E03): o item de uma tarefa
 * `DONE` ou `CANCELLED` é ignorado, a tarefa não muda e o item sai do plano publicado, em vez de a publicação inteira
 * ser recusada. A tarefa `IN_PROGRESS` que o rascunho passaria a outra camareira segue recusando a publicação (`422
 * ASSIGNMENT_TASK_NOT_ASSIGNABLE`, coberto pelo `AssignmentPlanPublishTest`).
 *
 * Como no `AssignmentPlanPublishTest`: numa data futura da janela, cada teste monta o rascunho exato com as próprias
 * tarefas e camareiras, e a tarefa "termina" por escrita direta depois de entrar no rascunho (o ajuste não deixa uma
 * tarefa terminada entrar no plano).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlanPublishFinishedTasksTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    private val date: LocalDate get() = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)

    @Test
    fun `a DONE task given to another attendant is ignored - the plan publishes, the task does not change`() {
        val ana = plans.attendant(date)
        val bia = plans.attendant(date)
        val done = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana, bia), listOf(done to ana, other to ana))
        plans.published(date)
        plans.draft(date, listOf(ana, bia), listOf(done to bia, other to bia))
        plans.setStatus(done, "DONE")
        val before = plans.task(done)

        val (body, _) = plans.published(date)

        body["status"].asString() shouldBe "PUBLISHED"
        plans.task(done) shouldBe before
        val plan = with(iam) { plans.plan(date).ok() }
        withClue("o item da tarefa terminada sai do plano publicado") {
            itemsOf(plan).keys shouldNotContain done
            unassignedOf(plan) shouldNotContain done
        }
        itemsOf(plan)[other] shouldBe (bia to 1)
        assigned(other) shouldBe (bia to 1)
    }

    @Test
    fun `a CANCELLED task left without attendant is ignored and leaves the plan`() {
        val ana = plans.attendant(date)
        val cancelled = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana), listOf(cancelled to ana, other to ana))
        plans.published(date)
        plans.draft(date, listOf(ana), listOf(cancelled to null, other to ana))
        plans.setStatus(cancelled, "CANCELLED")
        val before = plans.task(cancelled)

        plans.published(date)

        plans.task(cancelled) shouldBe before
        val plan = with(iam) { plans.plan(date).ok() }
        itemsOf(plan).keys shouldNotContain cancelled
        unassignedOf(plan) shouldNotContain cancelled
        assigned(other) shouldBe (ana to 1)
    }

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

    /** A camareira e a posição gravadas na tarefa. */
    private fun assigned(task: UUID): Pair<UUID, Int>? {
        val node = plans.task(task)
        if (node["assignedEmployeeId"].isNull) return null
        return UUID.fromString(node["assignedEmployeeId"].asString()) to node["assignmentPosition"].asInt()
    }

    private companion object {
        const val DAYS_AHEAD = 6L
    }
}
