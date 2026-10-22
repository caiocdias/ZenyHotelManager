package com.zeny.hotel.frontdesk

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileSummary
import com.zeny.hotel.frontdesk.domain.EarlyLateQuotes
import com.zeny.hotel.frontdesk.domain.GuestDataIncompleteException
import com.zeny.hotel.frontdesk.domain.GuestDataRules
import com.zeny.hotel.frontdesk.domain.MinorAttachmentRules
import com.zeny.hotel.frontdesk.domain.MissingGuestData
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestIdentification
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsFilePurposes
import com.zeny.hotel.guests.MaskedGuestDocument
import com.zeny.hotel.hotel.EarlyLateTerms
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** As regras puras do check-in da recepção (M5 E02): dados mínimos (H11), anexo do menor, prontidão e early/late. */
class FrontdeskDomainTest {
    private val guestId = GuestId.new()

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 22)
    }

    @Test
    fun `a Brazilian needs a CPF, a foreigner a passport, RNM or foreign id, and everyone a birth date`() {
        incomplete(identification("BR", DocumentType.CPF)).shouldBeNull()
        incomplete(identification("BR", DocumentType.RG))!!.let {
            it.missing shouldContainExactly listOf(MissingGuestData.DOCUMENT)
            it.acceptedDocumentTypes shouldContainExactly listOf(DocumentType.CPF)
        }
        incomplete(identification("AR", DocumentType.PASSPORT)).shouldBeNull()
        incomplete(identification("PT", DocumentType.RNM)).shouldBeNull()
        incomplete(identification("UY", DocumentType.CPF))!!.acceptedDocumentTypes shouldBe
            listOf(DocumentType.PASSPORT, DocumentType.RNM, DocumentType.FOREIGN_ID)
        incomplete(identification(null, DocumentType.PASSPORT, birthDate = null))!!.missing shouldBe
            listOf(MissingGuestData.NATIONALITY, MissingGuestData.BIRTH_DATE)
        incomplete(identification(null))!!.missing shouldBe
            listOf(MissingGuestData.NATIONALITY, MissingGuestData.DOCUMENT)

        val refused =
            shouldThrow<GuestDataIncompleteException> {
                GuestDataRules.require(listOfNotNull(incomplete(identification("BR"))))
            }
        refused.code.value shouldBe "GUEST_DATA_INCOMPLETE"
        refused.problemProperties.toString().contains("***") shouldBe false
    }

    @Test
    fun `a Brazilian minor enters with a CPF or an RG, an adult only with a CPF (B2)`() {
        val minorBirth = TODAY.minusYears(10)
        incomplete(identification("BR", DocumentType.RG, birthDate = minorBirth)).shouldBeNull()
        incomplete(identification("BR", DocumentType.CPF, birthDate = minorBirth)).shouldBeNull()
        incomplete(identification("BR", DocumentType.CNH, birthDate = minorBirth))!!.acceptedDocumentTypes shouldBe
            listOf(DocumentType.CPF, DocumentType.RG)
        // No dia em que faz 18 anos, já é adulto: só o CPF.
        incomplete(identification("BR", DocumentType.RG, birthDate = TODAY.minusYears(18)))!!.missing shouldBe
            listOf(MissingGuestData.DOCUMENT)
        incomplete(identification("BR", DocumentType.RG, birthDate = TODAY.minusYears(18).plusDays(1))).shouldBeNull()
        // O estrangeiro menor continua com os documentos do estrangeiro.
        incomplete(identification("AR", DocumentType.RG, birthDate = minorBirth))!!.acceptedDocumentTypes shouldBe
            GuestDataRules.FOREIGN_DOCUMENTS
    }

    @Test
    fun `the minor attachment must exist, have an authorization purpose and belong to the minor`() {
        val minor = GuestId.new()
        val survivor = GuestId.new()
        val owners = setOf(minor, survivor)
        MinorAttachmentRules.valid(file(GuestsFilePurposes.MINOR_AUTHORIZATION_PHOTO, minor), owners) shouldBe true
        MinorAttachmentRules.valid(file(GuestsFilePurposes.DOCUMENT_PDF, survivor), owners) shouldBe true
        MinorAttachmentRules.valid(file(GuestsFilePurposes.ID_DOCUMENT_PHOTO, minor), owners) shouldBe false
        MinorAttachmentRules.valid(file(GuestsFilePurposes.MINOR_AUTHORIZATION_PHOTO, GuestId.new()), owners) shouldBe
            false
        MinorAttachmentRules.valid(file(GuestsFilePurposes.MINOR_AUTHORIZATION_PHOTO, null), owners) shouldBe false
        MinorAttachmentRules.valid(null, owners) shouldBe false
    }

    @Test
    fun `the guest count defaults to the list and never goes below it`() {
        StayRules.guestCount(null, 2) shouldBe 2
        StayRules.guestCount(3, 2) shouldBe 3
        shouldThrow<InvalidInputException> { StayRules.guestCount(1, 2) }.violations.single().field shouldBe
            "guestCount"
        shouldThrow<InvalidInputException> { StayRules.guestCount(41, 2) }
    }

    @Test
    fun `the early and late quote is the policy window in the hotel time zone, or not offered`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val date = LocalDate.of(2026, 11, 3)
        val offered =
            policy(
                EarlyLateTerms(LocalTime.of(10, 0), Money.of("80.00")),
                EarlyLateTerms(LocalTime.of(16, 0), Money.of("95.50")),
            )
        EarlyLateQuotes.early(offered, date, zone).let {
            it.offered shouldBe true
            it.fee shouldBe Money.of("80.00")
            it.from shouldBe Instant.parse("2026-11-03T13:00:00Z")
            it.until shouldBe Instant.parse("2026-11-03T17:00:00Z")
        }
        EarlyLateQuotes.late(offered, date, zone).let {
            it.from shouldBe Instant.parse("2026-11-03T15:00:00Z")
            it.until shouldBe Instant.parse("2026-11-03T19:00:00Z")
        }
        EarlyLateQuotes.early(policy(null, null), date, zone).let {
            it.offered shouldBe false
            it.fee.shouldBeNull()
            it.from.shouldBeNull()
        }
    }

    private fun incomplete(identification: GuestIdentification) =
        GuestDataRules.incomplete(guestId, identification, TODAY)

    private fun identification(
        nationality: String?,
        vararg documents: DocumentType,
        birthDate: LocalDate? = LocalDate.of(1990, 1, 1),
    ) = GuestIdentification(
        guestId,
        GuestStatus.ACTIVE,
        nationality,
        birthDate,
        documents.map { MaskedGuestDocument(GuestDocumentId.new(), it, if (it.brazilian) "BR" else "AR", "***1234") },
    )

    private fun file(
        purpose: FilePurposeCode,
        owner: GuestId?,
    ) = FileSummary(FileId(UuidV7.generate()), purpose, "image/png", 10, true, owner?.toString(), null, Instant.EPOCH)

    private fun policy(
        early: EarlyLateTerms?,
        late: EarlyLateTerms?,
    ) = PolicySettings(
        checkInTime = LocalTime.of(14, 0),
        checkOutTime = LocalTime.of(12, 0),
        cleaningWindow = Duration.ofHours(2),
        earlyCheckIn = early,
        lateCheckOut = late,
        childMaxAge = 11,
        childAgeBands = emptyList(),
        stayPolicyText = "Diária de 24 horas.",
        additionalPolicyText = null,
    )
}
