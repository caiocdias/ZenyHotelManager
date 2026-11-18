package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.CleaningTypeStore
import com.zeny.hotel.housekeeping.application.HousekeepingTaskGeneration
import com.zeny.hotel.housekeeping.application.HousekeepingTaskStore
import com.zeny.hotel.housekeeping.application.TaskFilter
import com.zeny.hotel.housekeeping.application.TaskPageKey
import com.zeny.hotel.housekeeping.domain.CleaningCredit
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.CleaningTypeDetails
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A geração automática das tarefas com portas falsas (M6 E02, Task 2): o tipo da origem pela chave (D1-E02), inativo
 * sem tarefa nem exceção e com `noChanges`; os créditos do tipo para o tipo da UH (D2); a chegada prevista (D8) pelas
 * chegadas pendentes com UH alocada; e a entrega repetida, que não cria outra tarefa nem lança.
 */
class HousekeepingTaskGenerationTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val date = LocalDate.of(2026, 11, 18)
    private val roomType = RoomTypeId(UUID.randomUUID())
    private val room = RoomId(UUID.randomUUID())
    private val checkout =
        CleaningType.create(
            CleaningTypeDetails("SAIDA", "Saída", false, 4, null, null, listOf(CleaningCredit(roomType, 6))),
            now,
        )

    private val store = InMemoryTaskStore()
    private val cleaningTypes = mockk<CleaningTypeStore>()
    private val rooms = mockk<RoomsApi>()
    private val reservations = mockk<ReservationsApi>()
    private val hotel = mockk<HotelApi>()
    private val audit = mockk<AuditContext>(relaxed = true)
    private val generation =
        HousekeepingTaskGeneration(
            store,
            cleaningTypes,
            rooms,
            reservations,
            hotel,
            audit,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    init {
        every { cleaningTypes.byKey(CleaningTypeKey.CHECKOUT) } returns checkout
        every { rooms.room(room) } returns RoomSummary(room, "101", null, roomType, emptySet(), true)
        every { reservations.pendingArrivals(any()) } returns emptyList()
        every { reservations.assignedRooms(any()) } returns emptyMap()
        every { hotel.currentBusinessDate() } returns date
    }

    @Test
    fun `a check-out creates a PENDING CHECKOUT task with the type priority and credits, audited as created`() {
        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe true

        val task = store.saved.single()
        task.roomId shouldBe room
        task.businessDate shouldBe date
        task.cleaningTypeId shouldBe checkout.id
        task.source shouldBe TaskSource.CHECKOUT
        task.status shouldBe TaskStatus.PENDING
        task.priority shouldBe 4
        task.credits shouldBe 6
        task.arrivalExpected shouldBe false
        task.createdBy shouldBe null
        verify(exactly = 1) { audit.created("housekeeping.task", task.id.value, any()) }
        verify(exactly = 0) { audit.noChanges() }
    }

    @Test
    fun `a room move gives a ROOM_MOVE task, marked with the arrival when the room has an assigned arrival`() {
        val arriving = ReservationId(UUID.randomUUID())
        val elsewhere = ReservationId(UUID.randomUUID())
        every { reservations.pendingArrivals(date) } returns listOf(arriving, elsewhere)
        every { reservations.assignedRooms(listOf(arriving, elsewhere)) } returns
            mapOf(arriving to room, elsewhere to RoomId(UUID.randomUUID()))

        generation.generateCheckout(room, date, TaskSource.ROOM_MOVE) shouldBe true

        val task = store.saved.single()
        task.source shouldBe TaskSource.ROOM_MOVE
        task.arrivalExpected shouldBe true
    }

    @Test
    fun `the arrivals by room are the rooms assigned to the pending arrivals of the date`() {
        val first = ReservationId(UUID.randomUUID())
        val unassigned = ReservationId(UUID.randomUUID())
        val other = RoomId(UUID.randomUUID())
        every { reservations.pendingArrivals(date) } returns listOf(first, unassigned)
        every { reservations.assignedRooms(listOf(first, unassigned)) } returns mapOf(first to other)

        generation.arrivalsByRoom(date) shouldBe setOf(other)
    }

    @Test
    fun `on a future date only the arrivals exactly on it count, not the earlier ones still pending then`() {
        val future = date.plusDays(5)
        val earlier = ReservationId(UUID.randomUUID())
        val exact = ReservationId(UUID.randomUUID())
        val exactRoom = RoomId(UUID.randomUUID())
        every { reservations.pendingArrivals(future) } returns listOf(earlier, exact)
        every { reservations.pendingArrivals(future.minusDays(1)) } returns listOf(earlier)
        every { reservations.assignedRooms(listOf(exact)) } returns mapOf(exact to exactRoom)

        generation.arrivalsByRoom(future) shouldBe setOf(exactRoom)
        verify(exactly = 0) { reservations.assignedRooms(listOf(earlier, exact)) }
    }

    @Test
    fun `on the business date a late arrival still pending counts`() {
        val late = ReservationId(UUID.randomUUID())
        every { reservations.pendingArrivals(date) } returns listOf(late)
        every { reservations.assignedRooms(listOf(late)) } returns mapOf(late to room)

        generation.arrivalsByRoom(date) shouldBe setOf(room)
        verify(exactly = 0) { reservations.pendingArrivals(date.minusDays(1)) }
    }

    @Test
    fun `with the CHECKOUT type inactive there is no task, no exception and no audit entry`() {
        checkout.deactivate(now)

        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe false

        store.saved.shouldBeEmpty()
        verify(exactly = 1) { audit.noChanges() }
        verify(exactly = 0) { audit.created(any(), any(), any()) }
    }

    @Test
    fun `a repeated delivery does not create a second task nor throw, and records no change`() {
        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe true

        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe false

        store.saved shouldHaveSize 1
        verify(exactly = 1) { audit.created(any(), any(), any()) }
        verify(exactly = 1) { audit.noChanges() }
    }

    @Test
    fun `a room that no longer exists or was deactivated has no task and no exception`() {
        every { rooms.room(room) } returns null
        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe false

        every { rooms.room(room) } returns RoomSummary(room, "101", null, roomType, emptySet(), false)
        generation.generateCheckout(room, date, TaskSource.CHECKOUT) shouldBe false

        store.saved.shouldBeEmpty()
        verify(exactly = 2) { audit.noChanges() }
    }

    /** A porta das tarefas em memória, com a unicidade por (local, data, tipo) de D5-E02. */
    private class InMemoryTaskStore : HousekeepingTaskStore {
        val saved = mutableListOf<HousekeepingTask>()

        override fun insertIfAbsent(task: HousekeepingTask): Boolean {
            val taken =
                saved.any {
                    it.location == task.location &&
                        it.businessDate == task.businessDate &&
                        it.cleaningTypeId == task.cleaningTypeId
                }
            if (!taken) saved += task
            return !taken
        }

        override fun task(id: HousekeepingTaskId): HousekeepingTask? = saved.firstOrNull { it.id == id }

        override fun tasks(ids: List<UUID>): List<HousekeepingTask> = error("fora do teste")

        override fun page(
            date: LocalDate,
            filter: TaskFilter,
            after: TaskPageKey?,
            limit: Int,
        ): List<TaskPageKey> = error("fora do teste")

        override fun hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean = error("fora do teste")

        override fun lastTaskDate(
            roomId: RoomId,
            onOrBefore: LocalDate,
        ): LocalDate? = error("fora do teste")
    }
}
