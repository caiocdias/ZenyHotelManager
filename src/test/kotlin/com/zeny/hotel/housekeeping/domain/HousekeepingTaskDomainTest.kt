package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
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
 * origem `SCHEDULED`. Desde a M6 E04 (Task 1): a máquina de estados da SPEC §8.4 na execução (iniciar, pausar,
 * retomar, recusar, tentar de novo e concluir), a ordem dos instantes e o tempo trabalhado (D6, Review Focus 1 e 2).
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

    @Test
    fun `assign gives the task to the attendant at the position, and unassign takes it back to PENDING (M6 E03)`() {
        val task =
            HousekeepingTask.create(
                type,
                TaskLocation.Room(RoomId(UUID.randomUUID())),
                date,
                TaskSource.AD_HOC,
                false,
                6,
                now,
            )
        val ana = EmployeeId(UUID.randomUUID())
        val bia = EmployeeId(UUID.randomUUID())
        val later = now.plusSeconds(60)

        task.assign(ana, 2, later)
        task.status shouldBe TaskStatus.ASSIGNED
        task.assignedEmployeeId shouldBe ana
        task.assignmentPosition shouldBe 2
        task.updatedAt shouldBe later

        task.assign(bia, 1, later.plusSeconds(1))
        task.assignedEmployeeId shouldBe bia
        task.assignmentPosition shouldBe 1

        task.unassign(later.plusSeconds(2))
        task.status shouldBe TaskStatus.PENDING
        task.assignedEmployeeId shouldBe null
        task.assignmentPosition shouldBe null
        task.updatedAt shouldBe later.plusSeconds(2)
        shouldThrow<IllegalArgumentException> { task.assign(ana, 0, later) }
    }

    @Test
    fun `a task that already moved on is neither assigned nor unassigned (Review Focus 4)`() {
        val task =
            HousekeepingTask.create(
                type,
                TaskLocation.Area(AreaId(UUID.randomUUID())),
                date,
                TaskSource.AD_HOC,
                false,
                0,
                now,
            )
        task.assign(EmployeeId(UUID.randomUUID()), 1, now)
        HousekeepingTask::class.java
            .getDeclaredMethod("setStatus", TaskStatus::class.java)
            .apply { isAccessible = true }
            .invoke(task, TaskStatus.IN_PROGRESS)

        shouldThrow<IllegalStateException> { task.assign(EmployeeId(UUID.randomUUID()), 1, now) }
        shouldThrow<IllegalStateException> { task.unassign(now) }
        task.status shouldBe TaskStatus.IN_PROGRESS
    }

    // --- Execução (M6 E04, Task 1) --------------------------------------------------------------

    private fun assignedTask(at: Instant = now): HousekeepingTask =
        HousekeepingTask
            .create(type, TaskLocation.Room(RoomId(UUID.randomUUID())), date, TaskSource.AD_HOC, false, 6, now)
            .also { it.assign(EmployeeId(UUID.randomUUID()), 1, at) }

    /** A tarefa levada ao [status] pelas próprias transições, a partir de uma `ASSIGNED`. */
    private fun taskIn(status: TaskStatus): HousekeepingTask {
        val task = assignedTask()
        val t = now.plusSeconds(10)
        when (status) {
            TaskStatus.PENDING -> task.unassign(t)
            TaskStatus.ASSIGNED -> Unit
            TaskStatus.IN_PROGRESS -> task.start(t, date)
            TaskStatus.PAUSED -> task.start(t, date).also { task.pause(t) }
            TaskStatus.AWAITING_INSPECTION -> task.start(t, date).also { task.complete(cleaner, true, t) }
            TaskStatus.DONE -> task.start(t, date).also { task.complete(cleaner, false, t) }
            TaskStatus.REFUSED -> task.refuse(RefusalReason.GUEST_REFUSED, t)
            TaskStatus.REJECTED, TaskStatus.CANCELLED -> setStatus(task, status) // transições da E05 e do cancelamento
        }
        task.status shouldBe status
        return task
    }

    private fun setStatus(
        task: HousekeepingTask,
        status: TaskStatus,
    ) {
        HousekeepingTask::class.java
            .getDeclaredMethod("setStatus", TaskStatus::class.java)
            .apply { isAccessible = true }
            .invoke(task, status)
    }

    private val cleaner = UserId(UUID.randomUUID())

    /** Cada ação da execução e os status de onde ela parte (SPEC §8.4). */
    private val actions: Map<TaskAction, Pair<Set<TaskStatus>, (HousekeepingTask) -> Unit>> =
        mapOf(
            TaskAction.START to
                (setOf(TaskStatus.ASSIGNED, TaskStatus.REJECTED) to { t -> t.start(now.plusSeconds(60), date) }),
            TaskAction.PAUSE to (setOf(TaskStatus.IN_PROGRESS) to { t -> t.pause(now.plusSeconds(60)) }),
            TaskAction.RESUME to (setOf(TaskStatus.PAUSED) to { t -> t.resume(now.plusSeconds(60)) }),
            TaskAction.REFUSE to
                (setOf(TaskStatus.ASSIGNED) to { t -> t.refuse(RefusalReason.DO_NOT_DISTURB, now.plusSeconds(60)) }),
            TaskAction.RETRY to (setOf(TaskStatus.REFUSED) to { t -> t.retry(now.plusSeconds(60)) }),
            TaskAction.COMPLETE to
                (setOf(TaskStatus.IN_PROGRESS) to { t -> t.complete(cleaner, false, now.plusSeconds(60)) }),
        )

    @Test
    fun `each execution action is allowed only from its SPEC 8_4 statuses, otherwise 409 and nothing changes`() {
        actions.forEach { (action, rule) ->
            val (from, act) = rule
            TaskStatus.entries.forEach { status ->
                withClue("$action from $status") {
                    val task = taskIn(status)
                    if (status in from) {
                        act(task)
                    } else {
                        val updatedBefore = task.updatedAt
                        val error = shouldThrow<HousekeepingTaskTransitionNotAllowedException> { act(task) }
                        error.code.value shouldBe "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED"
                        error.problemProperties shouldBe mapOf("taskStatus" to status.name, "action" to action.name)
                        task.status shouldBe status
                        task.updatedAt shouldBe updatedBefore
                    }
                }
            }
        }
    }

    @Test
    fun `the valid transitions reach the SPEC 8_4 targets, including ASSIGNED to PENDING, REJECTED to IN_PROGRESS and REFUSED to ASSIGNED`() {
        taskIn(TaskStatus.ASSIGNED).apply { unassign(now.plusSeconds(60)) }.status shouldBe TaskStatus.PENDING
        taskIn(TaskStatus.ASSIGNED).apply { start(now.plusSeconds(60), date) }.status shouldBe TaskStatus.IN_PROGRESS
        taskIn(TaskStatus.REJECTED).apply { start(now.plusSeconds(60), date) }.status shouldBe TaskStatus.IN_PROGRESS
        taskIn(TaskStatus.IN_PROGRESS).apply { pause(now.plusSeconds(60)) }.status shouldBe TaskStatus.PAUSED
        taskIn(TaskStatus.PAUSED).apply { resume(now.plusSeconds(60)) }.status shouldBe TaskStatus.IN_PROGRESS
        taskIn(TaskStatus.IN_PROGRESS).apply { complete(cleaner, false, now.plusSeconds(60)) }.status shouldBe
            TaskStatus.DONE
        taskIn(TaskStatus.IN_PROGRESS).apply { complete(cleaner, true, now.plusSeconds(60)) }.status shouldBe
            TaskStatus.AWAITING_INSPECTION

        val refused = taskIn(TaskStatus.ASSIGNED).apply { refuse(RefusalReason.DO_NOT_DISTURB, now.plusSeconds(60)) }
        refused.status shouldBe TaskStatus.REFUSED
        refused.refusalReason shouldBe RefusalReason.DO_NOT_DISTURB
        val employee = refused.assignedEmployeeId
        refused.retry(now.plusSeconds(120))
        refused.status shouldBe TaskStatus.ASSIGNED
        refused.refusalReason shouldBe null
        refused.assignedEmployeeId shouldBe employee
    }

    @Test
    fun `start only takes a task up to the operational date (D11), and a future one is 422 without change`() {
        val task = assignedTask()
        val error = shouldThrow<BusinessRuleException> { task.start(now.plusSeconds(60), date.minusDays(1)) }
        error.code.value shouldBe "HOUSEKEEPING_TASK_NOT_DUE"
        task.status shouldBe TaskStatus.ASSIGNED
        task.startedAt shouldBe null

        // Uma tarefa aberta de data anterior segue válida.
        task.start(now.plusSeconds(60), date.plusDays(3))
        task.status shouldBe TaskStatus.IN_PROGRESS
    }

    @Test
    fun `the worked time sums only the IN_PROGRESS stretches, across several pauses (Review Focus 2)`() {
        val task = assignedTask()
        task.start(now.plusSeconds(100), date)
        task.startedAt shouldBe now.plusSeconds(100)
        task.inProgressSince shouldBe now.plusSeconds(100)
        task.pause(now.plusSeconds(160)) // 60 s
        task.inProgressSince shouldBe null
        task.workedSeconds shouldBe 60
        task.resume(now.plusSeconds(1000)) // pausada 840 s, não conta
        task.pause(now.plusSeconds(1030)) // +30 s
        task.resume(now.plusSeconds(2000))
        task.complete(cleaner, false, now.plusSeconds(2010)) // +10 s

        task.workedSeconds shouldBe 100
        task.startedAt shouldBe now.plusSeconds(100)
        task.finishedAt shouldBe now.plusSeconds(2010)
        task.cleanedBy shouldBe cleaner
        task.inProgressSince shouldBe null
        task.lastTransitionAt shouldBe now.plusSeconds(2010)
        task.updatedAt shouldBe now.plusSeconds(2010)
    }

    @Test
    fun `a pause right after the start is zero, never negative (Review Focus 2)`() {
        val task = assignedTask()
        task.start(now.plusSeconds(100), date)
        task.pause(now.plusSeconds(100))
        task.workedSeconds shouldBe 0
    }

    @Test
    fun `an instant before the last transition is recorded as the last transition, and the time never goes negative (Review Focus 1)`() {
        val task = assignedTask(now.plusSeconds(500))
        task.start(now.plusSeconds(100), date) // antes da atribuição (500)
        task.startedAt shouldBe now.plusSeconds(500)
        task.lastTransitionAt shouldBe now.plusSeconds(500)
        task.pause(now.plusSeconds(200)) // antes do início
        task.workedSeconds shouldBe 0
        task.lastTransitionAt shouldBe now.plusSeconds(500)
        task.resume(now.plusSeconds(800))
        task.complete(cleaner, false, now.plusSeconds(700)) // antes da retomada
        task.workedSeconds shouldBe 0
        task.finishedAt shouldBe now.plusSeconds(800)
        task.lastTransitionAt shouldBe now.plusSeconds(800)
    }

    @Test
    fun `redoing a rejected task keeps the first start and the worked time, and clears the previous completion`() {
        val task = assignedTask()
        task.start(now.plusSeconds(100), date)
        task.complete(cleaner, true, now.plusSeconds(160))
        setStatus(task, TaskStatus.REJECTED) // a reprovação é da E05

        task.start(now.plusSeconds(1000), date)
        task.startedAt shouldBe now.plusSeconds(100)
        task.finishedAt shouldBe null
        task.cleanedBy shouldBe null
        task.inProgressSince shouldBe now.plusSeconds(1000)
        task.complete(cleaner, false, now.plusSeconds(1040))
        task.workedSeconds shouldBe 100
        task.status shouldBe TaskStatus.DONE
    }
}
