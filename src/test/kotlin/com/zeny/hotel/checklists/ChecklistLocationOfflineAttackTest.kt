package com.zeny.hotel.checklists

import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * O ataque ao local da execução (M6 E08, D1, lacunas das revisões): o comando offline de início avulso com sujeito
 * `rooms.room` termina sem local; a cópia registrada à parte de uma rotina de UH herda o local; um
 * `ChecklistCompleted` gravado antes do campo existir (sem `location`) volta do outbox com `location = null`.
 */
@IntegrationTest
@Import(ChecklistExecutionsTestConfiguration::class)
@Suppress("LongParameterList") // os beans de que o teste precisa, injetados pelo contexto
class ChecklistLocationOfflineAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val generation: com.zeny.hotel.checklists.application.ChecklistRoutineGeneration,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)
    private val staff = StaffTestSupport(iam)

    @BeforeEach
    fun clearEvents() {
        ChecklistCompletedEvents.received.clear()
        InventoryListenersQuiescence.await(jdbc)
    }

    @Test
    fun `the offline start of a standalone execution with a room subject ends without a location`() {
        val (_, versionId) = support.publishedTemplate()
        val auth = support.auth(support.executor())
        val start =
            SyncCommand(
                UuidV7.generate(),
                ChecklistsOffline.START.value,
                mapOf("versionId" to versionId, "subjectType" to "rooms.room", "subjectId" to UuidV7.generate()),
                Instant.now(),
                Instant.now(),
            )
        val complete =
            SyncCommand(
                UuidV7.generate(),
                ChecklistsOffline.COMPLETE.value,
                mapOf("executionId" to start.id, "answers" to listOf(check("bed"), check("shower"))),
                Instant.now(),
                Instant.now(),
            )

        val results = support.sync(auth, start, complete)

        await().atMost(TIMEOUT).until { ChecklistCompletedEvents.received.any { it.executionId == start.id } }
        val event = ChecklistCompletedEvents.received.single { it.executionId == start.id }
        val row = support.executionRow(start.id)
        assertSoftly {
            results.map { it["status"].asString() } shouldBe listOf("APPLIED", "APPLIED")
            event.subjectType shouldBe "rooms.room"
            event.location.shouldBeNull()
            row["location_room_id"].shouldBeNull()
            row["location_area_id"].shouldBeNull()
        }
    }

    @Test
    @Suppress("LongMethod") // a rotina, a escala, o início do primeiro, a cópia offline e o evento
    fun `an offline copy of a room routine registered apart keeps the location of the routine`() {
        val day = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)
        val (templateId, versionId) = support.publishedTemplate()
        val department = staff.createDepartment()
        val morning = staff.createShift(department, LocalTime.of(6, 0), LocalTime.of(14, 0))
        val first = support.executor()
        val second = support.executor()
        listOf(first, second).forEach {
            staff.record(
                staff.createEmployee(department, userId = it.id.value),
                ScheduleEntryKind.SHIFT,
                day,
                shiftId = morning,
            )
        }
        val room = UuidV7.generate()
        val body =
            mapOf(
                "name" to "Rotina ${UuidV7.generate().toString().takeLast(SUFFIX)}",
                "templateId" to templateId,
                "departmentId" to department,
                "frequency" to "DAILY",
                "weekdays" to emptyList<String>(),
                "dueAt" to "09:00",
                "location" to mapOf("type" to "rooms.room", "id" to room),
            )
        val routine =
            with(iam) { UUID.fromString(call(POST, ROUTINES, asAdmin(), body).expectStatus(201)!!["id"].asString()) }
        (generation.generate(day) >= 1) shouldBe true
        val original =
            jdbc
                .sql("SELECT id FROM checklists.checklist_execution WHERE routine_id = :r AND business_date = :d")
                .param("r", routine)
                .param("d", day)
                .query(UUID::class.java)
                .single()
        with(iam) { call(POST, "$EXECUTIONS/$original/start", asTestUser(first)).ok() }

        val start =
            SyncCommand(
                UuidV7.generate(),
                ChecklistsOffline.START.value,
                mapOf("versionId" to versionId, "executionId" to original),
                Instant.now(),
                Instant.now(),
            )
        val complete =
            SyncCommand(
                UuidV7.generate(),
                ChecklistsOffline.COMPLETE.value,
                mapOf("executionId" to start.id, "answers" to listOf(check("bed"), check("shower"))),
                Instant.now(),
                Instant.now(),
            )
        support.sync(iam.asTestUser(second), start, complete)

        await().atMost(TIMEOUT).until { ChecklistCompletedEvents.received.any { it.executionId == start.id } }
        val event = ChecklistCompletedEvents.received.single { it.executionId == start.id }
        val copy = support.executionRow(start.id)
        assertSoftly {
            copy["duplicate_of"] shouldBe original
            copy["location_room_id"] shouldBe room
            copy["location_area_id"].shouldBeNull()
            event.duplicateOf shouldBe original
            event.location shouldBe ChecklistLocation.room(room)
        }
    }

    @Test
    fun `a ChecklistCompleted stored before the location existed is read back with a null location`() {
        val event =
            ChecklistCompleted(
                executionId = UuidV7.generate(),
                templateId = UuidV7.generate(),
                versionId = UuidV7.generate(),
                versionNumber = 1,
                context = ChecklistContext.HOUSEKEEPING,
                subjectType = "rooms.room",
                subjectId = UuidV7.generate(),
                routineId = null,
                executorId = UuidV7.generate(),
                startedAt = Instant.parse("2026-11-01T10:00:00Z"),
                completedAt = Instant.parse("2026-11-01T10:30:00Z"),
                actions = emptyList(),
                location = ChecklistLocation.room(UuidV7.generate()),
            )
        val full = jsonMapper.writeValueAsString(event)
        val old =
            (jsonMapper.readTree(full) as ObjectNode)
                .also { it.remove("location") }
                .let { jsonMapper.writeValueAsString(it) }

        val read = jsonMapper.readValue(old, ChecklistCompleted::class.java)

        assertSoftly {
            full.contains("\"location\"") shouldBe true
            old.contains("location") shouldBe false
            read.location.shouldBeNull()
            read.executionId shouldBe event.executionId
            read.subjectId shouldBe event.subjectId
        }
    }

    private fun check(key: String) = mapOf("itemKey" to key, "check" to "OK")

    private companion object {
        const val ROUTINES = "/api/v1/checklist-routines"
        const val EXECUTIONS = "/api/v1/checklist-executions"
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val SUFFIX = 8
        const val DAYS_AHEAD = 41L
    }
}
