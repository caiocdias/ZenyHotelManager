package com.zeny.hotel.reservations

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.domain.AllocationTerms
import com.zeny.hotel.reservations.domain.BlockAllocations
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.reservations.domain.RoomBlockBelowPickUpException
import com.zeny.hotel.reservations.domain.RoomBlockCutoff
import com.zeny.hotel.reservations.domain.RoomBlockDatesNotCoveredException
import com.zeny.hotel.reservations.domain.RoomBlockPeriodInPastException
import com.zeny.hotel.reservations.domain.RoomBlockRules
import com.zeny.hotel.reservations.domain.RoomBlockUnavailableException
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** As regras puras dos bloqueios de grupo (M4 E11): formato, alteração, pick-up, devolução, liberação e corte. */
class RoomBlockDomainTest {
    private val type = RoomTypeId(UuidV7.generate())
    private val day = LocalDate.of(2027, 3, 10)

    @Test
    fun `the format needs one holder, ranges without overlap and quantities and rates in range`() {
        val ok = BlockAllocationRange(type, day, day.plusDays(1), 2, Money.of("250.00"))
        RoomBlockRules.requireValid("Casamento", GuestId(UuidV7.generate()) to null, listOf(ok), true).keys shouldBe
            setOf(InventoryKey(type, day), InventoryKey(type, day.plusDays(1)))

        fun fields(
            name: String = "Casamento",
            holder: Pair<GuestId?, CompanyId?>? = GuestId(UuidV7.generate()) to null,
            ranges: List<BlockAllocationRange> = listOf(ok),
        ) = shouldThrow<InvalidInputException> { RoomBlockRules.requireValid(name, holder, ranges, true) }
            .violations
            .map { it.field }

        fields(name = " ") shouldContainExactly listOf("name")
        fields(holder = null to null) shouldContainExactly listOf("holderGuestId")
        fields(holder = GuestId(UuidV7.generate()) to CompanyId(UuidV7.generate())) shouldContainExactly
            listOf("holderGuestId")
        fields(ranges = emptyList()) shouldContainExactly listOf("allocations")
        fields(ranges = listOf(ok, ok.copy(from = day.plusDays(1), to = day.plusDays(2)))) shouldContainExactly
            listOf("allocations[1]")
        fields(
            ranges =
                listOf(
                    ok.copy(to = day.minusDays(1)),
                    ok.copy(roomTypeId = RoomTypeId(UuidV7.generate()), quantity = 0),
                ),
        ).shouldContainExactly(listOf("allocations[0].to", "allocations[1].quantity"))
        fields(ranges = listOf(ok.copy(rate = Money.of("-1.00")))) shouldContainExactly listOf("allocations[0].rate")
        // A alteração aceita ficar sem unidades.
        RoomBlockRules.requireValid("Casamento", null, emptyList(), false) shouldBe emptyMap()
    }

    @Test
    fun `the change replaces the units from the business date, moving only the difference through the inventory`() {
        val current =
            listOf(
                row(day.minusDays(1), 2, 2),
                row(day, 2, 1),
                row(day.plusDays(1), 2, 0),
                row(day.plusDays(2), 2, 0),
            )
        val requested =
            mapOf(
                key(day) to AllocationTerms(3, Money.of("250.00")),
                key(day.plusDays(1)) to AllocationTerms(2, Money.of("199.00")),
                key(day.plusDays(3)) to AllocationTerms(1, Money.of("250.00")),
            )

        val replacement = BlockAllocations.replace(current, requested, day)

        replacement.saved.map { Triple(it.date, it.quantity, it.rate.toString()) } shouldContainExactlyInAnyOrder
            listOf(
                Triple(day, 3, "250.00"),
                Triple(day.plusDays(1), 2, "199.00"),
                Triple(day.plusDays(3), 1, "250.00"),
            )
        replacement.saved.single { it.date == day }.pickedUp shouldBe 1
        // A noite anterior à data operacional fica; a que não veio no pedido sai.
        replacement.removed shouldContainExactly listOf(key(day.plusDays(2)))
        replacement.inventoryChanges shouldContainExactlyInAnyOrder
            listOf(
                InventoryChange.take(type, day, day.plusDays(1), 1, InventoryUse.BLOCKED),
                InventoryChange.take(type, day.plusDays(3), day.plusDays(4), 1, InventoryUse.BLOCKED),
                InventoryChange.release(type, day.plusDays(2), day.plusDays(3), 2, InventoryUse.BLOCKED),
            )
        shouldThrow<RoomBlockBelowPickUpException> {
            BlockAllocations.replace(current, mapOf(key(day.plusDays(1)) to AllocationTerms(1, Money.ZERO)), day)
        }.keys shouldContainExactly listOf(key(day))
        shouldThrow<RoomBlockPeriodInPastException> {
            BlockAllocations.replace(current, mapOf(key(day.minusDays(1)) to AllocationTerms(2, Money.ZERO)), day)
        }
        BlockAllocations
            .replace(
                current,
                current.drop(1).associate { key(it.date) to AllocationTerms(2, it.rate) },
                day,
            ).empty shouldBe true
    }

    @Test
    fun `the pick-up counts every reservation of the request together and needs every night in the block`() {
        val rows = BlockAllocations.byKey(listOf(row(day, 2, 1), row(day.plusDays(1), 2, 0)))

        BlockAllocations
            .pickUp(rows, mapOf(key(day) to 1, key(day.plusDays(1)) to 2))
            .map { it.date to it.pickedUp } shouldContainExactly listOf(day to 2, day.plusDays(1) to 2)
        shouldThrow<RoomBlockUnavailableException> {
            BlockAllocations.pickUp(rows, mapOf(key(day) to 2, key(day.plusDays(1)) to 1))
        }.keys shouldContainExactly listOf(key(day))
        shouldThrow<RoomBlockDatesNotCoveredException> {
            BlockAllocations.pickUp(rows, mapOf(key(day) to 1, key(day.plusDays(2)) to 1))
        }.keys shouldContainExactly listOf(key(day.plusDays(2)))
        BlockAllocations.nightlyRates(rows, listOf(key(day)), RatePlanId(UuidV7.generate())).single().let {
            it.roomAmount shouldBe Money.of("250.00")
            it.calculatedRoomAmount shouldBe it.roomAmount
            it.amount shouldBe it.roomAmount
            it.overridden shouldBe false
        }
    }

    @Test
    fun `the release gives back only the unused units from the business date, and the cutoff rules`() {
        val release =
            BlockAllocations.release(
                listOf(row(day.minusDays(1), 3, 0), row(day, 3, 1), row(day.plusDays(1), 2, 2)),
                day,
            )

        release.saved.map { Triple(it.date, it.pickedUp, it.released) } shouldContainExactly
            listOf(Triple(day, 1, 2))
        release.units shouldBe 2
        release.inventoryChanges shouldContainExactly
            listOf(InventoryChange.release(type, day, day.plusDays(1), 2, InventoryUse.BLOCKED))
        // A devolução de uma reserva cancelada: ao bloqueio ativo (pickedUp) ou ao inventário depois da liberação.
        val rows = BlockAllocations.byKey(listOf(row(day, 3, 1)))
        BlockAllocations.giveBack(rows, listOf(key(day))).single().pickedUp shouldBe 0
        BlockAllocations.toInventory(rows, listOf(key(day))).single().let {
            (it.pickedUp to it.released) shouldBe
                (0 to 1)
        }

        RoomBlockCutoff.pickUpOpen(day, day) shouldBe true
        RoomBlockCutoff.pickUpOpen(day, day.plusDays(1)) shouldBe false
        RoomBlockCutoff.releasable(day, day.minusDays(1)) shouldBe false
        RoomBlockCutoff.releasable(day, day) shouldBe true
    }

    private fun key(date: LocalDate) = InventoryKey(type, date)

    private fun row(
        date: LocalDate,
        quantity: Int,
        pickedUp: Int,
    ) = BlockAllocationSummary(type, date, quantity, pickedUp, 0, Money.of("250.00"))
}
