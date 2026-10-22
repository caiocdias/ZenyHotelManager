package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.CompanySummary
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.FnrhGuestDocument
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.GuestSummary
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.MAX_NAME_MATCHES
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestsRules
import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.FieldCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * A [GuestsApi]: leituras sem conferir permissão (quem chama já se autorizou). [guest] devolve o perfil pedido, mesmo
 * mesclado (com o sobrevivente em `mergedInto`); o alerta de restrição, a maioridade e os dados da FNRH de um perfil
 * mesclado são os do sobrevivente ([current]).
 *
 * Toda leitura relê o perfil do banco ([GuestsStore.freshGuest]), mesmo que a transação de quem chama já o tenha
 * lido: quem trava antes de reler (a criação de reservas, M4 E07) vê a mesclagem ou a anonimização confirmada entre as
 * duas leituras, e não a cópia do contexto de persistência.
 */
@Service
@Transactional(readOnly = true)
class GuestsApiService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val blindIndex: BlindIndex,
    private val cipher: FieldCipher,
) : GuestsApi {
    override fun guest(id: GuestId): GuestSummary? = store.freshGuest(id)?.summary()

    override fun findByDocument(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
    ): List<GuestSummary> =
        findGuestsByDocument(store, directory, blindIndex, DocumentCommand(type, issuingCountry, number))
            .map { it.summary() }

    override fun findIdsByName(
        name: String,
        limit: Int,
    ): List<GuestId> {
        require(limit in 1..MAX_NAME_MATCHES) { "O limite vai de 1 a $MAX_NAME_MATCHES: $limit" }
        val normalized = GuestsRules.searchKey(name).ifEmpty { return emptyList() }
        return directory.searchGuests(GuestFilter(name = normalized), null, limit).map { GuestId(it.id) }
    }

    override fun restrictionAlert(id: GuestId): RestrictionAlert? =
        current(id)?.takeIf { it.restricted }?.let {
            RestrictionAlert(it.id, checkNotNull(it.restrictionReason), checkNotNull(it.restrictedAt))
        }

    override fun isMinorOn(
        id: GuestId,
        date: LocalDate,
    ): Boolean? = current(id)?.isMinorOn(date)

    override fun preferences(id: GuestId): GuestPreferences? = current(id)?.preferences

    override fun company(id: CompanyId): CompanySummary? =
        store.company(id)?.let {
            CompanySummary(it.id, it.legalName, it.tradeName, it.cnpj, it.billingEmail, it.creditLimit, it.active)
        }

    override fun fnrhData(id: GuestId): FnrhGuestData? =
        current(id)?.let { guest ->
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

    /**
     * O perfil com os dados correntes do hóspede: o próprio, ou, num mesclado, o sobrevivente (seguindo a cadeia, se
     * o sobrevivente também foi absorvido depois).
     */
    private fun current(id: GuestId): Guest? {
        val start = store.freshGuest(id) ?: return null
        // Uma mesclagem nunca tem como sobrevivente um perfil já mesclado: a cadeia não fecha um ciclo.
        return generateSequence(start) { guest ->
            guest.mergedInto?.let { checkNotNull(store.freshGuest(it)) { "Sobrevivente inexistente: ${guest.id}" } }
        }.last()
    }

    private fun Guest.summary() =
        GuestSummary(id, fullName, socialName, birthDate, nationality, vip, restricted, companyId, status, mergedInto)
}
