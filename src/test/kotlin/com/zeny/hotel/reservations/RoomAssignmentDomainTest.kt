package com.zeny.hotel.reservations

import com.zeny.hotel.reservations.domain.AssignmentPeriod
import com.zeny.hotel.reservations.domain.BlockingPeriod
import com.zeny.hotel.reservations.domain.ReallocationMarks
import com.zeny.hotel.reservations.domain.ReallocationNotice
import com.zeny.hotel.reservations.domain.RoomCandidate
import com.zeny.hotel.reservations.domain.RoomSuggestions
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomTypeId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * As regras puras da alocação de UH (M4 E10): o período ocupado (meio-aberto, o day use como a própria data, H5), as
 * datas bloqueadas por um OOO, a marca de realocação (só OOO), a ordem das sugestões (OOS por último, características,
 * andar preferido) e o texto da notificação (sem dado de hóspede).
 */
class RoomAssignmentDomainTest {
    private val day = LocalDate.of(2027, 3, 10)

    @Test
    fun `the departure of one stay and the arrival of another on the same day do not overlap`() {
        val first = AssignmentPeriod(day, day.plusDays(2))
        val second = AssignmentPeriod(day.plusDays(2), day.plusDays(4))

        first.conflictsWith(second) shouldBe false
        second.conflictsWith(first) shouldBe false
        first.conflictsWith(AssignmentPeriod(day.plusDays(1), day.plusDays(3))) shouldBe true
        first.lastDate shouldBe day.plusDays(1)
    }

    @Test
    fun `a day use conflicts only with a stay that crosses its date and another day use, not departures or arrivals`() {
        val dayUse = AssignmentPeriod(day.plusDays(2), day.plusDays(2))

        dayUse.lastDate shouldBe day.plusDays(2)
        dayUse.conflictsWith(AssignmentPeriod(day, day.plusDays(2))) shouldBe false // sai na data
        dayUse.conflictsWith(AssignmentPeriod(day.plusDays(2), day.plusDays(3))) shouldBe false // chega na data
        dayUse.conflictsWith(AssignmentPeriod(day.plusDays(1), day.plusDays(4))) shouldBe true // atravessa a data
        AssignmentPeriod(day.plusDays(1), day.plusDays(3)).conflictsWith(dayUse) shouldBe true
        // Meios-dias: o day use ocupa só o dia; o pernoite, da noite da chegada até o dia antes da saída.
        dayUse.endSlot - dayUse.firstSlot shouldBe 1
        AssignmentPeriod(day, day.plusDays(2)).let { it.endSlot - it.firstSlot } shouldBe 3
        dayUse.conflictsWith(AssignmentPeriod(day.plusDays(2), day.plusDays(2))) shouldBe true
        dayUse.conflictsWith(AssignmentPeriod(day.plusDays(3), day.plusDays(3))) shouldBe false
    }

    @Test
    fun `an OOO marks the stay only on the nights it blocks, and an OOS never marks it`() {
        val room = RoomId.new()
        val stay = AssignmentPeriod(day, day.plusDays(3)) // noites 10, 11 e 12
        val ooo = BlockingPeriod(OutOfOrderId.new(), room, day.plusDays(2), day.plusDays(5), outOfService = false)
        val oos = BlockingPeriod(OutOfOrderId.new(), room, day, day.plusDays(1), outOfService = true)
        val onDeparture = BlockingPeriod(OutOfOrderId.new(), room, day.plusDays(3), day.plusDays(4), false)

        stay.datesBlockedBy(ooo) shouldContainExactly listOf(day.plusDays(2))
        ReallocationMarks.hitting(stay, listOf(oos, ooo)) shouldBe ooo
        ReallocationMarks.hitting(stay, listOf(oos)) shouldBe null
        ReallocationMarks.hitting(stay, listOf(onDeparture)) shouldBe null
        ReallocationMarks.hitting(AssignmentPeriod(day.plusDays(3), day.plusDays(3)), listOf(onDeparture)) shouldBe
            onDeparture
    }

    @Test
    fun `suggestions put the out of service rooms last, then the requested features, then the preferred floor`() {
        val view = RoomFeatureId.new()
        val accessible = RoomFeatureId.new()
        val plain = room("101", "1º andar")
        val withView = room("102", "1º andar", setOf(view))
        val preferredFloor = room("103", "3º Andar")
        val both = room("104", "3º andar ", setOf(view, accessible))
        val outOfService = room("105", "3º andar", setOf(view, accessible))

        val ranked =
            RoomSuggestions.rank(
                listOf(plain, withView, preferredFloor, both, outOfService).map {
                    RoomCandidate(it, outOfService = it == outOfService, current = false)
                },
                setOf(view, accessible),
                "3º ANDAR",
            )

        ranked.map { it.room.number } shouldContainExactly listOf("104", "102", "103", "101", "105")
        ranked.first().matchedFeatureIds shouldBe setOf(view, accessible)
        ranked.first().matchesFloorPreference shouldBe true
        ranked.single { it.room.number == "101" }.matchesFloorPreference shouldBe false
        RoomSuggestions.normalized("  Térreo   Bloco A ") shouldBe "terreo bloco a"
    }

    @Test
    fun `without preferences or features, suggestions keep the room order with the out of service ones last`() {
        val rooms = listOf(room("201", null), room("202", "2º"), room("203", null))

        val ranked =
            RoomSuggestions.rank(
                rooms.map { RoomCandidate(it, outOfService = it.number == "201", current = false) },
                emptySet(),
                null,
            )

        ranked.map { it.room.number } shouldContainExactly listOf("202", "203", "201")
        ranked.none { it.matchesFloorPreference } shouldBe true
    }

    @Test
    fun `the reallocation notice has the room number, the dates and the reservation codes, nothing about guests`() {
        ReallocationNotice.title("307") shouldBe "Realocação necessária na UH 307"
        val body = ReallocationNotice.body("307", day, day.plusDays(4), listOf("R-2027-000001", "R-2027-000002"))

        body shouldBe
            "A UH 307 está em OOO de 10/03/2027 a 14/03/2027: 2 reservas alocadas nela precisam de outra UH " +
            "(R-2027-000001, R-2027-000002)."
        ReallocationNotice.body("307", day, day, listOf("R-2027-000003")) shouldContain "1 reserva alocada nela precisa"
        val many = (1..25).map { "R-2027-" + it.toString().padStart(6, '0') }
        ReallocationNotice.body("9", day, day, many) shouldContain "e mais 5"
    }

    private fun room(
        number: String,
        floor: String?,
        features: Set<RoomFeatureId> = emptySet(),
    ) = RoomSummary(RoomId.new(), number, floor, ROOM_TYPE, features, true)

    private companion object {
        val ROOM_TYPE = RoomTypeId.new()
    }
}
