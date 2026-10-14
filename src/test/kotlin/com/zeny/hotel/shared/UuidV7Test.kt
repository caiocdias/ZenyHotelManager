package com.zeny.hotel.shared

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** D15: UUIDv7 (RFC 9562) gerados na aplicação, ordenáveis por tempo. */
class UuidV7Test {
    @Test
    fun `generates version 7 and the RFC 9562 variant`() {
        val id = UuidV7.generate()

        id.version() shouldBe 7
        id.variant() shouldBe 2
        id.isVersion7() shouldBe true
        UUID.randomUUID().isVersion7() shouldBe false
    }

    @Test
    fun `embeds the clock time in milliseconds`() {
        val now = Instant.parse("2026-10-13T12:34:56.789Z")
        val id = UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC)).generate()

        id.v7Instant() shouldBe now
        (id.mostSignificantBits ushr 16) shouldBe now.toEpochMilli()
    }

    @Test
    fun `ids from later milliseconds sort after earlier ones`() {
        val clock = SteppingClock(Instant.parse("2026-10-13T00:00:00Z"))
        val generator = UuidV7Generator(clock)

        val ids =
            List(1_000) {
                clock.advanceMillis(1)
                generator.generate()
            }

        ids.assertStrictlyIncreasing()
    }

    @Test
    fun `ids within the same millisecond keep the generation order, even past the counter`() {
        // 10 000 ids no mesmo milissegundo esgotam o contador de 12 bits mais de uma vez.
        val generator = UuidV7Generator(Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC))

        List(10_000) { generator.generate() }.assertStrictlyIncreasing()
    }

    @Test
    fun `ids keep increasing when the clock goes backwards`() {
        val clock = SteppingClock(Instant.parse("2026-10-13T00:00:10Z"))
        val generator = UuidV7Generator(clock)

        val before = generator.generate()
        clock.advanceMillis(-5_000)
        val after = generator.generate()

        listOf(before, after).assertStrictlyIncreasing()
    }

    @Test
    fun `the default generator is monotonic and unique`() {
        val ids = List(50_000) { UuidV7.generate() }

        ids.toSet().size shouldBe ids.size
        ids.assertStrictlyIncreasing()
    }

    private fun List<UUID>.assertStrictlyIncreasing() {
        zipWithNext().forEach { (a, b) ->
            withClue("$a deveria vir antes de $b") {
                // compareTo do Java e ordem do texto (a mesma do tipo uuid do PostgreSQL).
                (a < b) shouldBe true
                (a.toString() < b.toString()) shouldBe true
            }
        }
    }

    private class SteppingClock(
        private var now: Instant,
    ) : Clock() {
        fun advanceMillis(millis: Long) {
            now = now.plusMillis(millis)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
