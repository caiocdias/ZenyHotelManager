package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Endereço como chega da API, ainda sem a validação do [Address]. */
data class AddressCommand(
    val street: String,
    val number: String,
    val complement: String?,
    val district: String,
    val city: String,
    val state: BrazilianState,
    val postalCode: String,
    val ibgeCityCode: String,
) {
    /** Nunca mostra o endereço. */
    override fun toString(): String = "AddressCommand(***)"
}

/** Os dados de um perfil como chegam da API (RF-HOS-01 e 02). */
data class GuestCommand(
    val fullName: String,
    val socialName: String? = null,
    val nationality: String? = null,
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val email: String? = null,
    val phone: String? = null,
    val residenceCountry: String? = null,
    val address: AddressCommand? = null,
    val preferences: GuestPreferences = GuestPreferences(),
    val vip: Boolean = false,
    val notes: String? = null,
    val companyId: CompanyId? = null,
    /** Restrições alimentares: recusadas até haver consentimento (E03 do M3). */
    val dietaryRestrictions: String? = null,
) {
    override fun toString(): String = "GuestCommand(***)"
}

/** Um documento como chega da API. [toString] nunca mostra o número. */
data class DocumentCommand(
    val type: DocumentType,
    val issuingCountry: String?,
    val number: String,
) {
    override fun toString(): String = "DocumentCommand(type=$type, issuingCountry=$issuingCountry)"
}

/** Os dados sensíveis da FNRH como chegam da API (RN-19). [toString] nunca os mostra. */
data class SensitiveDataCommand(
    val raceColor: RaceColor?,
    val disability: String?,
) {
    override fun toString(): String = "SensitiveDataCommand(***)"
}

/** Os dados de uma empresa como chegam da API (RF-HOS-06). */
data class CompanyCommand(
    val legalName: String,
    val tradeName: String? = null,
    val cnpj: Cnpj,
    val stateRegistration: String? = null,
    val municipalRegistration: String? = null,
    val address: AddressCommand? = null,
    val contactName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val billingEmail: String? = null,
    val creditLimit: Money? = null,
)

/** Filtros da busca de perfis; todos opcionais e combinados com "e". */
data class GuestFilter(
    /** Trecho do nome ou do nome social, sem diferenciar acento nem maiúsculas. */
    val name: String? = null,
    /** E-mail exato (sem diferenciar maiúsculas). */
    val email: String? = null,
    val restricted: Boolean? = null,
    val vip: Boolean? = null,
    val companyId: CompanyId? = null,
)

/** Filtros da busca de empresas; todos opcionais e combinados com "e". */
data class CompanyFilter(
    /** Trecho da razão social ou do nome fantasia, sem diferenciar acento nem maiúsculas. */
    val name: String? = null,
    val cnpj: Cnpj? = null,
    val active: Boolean? = null,
)

/** Uma linha da busca: o id e a chave de ordenação (o nome normalizado), para o cursor. */
data class SearchRow(
    val id: UUID,
    val searchName: String,
)

/** Os agregados de `guests` na transação corrente (JPA). */
interface GuestsStore {
    fun guest(id: GuestId): Guest?

    /** Os perfis com os [ids], na ordem dos [ids] (os inexistentes ficam de fora). */
    fun guests(ids: List<UUID>): List<Guest>

    fun add(guest: Guest)

    fun company(id: CompanyId): Company?

    /** As empresas com os [ids], na ordem dos [ids]. */
    fun companies(ids: List<UUID>): List<Company>

    /** Grava a empresa nova; `false` se o CNPJ já é de outra (restrição `company_cnpj_uk`). */
    fun tryAdd(company: Company): Boolean

    fun flush()

    /** Envia as mudanças; `false` se violaram a restrição [constraint]. */
    fun tryFlush(constraint: String): Boolean
}

object GuestsConstraints {
    const val COMPANY_CNPJ = "company_cnpj_uk"
    const val DOCUMENT_KIND = "guest_document_kind_uk"
}

/** As buscas de `guests` pelo `JdbcClient` (só ids e a chave de ordenação; nada se decifra). */
interface GuestsDirectory {
    /** Até [limit] perfis que casam com o filtro, em ordem de nome normalizado e id, depois de [after]. */
    fun searchGuests(
        filter: GuestFilter,
        after: SearchRow?,
        limit: Int,
    ): List<SearchRow>

    /**
     * Até [limit] perfis com um documento cujo índice cego é um dos [hashes] (um por chave do anel), em ordem
     * de nome. A consulta compara só a coluna do índice: nenhum número é decifrado.
     */
    fun guestsWithDocumentIndex(
        hashes: Collection<String>,
        limit: Int,
    ): List<SearchRow>

    /** Até [limit] empresas que casam com o filtro, em ordem de nome normalizado e id, depois de [after]. */
    fun searchCompanies(
        filter: CompanyFilter,
        after: SearchRow?,
        limit: Int,
    ): List<SearchRow>
}

/** Um documento numa resposta: o número só mascarado (H9). */
data class GuestDocumentView(
    val id: GuestDocumentId,
    val type: DocumentType,
    val issuingCountry: String,
    val maskedNumber: String,
    val createdAt: Instant,
)

/** A inclusão na lista de restrição (RF-HOS-04). */
data class RestrictionView(
    val reason: String,
    val since: Instant,
    val restrictedBy: UserId?,
)

/**
 * Um perfil como a API o devolve: os documentos mascarados e só a indicação de que há dados sensíveis da FNRH
 * ([hasSensitiveData]), nunca os valores (RN-19).
 */
data class GuestView(
    val id: GuestId,
    val fullName: String,
    val socialName: String?,
    val nationality: String?,
    val birthDate: LocalDate?,
    val gender: Gender?,
    val email: String?,
    val phone: String?,
    val residenceCountry: String?,
    val address: Address?,
    val preferences: GuestPreferences,
    val vip: Boolean,
    val notes: String?,
    val companyId: CompanyId?,
    val restriction: RestrictionView?,
    val documents: List<GuestDocumentView>,
    val hasSensitiveData: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    override fun toString(): String = "GuestView(id=$id)"
}

/** O número inteiro de um documento, revelado por uma ação auditada (H9). [toString] não o mostra. */
data class RevealedDocument(
    val id: GuestDocumentId,
    val guestId: GuestId,
    val type: DocumentType,
    val issuingCountry: String,
    val number: String,
) {
    override fun toString(): String = "RevealedDocument(id=$id, type=$type)"
}

data class CompanyView(
    val id: CompanyId,
    val legalName: String,
    val tradeName: String?,
    val cnpj: Cnpj,
    val stateRegistration: String?,
    val municipalRegistration: String?,
    val address: Address?,
    val contactName: String?,
    val phone: String?,
    val email: String?,
    val billingEmail: String?,
    val creditLimit: Money?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)
