package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhFieldCodes
import com.zeny.hotel.shared.FieldViolationCodes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Ataques às bordas do validador local: idade, países, CEP, UF e documento. */
class FnrhLocalValidatorAttackTest {
    private val catalog =
        object : FnrhDomainCatalog {
            private val data =
                mapOf(
                    FnrhDomain.GENDERS to setOf("HOMEM", "MULHER", "OUTRO", "NAOINFORMADO"),
                    FnrhDomain.RACES to setOf("PARDA"),
                    FnrhDomain.DISABILITY_OPTIONS to setOf("SIM", "NAO", "NAOINFORMAR"),
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
    private val arrival = LocalDate.of(2026, 11, 10)
    private val person =
        FnrhPersonData(
            name = "Maria",
            nationality = "BR",
            gender = "MULHER",
            birthDate = LocalDate.of(1990, 5, 10),
            race = "PARDA",
            document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
            residenceCountry = "BR",
            cityIbge = 3550308,
            state = "SP",
        )
    private val stay = FnrhGuest(true, Instant.parse("2026-11-10T15:00:00Z"), "LAZER_FERIAS", "AVIAO", null)

    private fun errors(
        p: FnrhPersonData,
        responsibleKnown: Boolean = false,
    ) = validator.validate(p, stay, arrival, responsibleKnown).map { it.field to it.code }

    private val needsResponsible = listOf("guest.responsibleGuestId" to FieldViolationCodes.REQUIRED)

    @Test
    fun `the day before the eighteenth birthday is still a minor`() {
        errors(person.copy(birthDate = LocalDate.of(2008, 11, 11))) shouldBe needsResponsible
    }

    @Test
    fun `the eighteenth birthday itself and the day before it are adults`() {
        errors(person.copy(birthDate = LocalDate.of(2008, 11, 10))) shouldBe emptyList()
        errors(person.copy(birthDate = LocalDate.of(2008, 11, 9))) shouldBe emptyList()
    }

    @Test
    fun `a leap day birth is an adult by the arrival after the eighteenth year`() {
        errors(person.copy(birthDate = LocalDate.of(2008, 2, 29))) shouldBe emptyList()
    }

    @Test
    fun `a newborn without responsible is flagged`() {
        errors(person.copy(birthDate = arrival)) shouldBe needsResponsible
    }

    @Test
    fun `a minor with a known responsible has no error`() {
        errors(person.copy(birthDate = LocalDate.of(2015, 1, 1)), responsibleKnown = true) shouldBe emptyList()
    }

    @Test
    fun `lowercase nationality is not a valid country code`() {
        errors(person.copy(nationality = "br")) shouldBe listOf("person.nationality" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `nonexistent and malformed ISO codes are rejected`() {
        listOf("XX", "ZZ", "BRA", "B", "").forEach {
            errors(person.copy(nationality = it)) shouldBe
                listOf("person.nationality" to FnrhFieldCodes.NOT_IN_DOMAIN)
        }
    }

    @Test
    fun `a custom country set is honoured`() {
        val custom = FnrhLocalValidator(catalog, IbgeMunicipalities(), setOf("BR"))
        custom.validate(person.copy(nationality = "AR"), stay, arrival, true).map { it.field to it.code } shouldBe
            listOf("person.nationality" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `a Brazil residence without municipality is required even with a state`() {
        errors(person.copy(cityIbge = null)) shouldBe listOf("person.cityIbge" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `an unknown municipality does not also report the state as inconsistent`() {
        errors(person.copy(cityIbge = 9999999, state = "RJ")) shouldBe
            listOf("person.cityIbge" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `a lowercase state is inconsistent with the municipality`() {
        errors(person.copy(state = "sp")) shouldBe listOf("person.state" to FnrhFieldCodes.INCONSISTENT)
    }

    @Test
    fun `postal codes of seven and nine digits, with hyphen or letters are invalid`() {
        listOf("0131010", "013101000", "01310-100", "0131010a").forEach {
            val result =
                errors(person.copy(postalCode = it, street = "Rua A", number = "1", neighborhood = "Centro"))
            result shouldBe listOf("person.postalCode" to FieldViolationCodes.INVALID_VALUE)
        }
    }

    @Test
    fun `a postal code outside Brazil is not validated`() {
        errors(person.copy(residenceCountry = "AR", cityIbge = null, state = null, postalCode = "ABC")) shouldBe
            emptyList()
    }

    @Test
    fun `a foreign nationality residing in Brazil still needs the Brazilian address`() {
        errors(person.copy(nationality = "AR", cityIbge = null, state = null)) shouldBe
            listOf(
                "person.cityIbge" to FieldViolationCodes.REQUIRED,
                "person.state" to FieldViolationCodes.REQUIRED,
            )
    }

    @Test
    fun `an all-equal CPF is invalid and a blank passport is required`() {
        errors(person.copy(document = FnrhDocument(FnrhDocumentType.CPF, "11111111111"))) shouldBe
            listOf("person.document" to FieldViolationCodes.INVALID_VALUE)
        errors(person.copy(document = FnrhDocument(FnrhDocumentType.PASSPORT, ""))) shouldBe
            listOf("person.document" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `a passport is not checked as a CPF`() {
        errors(person.copy(document = FnrhDocument(FnrhDocumentType.PASSPORT, "X1"))) shouldBe emptyList()
    }

    @Test
    fun `a blank travel reason is required, not out of the domain`() {
        validator.validate(person, stay.copy(travelReason = " "), arrival, true).map { it.field to it.code } shouldBe
            listOf("guest.travelReason" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `every error of a very broken person carries only a field and a code`() {
        val broken = FnrhPersonData(name = "", residenceCountry = "BR")
        val result = validator.validate(broken, stay.copy(travelReason = null), arrival, false)
        result.isNotEmpty() shouldBe true
        result.forEach { it.toString() shouldBe "FnrhFieldError(field=${it.field}, code=${it.code})" }
    }
}
