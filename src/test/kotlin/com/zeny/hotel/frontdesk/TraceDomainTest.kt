package com.zeny.hotel.frontdesk

import com.zeny.hotel.frontdesk.domain.Trace
import com.zeny.hotel.frontdesk.domain.TraceLink
import com.zeny.hotel.frontdesk.domain.TraceRules
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** As regras puras do recado (M5 E06): texto, vencimento, vínculo, resolução e o que a trilha e o `toString` levam. */
class TraceDomainTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val now = Instant.parse("2026-10-23T12:00:00Z")
    private val secret = "ligar para o hóspede do 301 sobre o segredo"

    private fun trace() =
        Trace(
            TraceId.new(),
            DepartmentId.new(),
            today,
            secret,
            TraceLink.Room(RoomId.new()),
            null,
            now,
            UserId.new(),
            now,
            null,
            null,
            0,
        )

    private fun invalidField(block: () -> Unit): String {
        val e = shouldThrow<InvalidInputException> { block() }
        e.code shouldBe FrontdeskErrorCodes.TRACE_INVALID
        return e.violations.single().field
    }

    @Test
    fun `text is trimmed and limited to 1 to 500 characters`() {
        TraceRules.text("  oi  ") shouldBe "oi"
        TraceRules.text("a".repeat(500)).length shouldBe 500
        invalidField { TraceRules.text("") } shouldBe "text"
        invalidField { TraceRules.text("   ") } shouldBe "text"
        invalidField { TraceRules.text("a".repeat(501)) } shouldBe "text"
    }

    @Test
    fun `link takes exactly one of reservation and room`() {
        val reservation = ReservationId.new()
        val room = RoomId.new()
        TraceRules.link(reservation, null) shouldBe TraceLink.Reservation(reservation)
        TraceRules.link(null, room) shouldBe TraceLink.Room(room)
        invalidField { TraceRules.link(reservation, room) } shouldBe "reservationId"
        invalidField { TraceRules.link(null, null) } shouldBe "reservationId"
    }

    @Test
    fun `due date cannot be before the business date`() {
        TraceRules.dueOn(today, today) shouldBe today
        TraceRules.dueOn(today.plusDays(1), today) shouldBe today.plusDays(1)
        invalidField { TraceRules.dueOn(today.minusDays(1), today) } shouldBe "dueOn"
    }

    @Test
    fun `a resolved trace can be neither resolved nor changed again`() {
        val resolved = trace().resolve(UserId.new(), now)
        resolved.status shouldBe TraceStatus.RESOLVED
        shouldThrow<ConflictException> { resolved.resolve(UserId.new(), now) }.code shouldBe
            FrontdeskErrorCodes.TRACE_ALREADY_RESOLVED
        shouldThrow<ConflictException> {
            resolved.change(DepartmentId.new(), today, "novo", null, now)
        }.code shouldBe FrontdeskErrorCodes.TRACE_ALREADY_RESOLVED
    }

    @Test
    fun `an open trace changes department, due date, text and assignee`() {
        val department = DepartmentId.new()
        val assignee = UserId.new()
        val changed = trace().change(department, today.plusDays(2), "novo", assignee, now.plusSeconds(5))
        changed.departmentId shouldBe department
        changed.dueOn shouldBe today.plusDays(2)
        changed.text shouldBe "novo"
        changed.assigneeUserId shouldBe assignee
        changed.status shouldBe TraceStatus.OPEN
    }

    @Test
    fun `audit state and toString never carry the text`() {
        val trace = trace()
        trace.auditState().toString() shouldNotContain secret
        trace.auditState()["textLength"] shouldBe secret.codePointCount(0, secret.length)
        trace.toString() shouldNotContain secret
    }
}
