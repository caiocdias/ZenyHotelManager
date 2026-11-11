package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.shared.FieldViolationCodes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Descrição de gênero da FNRH (M5 E15, Task 5): do perfil ao `FnrhPersonData` e à validação local. */
class FnrhPersonMapperGenderDescriptionTest {
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
    fun `OTHER with a description maps the OUTRO id and the description`() {
        val person = FnrhPersonMapper.toPerson(guest(Gender.OTHER, "Não binário"))

        person.gender shouldBe "OUTRO"
        person.genderDescription shouldBe "Não binário"
    }

    @Test
    fun `another gender never carries a description`() {
        listOf(Gender.FEMALE, Gender.MALE, Gender.NOT_INFORMED).forEach {
            FnrhPersonMapper.toPerson(guest(it, "Não binário")).genderDescription shouldBe null
        }
    }

    @Test
    fun `OTHER with a description has no genderDescription error`() {
        errors(guest(Gender.OTHER, "Não binário")) shouldBe emptyList()
    }

    @Test
    fun `OTHER without a description still requires it`() {
        errors(guest(Gender.OTHER, null)) shouldBe
            listOf("person.genderDescription" to FieldViolationCodes.REQUIRED)
    }
}
