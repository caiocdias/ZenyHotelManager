package com.zeny.hotel.rooms

import com.zeny.hotel.rooms.HousekeepingStatus.CLEAN
import com.zeny.hotel.rooms.HousekeepingStatus.DIRTY
import com.zeny.hotel.rooms.HousekeepingStatus.INSPECTED
import com.zeny.hotel.rooms.OutOfOrderKind.OUT_OF_ORDER
import com.zeny.hotel.rooms.OutOfOrderKind.OUT_OF_SERVICE
import com.zeny.hotel.rooms.RoomStatusField.HOUSEKEEPING_STATUS
import com.zeny.hotel.rooms.RoomStatusField.INSPECTION_PENDING
import com.zeny.hotel.rooms.domain.OutOfOrder
import com.zeny.hotel.rooms.domain.OutOfOrderDetails
import com.zeny.hotel.rooms.domain.OutOfOrderRelease
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomDetails
import com.zeny.hotel.rooms.domain.RoomStatusFieldChange
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Períodos OOO e OOS (RF-UH-05, RN-05, RN-07; M2 E03) no domínio, sem Spring: validação do período,
 * OOO recusado em UH ocupada no período atual (OOS aceito), sobreposição entre períodos da mesma UH,
 * edição, liberação (com o status de retorno só quando o período está em vigor) e a UH pronta para
 * check-in, que não pode estar em OOO.
 */
class OutOfOrderDomainTest {
    private val today = LocalDate.parse("2026-10-15")
    private val t0 = Instant.parse("2026-10-15T12:00:00Z")
    private val t1 = Instant.parse("2026-10-15T13:00:00Z")
    private val user = UserId(UuidV7.generate())

    // --- Abertura ------------------------------------------------------------------------------------

    @Test
    fun `a period has valid dates, a reason and a responsible, and does not start before the business date`() {
        val room = newRoom()
        open(room, OUT_OF_ORDER, details(today, today.plusDays(2), reason = "  Vazamento no banheiro  ")).let {
            assertSoftly {
                it.roomId shouldBe room.id
                it.kind shouldBe OUT_OF_ORDER
                it.startsOn shouldBe today
                it.endsOn shouldBe today.plusDays(2)
                it.reason shouldBe "Vazamento no banheiro"
                it.responsible shouldBe "Equipe de manutenção"
                it.returnStatus shouldBe DIRTY
                it.openedAt shouldBe t0
                it.openedBy shouldBe user
                it.releasedOn.shouldBeNull()
                it.blockedUntil shouldBe today.plusDays(2)
            }
        }
        // Um dia só vale; de trás para a frente, não.
        open(room, OUT_OF_SERVICE, details(today.plusDays(5), today.plusDays(5))).blockedUntil shouldBe
            today.plusDays(5)
        refused<InvalidInputException>("OUT_OF_ORDER_PERIOD_INVALID") {
            open(room, OUT_OF_ORDER, details(today.plusDays(2), today.plusDays(1)))
        }
        // Até um ano (365 dias).
        open(room, OUT_OF_SERVICE, details(today, today.plusDays(OutOfOrder.MAX_DAYS - 1)))
        refused<InvalidInputException>("OUT_OF_ORDER_PERIOD_INVALID") {
            open(room, OUT_OF_SERVICE, details(today, today.plusDays(OutOfOrder.MAX_DAYS)))
        }
        refused<BusinessRuleException>("OUT_OF_ORDER_PERIOD_IN_PAST") {
            open(room, OUT_OF_ORDER, details(today.minusDays(1), today.plusDays(1)))
        }
        refused<InvalidInputException>("ROOMS_DATA_INVALID") {
            open(room, OUT_OF_ORDER, details(today, today, reason = "   "))
        }
        refused<InvalidInputException>("ROOMS_DATA_INVALID") {
            open(room, OUT_OF_ORDER, details(today, today, responsible = ""))
        }
        refused<InvalidInputException>("ROOMS_DATA_INVALID") {
            open(room, OUT_OF_ORDER, details(today, today, reason = "x".repeat(OutOfOrder.MAX_REASON_LENGTH + 1)))
        }
        room.deactivate(t0)
        refused<BusinessRuleException>("ROOM_INACTIVE") { open(room, OUT_OF_SERVICE, details(today, today)) }
    }

    @Test
    fun `an OOO is refused in a room occupied in the current period, an OOS is accepted (RN-07)`() {
        val occupied = newRoom().also { it.markOccupied(2, t0) }
        refused<BusinessRuleException>("OUT_OF_ORDER_ROOM_OCCUPIED") {
            open(occupied, OUT_OF_ORDER, details(today, today.plusDays(3)))
        }
        open(occupied, OUT_OF_SERVICE, details(today, today.plusDays(3))).kind shouldBe OUT_OF_SERVICE
        // Fora do período atual (a partir de amanhã), a ocupação de hoje não impede: quem tem reserva
        // no período é assunto do inventário (M4), que escuta OutOfOrderChanged.
        open(occupied, OUT_OF_ORDER, details(today.plusDays(1), today.plusDays(3))).kind shouldBe OUT_OF_ORDER
        open(newRoom(), OUT_OF_ORDER, details(today, today.plusDays(3))).kind shouldBe OUT_OF_ORDER
    }

    // --- Sobreposição ----------------------------------------------------------------------------------

    @Test
    fun `periods of the same room do not overlap, whatever their kind`() {
        val room = newRoom()
        val existing = open(room, OUT_OF_SERVICE, details(today.plusDays(2), today.plusDays(4)))

        for ((from, to) in listOf(
            today.plusDays(4) to today.plusDays(6),
            today to today.plusDays(2),
            today.plusDays(3) to today.plusDays(3),
            today to today.plusDays(9),
        )) {
            withClue("$from..$to") {
                refused<ConflictException>("OUT_OF_ORDER_OVERLAP") {
                    OutOfOrder.requireNoOverlap(listOf(existing), from, to)
                }
            }
        }
        // Encostados (o dia seguinte ao fim, o dia anterior ao início) não se sobrepõem.
        OutOfOrder.requireNoOverlap(listOf(existing), today.plusDays(5), today.plusDays(6))
        OutOfOrder.requireNoOverlap(listOf(existing), today, today.plusDays(1))
    }

    @Test
    fun `a released period only blocks the days before its release`() {
        val room = newRoom()
        val inEffect = open(room, OUT_OF_ORDER, details(today, today.plusDays(4)))
        val future = open(room, OUT_OF_ORDER, details(today.plusDays(10), today.plusDays(12)))
        val later = today.plusDays(2)

        inEffect.release(later, t1, user) shouldBe OutOfOrderRelease.RETURNED
        inEffect.releasedOn shouldBe later
        inEffect.blockedUntil shouldBe later.minusDays(1)
        inEffect.blocks(later.minusDays(1)) shouldBe true
        inEffect.blocks(later) shouldBe false
        OutOfOrder.requireNoOverlap(listOf(inEffect), later, later.plusDays(3))
        refused<ConflictException>("OUT_OF_ORDER_OVERLAP") {
            OutOfOrder.requireNoOverlap(listOf(inEffect), later.minusDays(1), later)
        }

        // Liberado antes de começar: cancelado, não bloqueia nada.
        future.release(today, t1, user) shouldBe OutOfOrderRelease.CANCELLED
        future.blockedUntil.shouldBeNull()
        (today.plusDays(9)..today.plusDays(13)).none { future.blocks(it) } shouldBe true
        OutOfOrder.requireNoOverlap(listOf(future), today.plusDays(10), today.plusDays(12))
    }

    // --- Edição ------------------------------------------------------------------------------------

    @Test
    fun `editing keeps the rules of the period and refuses a released one`() {
        val room = newRoom()
        val period = open(room, OUT_OF_ORDER, details(today.plusDays(1), today.plusDays(3)))

        period.update(room, details(today.plusDays(1), today.plusDays(3)), today, t1) shouldBe false
        period.update(room, details(today, today.plusDays(5), reason = "Troca do piso"), today, t1) shouldBe true
        period.startsOn shouldBe today
        period.endsOn shouldBe today.plusDays(5)
        period.reason shouldBe "Troca do piso"
        period.updatedAt shouldBe t1
        refused<BusinessRuleException>("OUT_OF_ORDER_PERIOD_IN_PAST") {
            period.update(room, details(today.minusDays(1), today.plusDays(5)), today, t1)
        }
        refused<InvalidInputException>("OUT_OF_ORDER_PERIOD_INVALID") {
            period.update(room, details(today, today.minusDays(1)), today.minusDays(5), t1)
        }

        // Já começado: o início fica (é passado), o fim só vai para hoje ou depois; outros campos mudam.
        val tomorrow = today.plusDays(1)
        period.update(room, details(today, today.plusDays(5), responsible = "Empresa X"), tomorrow, t1) shouldBe
            true
        refused<BusinessRuleException>("OUT_OF_ORDER_PERIOD_IN_PAST") {
            period.update(room, details(today, today), tomorrow, t1)
        }
        period.update(room, details(today, tomorrow), tomorrow, t1) shouldBe true

        period.release(tomorrow, t1, user)
        refused<BusinessRuleException>("OUT_OF_ORDER_RELEASED") {
            period.update(room, details(today, today.plusDays(9)), tomorrow, t1)
        }
    }

    @Test
    fun `an OOO edited to cover the current date is refused in an occupied room`() {
        val room = newRoom()
        val period = open(room, OUT_OF_ORDER, details(today.plusDays(2), today.plusDays(3)))
        room.markOccupied(1, t0)
        refused<BusinessRuleException>("OUT_OF_ORDER_ROOM_OCCUPIED") {
            period.update(room, details(today, today.plusDays(3)), today, t1)
        }
        // Sem passar a cobrir hoje, a edição vale.
        period.update(room, details(today.plusDays(1), today.plusDays(4)), today, t1) shouldBe true
        val oos = open(room, OUT_OF_SERVICE, details(today.plusDays(6), today.plusDays(7)))
        oos.update(room, details(today, today.plusDays(1)), today, t1) shouldBe true
    }

    // --- Liberação e status de retorno ---------------------------------------------------------------

    @Test
    fun `releasing a period in effect returns the room, the others only end it, and a second release does nothing`() {
        val room = newRoom()
        val inEffect = open(room, OUT_OF_ORDER, details(today, today.plusDays(1)))
        inEffect.release(today, t1, user) shouldBe OutOfOrderRelease.RETURNED
        inEffect.releasedAt shouldBe t1
        inEffect.releasedBy shouldBe user
        inEffect.release(today, t1, user) shouldBe OutOfOrderRelease.ALREADY_RELEASED
        inEffect.releasedOn shouldBe today

        val ended = open(room, OUT_OF_SERVICE, details(today.plusDays(2), today.plusDays(3)))
        ended.release(today.plusDays(4), t1, null) shouldBe OutOfOrderRelease.ENDED
        ended.releasedBy.shouldBeNull()
        ended.blockedUntil shouldBe today.plusDays(3)
    }

    @Test
    fun `the return status replaces the housekeeping status, whatever it was`() {
        for (target in HousekeepingStatus.entries) {
            val pending = newRoom().also { it.markClean(requiresInspection = true, now = t0) }
            withClue("limpa com inspeção pendente → $target") {
                pending.applyReturnStatus(target, t1) shouldContainExactly
                    buildList {
                        if (target != CLEAN) add(RoomStatusFieldChange(HOUSEKEEPING_STATUS, "CLEAN", target.name))
                        add(RoomStatusFieldChange(INSPECTION_PENDING, "true", "false"))
                    }
                pending.housekeepingStatus shouldBe target
                pending.inspectionPending shouldBe false
            }
        }
        val dirty = newRoom()
        dirty.applyReturnStatus(INSPECTED, t1) shouldContainExactly
            listOf(RoomStatusFieldChange(HOUSEKEEPING_STATUS, "DIRTY", "INSPECTED"))
        dirty.applyReturnStatus(INSPECTED, t1).shouldBeEmpty()
    }

    // --- RN-05 -------------------------------------------------------------------------------------

    @Test
    fun `a room in OOO is never ready for check-in, a room in OOS may be (RN-05)`() {
        val inspected =
            newRoom().also {
                it.markClean(requiresInspection = false, now = t0)
                it.markInspected(t0)
            }
        inspected.isReadyForCheckIn(outOfOrder = null) shouldBe true
        inspected.isReadyForCheckIn(outOfOrder = OUT_OF_SERVICE) shouldBe true
        inspected.isReadyForCheckIn(outOfOrder = OUT_OF_ORDER) shouldBe false
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun newRoom(): Room = Room.create(RoomDetails("101", null, RoomTypeId.new(), emptySet(), null), t0)

    private fun details(
        startsOn: LocalDate,
        endsOn: LocalDate,
        reason: String = "Vazamento no banheiro",
        responsible: String = "Equipe de manutenção",
        returnStatus: HousekeepingStatus = DIRTY,
    ) = OutOfOrderDetails(startsOn, endsOn, reason, responsible, returnStatus)

    private fun open(
        room: Room,
        kind: OutOfOrderKind,
        details: OutOfOrderDetails,
    ): OutOfOrder = OutOfOrder.open(room, kind, details, today, t0, user)

    private inline fun <reified T : DomainException> refused(
        code: String,
        block: () -> Unit,
    ) {
        shouldThrow<T>(block).code shouldBe ErrorCode(code)
    }

    private operator fun LocalDate.rangeTo(end: LocalDate): List<LocalDate> =
        generateSequence(this) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}
