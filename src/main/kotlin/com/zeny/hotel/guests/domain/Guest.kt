package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.ConsentPurpose
import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestConsentId
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestMergeField
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BlindIndexValue
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.BusinessRuleException
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
    /** Restrições alimentares: só com o consentimento ativo da finalidade `DIETARY_RESTRICTIONS` (RF-HOS-02). */
    val dietaryRestrictions: String? = null,
) {
    /** Nunca mostra dado pessoal. */
    override fun toString(): String = "GuestProfile(vip=$vip)"
}

/** Um consentimento dado na criação do perfil. */
data class ConsentGrant(
    val purpose: ConsentPurpose,
    val source: ConsentSource,
)

/**
 * Perfil de hóspede, pessoa física (RF-HOS-01, 02, 04 e 07, SPEC §8.2.4): os campos da FNRH, preferências, VIP,
 * observações, restrições alimentares (cifradas, só com consentimento), a lista de restrição, os documentos
 * ([GuestDocument], número cifrado com índice cego), os dados sensíveis da FNRH ([SensitiveGuestData], cifrados num
 * valor de tamanho fixo e nunca lidos pela API) e os consentimentos ([GuestConsent]). Nunca é apagado (o banco
 * recusa): a anonimização ([anonymize]) apaga os dados pessoais e mantém a linha. [toString] não mostra dado
 * pessoal.
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
    initialConsents: List<ConsentGrant>,
    grantedBy: UserId?,
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

    /** `MERGED` depois de absorvido por outro perfil ([mergedInto]); nunca volta a `ACTIVE`. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: GuestStatus = GuestStatus.ACTIVE
        protected set

    @Column(name = "merged_into")
    private var mergedIntoUuid: UUID? = null

    @Column(name = "merged_at")
    var mergedAt: Instant? = null
        protected set

    @Column(name = "merged_by")
    private var mergedByUuid: UUID? = null

    /** Restrições alimentares (dado de saúde), cifradas pelo conversor; só com o consentimento ativo. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "dietary_restrictions")
    var dietaryRestrictions: String? = null
        protected set

    /** Quando o perfil foi anonimizado a pedido do titular; `null` se não foi. */
    @Column(name = "anonymized_at")
    var anonymizedAt: Instant? = null
        protected set

    @Column(name = "anonymized_by")
    private var anonymizedByUuid: UUID? = null

    /** A UF do endereço, guardada na anonimização (relatórios agregados). */
    @Enumerated(EnumType.STRING)
    @Column(name = "anonymized_state")
    var anonymizedState: BrazilianState? = null
        protected set

    /** O código IBGE do município do endereço, guardado na anonimização. */
    @Column(name = "anonymized_ibge_city_code")
    var anonymizedIbgeCityCode: String? = null
        protected set

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false, updatable = false)
    @OrderBy("createdAt, id")
    private val documentList: MutableList<GuestDocument> = mutableListOf()

    @OneToMany(cascade = [CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REFRESH], fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false, updatable = false)
    @OrderBy("grantedAt, id")
    private val consentList: MutableList<GuestConsent> = mutableListOf()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    init {
        initialConsents.distinctBy { it.purpose }.forEach { grantConsent(it.purpose, it.source, grantedBy, createdAt) }
        apply(profile, createdAt)
    }

    val preferences: GuestPreferences get() = GuestPreferences(preferredFloor, preferredBed, preferredPillow)

    val companyId: CompanyId? get() = companyUuid?.let(::CompanyId)

    val restrictedBy: UserId? get() = restrictedByUuid?.let(::UserId)

    /** O sobrevivente que absorveu este perfil; `null` num perfil ativo. */
    val mergedInto: GuestId? get() = mergedIntoUuid?.let(::GuestId)

    /** Quem mesclou (`null`: o sistema, ou perfil ativo). */
    val mergedBy: UserId? get() = mergedByUuid?.let(::UserId)

    /** Quem anonimizou (`null`: o sistema, ou perfil não anonimizado). */
    val anonymizedBy: UserId? get() = anonymizedByUuid?.let(::UserId)

    val anonymized: Boolean get() = anonymizedAt != null

    /** Os consentimentos, inclusive os revogados, na ordem em que foram dados. */
    val consents: List<GuestConsent> get() = consentList.toList()

    fun hasActiveConsent(purpose: ConsentPurpose): Boolean = consentList.any { it.purpose == purpose && it.active }

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
        requireActive()
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
        requireActive()
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
        requireActive()
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
        requireActive()
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
        requireActive()
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
        requireActive()
        if (!restricted) return false
        restricted = false
        restrictionReason = null
        restrictedAt = null
        restrictedByUuid = null
        updatedAt = now
        return true
    }

    /**
     * Um perfil mesclado ou anonimizado não muda mais: `409 GUEST_ALREADY_MERGED` ou `409 GUEST_ANONYMIZED`
     * (o absorvido por um perfil anonimizado responde como mesclado).
     */
    fun requireActive() {
        if (status == GuestStatus.MERGED) {
            throw ConflictException(
                GuestsErrorCodes.GUEST_ALREADY_MERGED,
                "O perfil já foi mesclado em outro; use o perfil sobrevivente.",
            )
        }
        if (status == GuestStatus.ANONYMIZED) {
            throw ConflictException(
                GuestsErrorCodes.GUEST_ANONYMIZED,
                "O perfil foi anonimizado a pedido do titular e não muda mais.",
            )
        }
    }

    /**
     * Registra o consentimento do titular para a finalidade (RF-HOS-07); se já há um ativo dela, devolve-o sem mudar
     * nada (`created` falso).
     */
    fun grantConsent(
        purpose: ConsentPurpose,
        source: ConsentSource,
        by: UserId?,
        now: Instant,
    ): ConsentChange {
        requireActive()
        consentList.firstOrNull { it.purpose == purpose && it.active }?.let { return ConsentChange(it, false) }
        val consent = GuestConsent(GuestConsentId.new(), purpose, now, source, by)
        consentList += consent
        updatedAt = now
        return ConsentChange(consent, true)
    }

    fun consent(id: GuestConsentId): GuestConsent? = consentList.firstOrNull { it.id == id }

    /**
     * Revoga um consentimento; `null` se ele não é deste perfil. Revogar o de `DIETARY_RESTRICTIONS` apaga as
     * restrições alimentares gravadas (sem a base legal, o dado sai). Já revogado (ou substituído numa mesclagem):
     * nada muda. **Nunca é recusado** pela situação do perfil (LGPD art. 8º §5º: o titular revoga a qualquer tempo),
     * nem num perfil mesclado ou anonimizado.
     */
    fun revokeConsent(
        id: GuestConsentId,
        source: ConsentSource,
        by: UserId?,
        now: Instant,
    ): ConsentChange? =
        consent(id)?.let { consent ->
            val changed = consent.revoke(source, by, now)
            if (changed) {
                if (consent.purpose == ConsentPurpose.DIETARY_RESTRICTIONS &&
                    !hasActiveConsent(consent.purpose)
                ) {
                    dietaryRestrictions = null
                }
                updatedAt = now
            }
            ConsentChange(consent, changed)
        }

    /**
     * Anonimiza o perfil a pedido do titular (RF-HOS-07; decisões do usuário em 2026-10-17): mantém o id, os
     * consentimentos (prova), a nacionalidade, o país de residência, a UF e o município do endereço
     * ([anonymizedState], [anonymizedIbgeCityCode]), o VIP e as datas; apaga o nome (o nome completo vira
     * [ANONYMIZED_NAME]), o nome social, a data de nascimento, o gênero, os contatos, o endereço, as preferências, as
     * observações, as restrições alimentares, a empresa, a restrição (com o motivo), os dados sensíveis da FNRH e os
     * documentos (linhas inteiras, com o índice cego); os anexos são de quem chama (o `files`). Um perfil ativo fica
     * `ANONYMIZED`; um absorvido (da cadeia do pedido) continua `MERGED`, com [anonymizedAt]. Já anonimizado: `409
     * GUEST_ANONYMIZED`. Devolve os documentos apagados (para a trilha).
     */
    fun anonymize(
        by: UserId?,
        now: Instant,
    ): List<GuestDocument> {
        if (anonymized || status == GuestStatus.ANONYMIZED) {
            throw ConflictException(
                GuestsErrorCodes.GUEST_ANONYMIZED,
                "O perfil já foi anonimizado a pedido do titular.",
            )
        }
        anonymizedState = address?.state
        anonymizedIbgeCityCode = address?.ibgeCityCode
        fullName = ANONYMIZED_NAME
        socialName = null
        searchName = GuestsRules.searchKey(ANONYMIZED_NAME)
        birthDate = null
        gender = null
        email = null
        phone = null
        address = null
        preferredFloor = null
        preferredBed = null
        preferredPillow = null
        notes = null
        companyUuid = null
        restricted = false
        restrictionReason = null
        restrictedAt = null
        restrictedByUuid = null
        encodedSensitiveData = null
        dietaryRestrictions = null
        val removed = documentList.toList()
        documentList.clear()
        if (status == GuestStatus.ACTIVE) status = GuestStatus.ANONYMIZED
        anonymizedAt = now
        anonymizedByUuid = by?.value
        updatedAt = now
        return removed
    }

    /**
     * Confere se este perfil pode absorver [merged] (RF-HOS-05), sem mudar nada: o mesmo perfil, `409
     * GUEST_MERGE_SAME_PROFILE`; algum dos dois já mesclado, `409 GUEST_ALREADY_MERGED`.
     */
    fun requireMergeable(merged: Guest) {
        if (merged.id == id) {
            throw ConflictException(
                GuestsErrorCodes.GUEST_MERGE_SAME_PROFILE,
                "Um perfil não pode ser mesclado com ele mesmo.",
            )
        }
        requireActive()
        merged.requireActive()
    }

    /**
     * Absorve o perfil duplicado [merged] (RF-HOS-05, H6; regras decididas pelo usuário em 2026-10-17). Este perfil
     * é o sobrevivente; [merged] fica `MERGED`, com a referência a este, sem perder nenhum dado.
     *
     * - **Dados do perfil:** vale o que este perfil tem; os campos vazios dele recebem os do absorvido; os campos em
     *   [takeFromMerged] vêm do absorvido, se ele os tiver (a mesclagem nunca apaga um valor). País de residência e
     *   endereço vão juntos ([GuestMergeField.RESIDENCE]); os dados sensíveis da FNRH também seguem esta regra.
     * - **VIP:** se qualquer um for. **Restrição:** se qualquer um estiver restrito, o sobrevivente fica restrito, com
     *   o motivo dele; se só o absorvido estava, com o motivo, a data e o autor do absorvido.
     * - **Documentos:** os do absorvido de um tipo e país que este não tem passam para cá ([GuestMergeResult.moved]:
     *   quem chama troca o dono da linha); os iguais aos daqui ([sameNumber]: mesmo número) ficam no absorvido
     *   ([GuestMergeResult.duplicates]); do mesmo tipo e país com outro número, `409 GUEST_MERGE_DOCUMENT_CONFLICT`,
     *   sem mudar nada.
     *
     * - **Consentimentos** (M3 E03, decisão do usuário em 2026-10-17): todos os do absorvido passam para cá
     *   ([GuestMergeResult.consents]); o que estava ativo numa finalidade em que este já tem um ativo fica substituído
     *   pelo daqui (o sobrevivente prevalece). **Restrições alimentares:** pela regra dos dados do perfil
     *   ([GuestMergeField.DIETARY_RESTRICTIONS]), mas só se este terminar com o consentimento delas ativo (o dele ou o
     *   que vem do absorvido). O absorvido fica sem consentimento e sem restrição alimentar: nada dele continua legível
     *   ou revogável só por ele.
     *
     * Os documentos e os consentimentos não mudam de lista aqui (o mapeamento apagaria ou duplicaria a linha): quem
     * chama troca o dono das linhas de [GuestMergeResult.moved] e de [GuestMergeResult.consents] e recarrega os dois
     * perfis.
     */
    fun absorb(
        merged: Guest,
        takeFromMerged: Set<GuestMergeField>,
        sameNumber: (GuestDocument, GuestDocument) -> Boolean,
        by: UserId?,
        now: Instant,
    ): GuestMergeResult {
        requireMergeable(merged)
        val (moved, duplicates) = planDocuments(merged, sameNumber)
        val changed = apply(mergedProfile(merged, takeFromMerged), now).toMutableList()
        val sensitive =
            pick(GuestMergeField.SENSITIVE_DATA in takeFromMerged, encodedSensitiveData) {
                merged.encodedSensitiveData
            }
        if (sensitive != encodedSensitiveData) {
            encodedSensitiveData = sensitive
            changed += "sensitiveData"
        }
        val consentMoves =
            merged.consentList.map { theirs ->
                val prevailing =
                    if (theirs.active) consentList.firstOrNull { it.purpose == theirs.purpose && it.active } else null
                ConsentMove(theirs.id, prevailing?.id)
            }
        val dietaryConsent =
            hasActiveConsent(ConsentPurpose.DIETARY_RESTRICTIONS) ||
                merged.hasActiveConsent(ConsentPurpose.DIETARY_RESTRICTIONS)
        val dietary =
            if (dietaryConsent) {
                pick(GuestMergeField.DIETARY_RESTRICTIONS in takeFromMerged, dietaryRestrictions) {
                    merged.dietaryRestrictions
                }
            } else {
                null
            }
        if (dietary != dietaryRestrictions) {
            dietaryRestrictions = dietary
            changed += "dietaryRestrictions"
        }
        if (consentMoves.isNotEmpty()) changed += "consents"
        merged.dietaryRestrictions = null
        if (!restricted && merged.restricted) {
            restricted = true
            restrictionReason = merged.restrictionReason
            restrictedAt = merged.restrictedAt
            restrictedByUuid = merged.restrictedByUuid
            changed += listOf("restricted", "restrictionReason")
        }
        if (moved.isNotEmpty()) changed += "documents"
        updatedAt = now
        merged.status = GuestStatus.MERGED
        merged.mergedIntoUuid = id.value
        merged.mergedAt = now
        merged.mergedByUuid = by?.value
        merged.updatedAt = now
        return GuestMergeResult(changed, moved, duplicates, consentMoves)
    }

    /** Os documentos do absorvido que passam para cá e os iguais aos daqui; conflito, `409`. */
    private fun planDocuments(
        merged: Guest,
        sameNumber: (GuestDocument, GuestDocument) -> Boolean,
    ): Pair<List<GuestDocumentId>, List<GuestDocumentId>> {
        val moved = mutableListOf<GuestDocumentId>()
        val duplicates = mutableListOf<GuestDocumentId>()
        merged.documentList.forEach { theirs ->
            val mine = documentList.firstOrNull { it.type == theirs.type && it.issuingCountry == theirs.issuingCountry }
            when {
                mine == null -> moved += theirs.id

                sameNumber(mine, theirs) -> duplicates += theirs.id

                else -> throw ConflictException(
                    GuestsErrorCodes.GUEST_MERGE_DOCUMENT_CONFLICT,
                    "Os dois perfis têm um documento ${theirs.type} (${theirs.issuingCountry}) com números " +
                        "diferentes; retire o errado antes de mesclar.",
                )
            }
        }
        return moved to duplicates
    }

    /** Os dados do perfil sobrevivente depois da mesclagem (regra em [absorb]). */
    private fun mergedProfile(
        merged: Guest,
        take: Set<GuestMergeField>,
    ): GuestProfile {
        val residenceFromMerged =
            if (GuestMergeField.RESIDENCE in take) {
                merged.residenceCountry != null
            } else {
                residenceCountry == null && merged.residenceCountry != null
            }
        return GuestProfile(
            fullName = if (GuestMergeField.FULL_NAME in take) merged.fullName else fullName,
            socialName = pick(GuestMergeField.SOCIAL_NAME in take, socialName) { merged.socialName },
            nationality = pick(GuestMergeField.NATIONALITY in take, nationality) { merged.nationality },
            birthDate = pick(GuestMergeField.BIRTH_DATE in take, birthDate) { merged.birthDate },
            gender = pick(GuestMergeField.GENDER in take, gender) { merged.gender },
            email = pick(GuestMergeField.EMAIL in take, email) { merged.email },
            phone = pick(GuestMergeField.PHONE in take, phone) { merged.phone },
            residenceCountry = if (residenceFromMerged) merged.residenceCountry else residenceCountry,
            address = if (residenceFromMerged) merged.address else address,
            preferences =
                GuestPreferences(
                    pick(GuestMergeField.PREFERENCES in take, preferredFloor) { merged.preferredFloor },
                    pick(GuestMergeField.PREFERENCES in take, preferredBed) { merged.preferredBed },
                    pick(GuestMergeField.PREFERENCES in take, preferredPillow) { merged.preferredPillow },
                ),
            vip = vip || merged.vip,
            notes = pick(GuestMergeField.NOTES in take, notes) { merged.notes },
            companyId = pick(GuestMergeField.COMPANY in take, companyUuid) { merged.companyUuid }?.let(::CompanyId),
            // As restrições alimentares dependem do consentimento de cada perfil: fica a do sobrevivente.
            dietaryRestrictions = dietaryRestrictions,
        )
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
        val dietary = GuestsRules.optional("dietaryRestrictions", profile.dietaryRestrictions, MAX_DIETARY_LENGTH)
        if (dietary != null && dietary != dietaryRestrictions &&
            !hasActiveConsent(ConsentPurpose.DIETARY_RESTRICTIONS)
        ) {
            throw BusinessRuleException(
                GuestsErrorCodes.DIETARY_RESTRICTIONS_REQUIRE_CONSENT,
                "Restrições alimentares são dado sensível e só se gravam com o consentimento ativo do hóspede.",
            )
        }
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
        changes.set("dietaryRestrictions", dietaryRestrictions, dietary) { dietaryRestrictions = it }
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
        /**
         * A regra de cada campo na mesclagem: listado ([fromMerged]), o do absorvido, se houver; senão o do
         * sobrevivente ([mine]), ou o do absorvido quando o dele está vazio. Nunca troca um valor por vazio.
         */
        private fun <T> pick(
            fromMerged: Boolean,
            mine: T?,
            theirs: () -> T?,
        ): T? = if (fromMerged) theirs() ?: mine else mine ?: theirs()

        const val MAX_NAME_LENGTH = 200
        const val MAX_PREFERENCE_LENGTH = 60
        const val MAX_NOTES_LENGTH = 1000
        const val MAX_REASON_LENGTH = 500
        const val MAX_DIETARY_LENGTH = 500

        /** O nome completo de um perfil anonimizado (o banco confere o mesmo texto). */
        const val ANONYMIZED_NAME = "Hóspede anonimizado"
        const val ADULT_AGE = 18
        private const val BRAZIL = "BR"
        private val EARLIEST_BIRTH_DATE: LocalDate = LocalDate.of(1900, 1, 1)
        private val EMPTY_SENSITIVE = SensitiveGuestData(null, null)

        /** Um perfil novo; os [consents] são dados antes dos dados (as restrições alimentares dependem deles). */
        fun create(
            profile: GuestProfile,
            now: Instant,
            consents: List<ConsentGrant> = emptyList(),
            by: UserId? = null,
        ): Guest = Guest(GuestId.new(), profile, now, consents, by)
    }
}

/**
 * O que a mesclagem fez no sobrevivente ([Guest.absorb]): os campos que mudaram (para a trilha, H10), os documentos
 * do absorvido que passam para ele ([moved]) e os iguais aos dele, que ficam no absorvido ([duplicates]).
 */
data class GuestMergeResult(
    val changedFields: List<String>,
    val moved: List<GuestDocumentId>,
    val duplicates: List<GuestDocumentId>,
    /** Os consentimentos do absorvido, todos passando para o sobrevivente (M3 E03). */
    val consents: List<ConsentMove> = emptyList(),
)

/**
 * Um consentimento do absorvido que passa para o sobrevivente na mesclagem; [supersededBy]: o consentimento ativo do
 * sobrevivente que prevalece sobre ele (o dele estava ativo na mesma finalidade), ou `null`.
 */
data class ConsentMove(
    val id: GuestConsentId,
    val supersededBy: GuestConsentId?,
)

/** O consentimento depois de dado ou revogado, e se algo mudou. */
data class ConsentChange(
    val consent: GuestConsent,
    val changed: Boolean,
)
