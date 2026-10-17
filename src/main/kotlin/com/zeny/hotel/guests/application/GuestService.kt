package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestDocument
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.guests.domain.GuestsRules
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.domain.NormalizedDocument
import com.zeny.hotel.guests.domain.SensitiveGuestData
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Perfis de hóspedes (RF-HOS-01, 02 e 04, RN-19, H8 a H10 do M3). Consultar exige `guests.profiles.read`;
 * cadastrar e corrigir (perfil, documentos e dados sensíveis da FNRH), `guests.profiles.manage`; revelar o número
 * de um documento, `guests.documents.reveal`; a lista de restrição, `guests.restrictions.manage`.
 *
 * - **Documentos:** o número é normalizado, validado por tipo, gravado cifrado e indexado pelo índice cego com a
 *   chave ativa ([BlindIndex.compute]); a busca procura o hash calculado com cada chave do anel
 *   ([BlindIndex.candidates]) e nunca decifra os outros registros. As respostas trazem o número mascarado; o
 *   inteiro só por [revealDocument], com cada acesso na trilha.
 * - **Dados sensíveis da FNRH** (raça/cor e deficiência): gravados cifrados, nunca devolvidos (só
 *   `hasSensitiveData`), lidos só pela integração FNRH (`GuestsApi.fnrhData`).
 * - **Trilha** (H10): retratos sem dado pessoal, com a lista dos campos alterados.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // um caso de uso por operação; as portas de que eles precisam
class GuestService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val blindIndex: BlindIndex,
    private val cipher: FieldCipher,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun search(
        filter: GuestFilter,
        page: PageParams,
    ): CursorPage<GuestView> {
        authorization.require(GuestsPermissions.PROFILES_READ)
        val after = page.decodedCursor()?.let { SearchRow(it.uuid(1), it.string(0)) }
        val normalized =
            filter.copy(
                name = filter.name?.let { GuestsRules.searchKey(it) }?.ifEmpty { null },
                email = GuestsRules.email("email", filter.email),
            )
        val rows = directory.searchGuests(normalized, after, page.limit + 1)
        val cursorPage = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.searchName, it.id.toString()) }
        return CursorPage(store.guests(cursorPage.items.map { it.id }).map { it.view() }, cursorPage.nextCursor)
    }

    /**
     * Os perfis com o documento informado, pelo índice cego (H8): o número é normalizado como no cadastro e
     * comparado só pelo hash, com cada chave do anel; nenhum outro registro é decifrado. Até [MAX_DOCUMENT_MATCHES].
     */
    @Transactional(readOnly = true)
    fun searchByDocument(document: DocumentCommand): List<GuestView> {
        authorization.require(GuestsPermissions.PROFILES_READ)
        return findByDocument(document).map { it.view() }
    }

    @Transactional(readOnly = true)
    fun guest(id: GuestId): GuestView {
        authorization.require(GuestsPermissions.PROFILES_READ)
        return find(id).view()
    }

    @Audited(GuestsAudit.CREATE_GUEST)
    @Transactional
    fun create(
        command: GuestCommand,
        documents: List<DocumentCommand>,
        sensitiveData: SensitiveDataCommand?,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val now = clock.instant()
        val normalizedDocuments =
            documents.mapIndexed { index, document ->
                IdDocuments.normalize(
                    document.type,
                    document.issuingCountry,
                    document.number,
                    "documents[$index].number",
                    "documents[$index].issuingCountry",
                )
            }
        val guest = Guest.create(profile(command, current = null), now)
        normalizedDocuments.forEach { addTo(guest, it, now) }
        sensitiveData?.let { guest.recordSensitiveData(SensitiveGuestData(it.raceColor, it.disability), now) }
        audit.created(GuestsAudit.GUEST, guest.id.value) { guest.auditState() }
        store.add(guest)
        return guest.view()
    }

    @Audited(GuestsAudit.UPDATE_GUEST)
    @Transactional
    fun update(
        id: GuestId,
        expectedVersion: Long,
        command: GuestCommand,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val guest = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(guest.version))
        val before = guest.auditState()
        val changed = guest.update(profile(command, current = guest.companyId), clock.instant())
        if (changed.isEmpty()) {
            audit.noChanges()
        } else {
            audit.record(GuestsAudit.GUEST, id.value, before, withChangedFields(guest.auditState(), changed))
            store.flush()
        }
        return guest.view()
    }

    @Audited(GuestsAudit.ADD_DOCUMENT)
    @Transactional
    fun addDocument(
        id: GuestId,
        command: DocumentCommand,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val document = IdDocuments.normalize(command.type, command.issuingCountry, command.number)
        val guest = find(id)
        val now = clock.instant()
        val added = addTo(guest, document, now)
        audit.created(GuestsAudit.GUEST_DOCUMENT, added.id.value) { added.auditState() + ("guestId" to id.toString()) }
        if (!store.tryFlush(GuestsConstraints.DOCUMENT_KIND)) throw documentAlreadyRecorded()
        return guest.view()
    }

    @Audited(GuestsAudit.REMOVE_DOCUMENT)
    @Transactional
    fun removeDocument(
        id: GuestId,
        documentId: GuestDocumentId,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val guest = find(id)
        val removed = guest.removeDocument(documentId, clock.instant()) ?: throw documentNotFound()
        audit.record(
            GuestsAudit.GUEST_DOCUMENT,
            documentId.value,
            removed.auditState() + ("guestId" to id.toString()),
            null,
        )
        store.flush()
        return guest.view()
    }

    /**
     * O número inteiro de um documento (H9, RNF-09): exige `guests.documents.reveal`, e cada acesso vai para a
     * trilha (só o id, o tipo e o país, nunca o número). Transação de escrita só pela entrada da trilha.
     */
    @Audited(GuestsAudit.REVEAL_DOCUMENT)
    @Transactional
    fun revealDocument(
        id: GuestId,
        documentId: GuestDocumentId,
    ): RevealedDocument {
        authorization.require(GuestsPermissions.DOCUMENTS_REVEAL)
        val document = find(id).document(documentId) ?: throw documentNotFound()
        audit.record(
            GuestsAudit.GUEST_DOCUMENT,
            documentId.value,
            null,
            document.auditState() + mapOf("guestId" to id.toString(), "access" to "reveal"),
        )
        // Decifra só o documento pedido.
        val number = cipher.decrypt(document.encryptedNumber)
        return RevealedDocument(document.id, id, document.type, document.issuingCountry, number)
    }

    /**
     * Grava os dados sensíveis da FNRH (RN-19); os dois vazios apagam o que havia. Define o valor (sem
     * `If-Match`): os dados nunca são lidos pela API, então não há versão vista a conferir.
     */
    @Audited(GuestsAudit.RECORD_SENSITIVE_DATA)
    @Transactional
    fun recordSensitiveData(
        id: GuestId,
        command: SensitiveDataCommand,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val data = SensitiveGuestData(command.raceColor, command.disability)
        val guest = find(id)
        val before = guest.auditState()
        if (guest.recordSensitiveData(data, clock.instant())) {
            audit.record(GuestsAudit.GUEST, id.value, before, withChangedFields(guest.auditState(), listOf(SENSITIVE)))
            store.flush()
        } else {
            audit.noChanges()
        }
        return guest.view()
    }

    /** Inclui na lista de restrição, ou troca o motivo (RF-HOS-04). */
    @Audited(GuestsAudit.RESTRICT_GUEST)
    @Transactional
    fun restrict(
        id: GuestId,
        reason: String,
    ): GuestView {
        authorization.require(GuestsPermissions.RESTRICTIONS_MANAGE)
        val guest = find(id)
        val before = guest.auditState()
        val wasRestricted = guest.restricted
        if (guest.restrict(reason, currentActor.currentOrNull()?.userId, clock.instant())) {
            val changed = if (wasRestricted) listOf("restrictionReason") else listOf("restricted", "restrictionReason")
            audit.record(GuestsAudit.GUEST, id.value, before, withChangedFields(guest.auditState(), changed))
            store.flush()
        } else {
            audit.noChanges()
        }
        return guest.view()
    }

    @Audited(GuestsAudit.LIFT_RESTRICTION)
    @Transactional
    fun liftRestriction(id: GuestId): GuestView {
        authorization.require(GuestsPermissions.RESTRICTIONS_MANAGE)
        val guest = find(id)
        val before = guest.auditState()
        if (guest.liftRestriction(clock.instant())) {
            audit.record(
                GuestsAudit.GUEST,
                id.value,
                before,
                withChangedFields(guest.auditState(), listOf("restricted", "restrictionReason")),
            )
            store.flush()
        } else {
            audit.noChanges()
        }
        return guest.view()
    }

    /** Grava o documento cifrado, com o índice cego da chave ativa e o sufixo da máscara. */
    private fun addTo(
        guest: Guest,
        document: NormalizedDocument,
        now: Instant,
    ): GuestDocument =
        guest.addDocument(
            document,
            cipher.encrypt(document.number),
            blindIndex.compute(IdDocuments.INDEX_PURPOSE, document.indexInput),
            now,
        )

    private fun findByDocument(document: DocumentCommand): List<Guest> =
        findGuestsByDocument(store, directory, blindIndex, document)

    private fun profile(
        command: GuestCommand,
        current: CompanyId?,
    ): GuestProfile {
        if (!command.dietaryRestrictions.isNullOrBlank()) {
            throw BusinessRuleException(
                GuestsErrorCodes.DIETARY_RESTRICTIONS_REQUIRE_CONSENT,
                "Restrições alimentares são dado sensível e só se gravam com o consentimento do hóspede.",
            )
        }
        if (command.companyId != null && command.companyId != current) requireActiveCompany(command.companyId)
        return GuestProfile(
            fullName = command.fullName,
            socialName = command.socialName,
            nationality = command.nationality,
            birthDate = command.birthDate,
            gender = command.gender,
            email = command.email,
            phone = command.phone,
            residenceCountry = command.residenceCountry,
            address = command.address?.toAddress(),
            preferences = command.preferences,
            vip = command.vip,
            notes = command.notes,
            companyId = command.companyId,
        )
    }

    /** A empresa nova no perfil existe (`400`) e está ativa (`422`); a que já estava fica mesmo desativada. */
    private fun requireActiveCompany(id: CompanyId) {
        val company =
            store.company(id)
                ?: throw GuestsRules.invalid(
                    GuestsErrorCodes.UNKNOWN_COMPANY,
                    "companyId",
                    "A empresa informada não existe.",
                )
        if (!company.active) {
            throw BusinessRuleException(GuestsErrorCodes.COMPANY_INACTIVE, "A empresa informada está desativada.")
        }
    }

    private fun find(id: GuestId): Guest =
        store.guest(id) ?: throw NotFoundException(GuestsErrorCodes.GUEST_NOT_FOUND, "Hóspede não encontrado.")

    private fun documentNotFound() =
        NotFoundException(GuestsErrorCodes.GUEST_DOCUMENT_NOT_FOUND, "Documento não encontrado.")

    private fun documentAlreadyRecorded() =
        ConflictException(
            GuestsErrorCodes.DOCUMENT_ALREADY_RECORDED,
            "O perfil já tem um documento deste tipo e país; retire o anterior para corrigir.",
        )

    private companion object {
        const val SENSITIVE = "sensitiveData"
    }
}

/** O máximo de perfis devolvidos pela busca por documento (duplicados são tratados na E02). */
const val MAX_DOCUMENT_MATCHES = 50

/**
 * Os perfis com o documento, pelo índice cego (H8): o número é normalizado como no cadastro e comparado só pelo
 * hash, calculado com cada chave do anel; nenhum outro registro é decifrado.
 */
internal fun findGuestsByDocument(
    store: GuestsStore,
    directory: GuestsDirectory,
    blindIndex: BlindIndex,
    document: DocumentCommand,
): List<Guest> {
    val normalized = IdDocuments.normalize(document.type, document.issuingCountry, document.number)
    val hashes = blindIndex.candidates(IdDocuments.INDEX_PURPOSE, normalized.indexInput).map { it.hash }
    return store.guests(directory.guestsWithDocumentIndex(hashes, MAX_DOCUMENT_MATCHES).map { it.id })
}
