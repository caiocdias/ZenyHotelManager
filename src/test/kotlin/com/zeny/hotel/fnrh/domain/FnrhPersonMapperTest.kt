package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.FnrhGuestDocument
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FnrhPersonMapperTest {
    private fun guest(
        gender: Gender? = null,
        raceColor: RaceColor? = null,
        disability: String? = null,
        documents: List<FnrhGuestDocument> = emptyList(),
        address: Address? = null,
    ) = FnrhGuestData(
        id = GuestId.new(),
        fullName = "Maria da Silva",
        socialName = "Mari",
        nationality = "BR",
        birthDate = LocalDate.of(1990, 5, 10),
        gender = gender,
        email = "maria@example.com",
        phone = "+5511999990000",
        residenceCountry = "BR",
        address = address,
        documents = documents,
        raceColor = raceColor,
        disability = disability,
    )

    @Test
    fun `maps every gender`() {
        FnrhPersonMapper.toPerson(guest(gender = Gender.FEMALE)).gender shouldBe "MULHER"
        FnrhPersonMapper.toPerson(guest(gender = Gender.MALE)).gender shouldBe "HOMEM"
        FnrhPersonMapper.toPerson(guest(gender = Gender.OTHER)).gender shouldBe "OUTRO"
        FnrhPersonMapper.toPerson(guest(gender = Gender.OTHER)).genderDescription shouldBe null
        FnrhPersonMapper.toPerson(guest(gender = Gender.NOT_INFORMED)).gender shouldBe "NAOINFORMADO"
        FnrhPersonMapper.toPerson(guest(gender = null)).gender shouldBe null
    }

    @Test
    fun `maps every race`() {
        val expected =
            mapOf(
                RaceColor.WHITE to "BRANCA",
                RaceColor.BLACK to "PRETA",
                RaceColor.BROWN to "PARDA",
                RaceColor.YELLOW to "AMARELA",
                RaceColor.INDIGENOUS to "INDIGENA",
                RaceColor.NOT_DECLARED to "NAOINFORMAR",
            )
        expected.forEach { (race, id) -> FnrhPersonMapper.toPerson(guest(raceColor = race)).race shouldBe id }
        FnrhPersonMapper.toPerson(guest(raceColor = null)).race shouldBe null
    }

    @Test
    fun `maps the disability`() {
        FnrhPersonMapper.toPerson(guest(disability = null)).let {
            it.disabilityOption shouldBe null
            it.disabilityType shouldBe null
        }
        FnrhPersonMapper.toPerson(guest(disability = "VISUAL")).let {
            it.disabilityOption shouldBe "SIM"
            it.disabilityType shouldBe "VISUAL"
        }
        FnrhPersonMapper.toPerson(guest(disability = "NAO")).let {
            it.disabilityOption shouldBe "NAO"
            it.disabilityType shouldBe null
        }
        FnrhPersonMapper.toPerson(guest(disability = "NAOINFORMAR")).let {
            it.disabilityOption shouldBe "NAOINFORMAR"
            it.disabilityType shouldBe null
        }
    }

    @Test
    fun `prefers the CPF to the passport and drops the other documents`() {
        val cpf = FnrhGuestDocument(DocumentType.CPF, "BR", "52998224725")
        val passport = FnrhGuestDocument(DocumentType.PASSPORT, "AR", "X123456")
        val rg = FnrhGuestDocument(DocumentType.RG, "BR", "123456789")

        FnrhPersonMapper.toPerson(guest(documents = listOf(passport, rg, cpf))).document shouldBe
            FnrhDocument(FnrhDocumentType.CPF, "52998224725")
        FnrhPersonMapper.toPerson(guest(documents = listOf(rg, passport))).document shouldBe
            FnrhDocument(FnrhDocumentType.PASSPORT, "X123456")
        FnrhPersonMapper.toPerson(guest(documents = listOf(rg))).document shouldBe null
    }

    @Test
    fun `maps the address and the basic data`() {
        val address = Address("Rua A", "10", "ap 2", "Centro", "São Paulo", BrazilianState.SP, "01310-100", "3550308")
        val person = FnrhPersonMapper.toPerson(guest(address = address))

        person.name shouldBe "Maria da Silva"
        person.socialName shouldBe "Mari"
        person.nationality shouldBe "BR"
        person.birthDate shouldBe LocalDate.of(1990, 5, 10)
        person.residenceCountry shouldBe "BR"
        person.postalCode shouldBe "01310100"
        person.street shouldBe "Rua A"
        person.number shouldBe "10"
        person.complement shouldBe "ap 2"
        person.neighborhood shouldBe "Centro"
        person.cityIbge shouldBe 3550308
        person.state shouldBe "SP"
    }

    @Test
    fun `leaves the address empty when the profile has none`() {
        val person = FnrhPersonMapper.toPerson(guest())

        person.postalCode shouldBe null
        person.cityIbge shouldBe null
        person.state shouldBe null
    }

    @Test
    fun `toString of the person and the document shows neither name nor number`() {
        val person =
            FnrhPersonMapper.toPerson(
                guest(documents = listOf(FnrhGuestDocument(DocumentType.CPF, "BR", "52998224725"))),
            )

        person.toString() shouldNotContain "Maria"
        person.document.toString() shouldNotContain "52998224725"
        person.toString() shouldNotContain "52998224725"
    }
}
