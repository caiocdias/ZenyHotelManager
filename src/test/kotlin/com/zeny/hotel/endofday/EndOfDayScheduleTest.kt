package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDaySchedule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Quando o fechamento automático de uma data operacional vence (M6 E10, Task 12): horário antes do meio-dia é a
 * madrugada seguinte; do meio-dia em diante, o próprio dia; sempre no fuso do hotel.
 */
class EndOfDayScheduleTest {
    private val date = LocalDate.of(2026, 11, 12)

    @Test
    fun `03h00 in America Manaus is due at 07h00Z of the next day`() {
        EndOfDaySchedule.dueAt(date, LocalTime.of(3, 0), ZoneId.of("America/Manaus")) shouldBe
            Instant.parse("2026-11-13T07:00:00Z")
    }

    @Test
    fun `23h30 in Asia Tokyo is due the same day`() {
        EndOfDaySchedule.dueAt(date, LocalTime.of(23, 30), ZoneId.of("Asia/Tokyo")) shouldBe
            Instant.parse("2026-11-12T14:30:00Z")
    }

    @Test
    fun `12h00 is the same day`() {
        EndOfDaySchedule.dueAt(date, LocalTime.NOON, ZoneId.of("America/Sao_Paulo")) shouldBe
            Instant.parse("2026-11-12T15:00:00Z")
    }

    @Test
    fun `11h59 is the next day`() {
        EndOfDaySchedule.dueAt(date, LocalTime.of(11, 59), ZoneId.of("America/Sao_Paulo")) shouldBe
            Instant.parse("2026-11-13T14:59:00Z")
    }

    @Test
    fun `the JVM default zone does not matter`() {
        val original = TimeZone.getDefault()
        try {
            val dueAt =
                listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC").map {
                    TimeZone.setDefault(TimeZone.getTimeZone(it))
                    EndOfDaySchedule.dueAt(date, LocalTime.of(3, 0), ZoneId.of("America/Manaus"))
                }

            dueAt.toSet() shouldBe setOf(Instant.parse("2026-11-13T07:00:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
