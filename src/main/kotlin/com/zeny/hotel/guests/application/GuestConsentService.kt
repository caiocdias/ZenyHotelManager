package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.GuestConsentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Consentimentos do titular por finalidade (RF-HOS-02 e 07, SPEC §8.2.4): dados e revogados com o instante e o meio
 * (a origem), nunca apagados (prova, RN-20). Consultar exige `guests.profiles.read`; dar e revogar,
 * `guests.profiles.manage`. Revogar o consentimento de restrições alimentares apaga as restrições gravadas. Dar um
 * consentimento num perfil mesclado ou anonimizado é `409`; revogar nunca é recusado. As escritas travam o perfil
 * (`SELECT … FOR UPDATE`): dois pedidos simultâneos da mesma finalidade dão um consentimento só.
 */
@Service
class GuestConsentService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Os consentimentos do perfil, inclusive os revogados, do mais recente ao mais antigo. */
    @Transactional(readOnly = true)
    fun consents(id: GuestId): List<ConsentView> {
        authorization.require(GuestsPermissions.PROFILES_READ)
        val guest = store.guest(id) ?: throw guestNotFound()
        return guest.consents.reversed().map { it.view(id) }
    }

    /** Dá o consentimento da finalidade; com um ativo dela, devolve-o sem mudar nada. */
    @Audited(GuestsAudit.GRANT_CONSENT)
    @Transactional
    fun grant(
        id: GuestId,
        command: ConsentCommand,
    ): ConsentResult {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val guest = locked(id)
        val change = guest.grantConsent(command.purpose, command.source, actor(), clock.instant())
        if (change.changed) {
            audit.created(GuestsAudit.GUEST_CONSENT, change.consent.id.value) { change.consent.auditState(id) }
            store.flush()
        } else {
            audit.noChanges()
        }
        return ConsentResult(change.consent.view(id), change.changed)
    }

    /**
     * Revoga o consentimento (já revogado ou substituído: nada muda). **Nunca é recusado** pela situação do perfil
     * (LGPD art. 8º §5º): vale num perfil mesclado e num anonimizado. Pedido pelo id de um perfil mesclado, o
     * consentimento é procurado também no sobrevivente (seguindo a cadeia), para onde a mesclagem o levou. De outro
     * perfil ou inexistente: `404 CONSENT_NOT_FOUND`.
     */
    @Audited(GuestsAudit.REVOKE_CONSENT)
    @Transactional
    fun revoke(
        id: GuestId,
        consentId: GuestConsentId,
        source: ConsentSource,
    ): ConsentView {
        authorization.require(GuestsPermissions.PROFILES_MANAGE)
        val guest = ownerOf(id, consentId) ?: throw consentNotFound()
        val owner = guest.id
        val consent = checkNotNull(guest.consent(consentId))
        val before = consent.auditState(owner)
        val guestBefore = guest.auditState()
        val hadDietary = guest.dietaryRestrictions != null
        val change = checkNotNull(guest.revokeConsent(consentId, source, actor(), clock.instant()))
        if (change.changed) {
            audit.record(GuestsAudit.GUEST_CONSENT, consentId.value, before, change.consent.auditState(owner))
            if (hadDietary && guest.dietaryRestrictions == null) {
                audit.record(
                    GuestsAudit.GUEST,
                    owner.value,
                    guestBefore,
                    withChangedFields(guest.auditState(), listOf("dietaryRestrictions")),
                )
            }
            store.flush()
        } else {
            audit.noChanges()
        }
        return change.consent.view(owner)
    }

    /**
     * O perfil com o consentimento: [id] ou, se ele foi mesclado, um sobrevivente da cadeia. Os perfis da cadeia são
     * travados juntos, em ordem de id (`guestsForUpdate`, a mesma ordem da mesclagem e da anonimização): nenhuma
     * ordem de travas cruzada, nenhum deadlock. Se uma mesclagem concluída entre a leitura e a trava estendeu a cadeia,
     * `409 CONFLICT` (repita o pedido).
     */
    private fun ownerOf(
        id: GuestId,
        consentId: GuestConsentId,
    ): Guest? {
        val chain = directory.survivorChain(id).ifEmpty { throw guestNotFound() }
        val locked = store.guestsForUpdate(chain).map { checkNotNull(it) }
        locked.firstOrNull { it.consent(consentId) != null }?.let { return it }
        if (locked.last().mergedInto != null) {
            throw ConflictException(
                ErrorCodes.CONFLICT,
                "O perfil acabou de ser mesclado; repita o pedido.",
            )
        }
        return null
    }

    private fun locked(id: GuestId): Guest = store.guestsForUpdate(listOf(id)).single() ?: throw guestNotFound()

    private fun actor() = currentActor.currentOrNull()?.userId

    private fun guestNotFound() = NotFoundException(GuestsErrorCodes.GUEST_NOT_FOUND, "Hóspede não encontrado.")

    private fun consentNotFound() =
        NotFoundException(GuestsErrorCodes.CONSENT_NOT_FOUND, "Consentimento não encontrado.")
}
