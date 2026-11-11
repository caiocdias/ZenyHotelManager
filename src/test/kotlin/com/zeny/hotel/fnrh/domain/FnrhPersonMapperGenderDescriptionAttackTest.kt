package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.shared.FieldViolationCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Ataque ao mapeador e ao validador da descrição de gênero (M5 E15, Task 5): só espaços, `toString` e o valor cru. */
class FnrhPersonMapperGenderDescriptionAttackTest {
    private val catalog =
        object : FnrhDomainCatalog {
            private val data =
                mapOf(
                    FnrhDomain.GENDERS to setOf("HOMEM", "MULHER", "OUTRO", "NAOINFORMADO"),
                    FnrhDomain.RACES to setOf("NAOINFORMAR"),
                    FnrhDomain.DISABILITY_OPTIONS to setOf("NAO"),
                    FnrhDomain.DISABILITY_TYPES to setOf("VISUAL"),
                    FnrhDomain.TRAVEL_REASONS to setOf("LAZER_FERIAS"),
                    FnrhDomain.TRANSPORT_MODES to setOf("AVIAO"),
                )

            override fun contains(
                domain: FnrhDomain,
                code: String,
            ) = data[domain].orEmpty().contains(code)

            override fun values(domain: FnrhDomain) = data[domain].orEmpty().map { FnrhDomainValue(it, it) }
        }
    private val validator = FnrhLocalValidator(catalog, IbgeMunicipalities())
    private val fnrhGuest =
        FnrhGuest(true, Instant.parse("2026-11-10T15:00:00Z"), "LAZER_FERIAS", "AVIAO", null)

    private fun guest(
        gender: Gender?,
        description: String?,
    ) = FnrhGuestData(
        id = GuestId.new(),
        fullName = "Maria da Silva",
        socialName = null,
        nationality = "BR",
        birthDate = LocalDate.of(1990, 5, 10),
        gender = gender,
        genderDescription = description,
        email = null,
        phone = null,
        residenceCountry = "BR",
        address = null,
        documents = emptyList(),
        raceColor = null,
        disability = null,
    )

    private fun errors(guest: FnrhGuestData) =
        validator
            .validate(FnrhPersonMapper.toPerson(guest), fnrhGuest, LocalDate.of(2026, 11, 10), true)
            .filter { it.field == "person.genderDescription" }
            .map { it.field to it.code }

    @Test
    fun `OTHER with a whitespace-only description still reports genderDescription REQUIRED`() {
        listOf(" ", "   ", "\t", " \n ", "\u00A0").forEach {
            errors(guest(Gender.OTHER, it)) shouldBe
                listOf("person.genderDescription" to FieldViolationCodes.REQUIRED)
        }
    }

    @Test
    fun `OTHER with an empty description still reports genderDescription REQUIRED`() {
        errors(guest(Gender.OTHER, "")) shouldBe listOf("person.genderDescription" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `a description with a gender other than OTHER is dropped and never reported`() {
        listOf(Gender.FEMALE, Gender.MALE, Gender.NOT_INFORMED).forEach {
            errors(guest(it, "Pessoa não binária")) shouldBe emptyList()
        }
    }

    @Test
    fun `a null gender never carries a description`() {
        FnrhPersonMapper.toPerson(guest(null, "Pessoa não binária")).genderDescription shouldBe null
    }

    @Test
    fun `the mapper keeps the description as it came, with quotes, backslash and control characters`() {
        val raw = "Ele \"dito\" C:\\x\u0001y\u0000z"

        FnrhPersonMapper.toPerson(guest(Gender.OTHER, raw)).genderDescription shouldBe raw
    }

    @Test
    fun `FnrhPersonData and FnrhGuestData toString never show the description`() {
        val data = guest(Gender.OTHER, "Pessoa não binária")

        data.toString() shouldNotContain "binária"
        data.toString() shouldBe "FnrhGuestData(id=${data.id})"
        val person = FnrhPersonMapper.toPerson(data)
        person.toString() shouldNotContain "binária"
        person.toString() shouldNotContain "OUTRO"
        person.toString() shouldBe "FnrhPersonData(***)"
    }
}
