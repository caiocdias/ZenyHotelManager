package com.zeny.hotel.guests

import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestDocument
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

/** Ataques adicionais à descrição do gênero (M5 E15, Task 1): transições, atomicidade e mesclagem com dois OTHER. */
class GuestGenderDescriptionDomainTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val operator = UserId(UuidV7.generate())
    private val byHash: (GuestDocument, GuestDocument) -> Boolean = { a, b -> a.numberIndex == b.numberIndex }

    private fun other(description: String?) =
        Guest.create(GuestProfile("Maria", gender = Gender.OTHER, genderDescription = description), now)

    private fun Guest.merge(
        merged: Guest,
        vararg take: GuestMergeField,
    ) = absorb(merged, take.toSet(), byHash, operator, now)

    @Test
    fun `changing from OTHER to another gender with no description clears the description`() {
        val guest = other("Pessoa não binária")

        val changed = guest.update(GuestProfile("Maria", gender = Gender.MALE), now)

        guest.gender shouldBe Gender.MALE
        guest.genderDescription.shouldBeNull()
        changed shouldContainExactlyInAnyOrder listOf("gender", "genderDescription")
    }

    @Test
    fun `updating with the same gender and description changes nothing`() {
        val guest = other("Pessoa não binária")

        guest
            .update(GuestProfile("Maria", gender = Gender.OTHER, genderDescription = " Pessoa não binária "), now)
            .shouldBeEmpty()
    }

    @Test
    fun `clearing the description of an OTHER profile is a change to null`() {
        val guest = other("Pessoa não binária")

        guest.update(GuestProfile("Maria", gender = Gender.OTHER), now) shouldBe listOf("genderDescription")
        guest.genderDescription.shouldBeNull()
    }

    @Test
    fun `a refused description leaves every other field untouched`() {
        val guest = Guest.create(GuestProfile("Maria", gender = Gender.FEMALE, email = "a@example.com"), now)

        shouldThrow<InvalidInputException> {
            guest.update(
                GuestProfile("Outra Pessoa", gender = Gender.FEMALE, email = "b@example.com", genderDescription = "x"),
                now,
            )
        }

        guest.fullName shouldBe "Maria"
        guest.email shouldBe "a@example.com"
        guest.genderDescription.shouldBeNull()
    }

    @Test
    fun `the description is trimmed before the length limit`() {
        other(" " + "a".repeat(100) + " ").genderDescription shouldBe "a".repeat(100)
    }

    @Test
    fun `the length error does not repeat the description`() {
        val error = shouldThrow<InvalidInputException> { other("segredo" + "a".repeat(100)) }

        error.message shouldNotContain "segredo"
        error.violations.forEach { it.message shouldNotContain "segredo" }
    }

    @Test
    fun `merge with GENDER takes the description of the merged OTHER profile`() {
        val survivor = other("Do sobrevivente")

        survivor.merge(other("Do absorvido"), GuestMergeField.GENDER)

        survivor.gender shouldBe Gender.OTHER
        survivor.genderDescription shouldBe "Do absorvido"
    }

    @Test
    fun `merge with GENDER keeps survivor OTHER and description when the merged has no gender`() {
        val survivor = other("Do sobrevivente")

        survivor.merge(Guest.create(GuestProfile("Maria"), now), GuestMergeField.GENDER)

        survivor.gender shouldBe Gender.OTHER
        survivor.genderDescription shouldBe "Do sobrevivente"
    }

    @Test
    fun `merge with GENDER replaces a MALE survivor by the merged OTHER with its description`() {
        val survivor = Guest.create(GuestProfile("Maria", gender = Gender.MALE), now)

        survivor.merge(other("Do absorvido"), GuestMergeField.GENDER)

        survivor.gender shouldBe Gender.OTHER
        survivor.genderDescription shouldBe "Do absorvido"
    }

    @Test
    fun `anonymizing an absorbed profile clears its gender description`() {
        val survivor = Guest.create(GuestProfile("Maria"), now)
        val absorbed = other("Pessoa não binária")
        survivor.merge(absorbed)

        absorbed.anonymize(operator, now.plusSeconds(1))

        absorbed.genderDescription.shouldBeNull()
    }
}
