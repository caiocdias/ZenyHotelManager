package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.ConsentPurpose
import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPersonalDataContributor
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.FieldCipher
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A seção `guests` da exportação dos dados do titular ([GuestPersonalDataContributor], RF-HOS-07): os perfis (o
 * pedido e os absorvidos por ele) com os dados cadastrais, as preferências, as observações, as restrições
 * alimentares, a restrição com o motivo e os consentimentos, e **o pacote completo** (direito de acesso, LGPD art.
 * 18; decisão do usuário em 2026-10-17): os documentos com o **número inteiro** decifrado e os **dados sensíveis da
 * FNRH** decifrados (raça/cor e deficiência). É a exceção à RN-19 ("só a integração FNRH tem acesso"): quem recebe é
 * o próprio titular, por uma ação sensível, aprovada e auditada (a trilha nunca leva os dados).
 */
@Component
class GuestsOwnPersonalData(
    private val store: GuestsStore,
    private val cipher: FieldCipher,
) : GuestPersonalDataContributor {
    override val section = "guests"

    override fun personalData(guestIds: List<GuestId>): Any? {
        val profiles = store.guests(guestIds.map { it.value })
        if (profiles.isEmpty()) return null
        return GuestsPersonalData(
            profiles = profiles.map { it.exported() },
            consents =
                profiles.flatMap { guest ->
                    guest.consents.map {
                        ExportedConsent(
                            guestId = guest.id.value,
                            purpose = it.purpose,
                            grantedAt = it.grantedAt,
                            grantedSource = it.grantedSource,
                            revokedAt = it.revokedAt,
                            revokedSource = it.revokedSource,
                            supersededAt = it.supersededAt,
                        )
                    }
                },
        )
    }

    private fun Guest.exported() =
        ExportedGuestProfile(
            id = id.value,
            status = status,
            mergedInto = mergedInto?.value,
            fullName = fullName,
            socialName = socialName,
            nationality = nationality,
            birthDate = birthDate,
            gender = gender,
            email = email,
            phone = phone,
            residenceCountry = residenceCountry,
            address =
                address?.let {
                    ExportedAddress(
                        it.street,
                        it.number,
                        it.complement,
                        it.district,
                        it.city,
                        it.state.name,
                        it.postalCode,
                        it.ibgeCityCode,
                    )
                },
            preferences = preferences,
            vip = vip,
            notes = notes,
            dietaryRestrictions = dietaryRestrictions,
            companyId = companyId?.value,
            restriction = restrictionReason?.let { ExportedRestriction(it, restrictedAt) },
            documents =
                documents.map {
                    ExportedDocument(it.type, it.issuingCountry, cipher.decrypt(it.encryptedNumber), it.createdAt)
                },
            raceColor = sensitiveData?.raceColor,
            disability = sensitiveData?.disability,
            createdAt = createdAt,
            updatedAt = updatedAt,
            mergedAt = mergedAt,
            anonymizedAt = anonymizedAt,
        )
}

/** Os dados do `guests` no pacote de exportação. */
data class GuestsPersonalData(
    val profiles: List<ExportedGuestProfile>,
    val consents: List<ExportedConsent>,
) {
    override fun toString(): String = "GuestsPersonalData(profiles=${profiles.size})"
}

/** Um perfil no pacote de exportação, completo: documentos inteiros e os dados sensíveis da FNRH. */
data class ExportedGuestProfile(
    val id: UUID,
    val status: GuestStatus,
    val mergedInto: UUID?,
    val fullName: String,
    val socialName: String?,
    val nationality: String?,
    val birthDate: LocalDate?,
    val gender: Gender?,
    val email: String?,
    val phone: String?,
    val residenceCountry: String?,
    val address: ExportedAddress?,
    val preferences: GuestPreferences,
    val vip: Boolean,
    val notes: String?,
    val dietaryRestrictions: String?,
    val companyId: UUID?,
    val restriction: ExportedRestriction?,
    val documents: List<ExportedDocument>,
    /** Raça/cor (dado sensível da FNRH, RN-19), decifrada só para o titular. */
    val raceColor: RaceColor?,
    /** Deficiência (código do domínio da FNRH), decifrada só para o titular. */
    val disability: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val mergedAt: Instant?,
    val anonymizedAt: Instant?,
) {
    override fun toString(): String = "ExportedGuestProfile(id=$id)"
}

data class ExportedAddress(
    val street: String,
    val number: String,
    val complement: String?,
    val district: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val ibgeCityCode: String,
) {
    override fun toString(): String = "ExportedAddress(***)"
}

data class ExportedRestriction(
    val reason: String,
    val since: Instant?,
) {
    override fun toString(): String = "ExportedRestriction(***)"
}

data class ExportedDocument(
    val type: DocumentType,
    val issuingCountry: String,
    /** O número inteiro, decifrado só para o titular. */
    val number: String,
    val createdAt: Instant,
) {
    override fun toString(): String = "ExportedDocument(type=$type, issuingCountry=$issuingCountry)"
}

data class ExportedConsent(
    val guestId: UUID,
    val purpose: ConsentPurpose,
    val grantedAt: Instant,
    val grantedSource: ConsentSource,
    val revokedAt: Instant?,
    val revokedSource: ConsentSource?,
    val supersededAt: Instant?,
)
