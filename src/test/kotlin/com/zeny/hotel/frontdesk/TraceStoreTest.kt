package com.zeny.hotel.frontdesk

import com.zeny.hotel.frontdesk.application.TraceFilter
import com.zeny.hotel.frontdesk.application.TraceStore
import com.zeny.hotel.frontdesk.domain.Trace
import com.zeny.hotel.frontdesk.domain.TraceLink
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A tabela `frontdesk.trace` e o [TraceStore] (M5 E06): gravação, versão, trigger, `CHECK`s e a página. */
@IntegrationTest
class TraceStoreTest {
    @Autowired lateinit var store: TraceStore

    @Autowired lateinit var jdbc: JdbcClient

    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private val due = LocalDate.of(2031, 3, 10)

    private fun trace(
        department: DepartmentId = DepartmentId.new(),
        dueOn: LocalDate = due,
        link: TraceLink = TraceLink.Room(RoomId.new()),
        assignee: UserId? = null,
    ) = Trace(TraceId.new(), department, dueOn, "texto", link, assignee, now, UserId.new(), now, null, null, 0)

    @Test
    fun `adds and reads a trace, with and without link and assignee`() {
        val room = trace(assignee = UserId.new()).also { store.add(it) }
        val reservation = trace(link = TraceLink.Reservation(ReservationId.new())).also { store.add(it) }
        store.find(room.id) shouldBe room
        store.find(reservation.id) shouldBe reservation
        store.find(TraceId.new()).shouldBeNull()
    }

    @Test
    fun `update bumps the version and refuses a stale one`() {
        val trace = trace().also { store.add(it) }
        val resolved = trace.resolve(UserId.new(), now.plusSeconds(1))
        val saved = store.update(resolved)
        saved.version shouldBe 1
        store.forUpdate(trace.id) shouldBe saved
        shouldThrow<IllegalStateException> { store.update(resolved) }
    }

    @Test
    fun `physical delete is refused by the trigger`() {
        val trace = trace().also { store.add(it) }
        shouldThrow<DataAccessException> {
            jdbc.sql("DELETE FROM frontdesk.trace WHERE id = :id").param("id", trace.id.value).update()
        }
        store.find(trace.id) shouldBe trace
    }

    @Test
    fun `the link check refuses reservation and room together`() {
        shouldThrow<DataAccessException> {
            jdbc
                .sql(
                    """
                    INSERT INTO frontdesk.trace (id, department_id, due_on, text, reservation_id, room_id, created_at,
                        created_by, updated_at)
                    VALUES (gen_random_uuid(), gen_random_uuid(), :due, 'x', gen_random_uuid(), gen_random_uuid(),
                        now(), gen_random_uuid(), now())
                    """.trimIndent(),
                ).param("due", due)
                .update()
        }
    }

    @Test
    fun `page filters by each field and paginates by due date and id without repeating or skipping`() {
        val department = DepartmentId.new()
        val reservation = ReservationId.new()
        val room = RoomId.new()
        val day = LocalDate.of(2032, 5, 1)
        val a = trace(department, day, TraceLink.Reservation(reservation)).also { store.add(it) }
        val b = trace(department, day, TraceLink.Room(room)).also { store.add(it) }
        val c = trace(department, day, TraceLink.Room(RoomId.new())).also { store.add(it) }
        val later = trace(department, day.plusDays(1)).also { store.add(it) }
        val resolved =
            trace(department, day.plusDays(2)).also { store.add(it) }.let {
                store.update(it.resolve(UserId.new(), now))
            }
        val sameDay = listOf(a, b, c).sortedBy { it.id.value }

        val all = TraceFilter(departmentId = department)
        store.page(all, null, 10).map { it.id } shouldContainExactly (sameDay + later + resolved).map { it.id }
        store.page(all.copy(dueOn = day), null, 10).map { it.id } shouldContainExactly sameDay.map { it.id }
        store.page(all.copy(status = TraceStatus.RESOLVED), null, 10).map { it.id } shouldContainExactly
            listOf(resolved.id)
        store.page(all.copy(status = TraceStatus.OPEN), null, 10).size shouldBe 4
        store.page(all.copy(reservationId = reservation), null, 10).map { it.id } shouldContainExactly listOf(a.id)
        store.page(all.copy(roomId = room), null, 10).map { it.id } shouldContainExactly listOf(b.id)

        val first = store.page(all.copy(dueOn = day), null, 2)
        first.map { it.id } shouldContainExactly sameDay.take(2).map { it.id }
        val last = first.last()
        store.page(all.copy(dueOn = day), last.dueOn to last.id.value, 2).map { it.id } shouldContainExactly
            listOf(sameDay[2].id)
        val rest = store.page(all, sameDay.last().dueOn to sameDay.last().id.value, 10)
        rest.map { it.id } shouldContainExactly listOf(later.id, resolved.id)
    }
}
