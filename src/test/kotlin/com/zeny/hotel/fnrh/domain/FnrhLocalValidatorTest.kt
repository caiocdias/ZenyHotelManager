package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhFieldCodes
import com.zeny.hotel.shared.FieldViolationCodes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class FnrhLocalValidatorTest {
    private val catalog =
        object : FnrhDomainCatalog {
            private val data =
                mapOf(
                    FnrhDomain.GENDERS to setOf("HOMEM", "MULHER", "OUTRO", "NAOINFORMADO"),
                    FnrhDomain.RACES to setOf("AMARELA", "BRANCA", "INDIGENA", "PARDA", "PRETA", "NAOINFORMAR"),
                    FnrhDomain.DISABILITY_OPTIONS to setOf("SIM", "NAO", "NAOINFORMAR"),
                    FnrhDomain.DISABILITY_TYPES to setOf("FISICA", "VISUAL"),
                    FnrhDomain.TRAVEL_REASONS to setOf("LAZER_FERIAS", "NEGOCIOS"),
                    FnrhDomain.TRANSPORT_MODES to setOf("AVIAO", "ONIBUS"),
                )

            override fun contains(
                domain: FnrhDomain,
                code: String,
            ) = data[domain].orEmpty().contains(code)

            override fun values(domain: FnrhDomain) = data[domain].orEmpty().map { FnrhDomainValue(it, it) }
        }
    private val validator = FnrhLocalValidator(catalog, IbgeMunicipalities())
    private val arrival = LocalDate.of(2026, 11, 10)

    private val validPerson =
        FnrhPersonData(
            name = "Maria da Silva",
            nationality = "BR",
            gender = "MULHER",
            birthDate = LocalDate.of(1990, 5, 10),
            race = "PARDA",
            document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
            residenceCountry = "BR",
            cityIbge = 3550308,
            state = "SP",
        )
    private val validGuest =
        FnrhGuest(
            principal = true,
            checkedInAt = Instant.parse("2026-11-10T15:00:00Z"),
            travelReason = "LAZER_FERIAS",
            transportMode = "AVIAO",
            responsibleGuestId = null,
        )

    private fun run(
        person: FnrhPersonData = validPerson,
        guest: FnrhGuest = validGuest,
        responsibleKnown: Boolean = true,
    ) = validator.validate(person, guest, arrival, responsibleKnown)

    private fun errors(
        person: FnrhPersonData = validPerson,
        guest: FnrhGuest = validGuest,
        responsibleKnown: Boolean = true,
    ) = run(person, guest, responsibleKnown).map { it.field to it.code }

    @Test
    fun `a valid Brazilian guest has no errors`() {
        run().shouldBeEmpty()
    }

    @Test
    fun `a CPF with a wrong check digit is invalid`() {
        errors(validPerson.copy(document = FnrhDocument(FnrhDocumentType.CPF, "52998224724"))) shouldBe
            listOf("person.document" to FieldViolationCodes.INVALID_VALUE)
    }

    @Test
    fun `no document is required`() {
        errors(validPerson.copy(document = null)) shouldBe listOf("person.document" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `a name is required`() {
        errors(validPerson.copy(name = " ")) shouldBe listOf("person.name" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `an unknown nationality is out of the domain`() {
        errors(validPerson.copy(nationality = "XX")) shouldBe
            listOf("person.nationality" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `an unknown residence country is out of the domain`() {
        errors(validPerson.copy(residenceCountry = "XX")) shouldBe
            listOf("person.residenceCountry" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `a municipality that does not exist is out of the domain`() {
        errors(validPerson.copy(cityIbge = 9999999)) shouldBe
            listOf("person.cityIbge" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `a state that does not match the municipality is inconsistent`() {
        errors(validPerson.copy(state = "RJ")) shouldBe listOf("person.state" to FnrhFieldCodes.INCONSISTENT)
    }

    @Test
    fun `residing in Brazil requires municipality and state`() {
        errors(validPerson.copy(cityIbge = null, state = null)) shouldBe
            listOf(
                "person.cityIbge" to FieldViolationCodes.REQUIRED,
                "person.state" to FieldViolationCodes.REQUIRED,
            )
    }

    @Test
    fun `residing abroad needs no municipality`() {
        errors(validPerson.copy(residenceCountry = "AR", cityIbge = null, state = null)) shouldBe emptyList()
    }

    @Test
    fun `a postal code needs eight digits and the rest of the address`() {
        errors(validPerson.copy(postalCode = "123")) shouldBe
            listOf(
                "person.postalCode" to FieldViolationCodes.INVALID_VALUE,
                "person.street" to FieldViolationCodes.REQUIRED,
                "person.number" to FieldViolationCodes.REQUIRED,
                "person.neighborhood" to FieldViolationCodes.REQUIRED,
            )
        errors(
            validPerson.copy(postalCode = "01310100", street = "Rua A", number = "10", neighborhood = "Centro"),
        ) shouldBe emptyList()
    }

    @Test
    fun `an unknown transport mode is out of the domain`() {
        errors(guest = validGuest.copy(transportMode = "TELEPORTE")) shouldBe
            listOf("guest.transportMode" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `travel reason and transport mode are required`() {
        errors(guest = validGuest.copy(travelReason = null, transportMode = null)) shouldBe
            listOf(
                "guest.travelReason" to FieldViolationCodes.REQUIRED,
                "guest.transportMode" to FieldViolationCodes.REQUIRED,
            )
    }

    @Test
    fun `an unknown travel reason is out of the domain`() {
        errors(guest = validGuest.copy(travelReason = "FERIAS")) shouldBe
            listOf("guest.travelReason" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `a minor without a known responsible needs one`() {
        val minor = validPerson.copy(birthDate = LocalDate.of(2010, 11, 11))
        errors(minor, responsibleKnown = false) shouldBe
            listOf("guest.responsibleGuestId" to FieldViolationCodes.REQUIRED)
        errors(minor, responsibleKnown = true) shouldBe emptyList()
    }

    @Test
    fun `a person who turns eighteen on the arrival day is an adult`() {
        errors(validPerson.copy(birthDate = LocalDate.of(2008, 11, 10)), responsibleKnown = false) shouldBe emptyList()
    }

    @Test
    fun `gender OTHER requires a description`() {
        errors(validPerson.copy(gender = "OUTRO")) shouldBe
            listOf("person.genderDescription" to FieldViolationCodes.REQUIRED)
        errors(validPerson.copy(gender = "OUTRO", genderDescription = "Não binário")) shouldBe emptyList()
    }

    @Test
    fun `disability YES requires a type`() {
        errors(validPerson.copy(disabilityOption = "SIM")) shouldBe
            listOf("person.disabilityType" to FieldViolationCodes.REQUIRED)
        errors(validPerson.copy(disabilityOption = "SIM", disabilityType = "VISUAL")) shouldBe emptyList()
        errors(validPerson.copy(disabilityOption = "SIM", disabilityType = "XPTO")) shouldBe
            listOf("person.disabilityType" to FnrhFieldCodes.NOT_IN_DOMAIN)
        errors(validPerson.copy(disabilityOption = "XPTO")) shouldBe
            listOf("person.disabilityOption" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `birth date, gender and race are required`() {
        errors(validPerson.copy(birthDate = null, gender = null, race = null)) shouldBe
            listOf(
                "person.birthDate" to FieldViolationCodes.REQUIRED,
                "person.gender" to FieldViolationCodes.REQUIRED,
                "person.race" to FieldViolationCodes.REQUIRED,
            )
    }

    @Test
    fun `a gender or race outside the domain is rejected`() {
        errors(validPerson.copy(gender = "XPTO", race = "XPTO")) shouldBe
            listOf(
                "person.gender" to FnrhFieldCodes.NOT_IN_DOMAIN,
                "person.race" to FnrhFieldCodes.NOT_IN_DOMAIN,
            )
    }

    @Test
    fun `errors never carry the rejected value`() {
        val hostile =
            validPerson.copy(
                name = "Fulano Secreto",
                nationality = "ZZSECRETO",
                document = FnrhDocument(FnrhDocumentType.CPF, "00000000099"),
                cityIbge = 9999999,
                postalCode = "SEGREDO",
            )
        val result = run(hostile, validGuest.copy(transportMode = "TELEPORTE"))

        result.isNotEmpty() shouldBe true
        result.forEach {
            val text = it.toString()
            listOf("ZZSECRETO", "00000000099", "9999999", "SEGREDO", "TELEPORTE", "Fulano").forEach { v ->
                text shouldNotContain v
            }
        }
    }
}
