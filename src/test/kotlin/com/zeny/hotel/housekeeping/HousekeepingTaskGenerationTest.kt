package com.zeny.hotel.housekeeping

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.ArrivalsByRoom
import com.zeny.hotel.housekeeping.application.CleaningTypeStore
import com.zeny.hotel.housekeeping.application.DailyGenerationResult
import com.zeny.hotel.housekeeping.application.HousekeepingProperties
import com.zeny.hotel.housekeeping.application.HousekeepingTaskGeneration
import com.zeny.hotel.housekeeping.application.HousekeepingTaskListeners
import com.zeny.hotel.housekeeping.application.HousekeepingTaskStore
import com.zeny.hotel.housekeeping.application.ScheduledCleaningStore
import com.zeny.hotel.housekeeping.application.TaskFilter
import com.zeny.hotel.housekeeping.application.TaskPageKey
import com.zeny.hotel.housekeeping.domain.CleaningCredit
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.CleaningTypeDetails
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.ScheduledCleaning
import com.zeny.hotel.housekeeping.domain.ScheduledCleaningInput
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A geração automática das tarefas com portas falsas (M6 E02, Tasks 2 e 3): o tipo da origem pela chave (D1-E02),
 * inativo sem tarefa nem exceção e com `noChanges`; os créditos do tipo para o tipo da UH (D2); a chegada prevista (D8)
 * pelas chegadas pendentes com UH alocada; a entrega repetida, que não cria outra tarefa nem lança; a prioridade de
 * chegada sobre a permanência com as prioridades editadas (Review Focus 4); e o avanço atrasado ignorado pelo listener
 * (D10-E02). Os tipos inativos e as prioridades editadas ficam aqui, e não na integração: os testes nunca alteram os
 * tipos da carga.
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
    private val scheduledCleanings = mockk<ScheduledCleaningStore>()
    private val cleaningTypes = mockk<CleaningTypeStore>()
    private val rooms = mockk<RoomsApi>()
    private val reservations = mockk<ReservationsApi>()
    private val hotel = mockk<HotelApi>()
    private val audit = mockk<AuditContext>(relaxed = true)
    private val arrivals = ArrivalsByRoom(reservations, hotel)
    private val generation =
        HousekeepingTaskGeneration(
            store,
            scheduledCleanings,
            cleaningTypes,
            rooms,
            reservations,
            arrivals,
            HousekeepingProperties(preArrivalAfterDays = 3),
            audit,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    init {
        every { cleaningTypes.byKey(CleaningTypeKey.CHECKOUT) } returns checkout
        every { rooms.room(room) } returns RoomSummary(room, "101", null, roomType, emptySet(), true)
        every { reservations.pendingArrivals(any()) } returns emptyList()
        every { reservations.assignedRooms(any()) } returns emptyMap()
        every { hotel.currentBusinessDate() } returns date
        every { scheduledCleanings.dueOn(any()) } returns emptyList()
        every { reservations.inHouse(any()) } returns emptyList()
        every { reservations.pendingDepartures(any()) } returns emptyList()
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

        arrivals.arrivalsByRoom(date) shouldBe setOf(other)
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

        arrivals.arrivalsByRoom(future) shouldBe setOf(exactRoom)
        verify(exactly = 0) { reservations.assignedRooms(listOf(earlier, exact)) }
    }

    @Test
    fun `on the business date a late arrival still pending counts`() {
        val late = ReservationId(UUID.randomUUID())
        every { reservations.pendingArrivals(date) } returns listOf(late)
        every { reservations.assignedRooms(listOf(late)) } returns mapOf(late to room)

        arrivals.arrivalsByRoom(date) shouldBe setOf(room)
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

    // --- Tarefas do dia (Task 3) ----------------------------------------------------------------------------------

    @Test
    fun `a room with an arrival comes before a stayover in the list order, even with the STAYOVER at priority 5`() {
        val day = DailyScenario()
        day.types(stayoverPriority = 5, preArrivalPriority = 1)

        val result = generation.generateForDate(date)

        result.stayovers shouldBe 1
        result.preArrivals shouldBe 1
        val stayover = store.saved.single { it.source == TaskSource.STAYOVER }
        val preArrival = store.saved.single { it.source == TaskSource.PRE_ARRIVAL }
        stayover.roomId shouldBe day.staying
        stayover.priority shouldBe 5
        stayover.arrivalExpected shouldBe false
        preArrival.roomId shouldBe day.arriving
        preArrival.priority shouldBe 1
        preArrival.arrivalExpected shouldBe true
        // A ordem da lista (Review Focus 4): chegada prevista desc, prioridade desc, id.
        store.saved.sortedWith(LIST_ORDER).map { it.source } shouldBe
            listOf(TaskSource.PRE_ARRIVAL, TaskSource.STAYOVER)
        verify(exactly = 2) { audit.created("housekeeping.task", any(), any()) }
    }

    @Test
    fun `with the STAYOVER type inactive there is no stayover, and the pre-arrival is still generated`() {
        val day = DailyScenario()
        day.types(stayoverActive = false)

        val result = generation.generateForDate(date)

        result.stayovers shouldBe 0
        store.saved.map { it.source to it.roomId } shouldBe listOf(TaskSource.PRE_ARRIVAL to day.arriving)
    }

    @Test
    fun `with the PRE_ARRIVAL type inactive there is no pre-arrival, and the stayover is still generated`() {
        val day = DailyScenario()
        day.types(preArrivalActive = false)

        val result = generation.generateForDate(date)

        result.preArrivals shouldBe 0
        store.saved.map { it.source to it.roomId } shouldBe listOf(TaskSource.STAYOVER to day.staying)
    }

    @Test
    fun `a stayover only for a guest who arrived before the date, read after the departures`() {
        val day = DailyScenario()
        day.types()
        val arrivedToday = ReservationId(UUID.randomUUID())
        val arrivedRoom = RoomId(UUID.randomUUID())
        val leaving = ReservationId(UUID.randomUUID())
        val leavingRoom = RoomId(UUID.randomUUID())
        every { rooms.activeRooms(null) } returns
            listOf(day.staying, day.arriving, arrivedRoom, leavingRoom).map {
                RoomSummary(it, "$it", null, roomType, emptySet(), true)
            }
        // Em [date] a reserva que chegou hoje já está hospedada (a geração rodou depois do check-in), mas a permanência
        // olha só quem chegou até a véspera, menos quem sai na data.
        every { reservations.inHouse(date) } returns listOf(day.guest, arrivedToday)
        every { reservations.assignedRooms(listOf(day.guest, arrivedToday)) } returns
            mapOf(day.guest to day.staying, arrivedToday to arrivedRoom)
        every { reservations.inHouse(date.minusDays(1)) } returns listOf(day.guest, leaving)
        every { reservations.assignedRooms(listOf(day.guest, leaving)) } returns
            mapOf(day.guest to day.staying, leaving to leavingRoom)
        every { reservations.pendingDepartures(date) } returns listOf(leaving)

        generation.generateForDate(date)

        store.saved.filter { it.source == TaskSource.STAYOVER }.map { it.roomId } shouldBe listOf(day.staying)
        // As saídas são lidas antes das hospedadas: um check-out entre as leituras não vira permanência.
        verifyOrder {
            reservations.pendingDepartures(date)
            reservations.inHouse(date.minusDays(1))
        }
        verifyOrder {
            reservations.pendingDepartures(date)
            reservations.inHouse(date)
        }
    }

    @Test
    fun `with both types inactive and nothing due the day records no change`() {
        DailyScenario().types(stayoverActive = false, preArrivalActive = false)

        generation.generateForDate(date) shouldBe DailyGenerationResult(scheduled = 0, stayovers = 0, preArrivals = 0)

        store.saved.shouldBeEmpty()
        verify(exactly = 1) { audit.noChanges() }
        verify(exactly = 0) { audit.created(any(), any(), any()) }
    }

    @Test
    fun `a scheduled cleaning skipped for an inactive type or place is logged at INFO`() {
        DailyScenario().types(stayoverActive = false, preArrivalActive = false)
        val inactive =
            CleaningType
                .create(CleaningTypeDetails("FAXINA", "Faxina", false, 2, null, null, emptyList()), now)
                .also { it.deactivate(now) }
        val schedule = ScheduledCleaning.create(ScheduledCleaningInput(room, null, inactive.id, 7, null), date, now)
        every { scheduledCleanings.dueOn(date) } returns listOf(schedule)
        every { cleaningTypes.cleaningType(inactive.id) } returns inactive

        val events = capturingGenerationLog { generation.generateForDate(date) }

        store.saved.shouldBeEmpty()
        events.filter { schedule.id.toString() in it.formattedMessage }.map { it.level } shouldBe listOf(Level.INFO)
    }

    @Test
    fun `a scheduled cleaning skipped for an open task is logged at DEBUG, the normal case of every day`() {
        val day = DailyScenario()
        day.types(stayoverActive = false, preArrivalActive = false)
        val type = CleaningType.create(CleaningTypeDetails("FAXINA", "Faxina", false, 2, null, null, emptyList()), now)
        val schedule =
            ScheduledCleaning.create(ScheduledCleaningInput(day.arriving, null, type.id, 7, null), date, now)
        store.insertIfAbsent(
            HousekeepingTask.create(
                type,
                TaskLocation.Room(day.arriving),
                date.minusDays(1),
                TaskSource.SCHEDULED,
                arrivalExpected = false,
                credits = 0,
                now = now,
                scheduledCleaningId = schedule.id,
            ),
        )
        every { scheduledCleanings.dueOn(date) } returns listOf(schedule)
        every { cleaningTypes.cleaningType(type.id) } returns type

        val events = capturingGenerationLog { generation.generateForDate(date) }

        store.saved shouldHaveSize 1
        events.filter { schedule.id.toString() in it.formattedMessage }.map { it.level } shouldBe listOf(Level.DEBUG)
    }

    @Test
    fun `the listener ignores a late date advance and generates the day of a current one`() {
        val daily = mockk<HousekeepingTaskGeneration>(relaxed = true)
        val listeners = HousekeepingTaskListeners(daily, hotel)

        listeners.on(BusinessDateAdvanced(date.minusDays(2), date.minusDays(1), now))
        verify(exactly = 0) { daily.generateForDate(any()) }

        listeners.on(BusinessDateAdvanced(date.minusDays(1), date, now))
        verify(exactly = 1) { daily.generateForDate(date) }
    }

    /** Os eventos do log da geração durante o [block], inclusive os `DEBUG`, para conferir o nível de cada um. */
    private fun capturingGenerationLog(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(HousekeepingTaskGeneration::class.java) as Logger
        val level = logger.level
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(captured)
        try {
            block()
        } finally {
            logger.detachAppender(captured)
            logger.level = level
        }
        return captured.list.toList()
    }

    /**
     * O dia com uma UH hospedada que fica ([staying]) e uma UH vaga, limpa e com chegada alocada ([arriving]), as duas
     * ativas e fora de OOO/OOS.
     */
    private inner class DailyScenario {
        val staying = RoomId(UUID.randomUUID())
        val arriving = RoomId(UUID.randomUUID())
        val guest = ReservationId(UUID.randomUUID())

        init {
            val arrival = ReservationId(UUID.randomUUID())
            every { rooms.activeRooms(null) } returns
                listOf(
                    RoomSummary(staying, "201", null, roomType, emptySet(), true),
                    RoomSummary(arriving, "202", null, roomType, emptySet(), true),
                )
            every { rooms.status(staying) } returns status(staying, OccupancyStatus.OCCUPIED, HousekeepingStatus.DIRTY)
            every { rooms.status(arriving) } returns status(arriving, OccupancyStatus.VACANT, HousekeepingStatus.CLEAN)
            every { reservations.inHouse(date.minusDays(1)) } returns listOf(guest)
            every { reservations.assignedRooms(listOf(guest)) } returns mapOf(guest to staying)
            every { reservations.pendingArrivals(date) } returns listOf(arrival)
            every { reservations.assignedRooms(listOf(arrival)) } returns mapOf(arrival to arriving)
        }

        fun types(
            stayoverPriority: Int = 3,
            preArrivalPriority: Int = 4,
            stayoverActive: Boolean = true,
            preArrivalActive: Boolean = true,
        ) {
            every { cleaningTypes.byKey(CleaningTypeKey.STAYOVER) } returns
                type("PERMANENCIA", stayoverPriority, stayoverActive)
            every { cleaningTypes.byKey(CleaningTypeKey.PRE_ARRIVAL) } returns
                type("PRECHEGADA", preArrivalPriority, preArrivalActive)
        }

        private fun type(
            code: String,
            priority: Int,
            active: Boolean,
        ) = CleaningType
            .create(CleaningTypeDetails(code, code, false, priority, null, null, emptyList()), now)
            .also { if (!active) it.deactivate(now) }

        private fun status(
            id: RoomId,
            occupancy: OccupancyStatus,
            housekeeping: HousekeepingStatus,
        ) = RoomStatus(id, true, occupancy, null, housekeeping, false, GuestServiceFlag.NONE, null, false, now, 0)
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

        override fun findForUpdate(id: HousekeepingTaskId): HousekeepingTask? = error("fora do teste")

        override fun tasks(ids: List<UUID>): List<HousekeepingTask> = error("fora do teste")

        override fun page(
            date: LocalDate,
            filter: TaskFilter,
            after: TaskPageKey?,
            limit: Int,
        ): List<TaskPageKey> = error("fora do teste")

        override fun assignableUpTo(
            date: LocalDate,
            includePrevious: Boolean,
        ): List<HousekeepingTask> = error("fora do teste")

        override fun update(task: HousekeepingTask): Unit = error("fora do teste")

        override fun touch(task: HousekeepingTask): Unit = error("fora do teste")

        override fun recordAssignment(
            taskId: HousekeepingTaskId,
            employeeId: EmployeeId,
            at: Instant,
        ): Unit = error("fora do teste")

        override fun wasAssignedTo(
            taskId: HousekeepingTaskId,
            employeeId: EmployeeId,
        ): Boolean = error("fora do teste")

        override fun recordLeftScope(
            taskId: HousekeepingTaskId,
            users: Set<UserId>,
        ): Unit = error("fora do teste")

        override fun hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean =
            saved.any {
                it.scheduledCleaningId == scheduledCleaningId &&
                    it.status != TaskStatus.DONE &&
                    it.status != TaskStatus.CANCELLED
            }

        override fun lastTaskDate(
            roomId: RoomId,
            onOrBefore: LocalDate,
        ): LocalDate? =
            saved
                .filter { it.roomId == roomId && it.businessDate <= onOrBefore && it.status != TaskStatus.CANCELLED }
                .maxOfOrNull { it.businessDate }
    }

    private companion object {
        /** A ordem da lista de tarefas (`TaskPageKey`): chegada prevista desc, prioridade desc, id. */
        val LIST_ORDER: Comparator<HousekeepingTask> =
            compareByDescending<HousekeepingTask> { it.arrivalExpected }
                .thenByDescending { it.priority }
                .thenBy { it.id.value }
    }
}
