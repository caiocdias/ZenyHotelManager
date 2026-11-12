package com.zeny.hotel.billing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** A descrição do dono da conta no extrato (M5 E11, Task 9): o nome do hóspede nunca sai em `toString`. */
class FolioOwnerDescriptionTest {
    private val description =
        FolioOwnerDescription(
            label = "RES-2026-0042",
            guestName = "Maria Responsável da Silva",
            roomNumber = "204",
            arrival = LocalDate.of(2026, 11, 20),
            departure = LocalDate.of(2026, 11, 23),
        )

    @Test
    fun `toString never carries the guest name, not even a part of it`() {
        val text = description.toString()

        text shouldNotContain "Maria"
        text shouldNotContain "Silva"
        text shouldContain "RES-2026-0042"
        text shouldContain "204"
    }

    @Test
    fun `toString of a description without a guest does not fail and the name stays readable by the property`() {
        FolioOwnerDescription("Grupo", null, null, null, null).toString() shouldContain "Grupo"
        description.guestName shouldBe "Maria Responsável da Silva"
    }

    @Test
    fun `a string template and a collection of descriptions do not leak the name either`() {
        "$description".contains("Maria") shouldBe false
        listOf(description).toString().contains("Maria") shouldBe false
        mapOf("k" to description).toString().contains("Maria") shouldBe false
    }
}
