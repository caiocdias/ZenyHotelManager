package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestMergeField
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsMerged
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestDocument
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Detecção e mesclagem de perfis duplicados (RF-HOS-05, H6 do M3; regras decididas pelo usuário em 2026-10-17).
 *
 * - **Candidatos:** pares de perfis ativos com um documento igual (pelo índice cego, sem decifrar nada) ou o mesmo
 *   e-mail; exige `guests.profiles.read`.
 * - **Mesclagem:** exige `guests.profiles.merge` (não sensível). Trava os dois perfis em ordem de id, confere que são
 *   diferentes e ativos (`409`) e a versão do sobrevivente (`If-Match`), aplica as regras do domínio
 *   ([Guest.absorb]), passa os documentos que faltavam ao sobrevivente, grava a trilha com o estado anterior dos dois
 *   (H10, sem dado pessoal) e publica [GuestsMerged] na mesma transação (outbox). O absorvido nunca é apagado.
 */
@Service
@Suppress("LongParameterList") // as portas de que a mesclagem precisa
class GuestMergeService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val cipher: FieldCipher,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** Os pares de candidatos a duplicado, em ordem dos ids; só os de [guestId], se informado. */
    @Transactional(readOnly = true)
    fun duplicateCandidates(
        guestId: GuestId?,
        page: PageParams,
    ): CursorPage<DuplicateCandidateView> {
        authorization.require(GuestsPermissions.PROFILES_READ)
        if (guestId != null && store.guest(guestId) == null) throw guestNotFound()
        val after = page.decodedCursor()?.let { it.uuid(0) to it.uuid(1) }
        val rows = directory.duplicatePairs(guestId, after, page.limit + 1)
        val cursorPage =
            CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.firstId.toString(), it.secondId.toString()) }
        val profiles =
            store
                .guests(cursorPage.items.flatMap { listOf(it.firstId, it.secondId) }.distinct())
                .associateBy { it.id.value }
        return cursorPage.map { row ->
            DuplicateCandidateView(
                first = checkNotNull(profiles[row.firstId]).view(),
                second = checkNotNull(profiles[row.secondId]).view(),
                reasons =
                    buildList {
                        if (row.documentTypes.isNotEmpty()) add(DuplicateReason.DOCUMENT)
                        if (row.sameEmail) add(DuplicateReason.EMAIL)
                    },
                documentTypes = row.documentTypes,
            )
        }
    }

    /**
     * Mescla [mergedId] em [survivorId]: devolve o sobrevivente. Recusas: algum dos dois inexistente, `404
     * GUEST_NOT_FOUND`; o mesmo perfil, `409 GUEST_MERGE_SAME_PROFILE`; algum já mesclado, `409
     * GUEST_ALREADY_MERGED`; versão do sobrevivente diferente de [expectedVersion], `412`; documento do mesmo tipo e
     * país com outro número, `409 GUEST_MERGE_DOCUMENT_CONFLICT`.
     */
    @Audited(GuestsAudit.MERGE_GUEST)
    @Transactional
    fun merge(
        survivorId: GuestId,
        mergedId: GuestId,
        expectedVersion: Long,
        takeFromMerged: Set<GuestMergeField>,
    ): GuestView {
        authorization.require(GuestsPermissions.PROFILES_MERGE)
        // O mesmo id duas vezes devolve o mesmo perfil, que o domínio recusa (GUEST_MERGE_SAME_PROFILE).
        val (survivor, merged) = store.guestsForUpdate(listOf(survivorId, mergedId)).map { it ?: throw guestNotFound() }
        survivor.requireMergeable(merged)
        requireCurrentVersion(expectedVersion, checkNotNull(survivor.version))
        val survivorBefore = survivor.auditState()
        val mergedBefore = merged.auditState()
        val movedFrom = merged.documents.associateBy { it.id }
        val now = clock.instant()

        val result = survivor.absorb(merged, takeFromMerged, ::sameNumber, currentActor.currentOrNull()?.userId, now)
        if (!store.moveDocuments(result.moved, survivor.id)) throw documentConflict()
        val consentsBefore = merged.consents.associate { it.id to it.auditState(merged.id) }
        store.moveConsents(result.consents, survivor.id, now)
        store.refresh(survivor)
        store.refresh(merged)

        audit.record(
            GuestsAudit.GUEST,
            survivor.id.value,
            survivorBefore,
            withChangedFields(survivor.auditState(), result.changedFields) + ("absorbed" to merged.id.toString()),
        )
        audit.record(
            GuestsAudit.GUEST,
            merged.id.value,
            mergedBefore,
            withChangedFields(
                merged.auditState(),
                listOf("status", "mergedInto") +
                    listOfNotNull(
                        "dietaryRestrictions".takeIf { mergedBefore["hasDietaryRestrictions"] == true },
                        "consents".takeIf { result.consents.isNotEmpty() },
                    ),
            ),
        )
        result.moved.forEach { id ->
            val document = checkNotNull(movedFrom[id])
            audit.record(
                GuestsAudit.GUEST_DOCUMENT,
                id.value,
                document.auditState() + ("guestId" to merged.id.toString()),
                document.auditState() + ("guestId" to survivor.id.toString()),
            )
        }
        result.consents.forEach { move ->
            audit.record(
                GuestsAudit.GUEST_CONSENT,
                move.id.value,
                consentsBefore.getValue(move.id),
                checkNotNull(survivor.consent(move.id)).auditState(survivor.id) +
                    ("supersededBy" to move.supersededBy?.toString()),
            )
        }
        // O instante gravado no absorvido (na precisão do banco), o mesmo que a leitura devolve.
        events.publishEvent(GuestsMerged(survivor.id.value, merged.id.value, checkNotNull(merged.mergedAt)))
        return survivor.view()
    }

    /**
     * Se dois documentos do mesmo tipo e país têm o mesmo número: pelo índice cego quando os dois foram indexados com
     * a mesma chave do anel (nada se decifra); senão (no meio de uma rotação), decifrando só os dois, em memória.
     */
    private fun sameNumber(
        mine: GuestDocument,
        theirs: GuestDocument,
    ): Boolean =
        if (mine.numberIndexKey == theirs.numberIndexKey) {
            mine.numberIndex == theirs.numberIndex
        } else {
            cipher.decrypt(mine.encryptedNumber) == cipher.decrypt(theirs.encryptedNumber)
        }

    private fun guestNotFound() = NotFoundException(GuestsErrorCodes.GUEST_NOT_FOUND, "Hóspede não encontrado.")

    private fun documentConflict() =
        ConflictException(
            GuestsErrorCodes.GUEST_MERGE_DOCUMENT_CONFLICT,
            "O perfil sobrevivente já tem um documento do mesmo tipo e país; retire o errado antes de mesclar.",
        )
}
