package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BlindIndexValue
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.EncryptedStringConverter
import com.zeny.hotel.shared.UserId
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os dados de um perfil como chegam para criar ou editar (RF-HOS-01 e 02), com o endereço já montado. O
 * endereço completo é só de quem reside no Brasil (decisão do usuário em 2026-10-16): o estrangeiro informa só
 * o país de residência.
 */
data class GuestProfile(
    val fullName: String,
    val socialName: String? = null,
    val nationality: String? = null,
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val email: String? = null,
    val phone: String? = null,
    val residenceCountry: String? = null,
    val address: Address? = null,
    val preferences: GuestPreferences = GuestPreferences(),
    val vip: Boolean = false,
    val notes: String? = null,
    val companyId: CompanyId? = null,
) {
    /** Nunca mostra dado pessoal. */
    override fun toString(): String = "GuestProfile(vip=$vip)"
}

/**
 * Perfil de hóspede, pessoa física (RF-HOS-01, 02 e 04, SPEC §8.2.4): os campos da FNRH, preferências, VIP,
 * observações, a lista de restrição, os documentos ([GuestDocument], número cifrado com índice cego) e os dados
 * sensíveis da FNRH ([SensitiveGuestData], cifrados num valor de tamanho fixo e nunca lidos pela API). Nunca é
 * apagado (o banco recusa); a anonimização é da E03 do M3. [toString] não mostra dado pessoal.
 */
@Entity
@Table(name = "guest", schema = "guests")
@Suppress("TooManyFunctions") // as operações do agregado: perfil, documentos, dados sensíveis e restrição
class Guest private constructor(
    @Id
    val id: GuestId,
    profile: GuestProfile,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "full_name", nullable = false)
    var fullName: String = ""
        protected set

    @Column(name = "social_name")
    var socialName: String? = null
        protected set

    @Column(name = "search_name", nullable = false)
    private var searchName: String = ""

    var nationality: String? = null
        protected set

    @Column(name = "birth_date")
    var birthDate: LocalDate? = null
        protected set

    @Enumerated(EnumType.STRING)
    var gender: Gender? = null
        protected set

    var email: String? = null
        protected set

    var phone: String? = null
        protected set

    @Column(name = "residence_country")
    var residenceCountry: String? = null
        protected set

    @Embedded
    var address: Address? = null
        protected set

    @Column(name = "preferred_floor")
    private var preferredFloor: String? = null

    @Column(name = "preferred_bed")
    private var preferredBed: String? = null

    @Column(name = "preferred_pillow")
    private var preferredPillow: String? = null

    @Column(nullable = false)
    var vip: Boolean = false
        protected set

    var notes: String? = null
        protected set

    @Column(name = "company_id")
    private var companyUuid: UUID? = null

    @Column(nullable = false)
    var restricted: Boolean = false
        protected set

    @Column(name = "restriction_reason")
    var restrictionReason: String? = null
        protected set

    @Column(name = "restricted_at")
    var restrictedAt: Instant? = null
        protected set

    @Column(name = "restricted_by")
    private var restrictedByUuid: UUID? = null

    /** [SensitiveGuestData.encode], cifrado pelo conversor; nulo sem dados sensíveis. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "sensitive_data")
    private var encodedSensitiveData: String? = null

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false, updatable = false)
    @OrderBy("createdAt, id")
    private val documentList: MutableList<GuestDocument> = mutableListOf()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    init {
        apply(profile, createdAt)
    }

    val preferences: GuestPreferences get() = GuestPreferences(preferredFloor, preferredBed, preferredPillow)

    val companyId: CompanyId? get() = companyUuid?.let(::CompanyId)

    val restrictedBy: UserId? get() = restrictedByUuid?.let(::UserId)

    val documents: List<GuestDocument> get() = documentList.toList()

    /** Se há dados sensíveis da FNRH gravados (o valor nunca sai pela API). */
    val hasSensitiveData: Boolean get() = encodedSensitiveData != null

    /** Os dados sensíveis decifrados: só para a integração FNRH (RN-19). */
    val sensitiveData: SensitiveGuestData? get() = encodedSensitiveData?.let(SensitiveGuestData::decode)

    /**
     * Menor de idade na [date] (RN-24): ainda não completou 18 anos. Quem nasceu em 29 de fevereiro completa
     * no dia 1º de março dos anos não bissextos. `null` sem data de nascimento.
     */
    fun isMinorOn(date: LocalDate): Boolean? = birthDate?.let { Period.between(it, date).years < ADULT_AGE }

    /** Troca os dados do perfil; devolve os campos que mudaram (para a trilha, H10). */
    fun update(
        profile: GuestProfile,
        now: Instant,
    ): List<String> {
        val changed = apply(profile, now)
        if (changed.isNotEmpty()) updatedAt = now
        return changed
    }

    /**
     * Acrescenta um documento, com o número já cifrado por quem chama ([encryptedNumber]) e o índice cego; o
     * perfil já ter um do mesmo tipo e país: `409 DOCUMENT_ALREADY_RECORDED`.
     */
    fun addDocument(
        document: NormalizedDocument,
        encryptedNumber: String,
        index: BlindIndexValue,
        now: Instant,
    ): GuestDocument {
        if (documentList.any { it.type == document.type && it.issuingCountry == document.issuingCountry }) {
            throw ConflictException(
                GuestsErrorCodes.DOCUMENT_ALREADY_RECORDED,
                "O perfil já tem um documento deste tipo e país; retire o anterior para corrigir.",
            )
        }
        val added =
            GuestDocument(
                GuestDocumentId.new(),
                document.type,
                document.issuingCountry,
                encryptedNumber,
                document.suffix,
                index,
                now,
            )
        documentList += added
        updatedAt = now
        return added
    }

    fun document(id: GuestDocumentId): GuestDocument? = documentList.firstOrNull { it.id == id }

    /** Retira um documento; devolve o retirado, ou `null` se ele não é deste perfil. */
    fun removeDocument(
        id: GuestDocumentId,
        now: Instant,
    ): GuestDocument? {
        val removed = document(id) ?: return null
        documentList.remove(removed)
        updatedAt = now
        return removed
    }

    /** Os documentos de um tipo (a FNRH usa CPF ou passaporte). */
    fun documentsOf(type: DocumentType): List<GuestDocument> = documentList.filter { it.type == type }

    /** Grava (ou, vazio, apaga) os dados sensíveis da FNRH; devolve se mudou. */
    fun recordSensitiveData(
        data: SensitiveGuestData,
        now: Instant,
    ): Boolean {
        val current = sensitiveData ?: EMPTY_SENSITIVE
        if (current == data) return false
        encodedSensitiveData = if (data.isEmpty) null else data.encode()
        updatedAt = now
        return true
    }

    /** Inclui na lista de restrição (ou troca o motivo); devolve se mudou. */
    fun restrict(
        reason: String,
        by: UserId?,
        now: Instant,
    ): Boolean {
        val normalized = GuestsRules.required("reason", reason, MAX_REASON_LENGTH)
        if (restricted && restrictionReason == normalized) return false
        if (!restricted) restrictedAt = now
        restricted = true
        restrictionReason = normalized
        restrictedByUuid = by?.value
        updatedAt = now
        return true
    }

    /** Retira da lista de restrição; devolve se mudou. */
    fun liftRestriction(now: Instant): Boolean {
        if (!restricted) return false
        restricted = false
        restrictionReason = null
        restrictedAt = null
        restrictedByUuid = null
        updatedAt = now
        return true
    }

    private fun apply(
        profile: GuestProfile,
        now: Instant,
    ): List<String> {
        val fullName = GuestsRules.required("fullName", profile.fullName, MAX_NAME_LENGTH)
        val socialName = GuestsRules.optional("socialName", profile.socialName, MAX_NAME_LENGTH)
        val nationality = GuestsRules.country("nationality", profile.nationality)
        val residenceCountry = GuestsRules.country("residenceCountry", profile.residenceCountry)
        val email = GuestsRules.email("email", profile.email)
        val phone = GuestsRules.phone("phone", profile.phone)
        val floor = GuestsRules.optional("preferences.floor", profile.preferences.floor, MAX_PREFERENCE_LENGTH)
        val bed = GuestsRules.optional("preferences.bed", profile.preferences.bed, MAX_PREFERENCE_LENGTH)
        val pillow = GuestsRules.optional("preferences.pillow", profile.preferences.pillow, MAX_PREFERENCE_LENGTH)
        val notes = GuestsRules.optional("notes", profile.notes, MAX_NOTES_LENGTH)
        requireBirthDate(profile.birthDate, now)
        if (profile.address != null && residenceCountry != BRAZIL) {
            throw GuestsRules.invalid(
                GuestsErrorCodes.ADDRESS_REQUIRES_BRAZIL_RESIDENCE,
                "address",
                "O endereço completo é só de quem reside no Brasil (residenceCountry BR); o estrangeiro informa " +
                    "só o país de residência.",
            )
        }
        val changes = ChangedFields()
        changes.set("fullName", this.fullName, fullName) { this.fullName = it }
        changes.set("socialName", this.socialName, socialName) { this.socialName = it }
        changes.set("nationality", this.nationality, nationality) { this.nationality = it }
        changes.set("birthDate", this.birthDate, profile.birthDate) { this.birthDate = it }
        changes.set("gender", this.gender, profile.gender) { this.gender = it }
        changes.set("email", this.email, email) { this.email = it }
        changes.set("phone", this.phone, phone) { this.phone = it }
        changes.set("residenceCountry", this.residenceCountry, residenceCountry) { this.residenceCountry = it }
        changes.set("address", this.address, profile.address) { this.address = it }
        changes.set("preferences.floor", preferredFloor, floor) { preferredFloor = it }
        changes.set("preferences.bed", preferredBed, bed) { preferredBed = it }
        changes.set("preferences.pillow", preferredPillow, pillow) { preferredPillow = it }
        changes.set("vip", vip, profile.vip) { vip = it }
        changes.set("notes", this.notes, notes) { this.notes = it }
        changes.set("companyId", companyUuid, profile.companyId?.value) { companyUuid = it }
        searchName = GuestsRules.searchKey(this.fullName, this.socialName)
        return changes.fields
    }

    /** Nem no futuro (com um dia de folga para os fusos) nem antes de 1900. */
    private fun requireBirthDate(
        birthDate: LocalDate?,
        now: Instant,
    ) {
        if (birthDate == null) return
        if (birthDate.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)) ||
            birthDate.isBefore(EARLIEST_BIRTH_DATE)
        ) {
            throw GuestsRules.invalid(
                GuestsErrorCodes.BIRTH_DATE_INVALID,
                "birthDate",
                "Data de nascimento inválida: no futuro ou de antes de 1900.",
            )
        }
    }

    override fun toString(): String = "Guest(id=$id)"

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_PREFERENCE_LENGTH = 60
        const val MAX_NOTES_LENGTH = 1000
        const val MAX_REASON_LENGTH = 500
        const val ADULT_AGE = 18
        private const val BRAZIL = "BR"
        private val EARLIEST_BIRTH_DATE: LocalDate = LocalDate.of(1900, 1, 1)
        private val EMPTY_SENSITIVE = SensitiveGuestData(null, null)

        fun create(
            profile: GuestProfile,
            now: Instant,
        ): Guest = Guest(GuestId.new(), profile, now)
    }
}
