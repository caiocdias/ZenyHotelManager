package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport.Companion.EXECUTIONS
import com.zeny.hotel.checklists.application.ChecklistRoutineGeneration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * O local da execução (M6 E08, D1): informado pelo dono do sujeito ao iniciar pela API pública, derivado do sujeito
 * `rooms.room`/`rooms.area` de uma rotina e ausente na execução avulsa iniciada pelo cliente.
 */
@IntegrationTest
@Import(ChecklistExecutionsTestConfiguration::class)
@Suppress("LongParameterList") // os beans de que o teste precisa, injetados pelo contexto
class ChecklistExecutionLocationTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val generation: ChecklistRoutineGeneration,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)
    private val staff = StaffTestSupport(iam)

    @BeforeEach
    fun clearEvents() = ChecklistCompletedEvents.received.clear()

    @Test
    fun `starting with a location stores it and publishes it in the view and in ChecklistCompleted`() {
        val (_, versionId) = support.publishedTemplate()
        val user = support.executor()
        val location = ChecklistLocation(roomId = UuidV7.generate(), areaId = null)
        val subject = ChecklistSubject("housekeeping.task", UuidV7.generate())

        val started =
            checklistsApi.startExecution(
                ChecklistVersionId(versionId),
                subject,
                user.id,
                Instant.now(),
                location = location,
            )

        started.location shouldBe location
        checklistsApi.execution(started.id)!!.location shouldBe location
        support.executionRow(started.id.value).let {
            it["location_room_id"] shouldBe location.roomId
            it["location_area_id"] shouldBe null
        }
        checklistsApi.completeExecution(started.id, requiredAnswers(), Instant.now())
        awaitEvent(started.id.value).location shouldBe location
    }

    @Test
    fun `a routine on a room or an area publishes the subject as the location`() {
        val day = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)
        val (templateId, _) = support.publishedTemplate()
        val department = staff.createDepartment()
        val morning = staff.createShift(department, LocalTime.of(6, 0), LocalTime.of(14, 0))
        val user = support.executor()
        staff.record(
            staff.createEmployee(department, userId = user.id.value),
            ScheduleEntryKind.SHIFT,
            day,
            shiftId = morning,
        )
        val room = UuidV7.generate()
        val area = UuidV7.generate()
        val roomRoutine = createRoutine(templateId, department, "rooms.room", room)
        val areaRoutine = createRoutine(templateId, department, "rooms.area", area)
        (generation.generate(day) >= 2) shouldBe true

        val auth = support.auth(user)
        val roomEvent = complete(auth, executionOf(roomRoutine, day))
        val areaEvent = complete(auth, executionOf(areaRoutine, day))

        roomEvent.location shouldBe ChecklistLocation(roomId = room, areaId = null)
        areaEvent.location shouldBe ChecklistLocation(roomId = null, areaId = area)
    }

    @Test
    fun `a standalone execution chosen by the client has no location even with a room subject`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val auth = support.auth(support.executor())
            val subject = mapOf("type" to "rooms.room", "id" to UuidV7.generate())
            val id = UUID.fromString(support.start(auth, templateId, subject)["execution"]["id"].asString())

            support.answer(auth, id, check("bed"), check("shower")).expectStatus(200)
            support.complete(auth, id).ok()

            awaitEvent(id).location.shouldBeNull()
            support.executionRow(id)["location_room_id"].shouldBeNull()
            support.executionRow(id)["location_area_id"].shouldBeNull()
        }

    @Test
    fun `a location has exactly one of the room and the area`() {
        shouldThrow<IllegalArgumentException> { ChecklistLocation(UuidV7.generate(), UuidV7.generate()) }
        shouldThrow<IllegalArgumentException> { ChecklistLocation(null, null) }
    }

    private fun requiredAnswers() =
        listOf(
            ChecklistAnswerInput(itemKey = "bed", check = CheckAnswer.OK),
            ChecklistAnswerInput(itemKey = "shower", check = CheckAnswer.OK),
        )

    private fun check(key: String) = mapOf("itemKey" to key, "check" to "OK")

    private fun createRoutine(
        templateId: UUID,
        department: UUID,
        type: String,
        id: UUID,
    ): UUID =
        with(iam) {
            val body =
                mapOf(
                    "name" to "Rotina ${UuidV7.generate().toString().takeLast(SUFFIX)}",
                    "templateId" to templateId,
                    "departmentId" to department,
                    "frequency" to "DAILY",
                    "weekdays" to emptyList<String>(),
                    "dueAt" to "09:00",
                    "location" to mapOf("type" to type, "id" to id),
                )
            UUID.fromString(call(POST, ROUTINES, asAdmin(), body).expectStatus(201)!!["id"].asString())
        }

    private fun executionOf(
        routine: UUID,
        day: java.time.LocalDate,
    ): UUID =
        jdbc
            .sql("SELECT id FROM checklists.checklist_execution WHERE routine_id = :r AND business_date = :d")
            .param("r", routine)
            .param("d", day)
            .query(UUID::class.java)
            .single()

    private fun complete(
        auth: org.springframework.test.web.servlet.request.RequestPostProcessor,
        id: UUID,
    ): ChecklistCompleted {
        with(iam) {
            call(POST, "$EXECUTIONS/$id/start", auth).ok()
            support.answer(auth, id, check("bed"), check("shower")).expectStatus(200)
            support.complete(auth, id).ok()
        }
        return awaitEvent(id)
    }

    private fun awaitEvent(id: UUID): ChecklistCompleted {
        await()
            .atMost(
                Duration.ofSeconds(TIMEOUT),
            ).until { ChecklistCompletedEvents.received.any { it.executionId == id } }
        return ChecklistCompletedEvents.received.single { it.executionId == id }
    }

    private companion object {
        const val ROUTINES = "/api/v1/checklist-routines"
        const val TIMEOUT = 20L
        const val SUFFIX = 8
        const val DAYS_AHEAD = 35L
    }
}
