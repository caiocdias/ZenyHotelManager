package com.zeny.hotel.guests

import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.guests.domain.GuestsRules
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.domain.SensitiveGuestData
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BlindIndexValue
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Regras do perfil de hóspede (M3 E01): maioridade nos limites (RN-24), documentos por tipo e país, máscara
 * (H9), normalização para a busca, dados sensíveis num valor de tamanho fixo (RN-19), endereço só de residente no
 * Brasil e a lista dos campos alterados (H10).
 */
class GuestsDomainTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")

    private fun guest(
        birthDate: LocalDate? = null,
        residenceCountry: String? = null,
        address: Address? = null,
    ) = Guest.create(
        GuestProfile("Maria da Silva", birthDate = birthDate, residenceCountry = residenceCountry, address = address),
        now,
    )

    // --- RN-24 --------------------------------------------------------------------------------------------

    @Test
    fun `a guest is a minor until the 18th birthday, inclusive of the day before`() {
        val guest = guest(birthDate = LocalDate.of(2008, 10, 16))

        guest.isMinorOn(LocalDate.of(2026, 10, 15)) shouldBe true
        guest.isMinorOn(LocalDate.of(2026, 10, 16)) shouldBe false
        guest.isMinorOn(LocalDate.of(2026, 10, 17)) shouldBe false
        guest.isMinorOn(LocalDate.of(2008, 10, 16)) shouldBe true
    }

    @Test
    fun `someone born on February 29 comes of age on March 1 of a non-leap year, and unknown age is null`() {
        val leap = guest(birthDate = LocalDate.of(2008, 2, 29))

        leap.isMinorOn(LocalDate.of(2026, 2, 28)) shouldBe true
        leap.isMinorOn(LocalDate.of(2026, 3, 1)) shouldBe false
        guest().isMinorOn(LocalDate.of(2026, 3, 1)).shouldBeNull()
    }

    @Test
    fun `a birth date in the future or before 1900 is refused`() {
        shouldThrow<InvalidInputException> { guest(birthDate = LocalDate.of(2026, 10, 20)) }.code.value shouldBe
            "BIRTH_DATE_INVALID"
        shouldThrow<InvalidInputException> { guest(birthDate = LocalDate.of(1899, 12, 31)) }.code.value shouldBe
            "BIRTH_DATE_INVALID"
    }

    // --- Documentos -----------------------------------------------------------------------------------------

    @Test
    fun `a CPF is validated by its check digits and normalized to digits, always issued in BR`() {
        val formatted = IdDocuments.normalize(DocumentType.CPF, null, " 529.982.247-25 ")
        val digits = IdDocuments.normalize(DocumentType.CPF, "br", "52998224725")

        formatted.number shouldBe "52998224725"
        formatted.issuingCountry shouldBe "BR"
        formatted.indexInput shouldBe digits.indexInput
        formatted.indexInput shouldBe "CPF:BR:52998224725"
        formatted.masked shouldBe "***4725"
        formatted.toString() shouldNotContain "52998224725"

        val wrongDigit =
            shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.CPF, null, "52998224724") }
        wrongDigit.code.value shouldBe "DOCUMENT_NUMBER_INVALID"
        wrongDigit.violations.map { it.field } shouldContainExactly listOf("number")
        wrongDigit.message shouldNotContain "52998224724"
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.CPF, null, "111.111.111-11") }
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.CPF, "AR", "52998224725") }
            .code.value shouldBe "DOCUMENT_COUNTRY_INVALID"
    }

    @Test
    fun `other documents are upper-cased without spaces or punctuation, with the issuing country rules`() {
        val passport = IdDocuments.normalize(DocumentType.PASSPORT, "ar", "ab 123.456")
        passport.number shouldBe "AB123456"
        passport.issuingCountry shouldBe "AR"
        passport.indexInput shouldBe "PASSPORT:AR:AB123456"
        IdDocuments.normalize(DocumentType.PASSPORT, "BR", "FX123456").issuingCountry shouldBe "BR"
        IdDocuments.normalize(DocumentType.RG, null, "12.345.678-x").number shouldBe "12345678X"
        IdDocuments.normalize(DocumentType.CNH, null, "123 456 789 01").number shouldBe "12345678901"

        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.PASSPORT, null, "AB123456") }
            .code.value shouldBe "DOCUMENT_COUNTRY_INVALID"
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.FOREIGN_ID, "BR", "12345678") }
            .code.value shouldBe "DOCUMENT_COUNTRY_INVALID"
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.PASSPORT, "XX", "AB123456") }
            .code.value shouldBe "INVALID_COUNTRY"
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.CNH, null, "1234") }
            .code.value shouldBe "DOCUMENT_NUMBER_INVALID"
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.PASSPORT, "AR", "ABCDEF") }
            .code.value shouldBe "DOCUMENT_NUMBER_INVALID" // sem nenhum dígito
        shouldThrow<InvalidInputException> { IdDocuments.normalize(DocumentType.RG, null, "12#45678") }
            .code.value shouldBe "DOCUMENT_NUMBER_INVALID"
        IdDocuments.mask("A12345") shouldBe "***45"
        IdDocuments.suffix("A12345") shouldBe "45"
        IdDocuments.suffix("AB123456") shouldBe "3456"
    }

    @Test
    fun `a profile has at most one document per type and issuing country`() {
        val guest = guest()
        val cpf = IdDocuments.normalize(DocumentType.CPF, null, "52998224725")
        val index = BlindIndexValue("k1", "h".repeat(43))
        guest.addDocument(cpf, "v1:k1:opaque", index, now)
        guest.addDocument(IdDocuments.normalize(DocumentType.PASSPORT, "AR", "AB123456"), "v1:k1:opaque", index, now)

        shouldThrow<ConflictException> { guest.addDocument(cpf, "v1:k1:opaque", index, now) }.code.value shouldBe
            "DOCUMENT_ALREADY_RECORDED"
        // A máscara sai do sufixo gravado em claro: a entidade nunca decifra o número.
        guest.documents.map { it.masked } shouldContainExactly listOf("***4725", "***3456")
        guest.documents.map { it.numberSuffix } shouldContainExactly listOf("4725", "3456")
        guest.documents.first().encryptedNumber shouldBe "v1:k1:opaque"
        guest.removeDocument(guest.documents.first().id, now)!!.type shouldBe DocumentType.CPF
        guest.documents.map { it.type } shouldContainExactly listOf(DocumentType.PASSPORT)
    }

    // --- Dados sensíveis --------------------------------------------------------------------------------------

    @Test
    fun `sensitive data is encoded with a fixed length, round-trips and never shows in toString`() {
        val short = SensitiveGuestData(RaceColor.BLACK, null)
        val long = SensitiveGuestData(RaceColor.NOT_DECLARED, "A".repeat(60))

        short.encode().length shouldBe SensitiveGuestData.ENCODED_LENGTH
        long.encode().length shouldBe SensitiveGuestData.ENCODED_LENGTH
        SensitiveGuestData.decode(short.encode()) shouldBe short
        SensitiveGuestData.decode(long.encode()) shouldBe long
        short.toString() shouldNotContain "BLACK"
        shouldThrow<InvalidInputException> { SensitiveGuestData(null, "cadeirante") }.code.value shouldBe
            "SENSITIVE_DATA_INVALID"

        val guest = guest()
        guest.recordSensitiveData(short, now) shouldBe true
        guest.recordSensitiveData(short, now) shouldBe false
        guest.hasSensitiveData shouldBe true
        guest.recordSensitiveData(SensitiveGuestData(null, " "), now) shouldBe true
        guest.hasSensitiveData shouldBe false
    }

    // --- Perfil -----------------------------------------------------------------------------------------------

    @Test
    fun `the full address is only for residents of Brazil`() {
        val address = Address("Rua A", "10", null, "Centro", "São Paulo", BrazilianState.SP, "01310-100", "3550308")

        guest(residenceCountry = "BR", address = address).address shouldBe address
        shouldThrow<InvalidInputException> { guest(residenceCountry = "AR", address = address) }.code.value shouldBe
            "ADDRESS_REQUIRES_BRAZIL_RESIDENCE"
        shouldThrow<InvalidInputException> { guest(address = address) }.code.value shouldBe
            "ADDRESS_REQUIRES_BRAZIL_RESIDENCE"
        guest(residenceCountry = "ar").residenceCountry shouldBe "AR"
    }

    @Test
    fun `an update reports the changed fields only`() {
        val guest = guest()

        guest.update(GuestProfile("Maria da Silva"), now) shouldBe emptyList()
        guest.update(
            GuestProfile(
                "Maria  Souza",
                email = " Maria@Example.COM ",
                vip = true,
                preferences = GuestPreferences("Alto"),
            ),
            now,
        ) shouldContainExactly listOf("fullName", "email", "preferences.floor", "vip")
        guest.email shouldBe "maria@example.com"
    }

    @Test
    fun `the search key has no accents, is lower-case and collapses spaces`() {
        GuestsRules.searchKey("  José  da Conceição ", "Zé") shouldBe "jose da conceicao ze"
        GuestsRules.searchKey("ÁGUA Ñandú") shouldBe "agua nandu"
    }
}
