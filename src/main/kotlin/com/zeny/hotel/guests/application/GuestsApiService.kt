package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.CompanySummary
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.FnrhGuestDocument
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestSummary
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.FieldCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A [GuestsApi]: leituras sem conferir permissão (quem chama já se autorizou). */
@Service
@Transactional(readOnly = true)
class GuestsApiService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val blindIndex: BlindIndex,
    private val cipher: FieldCipher,
) : GuestsApi {
    override fun guest(id: GuestId): GuestSummary? = store.guest(id)?.summary()

    override fun findByDocument(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
    ): List<GuestSummary> =
        findGuestsByDocument(store, directory, blindIndex, DocumentCommand(type, issuingCountry, number))
            .map { it.summary() }

    override fun restrictionAlert(id: GuestId): RestrictionAlert? =
        store.guest(id)?.takeIf { it.restricted }?.let {
            RestrictionAlert(it.id, checkNotNull(it.restrictionReason), checkNotNull(it.restrictedAt))
        }

    override fun isMinorOn(
        id: GuestId,
        date: LocalDate,
    ): Boolean? = store.guest(id)?.isMinorOn(date)

    override fun company(id: CompanyId): CompanySummary? =
        store.company(id)?.let {
            CompanySummary(it.id, it.legalName, it.tradeName, it.cnpj, it.billingEmail, it.creditLimit, it.active)
        }

    override fun fnrhData(id: GuestId): FnrhGuestData? =
        store.guest(id)?.let { guest ->
            val sensitive = guest.sensitiveData
            FnrhGuestData(
                id = guest.id,
                fullName = guest.fullName,
                socialName = guest.socialName,
                nationality = guest.nationality,
                birthDate = guest.birthDate,
                gender = guest.gender,
                email = guest.email,
                phone = guest.phone,
                residenceCountry = guest.residenceCountry,
                address = guest.address,
                documents =
                    guest.documents.map {
                        FnrhGuestDocument(
                            it.type,
                            it.issuingCountry,
                            cipher.decrypt(it.encryptedNumber),
                        )
                    },
                raceColor = sensitive?.raceColor,
                disability = sensitive?.disability,
            )
        }

    private fun Guest.summary() =
        GuestSummary(id, fullName, socialName, birthDate, nationality, vip, restricted, companyId)
}
