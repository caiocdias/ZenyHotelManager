package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * O ataque da etapa E04 sobre a conclusão (`complete`): autorização do caminho próprio, os `400` de entrada, o
 * rollback completo quando o `rooms` recusa o número de hóspedes, `answers: [null]`, a trilha e a foto de outro autor.
 * Cada teste usa UH, tipo e camareira próprios (banco compartilhado).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LargeClass", "LongMethod")
class HousekeepingTaskExecutionAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired files: FilesTestSupport,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)
    private val executions = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    // --- autorização do complete -----------------------------------------------------------------------

    @Test
    fun `complete with OWN - a foreign and a missing task are the same 403, before 412, 409 and 400`(): Unit =
        with(iam) {
            val mine = execution.attendant()
            val other = execution.attendant()
            val auth = asTestUser(mine.user)
            val foreign = execution.assignedTask(other.employee)
            val foreignStarted = execution.assignedTask(other.employee)
            execution.acted(foreignStarted, "start", asTestUser(other.user))
            val missing = UUID.randomUUID()

            // Tarefa sem checklist: as respostas dão 400 de regra do serviço, que vem depois do 403.
            val badAnswers = mapOf("answers" to listOf(check("bed")))
            val details =
                listOf(
                    execution.act(foreign, "complete", auth), // 409 (ASSIGNED)
                    execution.act(foreign, "complete", auth, ifMatch = "\"999\""), // 412
                    execution.act(foreign, "complete", auth, body = badAnswers), // 400
                    execution.act(foreignStarted, "complete", auth), // passaria
                    execution.act(foreignStarted, "complete", auth, body = badAnswers),
                    execution.act(missing, "complete", auth, ifMatch = "\"0\""),
                    execution.act(missing, "complete", auth, ifMatch = "\"0\"", body = badAnswers),
                ).map { it.problem(403, "RESOURCE_OUT_OF_SCOPE")["detail"].asString() }
            withClue(details) { details.toSet().size shouldBe 1 }
            execution.plans.task(foreignStarted)["status"].asString() shouldBe "IN_PROGRESS"
        }

    @Test
    fun `complete with ALL - a missing task is 404, and without the permission it is 403 before everything`(): Unit =
        with(iam) {
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            execution
                .act(UUID.randomUUID(), "complete", housekeeper, ifMatch = "\"0\"")
                .problem(404, "HOUSEKEEPING_TASK_NOT_FOUND")
            val nobody = asTestUser(createUser())
            val task = execution.assignedTask(execution.attendant().employee)
            listOf(
                execution.act(task, "complete", nobody),
                execution.act(
                    task,
                    "complete",
                    nobody,
                    ifMatch = "\"999\"",
                    body =
                        mapOf("answers" to listOf(check("bed"))),
                ),
                execution.act(UUID.randomUUID(), "complete", nobody, ifMatch = "\"0\""),
            ).forEach { it.problem(403, "PERMISSION_DENIED") }
            // Sem o If-Match, o 428 vem antes da autorização (exigência do cabeçalho, sem tocar no recurso).
            execution.act(task, "complete", nobody, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
        }

    @Test
    fun `complete - without If-Match is 428, a stale one is 412, and nothing changes`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = execution.assignedTask(attendant.employee)
            execution.acted(task, "start", auth)
            execution.act(task, "complete", auth, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
            execution.act(task, "complete", auth, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"
        }

    @Test
    fun `the housekeeper completing the attendant's task is recorded as cleanedBy`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val chief = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
            val task = execution.assignedTask(attendant.employee)
            execution.acted(task, "start", asTestUser(attendant.user))

            val done = execution.act(task, "complete", asTestUser(chief), body = emptyMap<String, Any>()).ok()
            done["status"].asString() shouldBe "DONE"
            done["cleanedBy"].asString() shouldBe chief.id.toString()
        }

    @Test
    fun `a photo sent by the housekeeper is CHECKLIST_PHOTO_INVALID when she completes`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val chief = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
            val room = housekeeping.createRoom()
            val task = taskWithChecklist(attendant, room, photoRequired = true)
            execution.acted(task, "start", asTestUser(attendant.user))
            val photo = executions.uploadPhoto(asTestUser(chief))

            val answers = listOf(check("bed"), mapOf("itemKey" to "photo", "photoFileId" to photo))
            execution
                .act(task, "complete", asTestUser(chief), body = mapOf("answers" to answers))
                .problem(422, "CHECKLIST_PHOTO_INVALID")
            execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"

            // O controle positivo: a foto da camareira que iniciou serve.
            val own = executions.uploadPhoto(asTestUser(attendant.user))
            val ownAnswers = listOf(check("bed"), mapOf("itemKey" to "photo", "photoFileId" to own))
            execution
                .act(task, "complete", asTestUser(attendant.user), body = mapOf("answers" to ownAnswers))
                .ok()["status"]
                .asString() shouldBe "DONE"
        }

    // --- entradas do complete --------------------------------------------------------------------------

    @Test
    fun `foundOccupancy on an area task is 400 at foundOccupancy, and foundGuestCount alone is REQUIRED`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val type = housekeeping.createCleaningType()
            val area = housekeeping.rooms.createArea()
            val task =
                UUID.fromString(
                    housekeeping.requestTask(housekeeping.taskBody(type, areaId = area))["id"].asString(),
                )
            execution.assign(task, attendant.employee)
            execution.acted(task, "start", auth)

            val areaProblem =
                execution
                    .act(task, "complete", auth, body = mapOf("foundOccupancy" to "VACANT"))
                    .problem(400, "VALIDATION_FAILED")
            areaProblem["errors"].values().map { it["field"].asString() } shouldBe listOf("foundOccupancy")

            val roomTask = execution.assignedTask(attendant.employee)
            execution.acted(roomTask, "start", auth)
            execution
                .act(roomTask, "complete", auth, body = mapOf("foundGuestCount" to 2))
                .problem(400, "VALIDATION_FAILED")["errors"]
                .values()
                .map { it["field"].asString() to it["code"].asString() } shouldBe listOf("foundOccupancy" to "REQUIRED")
            execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"
            execution.plans.task(roomTask)["status"].asString() shouldBe "IN_PROGRESS"
        }

    @Test
    fun `an OCCUPIED observation records the guest count up to 40, and 0 and 41 are 400`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val limits = listOf(0 to false, 41 to false, 1 to true, 40 to true)
            limits.forEach { (count, ok) ->
                val room = RoomId(housekeeping.createRoom())
                val task = execution.assignedTask(attendant.employee).also { assignRoom(it, room) }
                execution.acted(task, "start", auth)
                val response =
                    execution.act(
                        task,
                        "complete",
                        auth,
                        body = mapOf("foundOccupancy" to "OCCUPIED", "foundGuestCount" to count),
                    )
                if (ok) {
                    response.ok()["status"].asString() shouldBe "DONE"
                    observedCount(room) shouldBe count
                } else {
                    response.problem(400, "GUEST_COUNT_INVALID")
                    execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"
                }
            }
        }

    @Test
    fun `rooms refusing the guest count after the checklist and the room rolls everything back`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val room = RoomId(housekeeping.createRoom())
            val task = taskWithChecklist(attendant, room.value, photoRequired = false, requiresInspection = true)
            execution.acted(task, "start", auth)
            val checklist = checklistOf(task).shouldNotBeNull()
            val taskBefore = taskRow(task)
            val roomBefore = roomsApi.status(room).shouldNotBeNull()
            roomBefore.housekeepingStatus shouldBe HousekeepingStatus.DIRTY

            // VACANT com número de hóspedes: o rooms recusa depois do checklist e da UH.
            execution
                .act(
                    task,
                    "complete",
                    auth,
                    body =
                        mapOf(
                            "answers" to listOf(check("bed")),
                            "foundOccupancy" to "VACANT",
                            "foundGuestCount" to 2,
                        ),
                ).problem(400, "GUEST_COUNT_INVALID")

            taskRow(task) shouldBe taskBefore
            checklistsApi.execution(checklist).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.IN_PROGRESS
            roomsApi.status(room) shouldBe roomBefore
            observations(room) shouldBe 0
        }

    @Test
    fun `answers with a null element is 400, not 500, with and without a checklist`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val withList = taskWithChecklist(attendant, housekeeping.createRoom(), photoRequired = false)
            val without = execution.assignedTask(attendant.employee)
            listOf(withList, without).forEach { task ->
                execution.acted(task, "start", auth)
                val status = execution.act(task, "complete", auth, body = mapOf("answers" to listOf(null))).status
                withClue("tarefa $task") { status shouldBe 400 }
                execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"
            }
        }

    // --- trilha ----------------------------------------------------------------------------------------

    @Test
    fun `the 422 leaves no successful complete trail, and completed-elsewhere is recorded`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = taskWithChecklist(attendant, housekeeping.createRoom(), photoRequired = false)
            execution.acted(task, "start", auth)

            execution
                .act(task, "complete", auth, body = mapOf("answers" to emptyList<Any>()))
                .problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")
            succeededCompletes(task) shouldBe 0

            val checklist = checklistOf(task).shouldNotBeNull()
            checklistsApi.completeExecution(
                checklist,
                listOf(
                    com.zeny.hotel.checklists.ChecklistAnswerInput(
                        "bed",
                        check = com.zeny.hotel.checklists.CheckAnswer.OK,
                    ),
                ),
                Instant.now(),
            )
            execution.act(task, "complete", auth).ok()["status"].asString() shouldBe "DONE"
            succeededCompletes(task) shouldBe 1
            jdbc
                .sql(
                    "SELECT after_state ->> 'checklistCompletedElsewhere' FROM audit.audit_entry " +
                        "WHERE entity_type = 'housekeeping.task' AND entity_id = :id " +
                        "AND action = 'housekeeping.task.complete' AND outcome = 'SUCCEEDED'",
                ).param("id", task.toString())
                .query(String::class.java)
                .single() shouldBe "true"
        }

    @Test
    fun `an empty applicability rule on a HOUSEKEEPING template is refused at creation`(): Unit =
        with(iam) {
            val response =
                call(
                    POST,
                    "/api/v1/checklist-templates",
                    asAdmin(),
                    templates.templateBody(applicability = listOf(emptyMap())),
                )
            withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 400 }
        }

    // --- apoio ---------------------------------------------------------------------------------------

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun taskWithChecklist(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        room: UUID,
        photoRequired: Boolean,
        requiresInspection: Boolean = false,
    ): UUID {
        val items =
            buildList {
                add(templates.item("bed", extra = mapOf("required" to true)))
                if (photoRequired) {
                    add(templates.item("photo", type = ChecklistItemType.PHOTO, extra = mapOf("required" to true)))
                }
            }
        val template =
            templates.create(
                templates.templateBody(
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(UuidV7.generate()))),
                    sections = listOf(templates.section("Quarto", *items.toTypedArray())),
                ),
            )
        templates.publish(template)
        val type =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(requiresInspection = requiresInspection, checklistTemplateId = template),
            )
        val task =
            UUID.fromString(
                housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))["id"].asString(),
            )
        execution.assign(task, attendant.employee)
        return task
    }

    private fun assignRoom(
        task: UUID,
        room: RoomId,
    ) {
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET room_id = :room WHERE id = :id")
            .param("room", room.value)
            .param("id", task)
            .update() shouldBe 1
    }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun checklistOf(task: UUID): ChecklistExecutionId? =
        (taskRow(task)["checklist_execution_id"] as UUID?)?.let(::ChecklistExecutionId)

    private fun observations(room: RoomId): Int =
        jdbc
            .sql("SELECT count(*) FROM rooms.housekeeping_observation WHERE room_id = :id")
            .param("id", room.value)
            .query(Int::class.java)
            .single()

    private fun observedCount(room: RoomId): Int =
        jdbc
            .sql("SELECT guest_count FROM rooms.housekeeping_observation WHERE room_id = :id")
            .param("id", room.value)
            .query(Int::class.java)
            .single()

    private fun succeededCompletes(task: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE entity_type = 'housekeeping.task' AND entity_id = :id " +
                    "AND action = 'housekeeping.task.complete' AND outcome = 'SUCCEEDED'",
            ).param("id", task.toString())
            .query(Int::class.java)
            .single()
}
