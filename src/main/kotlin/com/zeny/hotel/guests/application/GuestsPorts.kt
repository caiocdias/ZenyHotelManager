package com.zeny.hotel.guests.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.ConsentPurpose
import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestConsentId
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.ConsentMove
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
    /** Restrições alimentares: só com o consentimento ativo da finalidade `DIETARY_RESTRICTIONS`. */
    val dietaryRestrictions: String? = null,
) {
    override fun toString(): String = "GuestCommand(***)"
}

/** Um consentimento como chega da API: a finalidade e o meio pelo qual o titular o deu ou revogou. */
data class ConsentCommand(
    val purpose: ConsentPurpose,
    val source: ConsentSource,
)

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
@Suppress("TooManyFunctions") // os dois agregados e as trocas de dono da mesclagem
interface GuestsStore {
    fun guest(id: GuestId): Guest?

    /** Os perfis com os [ids], na ordem dos [ids] (os inexistentes ficam de fora). */
    fun guests(ids: List<UUID>): List<Guest>

    fun add(guest: Guest)

    /**
     * Os perfis com os [ids], travados (`SELECT … FOR UPDATE`) sempre na mesma ordem (pelo id), para duas mesclagens
     * simultâneas se enfileirarem sem deadlock; na ordem dos [ids], `null` no que não existe. Quem esperou a trava
     * recebe o perfil como a transação anterior o deixou.
     */
    fun guestsForUpdate(ids: List<GuestId>): List<Guest?>

    /**
     * Passa os documentos [documentIds] para o perfil [to] (a mesma linha, só muda o dono), depois de enviar as
     * mudanças pendentes; `false` se o perfil já tinha um documento do mesmo tipo e país (`guest_document_kind_uk`).
     */
    fun moveDocuments(
        documentIds: List<GuestDocumentId>,
        to: GuestId,
    ): Boolean

    /**
     * Passa os consentimentos do absorvido para o perfil [to] na mesclagem (a mesma linha, só muda o dono), depois de
     * enviar as mudanças pendentes; os que têm [ConsentMove.supersededBy] ficam antes substituídos em [now] (deixam
     * de valer sem ser uma revogação). Os consentimentos nunca são apagados.
     */
    fun moveConsents(
        moves: List<ConsentMove>,
        to: GuestId,
        now: Instant,
    )

    /** Recarrega o perfil (e os documentos) do banco, descartando o que estava em memória. */
    fun refresh(guest: Guest)

    fun company(id: CompanyId): Company?

    /** As empresas com os [ids], na ordem dos [ids]. */
    fun companies(ids: List<UUID>): List<Company>

    /** Grava a empresa nova; `false` se o CNPJ já é de outra (restrição `company_cnpj_uk`). */
    fun tryAdd(company: Company): Boolean

    fun flush()

    /** Envia as mudanças; `false` se violaram a restrição [constraint]. */
    fun tryFlush(constraint: String): Boolean
}

/** Os vínculos dos perfis com os anexos do `files` (`guests.guest_attachment`, pelo `JdbcClient`). */
interface GuestAttachmentsStore {
    /** Os anexos dos perfis [guestIds], em ordem de vínculo. */
    fun attachments(guestIds: Collection<GuestId>): List<GuestAttachmentRow>

    /** O vínculo do anexo [fileId], de qualquer perfil; `null` se não há. */
    fun attachment(fileId: FileId): GuestAttachmentRow?

    fun add(row: GuestAttachmentRow)

    fun remove(fileId: FileId)
}

/** O vínculo de um perfil com um anexo do `files`. */
data class GuestAttachmentRow(
    val fileId: FileId,
    val guestId: GuestId,
    val purpose: FilePurposeCode,
    val attachedAt: Instant,
    val attachedBy: UserId?,
)

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

    /**
     * Até [limit] pares de perfis **ativos** que parecem o mesmo hóspede (RF-HOS-05): com um documento de mesmo
     * índice cego (o mesmo tipo, país e número, indexado com a mesma chave do anel) ou o mesmo e-mail (já gravado em
     * minúsculas). Cada par uma vez, com o menor id primeiro, em ordem dos dois ids, depois de [after]; só os de
     * [guestId], se informado. Nada se decifra.
     */
    fun duplicatePairs(
        guestId: GuestId?,
        after: Pair<UUID, UUID>?,
        limit: Int,
    ): List<DuplicatePairRow>

    /**
     * Os perfis absorvidos, direta ou indiretamente (cadeia A → B → C), pelo perfil [survivorId] em mesclagens
     * (RF-HOS-05), sem ele mesmo; em ordem de id.
     */
    fun absorbedChain(survivorId: GuestId): List<GuestId>

    /**
     * O perfil [id] e, se ele foi mesclado, os sobreviventes seguintes (`merged_into`, até um que não foi mesclado),
     * nessa ordem; vazio se [id] não existe. Lido sem trava: quem chama trava os perfis depois, em ordem de id.
     */
    fun survivorChain(id: GuestId): List<GuestId>

    /** Até [limit] empresas que casam com o filtro, em ordem de nome normalizado e id, depois de [after]. */
    fun searchCompanies(
        filter: CompanyFilter,
        after: SearchRow?,
        limit: Int,
    ): List<SearchRow>
}

/** Um par de candidatos a duplicado: os dois ids (o menor primeiro) e o que eles têm em comum. */
data class DuplicatePairRow(
    val firstId: UUID,
    val secondId: UUID,
    val sameEmail: Boolean,
    /** Os tipos dos documentos iguais (vazio: só o e-mail). */
    val documentTypes: List<DocumentType>,
)

/** O que dois candidatos a duplicado têm em comum. */
enum class DuplicateReason {
    DOCUMENT,
    EMAIL,
}

/** Um par de candidatos a duplicado como a API o devolve: os dois perfis e os motivos. */
data class DuplicateCandidateView(
    val first: GuestView,
    val second: GuestView,
    val reasons: List<DuplicateReason>,
    val documentTypes: List<DocumentType>,
)

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
    val dietaryRestrictions: String?,
    val restriction: RestrictionView?,
    val documents: List<GuestDocumentView>,
    val hasSensitiveData: Boolean,
    val status: GuestStatus,
    val mergedInto: GuestId?,
    val mergedAt: Instant?,
    val anonymizedAt: Instant?,
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

/** Um consentimento numa resposta (RF-HOS-07). */
data class ConsentView(
    val id: GuestConsentId,
    val guestId: GuestId,
    val purpose: ConsentPurpose,
    val active: Boolean,
    val grantedAt: Instant,
    val grantedSource: ConsentSource,
    val grantedBy: UserId?,
    val revokedAt: Instant?,
    val revokedSource: ConsentSource?,
    val revokedBy: UserId?,
    val supersededAt: Instant?,
    val supersededBy: GuestConsentId?,
)

/** Um consentimento dado ou revogado, e se foi criado agora (`201`) ou já existia (`200`). */
data class ConsentResult(
    val consent: ConsentView,
    val created: Boolean,
)

/**
 * Um anexo de um perfil (RF-HOS-08): o vínculo e os metadados do `files` (sem o nome original nem o conteúdo, que
 * sai pela URL do `files`).
 */
data class GuestAttachmentView(
    val fileId: FileId,
    val guestId: GuestId,
    val purpose: FilePurposeCode,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: UserId?,
    val uploadedAt: Instant,
    val attachedAt: Instant,
    val attachedBy: UserId?,
)

/** Um anexo vinculado, e se o vínculo foi criado agora (`201`) ou já existia (`200`). */
data class GuestAttachmentResult(
    val attachment: GuestAttachmentView,
    val created: Boolean,
)

/** Uma seção do pacote de exportação: o nome do contribuidor e os dados dele. */
data class PersonalDataSection(
    val name: String,
    val data: Any,
)

/**
 * O pacote de exportação dos dados do titular (RF-HOS-07, H12): os perfis incluídos (o pedido e os absorvidos por
 * ele), as seções dos contribuidores e os anexos (o conteúdo sai pela URL do `files`).
 */
data class GuestDataExportView(
    val guestId: GuestId,
    val guestIds: List<GuestId>,
    val generatedAt: Instant,
    val sections: List<PersonalDataSection>,
    val attachments: List<GuestAttachmentView>,
) {
    override fun toString(): String = "GuestDataExportView(guestId=$guestId)"
}
