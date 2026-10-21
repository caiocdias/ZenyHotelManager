package com.zeny.hotel.reservations

import com.zeny.hotel.reservations.domain.BlockedRange
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryHorizon
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryLedger
import com.zeny.hotel.reservations.domain.InventoryOutOfHorizonException
import com.zeny.hotel.reservations.domain.InventoryPeriod
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.reservations.domain.OverbookingLimit
import com.zeny.hotel.reservations.domain.RoomCounting
import com.zeny.hotel.reservations.domain.RoomCounts
import com.zeny.hotel.reservations.domain.RoomTypeUnavailableException
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** As regras do inventário (M4 E05): tomada, devolução, overbooking, horizonte, contagem do OOO e períodos. */
class InventoryDomainTest {
    private val single = RoomTypeId.new()
    private val double = RoomTypeId.new()
    private val day = LocalDate.of(2026, 11, 10)

    private fun rows(
        type: RoomTypeId,
        counters: InventoryCounters,
        vararg dates: LocalDate,
    ) = dates.associate { InventoryKey(type, it) to counters }

    @Test
    fun `taking needs the availability after the change, including the overbooking limit`() {
        val current = rows(single, InventoryCounters(2, 1, 0, 0, 0), day, day.plusDays(1))
        val after = InventoryLedger.apply(current, listOf(InventoryChange.take(single, day, day.plusDays(2))))
        after.values.map { it.sold } shouldContainExactly listOf(2, 2)
        after.values.all { it.available == 0 } shouldBe true

        val refused =
            shouldThrow<RoomTypeUnavailableException> {
                InventoryLedger.apply(after, listOf(InventoryChange.take(single, day.plusDays(1), day.plusDays(2))))
            }
        refused.keys shouldContainExactly listOf(InventoryKey(single, day.plusDays(1)))
        refused.problemProperties["dates"] shouldBe listOf(day.plusDays(1).toString())

        val withLimit = after.mapValues { it.value.copy(overbookingLimit = 1) }
        InventoryLedger
            .apply(withLimit, listOf(InventoryChange.take(single, day, day.plusDays(1))))
            .values
            .single()
            .available shouldBe 0
    }

    @Test
    fun `OOO and blocks count against the availability, and an oversold date still accepts releases`() {
        val oversold = rows(single, InventoryCounters(2, 2, 1, 1, 0), day)
        oversold.values.single().available shouldBe -2
        shouldThrow<RoomTypeUnavailableException> {
            InventoryLedger.apply(oversold, listOf(InventoryChange.take(single, day, day.plusDays(1))))
        }
        InventoryLedger
            .apply(oversold, listOf(InventoryChange.release(single, day, day.plusDays(1))))
            .values
            .single() shouldBe InventoryCounters(2, 1, 1, 1, 0)
    }

    @Test
    fun `changes in one call count together per row, so a block that becomes a sale needs no extra unit`() {
        val current = rows(single, InventoryCounters(1, 0, 1, 0, 0), day)
        val after =
            InventoryLedger.apply(
                current,
                listOf(
                    InventoryChange.release(single, day, day.plusDays(1), use = InventoryUse.BLOCKED),
                    InventoryChange.take(single, day, day.plusDays(1)),
                ),
            )
        after.values.single() shouldBe InventoryCounters(1, 1, 0, 0, 0)
        InventoryLedger
            .apply(
                current,
                listOf(
                    InventoryChange.take(single, day, day.plusDays(1)),
                    InventoryChange.release(single, day, day.plusDays(1)),
                ),
            ).shouldBeEmpty()
    }

    @Test
    fun `the keys are in a deterministic order of room type and date, whatever the order of the changes`() {
        val changes =
            listOf(
                InventoryChange.take(double, day.plusDays(1), day.plusDays(3)),
                InventoryChange.take(single, day, day.plusDays(2)),
            )
        val keys = InventoryLedger.keys(changes)
        keys shouldBe InventoryLedger.keys(changes.reversed())
        keys shouldBe keys.sortedWith(compareBy({ it.roomTypeId.value.toString() }, { it.date }))
    }

    @Test
    fun `taking a date without a row is out of the horizon, releasing more than taken is a bug`() {
        val current = rows(single, InventoryCounters(3, 0, 0, 0, 0), day)
        shouldThrow<InventoryOutOfHorizonException> {
            InventoryLedger.apply(current, listOf(InventoryChange.take(single, day, day.plusDays(2))))
        }.dates shouldContainExactly listOf(day.plusDays(1))
        shouldThrow<IllegalStateException> {
            InventoryLedger.apply(current, listOf(InventoryChange.release(single, day, day.plusDays(1))))
        }
        shouldThrow<IllegalStateException> {
            InventoryLedger.apply(current, listOf(InventoryChange.release(single, day.plusDays(1), day.plusDays(2))))
        }
        shouldThrow<IllegalArgumentException> { InventoryChange.take(single, day, day) }
    }

    @Test
    fun `the room counts ignore the OOO of inactive rooms and count each room once per date`() {
        val a = RoomId.new()
        val b = RoomId.new()
        val inactive = RoomId.new()
        val counts =
            RoomCounting.perDate(
                setOf(a, b),
                listOf(
                    BlockedRange(a, day, day.plusDays(1)),
                    BlockedRange(inactive, day, day.plusDays(3)),
                    BlockedRange(b, day.plusDays(1), day.plusDays(1)),
                ),
                day,
                day.plusDays(2),
            )
        counts shouldBe
            mapOf(
                day to RoomCounts(2, 1),
                day.plusDays(1) to RoomCounts(2, 2),
                day.plusDays(2) to RoomCounts(2, 0),
            )
    }

    @Test
    fun `horizon, periods and the overbooking limit`() {
        InventoryHorizon.end(day) shouldBe day.plusDays(729)
        InventoryPeriod.of(day, day.plusDays(365)).dates().size shouldBe 366
        shouldThrow<InvalidInputException> { InventoryPeriod.of(day, day.plusDays(366)) }.code.value shouldBe
            "INVENTORY_PERIOD_TOO_LONG"
        shouldThrow<InvalidInputException> { InventoryPeriod.of(day, day.minusDays(1)) }.code.value shouldBe
            "INVENTORY_PERIOD_INVALID"
        InventoryPeriod.of(day, day.plusDays(13), setOf(DayOfWeek.MONDAY)).dates().all {
            it.dayOfWeek == DayOfWeek.MONDAY
        } shouldBe true
        OverbookingLimit.of(0) shouldBe 0
        OverbookingLimit.of(50) shouldBe 50
        shouldThrow<InvalidInputException> { OverbookingLimit.of(-1) }
        shouldThrow<InvalidInputException> { OverbookingLimit.of(51) }
        InventoryCounters.EMPTY.available shouldBe 0
    }
}
