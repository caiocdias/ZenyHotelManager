package com.zeny.hotel.reservations

import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationTransitionNotAllowedException
import com.zeny.hotel.reservations.domain.ReservationTransitions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A transição `NO_SHOW` (M6 E11; SPEC §8.4 `Confirmed → NoShow`): só a confirmada vira no-show. */
class ReservationNoShowDomainTest {
    @Test
    fun `only a confirmed reservation can be marked no-show`() {
        ReservationStatus.entries.forEach { status ->
            ReservationTransitions.allows(ReservationAction.NO_SHOW, status) shouldBe
                (status == ReservationStatus.CONFIRMED)
        }
        ReservationTransitions.require(ReservationAction.NO_SHOW, ReservationStatus.CONFIRMED)
    }

    @Test
    fun `every other status refuses no-show with the action in the problem`() {
        ReservationStatus.entries.filter { it != ReservationStatus.CONFIRMED }.forEach { status ->
            val refusal =
                shouldThrow<ReservationTransitionNotAllowedException> {
                    ReservationTransitions.require(ReservationAction.NO_SHOW, status)
                }
            refusal.action shouldBe ReservationAction.NO_SHOW
            refusal.status shouldBe status
            refusal.problemProperties["action"] shouldBe "NO_SHOW"
            refusal.message.contains("marcada como no-show") shouldBe true
        }
    }

    @Test
    fun `a no-show reservation can still be reactivated`() {
        ReservationTransitions.allows(ReservationAction.REACTIVATE, ReservationStatus.NO_SHOW) shouldBe true
    }
}
