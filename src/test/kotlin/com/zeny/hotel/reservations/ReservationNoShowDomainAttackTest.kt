package com.zeny.hotel.reservations

import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationTransitionNotAllowedException
import com.zeny.hotel.reservations.domain.ReservationTransitions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Ataque à transição `NO_SHOW` e ao evento `ReservationMarkedNoShow` (M6 E11 Task 3). */
class ReservationNoShowDomainAttackTest {
    /** A matriz esperada, escrita à mão e independente do mapa de produção. */
    private val expected: Map<ReservationAction, Set<ReservationStatus>> =
        mapOf(
            ReservationAction.CHANGE to
                setOf(ReservationStatus.PROVISIONAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_HOUSE),
            ReservationAction.CANCEL to setOf(ReservationStatus.PROVISIONAL, ReservationStatus.CONFIRMED),
            ReservationAction.REACTIVATE to setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW),
            ReservationAction.CONFIRM to setOf(ReservationStatus.PROVISIONAL),
            ReservationAction.CHECK_IN to setOf(ReservationStatus.CONFIRMED),
            ReservationAction.UNDO_CHECK_IN to setOf(ReservationStatus.IN_HOUSE),
            ReservationAction.CHECK_OUT to setOf(ReservationStatus.IN_HOUSE),
            ReservationAction.MOVE_ROOM to setOf(ReservationStatus.IN_HOUSE),
            ReservationAction.NO_SHOW to setOf(ReservationStatus.CONFIRMED),
        )

    @Test
    fun `the full action by status matrix is exactly the expected one`() {
        ReservationAction.entries.toSet() shouldBe expected.keys
        for (action in ReservationAction.entries) {
            for (status in ReservationStatus.entries) {
                withClue("$action x $status") {
                    ReservationTransitions.allows(action, status) shouldBe (status in expected.getValue(action))
                }
            }
        }
    }

    @Test
    fun `no old action gained or lost a status because of no-show`() {
        val old = ReservationAction.entries.filter { it != ReservationAction.NO_SHOW }
        old.size shouldBe 8
        old.forEach { action ->
            ReservationStatus.entries.filter { ReservationTransitions.allows(action, it) }.toSet() shouldBe
                expected.getValue(action)
        }
    }

    @Test
    fun `no-show refusal exposes status and action and a pt-BR message without personal data`() {
        val refusal =
            shouldThrow<ReservationTransitionNotAllowedException> {
                ReservationTransitions.require(ReservationAction.NO_SHOW, ReservationStatus.IN_HOUSE)
            }
        refusal.problemProperties shouldBe mapOf("reservationStatus" to "IN_HOUSE", "action" to "NO_SHOW")
        refusal.message shouldBe "A reserva hospedada não pode ser marcada como no-show."
        refusal.code shouldBe ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED
    }

    @Test
    fun `no-show on a no-show reservation says no-show for the status`() {
        val refusal =
            shouldThrow<ReservationTransitionNotAllowedException> {
                ReservationTransitions.require(ReservationAction.NO_SHOW, ReservationStatus.NO_SHOW)
            }
        refusal.message shouldBe "A reserva no-show não pode ser marcada como no-show."
        refusal.problemProperties["reservationStatus"] shouldBe "NO_SHOW"
    }

    @Test
    fun `reactivating a no-show is allowed and a confirmed reservation cannot be reactivated`() {
        ReservationTransitions.require(ReservationAction.REACTIVATE, ReservationStatus.NO_SHOW)
        ReservationTransitions.allows(ReservationAction.REACTIVATE, ReservationStatus.CONFIRMED) shouldBe false
    }

    @Test
    fun `the event carries exactly the brief fields and only ids, dates, flags, version and instant`() {
        val props = ReservationMarkedNoShow::class.memberProperties.associate { it.name to it.returnType.toString() }
        props shouldBe
            mapOf(
                "reservationId" to "java.util.UUID",
                "bookingId" to "java.util.UUID",
                "roomTypeId" to "java.util.UUID",
                "roomId" to "java.util.UUID?",
                "arrival" to "java.time.LocalDate",
                "departure" to "java.time.LocalDate",
                "dayUse" to "kotlin.Boolean",
                "businessDate" to "java.time.LocalDate",
                "penaltyCharged" to "kotlin.Boolean",
                "performedBy" to "java.util.UUID?",
                "version" to "kotlin.Long",
                "markedAt" to "java.time.Instant",
            )
        // a ordem do construtor é a do brief (o contrato de serialização do outbox depende dos nomes, não da ordem)
        ReservationMarkedNoShow::class.primaryConstructor!!.parameters.map { it.name } shouldBe
            listOf(
                "reservationId",
                "bookingId",
                "roomTypeId",
                "roomId",
                "arrival",
                "departure",
                "dayUse",
                "businessDate",
                "penaltyCharged",
                "performedBy",
                "version",
                "markedAt",
            )
    }

    @Test
    fun `the event accepts null room and performer and keeps its values`() {
        val id = UUID.randomUUID()
        val day = LocalDate.parse("2026-11-13")
        val at = Instant.parse("2026-11-14T03:00:00Z")
        val event =
            ReservationMarkedNoShow(id, id, id, null, day, day, true, day, false, null, 7L, at)
        event.roomId shouldBe null
        event.performedBy shouldBe null
        event.dayUse shouldBe true
        event.version shouldBe 7L
        event.markedAt shouldBe at
        event shouldBe event.copy()
    }

    private inline fun <T> withClue(
        clue: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$clue: ${e.message}", e)
        }
}
