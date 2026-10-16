package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport.Companion.EXECUTIONS
import com.zeny.hotel.checklists.application.ChecklistRoutineGeneration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Rotinas de checklist (M2 E07, RF-CHK-08, H14, D1): o cadastro, a geração pelo listener de
 * `BusinessDateAdvanced` (uma execução pendente por rotina e data, com os candidatos escalados no setor num turno
 * que cobre o horário; nunca quem está de folga), a reentrega do evento sem duplicar, o primeiro candidato que
 * assume, o escopo `OWN` e a execução offline de uma rotina já assumida (registrada à parte, RN-22).
 */
@IntegrationTest
@Import(ChecklistExecutionsTestConfiguration::class)
@Suppress("LongParameterList") // os beans de que o teste precisa, injetados pelo contexto
class ChecklistRoutinesTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val generation: ChecklistRoutineGeneration,
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)
    private val staff = StaffTestSupport(iam)

    @Test
    @Suppress("LongMethod") // o cenário da escala inteiro: turnos, folga, outro setor, sem usuário e a véspera
    fun `after BusinessDateAdvanced the routines of the day go to the scheduled staff only, once`() {
        val current = hotel.currentBusinessDate()
        val day = current.plusDays(1)
        val (templateId, _) = support.publishedTemplate()
        val department = staff.createDepartment()
        val otherDepartment = staff.createDepartment()
        val morning = staff.createShift(department, LocalTime.of(6, 0), LocalTime.of(14, 0))
        val afternoon = staff.createShift(department, LocalTime.of(14, 0), LocalTime.of(22, 0))
        val night = staff.createShift(department, LocalTime.of(22, 0), LocalTime.of(6, 0))
        val otherMorning = staff.createShift(otherDepartment, LocalTime.of(6, 0), LocalTime.of(14, 0))

        val onShift = scheduled(department, morning, day)
        val dayOff = support.executor()
        val dayOffEmployee = staff.createEmployee(department, userId = dayOff.id.value)
        staff.createPattern(dayOffEmployee, morning, SchedulePatternKind.FIXED, day, day, DayOfWeekNames.ALL)
        staff.generate(day, day, employeeId = dayOffEmployee)
        staff.record(dayOffEmployee, ScheduleEntryKind.DAY_OFF, day)
        val lateShift = scheduled(department, afternoon, day)
        val nightBefore = scheduled(department, night, current)
        val otherDept = scheduled(otherDepartment, otherMorning, day)
        val noUser = staff.createEmployee(department)
        staff.record(noUser, ScheduleEntryKind.SHIFT, day, shiftId = morning)

        val breakfast = createRoutine(templateId, department, "08:00")
        val overnight = createRoutine(templateId, department, "03:00", "WEEKLY", listOf(day.dayOfWeek.name))
        val otherDay = createRoutine(templateId, department, "08:00", "WEEKLY", listOf(day.plusDays(1).dayOfWeek.name))
        val inactive = createRoutine(templateId, department, "08:00")
        with(iam) { call(POST, "$ROUTINES/$inactive/deactivate", asAdmin()).ok() }

        val event = hotel.advanceBusinessDate(current)
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { executionsOf(breakfast, day).isNotEmpty() }

        val generated = executionsOf(breakfast, day).single()
        assertSoftly {
            generated["status"] shouldBe "PENDING"
            generated["executor_id"] shouldBe null
            candidatesOf(generated) shouldContainExactlyInAnyOrder listOf(onShift.id.value)
            candidatesOf(executionsOf(overnight, day).single()) shouldContainExactlyInAnyOrder
                listOf(nightBefore.id.value)
            (generated["due_at"] as Timestamp).toInstant() shouldBe
                day.atTime(LocalTime.of(8, 0)).atZone(hotel.timezone()).toInstant()
            executionsOf(otherDay, day) shouldBe emptyList()
            executionsOf(inactive, day) shouldBe emptyList()
        }
        listOf(dayOff, lateShift, otherDept).forEach { candidatesOf(generated).contains(it.id.value) shouldBe false }

        // O mesmo evento entregue de novo (o outbox reentrega) e a geração repetida não duplicam nada.
        transactions.executeWithoutResult { events.publishEvent(event) }
        generation.generate(day) shouldBe 0
        Thread.sleep(SETTLE_MILLIS)
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { redeliveriesDone(event) }
        executionsOf(breakfast, day).size shouldBe 1
        executionsOf(overnight, day).size shouldBe 1

        // Com OWN, o candidato vê a rotina; quem não é candidato, não.
        with(iam) {
            val id = generated["id"].toString()
            listed(asTestUser(onShift)).contains(id) shouldBe true
            listed(asTestUser(lateShift)).contains(id) shouldBe false
            call(GET, "$EXECUTIONS/$id", asTestUser(lateShift)).problem(404, "CHECKLIST_EXECUTION_NOT_FOUND")
            call(POST, "$EXECUTIONS/$id/start", asTestUser(lateShift)).problem(403, "RESOURCE_OUT_OF_SCOPE")
            val started = call(POST, "$EXECUTIONS/$id/start", asTestUser(onShift)).ok()
            started["execution"]["status"].asString() shouldBe "IN_PROGRESS"
            started["execution"]["executorId"].asString() shouldBe onShift.id.value.toString()
            call(POST, "$EXECUTIONS/$id/start", asTestUser(onShift)).problem(409, "CHECKLIST_EXECUTION_ALREADY_STARTED")
        }
    }

    @Test
    fun `an offline start of a routine someone else took is recorded apart and completed, never discarded`() {
        val day = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)
        val (templateId, versionId) = support.publishedTemplate()
        val department = staff.createDepartment()
        val morning = staff.createShift(department, LocalTime.of(6, 0), LocalTime.of(14, 0))
        val first = scheduled(department, morning, day)
        val second = scheduled(department, morning, day)
        val routine = createRoutine(templateId, department, "09:00")
        (generation.generate(day) >= 1) shouldBe true
        generation.generate(day) shouldBe 0
        val pending = executionsOf(routine, day).single()
        val id = pending["id"] as UUID
        candidatesOf(pending) shouldContainExactlyInAnyOrder listOf(first.id.value, second.id.value)

        with(iam) { call(POST, "$EXECUTIONS/$id/start", asTestUser(first)).ok() }
        val start = startCommand(versionId, id)
        val results =
            support.sync(
                iam.asTestUser(second),
                start,
                SyncCommand(
                    UuidV7.generate(),
                    ChecklistsOffline.COMPLETE.value,
                    mapOf(
                        "executionId" to id,
                        "answers" to listOf(check("bed"), check("shower")),
                    ),
                    Instant.now(),
                    Instant.now(),
                ),
            )

        results[0]["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        results[0]["warnings"][0]["code"].asString() shouldBe "CHECKLIST_ROUTINE_ALREADY_TAKEN"
        results[1]["status"].asString() shouldBe "APPLIED"
        val duplicate = support.executionRow(start.id)
        assertSoftly {
            duplicate["duplicate_of"] shouldBe id
            duplicate["executor_id"] shouldBe second.id.value
            duplicate["status"] shouldBe "COMPLETED"
            duplicate["routine_id"] shouldBe routine
            support.executionRow(id)["status"] shouldBe "IN_PROGRESS"
            support.executionRow(id)["executor_id"] shouldBe first.id.value
        }
        with(iam) {
            call(POST, "$EXECUTIONS/$id/start", asTestUser(second)).problem(409, "CHECKLIST_EXECUTION_ALREADY_STARTED")
        }
        // A registrada à parte publica o próprio evento, com a rotina e a original para o consumidor decidir.
        await().atMost(Duration.ofSeconds(TIMEOUT)).until {
            ChecklistCompletedEvents.received.any { it.executionId == start.id }
        }
        val event = ChecklistCompletedEvents.received.single { it.executionId == start.id }
        event.duplicateOf shouldBe id
        event.routineId shouldBe routine
    }

    @Test
    fun `routines are registered with permission, validated and never deleted`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val department = staff.createDepartment()
            val body = routineBody(templateId, department, "06:00")
            val executor = asTestUser(support.executor())
            call(POST, ROUTINES, executor, body).problem(403, "PERMISSION_DENIED")

            call(POST, ROUTINES, asAdmin(), body + ("weekdays" to listOf("MONDAY")))
                .problem(400, "CHECKLIST_ROUTINE_INVALID")["errors"][0]["field"]
                .asString() shouldBe "weekdays"
            call(POST, ROUTINES, asAdmin(), body + ("frequency" to "WEEKLY")).problem(400, "CHECKLIST_ROUTINE_INVALID")
            call(POST, ROUTINES, asAdmin(), body + ("dueAt" to "06:00:30")).problem(400, "CHECKLIST_ROUTINE_INVALID")
            call(POST, ROUTINES, asAdmin(), body + ("departmentId" to UuidV7.generate()))
                .problem(400, "UNKNOWN_DEPARTMENT")
            call(POST, ROUTINES, asAdmin(), body + ("templateId" to UuidV7.generate()))
                .problem(400, "UNKNOWN_CHECKLIST_TEMPLATE")

            val created = call(POST, ROUTINES, asAdmin(), body).expectStatus(201)!!
            val id = created["id"].asString()
            created["dueAt"].asString() shouldBe "06:00"
            call(PUT, "$ROUTINES/$id", asAdmin(), body + ("dueAt" to "07:30")).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "$ROUTINES/$id", asAdmin(), body + ("dueAt" to "07:30"), "\"0\"")
                .ok()["dueAt"]
                .asString() shouldBe "07:30"
            call(POST, "$ROUTINES/$id/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(GET, "$ROUTINES/$id", asAdmin()).ok()["version"].asLong() shouldBe 2
            call(GET, "$ROUTINES/${UuidV7.generate()}", asAdmin()).problem(404, "CHECKLIST_ROUTINE_NOT_FOUND")
            runCatching {
                jdbc
                    .sql(
                        "DELETE FROM checklists.checklist_routine WHERE id = CAST(:id AS uuid)",
                    ).param("id", id)
                    .update()
            }.isFailure shouldBe true
        }

    /** Um usuário executor (`OWN`) com colaborador no setor, escalado no turno na data. */
    private fun scheduled(
        department: UUID,
        shift: UUID,
        date: LocalDate,
    ): TestUser {
        val user = support.executor()
        val employee = staff.createEmployee(department, userId = user.id.value)
        staff.record(employee, ScheduleEntryKind.SHIFT, date, shiftId = shift)
        return user
    }

    private fun routineBody(
        templateId: UUID,
        department: UUID,
        dueAt: String,
        frequency: String = "DAILY",
        weekdays: List<String> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "name" to "Rotina ${UuidV7.generate().toString().takeLast(SUFFIX)}",
            "templateId" to templateId,
            "departmentId" to department,
            "frequency" to frequency,
            "weekdays" to weekdays,
            "dueAt" to dueAt,
            "location" to mapOf("type" to "rooms.area", "id" to UuidV7.generate()),
        )

    private fun createRoutine(
        templateId: UUID,
        department: UUID,
        dueAt: String,
        frequency: String = "DAILY",
        weekdays: List<String> = emptyList(),
    ): UUID =
        with(iam) {
            UUID.fromString(
                call(POST, ROUTINES, asAdmin(), routineBody(templateId, department, dueAt, frequency, weekdays))
                    .expectStatus(201)!!["id"]
                    .asString(),
            )
        }

    private fun executionsOf(
        routine: UUID,
        date: LocalDate,
    ): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM checklists.checklist_execution WHERE routine_id = :routine AND business_date = :date")
            .param("routine", routine)
            .param("date", date)
            .query()
            .listOfRows()

    private fun candidatesOf(row: Map<String, Any?>): List<UUID> =
        ((row["candidate_ids"] as java.sql.Array).array as Array<*>).map { it as UUID }

    /** As publicações do evento para o listener das rotinas já concluídas (a original e a reentregue). */
    private fun redeliveriesDone(event: BusinessDateAdvanced): Boolean =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE event_type = :type AND completion_date IS NULL " +
                    "AND serialized_event LIKE :current AND listener_id LIKE '%ChecklistRoutineListener%'",
            ).param("type", BusinessDateAdvanced::class.java.name)
            .param("current", "%\"current\":\"${event.current}\"%")
            .query(Int::class.java)
            .single() == 0

    private fun listed(auth: RequestPostProcessor): List<String> =
        with(iam) {
            call(GET, "$EXECUTIONS?limit=200&status=PENDING", auth).ok()["items"].values().map { it["id"].asString() }
        }

    private fun startCommand(
        versionId: UUID,
        executionId: UUID,
    ) = SyncCommand(
        UuidV7.generate(),
        ChecklistsOffline.START.value,
        mapOf("versionId" to versionId, "executionId" to executionId),
        Instant.now(),
        Instant.now(),
    )

    private fun check(key: String) = mapOf("itemKey" to key, "check" to "OK")

    private object DayOfWeekNames {
        val ALL = DayOfWeek.entries.map { it.name }
    }

    private companion object {
        const val ROUTINES = "/api/v1/checklist-routines"
        const val TIMEOUT = 20L
        const val SETTLE_MILLIS = 500L
        const val SUFFIX = 8
        const val DAYS_AHEAD = 20L
    }
}
