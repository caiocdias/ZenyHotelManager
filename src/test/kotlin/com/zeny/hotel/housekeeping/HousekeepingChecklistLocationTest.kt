package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistLocation
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.util.UUID

/**
 * O local do checklist da governança (M6 E08, D1): a conclusão de uma tarefa com checklist e a inspeção dela publicam
 * `ChecklistCompleted` com a UH da tarefa como local, para o `maintenance` abrir a OS no lugar certo.
 */
@IntegrationTest
@RecordApplicationEvents
@Import(IamTestConfiguration::class)
class HousekeepingChecklistLocationTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    // O registro de eventos do Spring só chega por campo (não pelo construtor).
    @Suppress("VarCouldBeVal") // lateinit, injetado pelo Spring
    @Autowired
    private lateinit var events: ApplicationEvents

    private val support = InspectionTestSupport(iam, jdbc, staffApi)
    private val execution = support.execution
    private val housekeeping = support.housekeeping
    private val templates = ChecklistsTestSupport(iam)

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `completing a task with a checklist publishes the room of the task as the location`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val template = housekeepingTemplate()
            val type =
                housekeeping.createCleaningType(
                    housekeeping.cleaningTypeBody(requiresInspection = false, checklistTemplateId = template),
                )
            val (task, room) = support.assignedTask(type, attendant.employee)

            execution.acted(task, "start", auth)
            execution.acted(task, "complete", auth, mapOf("answers" to listOf(support.check("bed"))))

            val checklist = checklistOf(task)
            completed(checklist).location shouldBe ChecklistLocation(roomId = room.value, areaId = null)
        }

    @Test
    fun `an inspection publishes the room of the task as the location`(): Unit =
        with(iam) {
            val type = support.cleaningType(requiresInspection = true)
            support.inspectionTemplate(type)
            val (task, room) = support.awaitingInspection(type)
            val inspector = asTestUser(support.inspector())

            execution.acted(task, "inspection/start", inspector)
            execution.acted(
                task,
                "inspection/complete",
                inspector,
                support.complete("APPROVED", answers = listOf(support.check("bed"))),
            )

            val checklist =
                jdbc
                    .sql("SELECT checklist_execution_id FROM housekeeping.inspection WHERE task_id = :task")
                    .param("task", task)
                    .query(UUID::class.java)
                    .single()
            completed(checklist).location shouldBe ChecklistLocation(roomId = room.value, areaId = null)
        }

    private fun housekeepingTemplate(): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    context = ChecklistContext.HOUSEKEEPING,
                    // Uma regra que não vale para nenhuma tarefa: o modelo só entra pelo vínculo do tipo.
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(UuidV7.generate()))),
                    sections =
                        listOf(
                            templates.section("Quarto", templates.item("bed", extra = mapOf("required" to true))),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    private fun checklistOf(task: UUID): UUID =
        jdbc
            .sql("SELECT checklist_execution_id FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query(UUID::class.java)
            .single()

    private fun completed(checklist: UUID): ChecklistCompleted =
        events
            .stream(ChecklistCompleted::class.java)
            .filter { it.executionId == checklist }
            .toList()
            .single()
}
