package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.StorableRange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As regras da faxina programada no domínio (M6 E01, Task 3; D7): a próxima data, o local (exatamente um), as faixas,
 * o piso do `lastDoneOn`, a recusa do `lastDoneOn` no futuro, a reativação que mantém a próxima data e o `recordDone`,
 * que nunca faz o `lastDoneOn` voltar (Review Focus 5).
 */
class ScheduledCleaningDomainTest {
    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private val today = LocalDate.parse("2026-11-17")
    private val room = RoomId(UUID.randomUUID())
    private val area = AreaId(UUID.randomUUID())
    private val type = CleaningTypeId(UUID.randomUUID())

    private fun schedule(
        lastDoneOn: LocalDate? = null,
        frequencyDays: Int = 7,
    ) = ScheduledCleaning.create(ScheduledCleaningInput(room, null, type, frequencyDays, lastDoneOn), today, now)

    @Test
    fun `the next date is the last one plus the frequency or, without the last one, the business date`() {
        val done = schedule(lastDoneOn = LocalDate.parse("2026-11-10"), frequencyDays = 30)
        done.nextDueOn shouldBe LocalDate.parse("2026-12-10")
        done.lastDoneOn shouldBe LocalDate.parse("2026-11-10")
        done.active shouldBe true
        done.roomId shouldBe room
        done.areaId shouldBe null

        val never = schedule()
        never.nextDueOn shouldBe today
        never.lastDoneOn shouldBe null

        // Feita hoje: vale (só depois da data operacional é recusada).
        schedule(lastDoneOn = today, frequencyDays = 1).nextDueOn shouldBe today.plusDays(1)
    }

    @Test
    fun `the location must be exactly one, the frequency from 1 to 365 and the last date at least the floor`() {
        val cases =
            listOf(
                ScheduledCleaningInput(room, area, type, 7, null) to "SCHEDULED_CLEANING_LOCATION_INVALID",
                ScheduledCleaningInput(null, null, type, 7, null) to "SCHEDULED_CLEANING_LOCATION_INVALID",
                ScheduledCleaningInput(room, null, type, 0, null) to "HOUSEKEEPING_DATA_INVALID",
                ScheduledCleaningInput(room, null, type, 366, null) to "HOUSEKEEPING_DATA_INVALID",
                ScheduledCleaningInput(room, null, type, 7, StorableRange.MIN_DATE.minusDays(1)) to
                    "VALIDATION_FAILED",
            )
        cases.forEach { (input, code) ->
            withClue(input) {
                shouldThrow<InvalidInputException> { ScheduledCleaning.validate(input) }.code.value shouldBe code
                shouldThrow<InvalidInputException> { ScheduledCleaning.create(input, today, now) }.code.value shouldBe
                    code
            }
        }
        val belowFloor = ScheduledCleaningInput(room, null, type, 7, LocalDate.of(-4713, 12, 31))
        val error = shouldThrow<InvalidInputException> { ScheduledCleaning.validate(belowFloor) }
        error.violations.map { it.field to it.code } shouldBe listOf("lastDoneOn" to "INVALID_VALUE")

        // Os extremos valem.
        ScheduledCleaning.validate(ScheduledCleaningInput(null, area, type, 1, StorableRange.MIN_DATE))
        ScheduledCleaning.create(ScheduledCleaningInput(null, area, type, 365, null), today, now).areaId shouldBe area
    }

    @Test
    fun `a last date after the business date is a business rule refusal`() {
        val error =
            shouldThrow<BusinessRuleException> { schedule(lastDoneOn = today.plusDays(1)) }
        error.code.value shouldBe "SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE"
    }

    @Test
    fun `the update changes only type and frequency and recalculates the next date`() {
        val withLast = schedule(lastDoneOn = LocalDate.parse("2026-11-01"), frequencyDays = 7)
        val other = CleaningTypeId(UUID.randomUUID())

        withLast.update(other, 10, today.plusDays(3), now.plusSeconds(60)) shouldBe true
        withLast.cleaningTypeId shouldBe other
        withLast.frequencyDays shouldBe 10
        withLast.nextDueOn shouldBe LocalDate.parse("2026-11-11")
        withLast.updatedAt shouldBe now.plusSeconds(60)
        withLast.update(other, 10, today.plusDays(4), now.plusSeconds(120)) shouldBe false
        withLast.updatedAt shouldBe now.plusSeconds(60)

        val withoutLast = schedule()
        withoutLast.update(type, 3, today.plusDays(2), now) shouldBe true
        withoutLast.nextDueOn shouldBe today.plusDays(2)

        shouldThrow<DomainException> { withoutLast.update(type, 0, today, now) }.code.value shouldBe
            "HOUSEKEEPING_DATA_INVALID"
    }

    @Test
    fun `reactivation keeps the old next date, even overdue`() {
        val schedule = schedule(lastDoneOn = LocalDate.parse("2026-10-01"), frequencyDays = 7)

        schedule.deactivate(now) shouldBe true
        schedule.deactivate(now) shouldBe false
        schedule.active shouldBe false
        schedule.reactivate(now.plusSeconds(1)) shouldBe true
        schedule.reactivate(now.plusSeconds(2)) shouldBe false
        schedule.active shouldBe true
        schedule.nextDueOn shouldBe LocalDate.parse("2026-10-08")
    }

    @Test
    fun `recordDone moves the last date forward and never back`() {
        val schedule = schedule(lastDoneOn = LocalDate.parse("2026-11-10"), frequencyDays = 5)
        val later = now.plusSeconds(60)

        schedule.recordDone(LocalDate.parse("2026-11-12"), later) shouldBe true
        schedule.lastDoneOn shouldBe LocalDate.parse("2026-11-12")
        schedule.nextDueOn shouldBe LocalDate.parse("2026-11-17")
        schedule.updatedAt shouldBe later

        // Uma data anterior ou igual não muda nada (nem o updatedAt).
        schedule.recordDone(LocalDate.parse("2026-11-11"), later.plusSeconds(1)) shouldBe false
        schedule.recordDone(LocalDate.parse("2026-11-12"), later.plusSeconds(1)) shouldBe false
        schedule.lastDoneOn shouldBe LocalDate.parse("2026-11-12")
        schedule.nextDueOn shouldBe LocalDate.parse("2026-11-17")
        schedule.updatedAt shouldBe later

        // Sem data anterior, a primeira vale.
        val never = schedule()
        never.recordDone(LocalDate.parse("2026-11-16"), later) shouldBe true
        never.lastDoneOn shouldBe LocalDate.parse("2026-11-16")
        never.nextDueOn shouldBe LocalDate.parse("2026-11-23")
    }

    @Test
    fun `recordDone does not act on an inactive schedule (M6 E04, D10)`() {
        val schedule = schedule(lastDoneOn = LocalDate.parse("2026-11-10"), frequencyDays = 5)
        schedule.deactivate(now)

        schedule.recordDone(LocalDate.parse("2026-11-16"), now.plusSeconds(60)) shouldBe false
        schedule.lastDoneOn shouldBe LocalDate.parse("2026-11-10")
        schedule.nextDueOn shouldBe LocalDate.parse("2026-11-15")
        schedule.updatedAt shouldBe now
    }

    @Test
    fun `recordDone refuses a date, or a next date, outside the storable range (M6 E04, D10)`() {
        val schedule = schedule(frequencyDays = 5)
        listOf(StorableRange.MIN_DATE.minusDays(1), StorableRange.MAX_DATE, StorableRange.MAX_DATE.minusDays(4))
            .forEach { date ->
                withClue(date) {
                    val error = shouldThrow<InvalidInputException> { schedule.recordDone(date, now) }
                    error.code.value shouldBe "VALIDATION_FAILED"
                    error.violations.map { it.field to it.code } shouldBe listOf("date" to "INVALID_VALUE")
                }
            }
        schedule.lastDoneOn shouldBe null
        schedule.nextDueOn shouldBe today

        // O extremo que ainda cabe vale.
        schedule.recordDone(StorableRange.MAX_DATE.minusDays(5), now) shouldBe true
        schedule.nextDueOn shouldBe StorableRange.MAX_DATE
    }
}
