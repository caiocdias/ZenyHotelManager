package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhFieldCodes
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.FnrhGuestDocument
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.FieldViolationCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Ataques ao mapeamento e à sua composição com o validador. */
class FnrhPersonMapperAttackTest {
    private val catalog =
        object : FnrhDomainCatalog {
            private val data =
                mapOf(
                    FnrhDomain.GENDERS to setOf("HOMEM", "MULHER", "OUTRO", "NAOINFORMADO"),
                    FnrhDomain.RACES to setOf("AMARELA", "BRANCA", "INDIGENA", "PARDA", "PRETA", "NAOINFORMAR"),
                    FnrhDomain.DISABILITY_OPTIONS to setOf("SIM", "NAO", "NAOINFORMAR"),
                    FnrhDomain.DISABILITY_TYPES to setOf("FISICA", "VISUAL"),
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
    private val stay = FnrhGuest(true, Instant.parse("2026-11-10T15:00:00Z"), "LAZER_FERIAS", "AVIAO", null)
    private val saoPaulo =
        Address("Rua A", "10", null, "Centro", "São Paulo", BrazilianState.SP, "01310-100", "3550308")

    private fun guest(
        gender: Gender? = Gender.FEMALE,
        disability: String? = null,
        documents: List<FnrhGuestDocument> = listOf(FnrhGuestDocument(DocumentType.CPF, "BR", "52998224725")),
        address: Address? = saoPaulo,
        residence: String? = "BR",
    ) = FnrhGuestData(
        id = GuestId.new(),
        fullName = "Maria da Silva",
        socialName = null,
        nationality = "BR",
        birthDate = LocalDate.of(1990, 5, 10),
        gender = gender,
        email = null,
        phone = null,
        residenceCountry = residence,
        address = address,
        documents = documents,
        raceColor = RaceColor.BROWN,
        disability = disability,
    )

    private fun errors(data: FnrhGuestData) =
        validator
            .validate(FnrhPersonMapper.toPerson(data), stay, LocalDate.of(2026, 11, 10), true)
            .map { it.field to it.code }

    @Test
    fun `a full Brazilian profile mapped and validated has no errors`() {
        errors(guest()) shouldBe emptyList()
    }

    @Test
    fun `an unknown disability code becomes YES with that type and is rejected by the domain`() {
        val person = FnrhPersonMapper.toPerson(guest(disability = "XPTO"))
        person.disabilityOption shouldBe "SIM"
        person.disabilityType shouldBe "XPTO"
        errors(guest(disability = "XPTO")) shouldBe listOf("person.disabilityType" to FnrhFieldCodes.NOT_IN_DOMAIN)
    }

    @Test
    fun `each valid disability type maps to YES and passes`() {
        listOf("FISICA", "VISUAL").forEach {
            FnrhPersonMapper.toPerson(guest(disability = it)).disabilityOption shouldBe "SIM"
            errors(guest(disability = it)) shouldBe emptyList()
        }
        errors(guest(disability = "NAO")) shouldBe emptyList()
        errors(guest(disability = "NAOINFORMAR")) shouldBe emptyList()
    }

    @Test
    fun `gender OTHER from the profile always asks for a description`() {
        errors(guest(gender = Gender.OTHER)) shouldBe
            listOf("person.genderDescription" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `document types other than CPF and passport never become the FNRH document`() {
        val others = DocumentType.entries.filter { it != DocumentType.CPF && it != DocumentType.PASSPORT }
        others.isNotEmpty() shouldBe true
        others.forEach {
            val doc = FnrhGuestDocument(it, "BR", "99887766")
            FnrhPersonMapper.toPerson(guest(documents = listOf(doc))).document shouldBe null
            errors(guest(documents = listOf(doc))) shouldBe listOf("person.document" to FieldViolationCodes.REQUIRED)
        }
    }

    @Test
    fun `a blank passport is required and an all-equal CPF is invalid`() {
        val blank = listOf(FnrhGuestDocument(DocumentType.PASSPORT, "AR", "  "))
        errors(guest(documents = blank)) shouldBe listOf("person.document" to FieldViolationCodes.REQUIRED)
        val same = listOf(FnrhGuestDocument(DocumentType.CPF, "BR", "11111111111"))
        errors(guest(documents = same)) shouldBe listOf("person.document" to FieldViolationCodes.INVALID_VALUE)
    }

    @Test
    fun `a foreigner living abroad with a passport and no address is valid`() {
        val data =
            guest(
                documents = listOf(FnrhGuestDocument(DocumentType.PASSPORT, "AR", "X123456")),
                address = null,
                residence = "AR",
            )
        errors(data) shouldBe emptyList()
    }

    @Test
    fun `a Brazilian resident without address reports municipality and state as required`() {
        errors(guest(address = null)) shouldBe
            listOf("person.cityIbge" to FieldViolationCodes.REQUIRED, "person.state" to FieldViolationCodes.REQUIRED)
    }

    @Test
    fun `the formatted postal code is stored with digits only`() {
        FnrhPersonMapper.toPerson(guest()).postalCode shouldBe "01310100"
    }

    @Test
    fun `no toString of the mapped objects leaks personal data`() {
        val person = FnrhPersonMapper.toPerson(guest(disability = "VISUAL"))
        listOf(person.toString(), person.document.toString(), stay.toString()).forEach {
            listOf("Maria", "52998224725", "Rua A", "01310", "PARDA", "VISUAL", "3550308").forEach { v ->
                it shouldNotContain v
            }
        }
    }
}
