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
 * Ataques ao vencimento do fechamento automático (M6 E10, Task 12): as bordas do meio-dia e da meia-noite, as noites de
 * horário de verão (pulo e repetição), fusos com deslocamento de meia hora e de 45 minutos e o fuso da JVM. Os
 * instantes esperados são calculados à mão, pelo deslocamento do fuso naquela data.
 */
class EndOfDayScheduleAttackTest {
    private val date = LocalDate.of(2026, 11, 12)

    @Test
    fun `midnight is due at the start of the next day`() {
        EndOfDaySchedule.dueAt(date, LocalTime.MIDNIGHT, ZoneId.of("America/Sao_Paulo")) shouldBe
            Instant.parse("2026-11-13T03:00:00Z")
    }

    @Test
    fun `23h59 is due on the business date itself`() {
        EndOfDaySchedule.dueAt(date, LocalTime.of(23, 59), ZoneId.of("America/Sao_Paulo")) shouldBe
            Instant.parse("2026-11-13T02:59:00Z")
    }

    @Test
    fun `a time skipped by the spring-forward gap in New York moves forward by the gap`() {
        // 08/03/2026: 02:00 EST vira 03:00 EDT; 02:30 não existe e vira 03:30 EDT (UTC-4).
        EndOfDaySchedule.dueAt(LocalDate.of(2026, 3, 7), LocalTime.of(2, 30), ZoneId.of("America/New_York")) shouldBe
            Instant.parse("2026-03-08T07:30:00Z")
    }

    @Test
    fun `a time repeated by the fall-back overlap in New York is the first one`() {
        // 01/11/2026: 02:00 EDT vira 01:00 EST; 01:30 existe duas vezes, e a primeira é a EDT (UTC-4).
        EndOfDaySchedule.dueAt(LocalDate.of(2026, 10, 31), LocalTime.of(1, 30), ZoneId.of("America/New_York")) shouldBe
            Instant.parse("2026-11-01T05:30:00Z")
    }

    @Test
    fun `a time skipped by the spring-forward gap in Lisbon moves forward by the gap`() {
        // 29/03/2026: 01:00 WET vira 02:00 WEST; 01:30 não existe e vira 02:30 WEST (UTC+1).
        EndOfDaySchedule.dueAt(LocalDate.of(2026, 3, 28), LocalTime.of(1, 30), ZoneId.of("Europe/Lisbon")) shouldBe
            Instant.parse("2026-03-29T01:30:00Z")
    }

    @Test
    fun `a time repeated by the fall-back overlap in Lisbon is the first one`() {
        // 25/10/2026: 02:00 WEST vira 01:00 WET; 01:30 existe duas vezes, e a primeira é a WEST (UTC+1).
        EndOfDaySchedule.dueAt(LocalDate.of(2026, 10, 24), LocalTime.of(1, 30), ZoneId.of("Europe/Lisbon")) shouldBe
            Instant.parse("2026-10-25T00:30:00Z")
    }

    @Test
    fun `a half-hour offset zone is due on the UTC day of the business date`() {
        // Kolkata é UTC+5:30: 03:00 do dia 13 é 21:30Z do dia 12.
        EndOfDaySchedule.dueAt(date, LocalTime.of(3, 0), ZoneId.of("Asia/Kolkata")) shouldBe
            Instant.parse("2026-11-12T21:30:00Z")
    }

    @Test
    fun `a 45-minute offset zone keeps the minutes of the offset`() {
        // Eucla é UTC+8:45 o ano todo.
        EndOfDaySchedule.dueAt(date, LocalTime.of(23, 30), ZoneId.of("Australia/Eucla")) shouldBe
            Instant.parse("2026-11-12T14:45:00Z")
    }

    @Test
    fun `the noon border does not depend on the JVM default zone`() {
        val original = TimeZone.getDefault()
        try {
            val dueAt =
                listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kolkata").map {
                    TimeZone.setDefault(TimeZone.getTimeZone(it))
                    EndOfDaySchedule.dueAt(date, LocalTime.of(11, 59), ZoneId.of("America/Manaus")) to
                        EndOfDaySchedule.dueAt(date, LocalTime.NOON, ZoneId.of("America/Manaus"))
                }

            dueAt.toSet() shouldBe
                setOf(Instant.parse("2026-11-13T15:59:00Z") to Instant.parse("2026-11-12T16:00:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
