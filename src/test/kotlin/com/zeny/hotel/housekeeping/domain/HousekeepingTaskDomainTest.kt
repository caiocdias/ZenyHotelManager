package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A tarefa de governança no domínio (M6 E02, Task 1): nasce `PENDING` com a prioridade padrão do tipo, os créditos do
 * tipo para o tipo da UH (D2), o local exatamente um, as `notes` aparadas e limitadas, e a faxina programada só na
 * origem `SCHEDULED`.
 */
class HousekeepingTaskDomainTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val date = LocalDate.of(2026, 11, 18)
    private val roomType = RoomTypeId(UUID.randomUUID())
    private val type =
        CleaningType.create(
            CleaningTypeDetails("X", "Limpeza", false, 4, null, null, listOf(CleaningCredit(roomType, 6))),
            now,
        )

    @Test
    fun `a new task is PENDING with the type priority, the given credits and the location`() {
        val room = RoomId(UUID.randomUUID())
        val user = UserId(UUID.randomUUID())

        val task =
            HousekeepingTask.create(
                type,
                TaskLocation.Room(room),
                date,
                TaskSource.AD_HOC,
                arrivalExpected = true,
                credits = 6,
                now = now,
                notes = "  Repor toalhas ",
                createdBy = user,
            )

        task.status shouldBe TaskStatus.PENDING
        task.priority shouldBe 4
        task.credits shouldBe 6
        task.roomId shouldBe room
        task.areaId shouldBe null
        task.location shouldBe TaskLocation.Room(room)
        task.businessDate shouldBe date
        task.cleaningTypeId shouldBe type.id
        task.arrivalExpected shouldBe true
        task.notes shouldBe "Repor toalhas"
        task.createdBy shouldBe user
        task.createdAt shouldBe now
        task.updatedAt shouldBe now
    }

    @Test
    fun `the credits are the type ones for the room type, and 0 for an area or a room type without credit`() {
        HousekeepingTask.creditsFor(type, roomType) shouldBe 6
        HousekeepingTask.creditsFor(type, RoomTypeId(UUID.randomUUID())) shouldBe 0
        HousekeepingTask.creditsFor(type, null) shouldBe 0
    }

    @Test
    fun `the location is exactly one of room or area`() {
        val room = RoomId(UUID.randomUUID())
        val area = AreaId(UUID.randomUUID())

        TaskLocation.of(room, null) shouldBe TaskLocation.Room(room)
        TaskLocation.of(null, area) shouldBe TaskLocation.Area(area)
        listOf(room to area, null to null).forEach { (r, a) ->
            withClue("$r $a") {
                shouldThrow<InvalidInputException> { TaskLocation.of(r, a) }.code.value shouldBe
                    "HOUSEKEEPING_TASK_LOCATION_INVALID"
            }
        }
    }

    @Test
    fun `the notes are trimmed, blank is null, and over 500 characters or with NUL are 400`() {
        HousekeepingTask.notes(null) shouldBe null
        HousekeepingTask.notes("   ") shouldBe null
        HousekeepingTask.notes(" a ") shouldBe "a"
        HousekeepingTask.notes("é".repeat(500)) shouldBe "é".repeat(500)
        // 500 caracteres fora do plano básico (1000 unidades UTF-16) cabem no varchar(500).
        HousekeepingTask.notes("😀".repeat(500))!!.length shouldBe 1000
        listOf("a".repeat(501), "a\u0000b").forEach { notes ->
            withClue(notes.length) {
                val error = shouldThrow<InvalidInputException> { HousekeepingTask.notes(notes) }
                error.code.value shouldBe "HOUSEKEEPING_DATA_INVALID"
                error.violations.map { it.field } shouldBe listOf("notes")
            }
        }
    }

    @Test
    fun `only a SCHEDULED task carries the scheduled cleaning, and always does`() {
        val location = TaskLocation.Area(AreaId(UUID.randomUUID()))
        val schedule = ScheduledCleaningId(UUID.randomUUID())

        HousekeepingTask
            .create(type, location, date, TaskSource.SCHEDULED, false, 0, now, schedule)
            .scheduledCleaningId shouldBe schedule
        shouldThrow<IllegalArgumentException> {
            HousekeepingTask.create(type, location, date, TaskSource.SCHEDULED, false, 0, now)
        }
        shouldThrow<IllegalArgumentException> {
            HousekeepingTask.create(type, location, date, TaskSource.AD_HOC, false, 0, now, schedule)
        }
    }
}
