package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** D15: números legíveis com prefixo e ano, como `R-2026-000123`. */
class DocumentNumberTest {
    @Test
    fun `formats prefix, year and a zero-padded sequence`() {
        DocumentNumber("R", 2026, 123).toString() shouldBe "R-2026-000123"
        DocumentNumber("OS", 2027, 1).toString() shouldBe "OS-2027-000001"
        DocumentNumber("R", 2026, 1_234_567).toString() shouldBe "R-2026-1234567"
    }

    @Test
    fun `parses its own text`() {
        DocumentNumber.parse("R-2026-000123") shouldBe DocumentNumber("R", 2026, 123)
        DocumentNumber.parse("OS-2027-1234567") shouldBe DocumentNumber("OS", 2027, 1_234_567)
        shouldThrow<IllegalArgumentException> { DocumentNumber.parse("R-2026-123") }
        shouldThrow<IllegalArgumentException> { DocumentNumber.parse("r-2026-000123") }
        shouldThrow<IllegalArgumentException> { DocumentNumber.parse("R-26-000123") }
        shouldThrow<IllegalArgumentException> { DocumentNumber.parse("R2026000123") }
    }

    @Test
    fun `rejects invalid parts`() {
        shouldThrow<IllegalArgumentException> { DocumentNumber("r", 2026, 1) }
        shouldThrow<IllegalArgumentException> { DocumentNumber("ABCDEF", 2026, 1) }
        shouldThrow<IllegalArgumentException> { DocumentNumber("R", 1999, 1) }
        shouldThrow<IllegalArgumentException> { DocumentNumber("R", 10_000, 1) }
        shouldThrow<IllegalArgumentException> { DocumentNumber("R", 2026, 0) }
    }

    @Test
    fun `document types name a schema-qualified sequence and nothing else`() {
        val type = DocumentNumberType("R", "reservations.reservation_number_seq")
        type.sequence shouldBe "reservations.reservation_number_seq"

        shouldThrow<IllegalArgumentException> { DocumentNumberType("R", "reservation_number_seq") }
        shouldThrow<IllegalArgumentException> { DocumentNumberType("R", "a.b; DROP TABLE x") }
        shouldThrow<IllegalArgumentException> { DocumentNumberType("R", "Reservations.Seq") }
        shouldThrow<IllegalArgumentException> { DocumentNumberType("r1", "reservations.reservation_number_seq") }
    }
}
