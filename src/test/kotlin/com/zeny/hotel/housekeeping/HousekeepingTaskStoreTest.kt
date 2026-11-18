package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.housekeeping.application.CleaningTypeStore
import com.zeny.hotel.housekeeping.application.HousekeepingTaskStore
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As portas de persistência que a geração usa (M6 E02, Task 1): o tipo da carga pela chave estável
 * (`CleaningTypeStore.byKey`), a gravação idempotente sem abortar a transação (`insertIfAbsent`), a tarefa aberta de
 * uma faxina programada (`hasOpenTaskFor`) e a última tarefa de uma UH (`lastTaskDate`, sem as canceladas). As tarefas
 * ficam em UHs e áreas criadas pelo teste, em datas longe da corrente, e as faxinas criadas terminam inativas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskStoreTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val store: HousekeepingTaskStore,
    @Autowired private val cleaningTypes: CleaningTypeStore,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `byKey finds each type of the initial load by its stable key`() {
        CleaningTypeKey.entries.forEach { key ->
            withClue(key) {
                val type = transactions.execute { cleaningTypes.byKey(key) }.shouldNotBeNull()
                type.key shouldBe key
                type.code shouldBe key.name
                type.active shouldBe (key != CleaningTypeKey.TURNDOWN)
            }
        }
    }

    @Test
    fun `insertIfAbsent writes once per location, date and type, and the repeat neither throws nor aborts`() {
        val room = RoomId(housekeeping.createRoom())
        val area = AreaId(housekeeping.rooms.createArea())
        val type = type(housekeeping.createCleaningType())
        val date = LocalDate.of(2003, 3, 3)

        val results =
            transactions.execute {
                val first = store.insertIfAbsent(task(type, TaskLocation.Room(room), date))
                val repeat = store.insertIfAbsent(task(type, TaskLocation.Room(room), date))
                // A transação segue viva depois do conflito: outra escrita e uma leitura passam.
                val otherDay = store.insertIfAbsent(task(type, TaskLocation.Room(room), date.plusDays(1)))
                val areaFirst = store.insertIfAbsent(task(type, TaskLocation.Area(area), date))
                val areaRepeat = store.insertIfAbsent(task(type, TaskLocation.Area(area), date))
                listOf(first, repeat, otherDay, areaFirst, areaRepeat)
            }

        results shouldBe listOf(true, false, true, true, false)
        countOf("room_id", room.value) shouldBe 2
        countOf("area_id", area.value) shouldBe 1
    }

    @Test
    fun `hasOpenTaskFor is true for any status but DONE and CANCELLED`() {
        val type = housekeeping.createCleaningType()
        val room = housekeeping.createRoom()
        val schedule = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = room))
        try {
            val scheduleId = ScheduledCleaningId(schedule)
            transactions.execute { store.hasOpenTaskFor(scheduleId) } shouldBe false
            val task =
                task(
                    type(type),
                    TaskLocation.Room(RoomId(room)),
                    LocalDate.of(2004, 4, 4),
                    TaskSource.SCHEDULED,
                    scheduleId,
                )
            transactions.execute { store.insertIfAbsent(task) } shouldBe true

            TaskStatus.entries.forEach { status ->
                withClue(status) {
                    setStatus(task.id.value, status)
                    transactions.execute { store.hasOpenTaskFor(scheduleId) } shouldBe
                        (status != TaskStatus.DONE && status != TaskStatus.CANCELLED)
                }
            }
        } finally {
            with(iam) { call(POST, "$SCHEDULED_CLEANINGS/$schedule/deactivate", asAdmin()).ok() }
        }
    }

    @Test
    fun `lastTaskDate is the latest task date up to the given day, without the cancelled ones`() {
        val room = RoomId(housekeeping.createRoom())
        val other = RoomId(housekeeping.createRoom())
        val day = LocalDate.of(2005, 5, 20)
        val types = (1..4).map { type(housekeeping.createCleaningType()) }
        val done = task(types[0], TaskLocation.Room(room), day.minusDays(5))
        val cancelled = task(types[1], TaskLocation.Room(room), day.minusDays(2))
        val later = task(types[2], TaskLocation.Room(room), day.plusDays(1))
        val otherRoom = task(types[3], TaskLocation.Room(other), day)
        transactions.execute {
            listOf(done, cancelled, later, otherRoom).forEach { task ->
                store.insertIfAbsent(task)
            }
        }
        setStatus(done.id.value, TaskStatus.DONE)
        setStatus(cancelled.id.value, TaskStatus.CANCELLED)

        transactions.execute { store.lastTaskDate(room, day) } shouldBe day.minusDays(5)
        transactions.execute { store.lastTaskDate(room, day.plusDays(1)) } shouldBe day.plusDays(1)
        transactions.execute { store.lastTaskDate(room, day.minusDays(6)) }.shouldBeNull()
        transactions.execute { store.lastTaskDate(RoomId(housekeeping.createRoom()), day) }.shouldBeNull()
    }

    private fun type(id: UUID): CleaningType =
        transactions.execute { cleaningTypes.cleaningType(CleaningTypeId(id)) }.shouldNotBeNull()

    private fun task(
        type: CleaningType,
        location: TaskLocation,
        date: LocalDate,
        source: TaskSource = TaskSource.AD_HOC,
        schedule: ScheduledCleaningId? = null,
    ) = HousekeepingTask.create(type, location, date, source, false, 0, Instant.now(), schedule)

    private fun setStatus(
        id: UUID,
        status: TaskStatus,
    ) = owner
        .sql("UPDATE housekeeping.housekeeping_task SET status = :status WHERE id = :id")
        .param("status", status.name)
        .param("id", id)
        .update()

    private fun countOf(
        column: String,
        id: UUID,
    ): Int =
        owner
            .sql("SELECT count(*) FROM housekeeping.housekeeping_task WHERE $column = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()
}
