package com.zeny.hotel.guests

import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestDocument
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.domain.SensitiveGuestData
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BlindIndexValue
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Regras da mesclagem de duplicados (M3 E02, RF-HOS-05, H6; decididas pelo usuário em 2026-10-17): o sobrevivente
 * mantém os dados dele e completa os vazios com os do absorvido; os campos pedidos vêm do absorvido (sem apagar
 * nada); VIP e restrição de qualquer um dos dois ficam; documentos iguais uma vez só, os que faltam passam, e o
 * mesmo tipo e país com outro número é recusado; o absorvido fica mesclado, com a referência ao sobrevivente; nem o
 * mesmo perfil nem um já mesclado se mesclam.
 */
class GuestMergeDomainTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val later = now.plusSeconds(60)
    private val operator = UserId(UuidV7.generate())
    private val byHash: (GuestDocument, GuestDocument) -> Boolean = { a, b -> a.numberIndex == b.numberIndex }

    private val address =
        Address(
            "Rua das Acácias",
            "742",
            null,
            "Jardim Paulista",
            "São Paulo",
            BrazilianState.SP,
            "01407000",
            "3550308",
        )

    private fun guest(profile: GuestProfile) = Guest.create(profile, now)

    private fun Guest.withDocument(
        type: DocumentType,
        number: String,
        country: String? = null,
    ): Guest =
        apply {
            val document = IdDocuments.normalize(type, country, number)
            addDocument(document, "v1:k1:opaque", BlindIndexValue("k1", document.indexInput.padEnd(43, '0')), now)
        }

    private fun Guest.merge(
        merged: Guest,
        vararg take: GuestMergeField,
    ) = absorb(merged, take.toSet(), byHash, operator, later)

    // --- Dados do perfil ------------------------------------------------------------------------------------

    @Test
    fun `the survivor keeps its data and only its empty fields are filled with the merged profile's`() {
        val survivor =
            guest(
                GuestProfile(
                    "Maria da Silva",
                    email = "maria@exemplo.com",
                    preferences = GuestPreferences(floor = "Alto"),
                ),
            )
        val merged =
            guest(
                GuestProfile(
                    "Maria S.",
                    socialName = "Mari",
                    nationality = "BR",
                    birthDate = LocalDate.of(1990, 5, 20),
                    gender = Gender.FEMALE,
                    email = "outra@exemplo.com",
                    phone = "+55 11 98765-4321",
                    residenceCountry = "BR",
                    address = address,
                    preferences = GuestPreferences(floor = "Baixo", bed = "Casal"),
                    notes = "Alérgica a penas",
                ),
            )

        val result = survivor.merge(merged)

        survivor.fullName shouldBe "Maria da Silva"
        survivor.email shouldBe "maria@exemplo.com"
        survivor.preferences shouldBe GuestPreferences(floor = "Alto", bed = "Casal")
        survivor.socialName shouldBe "Mari"
        survivor.nationality shouldBe "BR"
        survivor.birthDate shouldBe LocalDate.of(1990, 5, 20)
        survivor.gender shouldBe Gender.FEMALE
        survivor.phone shouldBe "+55 11 98765-4321"
        survivor.residenceCountry shouldBe "BR"
        survivor.address shouldBe address
        survivor.notes shouldBe "Alérgica a penas"
        survivor.updatedAt shouldBe later
        result.changedFields shouldContainExactlyInAnyOrder
            listOf(
                "socialName",
                "nationality",
                "birthDate",
                "gender",
                "phone",
                "residenceCountry",
                "address",
                "preferences.bed",
                "notes",
            )
    }

    @Test
    fun `the listed fields come from the merged profile, but never erase a value it does not have`() {
        val survivor =
            guest(
                GuestProfile(
                    "Maria da Silva",
                    email = "maria@exemplo.com",
                    phone = "+55 11 1111-1111",
                    residenceCountry = "BR",
                    address = address,
                    notes = "Antiga",
                ),
            )
        val merged =
            guest(GuestProfile("Maria Souza da Silva", email = "maria.souza@exemplo.com", residenceCountry = "AR"))

        survivor.merge(merged, GuestMergeField.FULL_NAME, GuestMergeField.EMAIL, GuestMergeField.PHONE)
        survivor.fullName shouldBe "Maria Souza da Silva"
        survivor.email shouldBe "maria.souza@exemplo.com"
        // O absorvido não tem telefone: o do sobrevivente fica.
        survivor.phone shouldBe "+55 11 1111-1111"
        // Residência não pedida: o país e o endereço do sobrevivente ficam juntos.
        survivor.residenceCountry shouldBe "BR"
        survivor.address shouldBe address
        survivor.notes shouldBe "Antiga"

        val other = guest(GuestProfile("João", residenceCountry = "BR", address = address))
        val foreign = guest(GuestProfile("João", residenceCountry = "AR"))
        other.merge(foreign, GuestMergeField.RESIDENCE)
        // A residência vem inteira do absorvido: o estrangeiro não tem endereço completo.
        other.residenceCountry shouldBe "AR"
        other.address.shouldBeNull()
    }

    @Test
    fun `the company and the FNRH sensitive data follow the same rule`() {
        val company = CompanyId(UuidV7.generate())
        val survivor = guest(GuestProfile("Maria"))
        val merged = guest(GuestProfile("Maria", companyId = company))
        merged.recordSensitiveData(SensitiveGuestData(RaceColor.BROWN, null), now)

        val result = survivor.merge(merged)

        survivor.companyId shouldBe company
        survivor.sensitiveData shouldBe SensitiveGuestData(RaceColor.BROWN, null)
        result.changedFields shouldContainExactlyInAnyOrder listOf("companyId", "sensitiveData")

        val keeps = guest(GuestProfile("Ana"))
        keeps.recordSensitiveData(SensitiveGuestData(RaceColor.WHITE, null), now)
        val other = guest(GuestProfile("Ana"))
        other.recordSensitiveData(SensitiveGuestData(RaceColor.BLACK, "HEARING_FULL"), now)
        keeps.merge(other)
        keeps.sensitiveData shouldBe SensitiveGuestData(RaceColor.WHITE, null)
        val takes = guest(GuestProfile("Ana"))
        takes.recordSensitiveData(SensitiveGuestData(RaceColor.WHITE, null), now)
        val another = guest(GuestProfile("Ana"))
        another.recordSensitiveData(SensitiveGuestData(RaceColor.BLACK, "HEARING_FULL"), now)
        takes.merge(another, GuestMergeField.SENSITIVE_DATA)
        takes.sensitiveData shouldBe SensitiveGuestData(RaceColor.BLACK, "HEARING_FULL")
    }

    // --- VIP e restrição --------------------------------------------------------------------------------------

    @Test
    fun `VIP holds if either is VIP, and a restriction of either one is never dropped`() {
        val survivor = guest(GuestProfile("Maria"))
        val merged = guest(GuestProfile("Maria", vip = true))
        val restrictedBy = UserId(UuidV7.generate())
        merged.restrict("Danos ao quarto", restrictedBy, now)

        val result = survivor.merge(merged)

        survivor.vip shouldBe true
        survivor.restricted shouldBe true
        survivor.restrictionReason shouldBe "Danos ao quarto"
        survivor.restrictedAt shouldBe now
        survivor.restrictedBy shouldBe restrictedBy
        result.changedFields shouldContainExactlyInAnyOrder listOf("vip", "restricted", "restrictionReason")
        // O absorvido continua com a própria restrição (histórico).
        merged.restricted shouldBe true

        // Os dois restritos: vale o motivo do sobrevivente.
        val both = guest(GuestProfile("João"))
        both.restrict("Inadimplência", operator, now)
        val alsoRestricted = guest(GuestProfile("João"))
        alsoRestricted.restrict("Danos", operator, now)
        both.merge(alsoRestricted)
        both.restrictionReason shouldBe "Inadimplência"

        // Só o sobrevivente restrito: continua restrito.
        val onlySurvivor = guest(GuestProfile("Ana"))
        onlySurvivor.restrict("Inadimplência", operator, now)
        onlySurvivor.merge(guest(GuestProfile("Ana", vip = false)))
        onlySurvivor.restricted shouldBe true
        onlySurvivor.vip shouldBe false
    }

    // --- Documentos -----------------------------------------------------------------------------------------

    @Test
    fun `equal documents stay once, missing ones move and a different number of the same kind is refused`() {
        val survivor = guest(GuestProfile("Maria")).withDocument(DocumentType.CPF, "52998224725")
        val merged =
            guest(GuestProfile("Maria"))
                .withDocument(DocumentType.CPF, "529.982.247-25")
                .withDocument(DocumentType.PASSPORT, "AB123456", "AR")
        val cpf = merged.documentsOf(DocumentType.CPF).single().id
        val passport = merged.documentsOf(DocumentType.PASSPORT).single().id

        val result = survivor.merge(merged)

        result.moved shouldContainExactly listOf(passport)
        result.duplicates shouldContainExactly listOf(cpf)
        result.changedFields shouldContainExactly listOf("documents")

        val conflicting = guest(GuestProfile("João")).withDocument(DocumentType.CPF, "52998224725")
        val other =
            guest(GuestProfile("João", vip = true))
                .withDocument(DocumentType.PASSPORT, "AB123456", "AR")
                .withDocument(DocumentType.CPF, "11144477735")
        shouldThrow<ConflictException> { conflicting.merge(other) }.code.value shouldBe "GUEST_MERGE_DOCUMENT_CONFLICT"
        // Nada mudou nos dois.
        conflicting.vip shouldBe false
        other.status shouldBe GuestStatus.ACTIVE
        other.mergedInto.shouldBeNull()

        // O mesmo tipo de outro país não é conflito: passa.
        val brazilian = guest(GuestProfile("Ana")).withDocument(DocumentType.PASSPORT, "AB123456", "BR")
        val argentinian = guest(GuestProfile("Ana")).withDocument(DocumentType.PASSPORT, "AB123456", "AR")
        brazilian.merge(argentinian).moved shouldContainExactly argentinian.documents.map { it.id }
    }

    // --- Situação ---------------------------------------------------------------------------------------------

    @Test
    fun `the merged profile is kept as merged with the survivor, and is not changed or merged again`() {
        val survivor = guest(GuestProfile("Maria"))
        val merged = guest(GuestProfile("Maria"))

        survivor.merge(merged)

        merged.status shouldBe GuestStatus.MERGED
        merged.mergedInto shouldBe survivor.id
        merged.mergedAt shouldBe later
        merged.mergedBy shouldBe operator
        merged.updatedAt shouldBe later
        merged.fullName shouldBe "Maria"
        survivor.status shouldBe GuestStatus.ACTIVE
        survivor.mergedInto.shouldBeNull()

        val third = guest(GuestProfile("Maria"))
        shouldThrow<ConflictException> { third.merge(merged) }.code.value shouldBe "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> { merged.merge(third) }.code.value shouldBe "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> { survivor.merge(survivor) }.code.value shouldBe "GUEST_MERGE_SAME_PROFILE"
        shouldThrow<ConflictException> { merged.update(GuestProfile("Outra"), later) }.code.value shouldBe
            "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> { merged.restrict("Motivo", operator, later) }.code.value shouldBe
            "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> { merged.liftRestriction(later) }.code.value shouldBe "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> {
            merged.recordSensitiveData(SensitiveGuestData(RaceColor.WHITE, null), later)
        }.code.value shouldBe "GUEST_ALREADY_MERGED"
        shouldThrow<ConflictException> { merged.withDocument(DocumentType.CPF, "52998224725") }.code.value shouldBe
            "GUEST_ALREADY_MERGED"
        // O sobrevivente pode ser absorvido depois por outro (cadeia).
        third.merge(survivor)
        survivor.mergedInto shouldBe third.id
        third.documents.shouldBeEmpty()
    }
}
