package com.zeny.hotel.frontdesk

import com.zeny.hotel.frontdesk.domain.RoomMoveTariffs
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.frontdesk.domain.StayTransitionNotAllowedException
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** As regras puras da troca de UH na recepção (M5 E05): a soma das noites restantes (D8) e a recusa da situação. */
class RoomMoveDomainTest {
    @Test
    fun `the remaining amount sums only the nights from the business date`() {
        val nights =
            listOf(
                night(ARRIVAL, "400.00", additional = "0.00"),
                night(ARRIVAL.plusDays(1), "400.00", additional = "50.00"),
                night(ARRIVAL.plusDays(2), "550.00", additional = "0.00"),
            )

        RoomMoveTariffs.remaining(nights, ARRIVAL) shouldBe Money.of("1400.00")
        // As noites antes da data operacional ficam de fora; os pacotes adicionais entram (o `amount` da noite).
        RoomMoveTariffs.remaining(nights, ARRIVAL.plusDays(1)) shouldBe Money.of("1000.00")
        RoomMoveTariffs.remaining(nights, ARRIVAL.plusDays(2)) shouldBe Money.of("550.00")
        RoomMoveTariffs.remaining(nights, ARRIVAL.plusDays(3)) shouldBe Money.ZERO
        RoomMoveTariffs.remaining(emptyList(), ARRIVAL) shouldBe Money.ZERO
    }

    @Test
    fun `the refused move names its action`() {
        StayTransitionNotAllowedException(ReservationStatus.CONFIRMED, StayRules.MOVE_ROOM).let {
            it.message shouldBe "Só uma reserva hospedada troca de UH."
            it.problemProperties shouldBe mapOf("reservationStatus" to "CONFIRMED", "action" to "MOVE_ROOM")
        }
    }

    private fun night(
        date: LocalDate,
        room: String,
        additional: String,
    ) = NightlyRateSummary(
        date = date,
        ratePlanId = PLAN,
        calculatedRoomAmount = Money.of(room),
        roomAmount = Money.of(room),
        includedPackagesAmount = Money.ZERO,
        additionalPackagesAmount = Money.of(additional),
        amount = Money.of(room) + Money.of(additional),
        overridden = false,
        discountPercent = null,
        packages = emptyList(),
    )

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 10, 22)
        val PLAN = RatePlanId(UuidV7.generate())
    }
}
