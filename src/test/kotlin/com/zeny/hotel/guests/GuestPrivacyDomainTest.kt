package com.zeny.hotel.guests

import com.zeny.hotel.guests.domain.ConsentGrant
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.domain.SensitiveGuestData
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BlindIndexValue
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Regras de LGPD do perfil (M3 E03, RF-HOS-02 e 07): restrições alimentares só com o consentimento ativo, revogação
 * que as apaga, consentimento ativo único por finalidade, e a anonimização que apaga os dados pessoais e os
 * documentos e mantém o id, os consentimentos, a nacionalidade, o país, a UF e o município e o VIP.
 */
class GuestPrivacyDomainTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val by = UserId(UuidV7.generate())

    private val address =
        Address(
            "Rua das Acácias",
            "742",
            "Apto 12",
            "Jardim Paulista",
            "São Paulo",
            BrazilianState.SP,
            "01407000",
            "3550308",
        )

    private fun profile(dietary: String? = null) =
        GuestProfile(
            fullName = "Maria da Silva",
            socialName = "Maria",
            nationality = "BR",
            birthDate = LocalDate.of(1990, 5, 20),
            gender = Gender.FEMALE,
            email = "maria@exemplo.com.br",
            phone = "+55 11 98765-4321",
            residenceCountry = "BR",
            address = address,
            preferences = GuestPreferences("Alto", "Casal", "Baixo"),
            vip = true,
            notes = "Prefere quarto silencioso",
            dietaryRestrictions = dietary,
        )

    @Test
    fun `dietary restrictions need the active consent, and revoking it erases them`() {
        shouldThrow<BusinessRuleException> { Guest.create(profile(dietary = "Sem glúten"), now) }
            .code shouldBe GuestsErrorCodes.DIETARY_RESTRICTIONS_REQUIRE_CONSENT

        val guest =
            Guest.create(
                profile(dietary = "Sem glúten"),
                now,
                listOf(ConsentGrant(ConsentPurpose.DIETARY_RESTRICTIONS, ConsentSource.IN_PERSON)),
                by,
            )
        guest.dietaryRestrictions shouldBe "Sem glúten"
        val consent = guest.consents.single()
        consent.grantedBy shouldBe by

        // Um ativo por finalidade: dar de novo devolve o mesmo.
        guest.grantConsent(ConsentPurpose.DIETARY_RESTRICTIONS, ConsentSource.EMAIL, by, now).changed shouldBe false
        guest.consents shouldHaveSize 1

        val revoked = guest.revokeConsent(consent.id, ConsentSource.EMAIL, by, now.plusSeconds(60))
        revoked?.changed shouldBe true
        guest.dietaryRestrictions.shouldBeNull()
        guest.hasActiveConsent(ConsentPurpose.DIETARY_RESTRICTIONS) shouldBe false
        shouldThrow<BusinessRuleException> { guest.update(profile(dietary = "Sem lactose"), now.plusSeconds(120)) }
        // Revogar outra finalidade não mexe nas restrições; revogar de novo não muda nada.
        guest.revokeConsent(consent.id, ConsentSource.PHONE, by, now.plusSeconds(180))?.changed shouldBe false
        guest.consent(consent.id)?.revokedSource shouldBe ConsentSource.EMAIL
    }

    @Test
    fun `the anonymization erases personal data and documents and keeps the id, consents and aggregates`() {
        val guest =
            Guest.create(
                profile(),
                now,
                listOf(ConsentGrant(ConsentPurpose.MARKETING, ConsentSource.WEBSITE)),
                by,
            )
        val document = IdDocuments.normalize(DocumentType.CPF, null, "52998224725")
        guest.addDocument(document, "v1:test:cifrado", BlindIndexValue("test", "hash"), now)
        guest.recordSensitiveData(SensitiveGuestData(RaceColor.BROWN, "VISUAL"), now)
        guest.restrict("Danos ao quarto", by, now)
        val id = guest.id

        val removed = guest.anonymize(by, now.plusSeconds(60))

        removed shouldHaveSize 1
        guest.id shouldBe id
        guest.status shouldBe GuestStatus.ANONYMIZED
        guest.anonymizedAt shouldBe now.plusSeconds(60)
        guest.anonymizedBy shouldBe by
        guest.fullName shouldBe Guest.ANONYMIZED_NAME
        listOf(
            guest.socialName,
            guest.birthDate,
            guest.gender,
            guest.email,
            guest.phone,
            guest.address,
            guest.notes,
            guest.companyId,
            guest.restrictionReason,
            guest.dietaryRestrictions,
            guest.sensitiveData,
        ).forEach { it.shouldBeNull() }
        guest.preferences shouldBe GuestPreferences()
        guest.restricted shouldBe false
        guest.documents.shouldBeEmpty()
        // Fica: nacionalidade, país, UF e município, VIP e os consentimentos (prova).
        guest.nationality shouldBe "BR"
        guest.residenceCountry shouldBe "BR"
        guest.anonymizedState shouldBe BrazilianState.SP
        guest.anonymizedIbgeCityCode shouldBe "3550308"
        guest.vip shouldBe true
        guest.consents shouldHaveSize 1

        // Não muda mais.
        shouldThrow<ConflictException> { guest.update(profile(), now) }.code shouldBe GuestsErrorCodes.GUEST_ANONYMIZED
        shouldThrow<ConflictException> { guest.anonymize(by, now) }.code shouldBe GuestsErrorCodes.GUEST_ANONYMIZED
        shouldThrow<ConflictException> {
            guest.grantConsent(ConsentPurpose.DIETARY_RESTRICTIONS, ConsentSource.EMAIL, by, now)
        }.code shouldBe GuestsErrorCodes.GUEST_ANONYMIZED
        shouldThrow<ConflictException> { Guest.create(profile(), now).requireMergeable(guest) }
            .code shouldBe GuestsErrorCodes.GUEST_ANONYMIZED
    }

    @Test
    fun `an absorbed profile stays MERGED when anonymized with its survivor`() {
        val survivor = Guest.create(profile(), now)
        val merged = Guest.create(profile(), now)
        survivor.absorb(merged, emptySet(), { _, _ -> true }, by, now)

        merged.anonymize(by, now)

        merged.status shouldBe GuestStatus.MERGED
        merged.anonymized shouldBe true
        merged.email.shouldBeNull()
    }

    @Test
    fun `the merge moves the consents and the dietary restrictions to the survivor, which prevails`() {
        val dietary = listOf(ConsentGrant(ConsentPurpose.DIETARY_RESTRICTIONS, ConsentSource.IN_PERSON))

        // Só no absorvido: o consentimento passa sem substituição e a restrição vai para o sobrevivente.
        val survivor = Guest.create(profile(), now)
        val merged = Guest.create(profile(dietary = "Vegano"), now, dietary, by)
        val result = survivor.absorb(merged, emptySet(), { _, _ -> true }, by, now)
        result.consents.map { it.id to it.supersededBy } shouldBe listOf(merged.consents.single().id to null)
        survivor.dietaryRestrictions shouldBe "Vegano"
        merged.dietaryRestrictions.shouldBeNull()
        result.changedFields shouldContain "dietaryRestrictions"
        result.changedFields shouldContain "consents"

        // Nos dois: o sobrevivente prevalece (a menos que a restrição venha pedida), e o ativo do absorvido é
        // substituído pelo dele.
        val keeper = Guest.create(profile(dietary = "Sem glúten"), now, dietary, by)
        val other = Guest.create(profile(dietary = "Vegano"), now, dietary, by)
        val both = keeper.absorb(other, setOf(GuestMergeField.DIETARY_RESTRICTIONS), { _, _ -> true }, by, now)
        both.consents.single().supersededBy shouldBe keeper.consents.single().id
        keeper.dietaryRestrictions shouldBe "Vegano"
        other.dietaryRestrictions.shouldBeNull()

        // Sem consentimento em nenhum: nada de restrição.
        val plain = Guest.create(profile(), now)
        plain
            .absorb(
                Guest.create(profile(), now),
                setOf(GuestMergeField.DIETARY_RESTRICTIONS),
                { _, _ -> true },
                by,
                now,
            ).consents
            .shouldBeEmpty()
        plain.dietaryRestrictions.shouldBeNull()
    }

    @Test
    fun `revoking a consent is never refused, not even on an anonymized profile`() {
        val guest =
            Guest.create(
                profile(dietary = "Sem glúten"),
                now,
                listOf(ConsentGrant(ConsentPurpose.DIETARY_RESTRICTIONS, ConsentSource.IN_PERSON)),
                by,
            )
        guest.anonymize(by, now)
        val consent = guest.consents.single()

        guest.revokeConsent(consent.id, ConsentSource.EMAIL, by, now.plusSeconds(1))?.changed shouldBe true
        consent.active shouldBe false
    }
}
