package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * As regras do tipo de limpeza no domínio (M6 E01, Task 1), a defesa que vale também fora da API (onde a Bean
 * Validation do corpo recusa antes): faixas, NUL no nome, créditos repetidos, código normalizado e a edição que não
 * muda nada.
 */
class CleaningTypeDomainTest {
    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private val roomType = RoomTypeId(UUID.randomUUID())

    private fun details(
        code: String = "x-1",
        name: String = " Saída ",
        defaultPriority: Int = 3,
        linenChangeEveryNights: Int? = null,
        credits: List<CleaningCredit> = listOf(CleaningCredit(roomType, 2)),
    ) = CleaningTypeDetails(code, name, false, defaultPriority, linenChangeEveryNights, null, credits)

    @Test
    fun `a new type normalizes the code and the name and starts active, without key`() {
        val type = CleaningType.create(details(), now)

        type.code shouldBe "X-1"
        type.name shouldBe "Saída"
        type.key shouldBe null
        type.active shouldBe true
        type.credits shouldBe listOf(CleaningCredit(roomType, 2))
    }

    @Test
    fun `values out of range, NUL in the name and repeated credits are refused with the field`() {
        val cases =
            listOf(
                details(defaultPriority = 0) to ("HOUSEKEEPING_DATA_INVALID" to "defaultPriority"),
                details(defaultPriority = 6) to ("HOUSEKEEPING_DATA_INVALID" to "defaultPriority"),
                details(linenChangeEveryNights = 0) to ("HOUSEKEEPING_DATA_INVALID" to "linenChangeEveryNights"),
                details(linenChangeEveryNights = 61) to ("HOUSEKEEPING_DATA_INVALID" to "linenChangeEveryNights"),
                details(credits = listOf(CleaningCredit(roomType, 0))) to
                    ("HOUSEKEEPING_DATA_INVALID" to "credits[0].credits"),
                details(credits = listOf(CleaningCredit(roomType, 101))) to
                    ("HOUSEKEEPING_DATA_INVALID" to "credits[0].credits"),
                details(credits = (1..201).map { CleaningCredit(RoomTypeId(UUID.randomUUID()), 1) }) to
                    ("HOUSEKEEPING_DATA_INVALID" to "credits"),
                details(credits = listOf(CleaningCredit(roomType, 1), CleaningCredit(roomType, 2))) to
                    ("CLEANING_TYPE_CREDIT_DUPLICATED" to "credits"),
                details(name = "Faxina\u0000pesada") to ("HOUSEKEEPING_DATA_INVALID" to "name"),
                details(name = "x".repeat(81)) to ("HOUSEKEEPING_DATA_INVALID" to "name"),
                details(code = "A\u0000") to ("HOUSEKEEPING_DATA_INVALID" to "code"),
                details(code = "ÁGUA") to ("HOUSEKEEPING_DATA_INVALID" to "code"),
            )

        cases.forEach { (invalid, expected) ->
            withClue(expected) {
                val error = shouldThrow<InvalidInputException> { CleaningType.create(invalid, now) }
                error.code.value shouldBe expected.first
                error.violations.map { it.field } shouldBe listOf(expected.second)
            }
        }
    }

    @Test
    fun `an edit that changes nothing reports no change, and one that does replaces the credits`() {
        val type = CleaningType.create(details(), now)
        val later = now.plusSeconds(60)
        val other = RoomTypeId(UUID.randomUUID())

        type.update(details(code = "X-1", name = "Saída"), later) shouldBe false
        type.updatedAt shouldBe now
        type.update(details(credits = listOf(CleaningCredit(other, 5))), later) shouldBe true
        type.credits shouldBe listOf(CleaningCredit(other, 5))
        type.updatedAt shouldBe later
        type.deactivate(later) shouldBe true
        type.deactivate(later) shouldBe false
        type.reactivate(later) shouldBe true
        type.reactivate(later) shouldBe false
    }
}
