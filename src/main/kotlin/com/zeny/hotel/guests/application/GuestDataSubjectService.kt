package com.zeny.hotel.guests.application

import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.guests.GuestActiveStayCheck
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPersonalDataContributor
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.SensitiveAction
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Os direitos do titular atendidos sob pedido (RF-HOS-07, RF-ACS-08, H3 e H12 do M3; decisões do usuário em
 * 2026-10-17): exportar e anonimizar. As duas são **ações sensíveis**: sem a permissão (`guests.data.export`,
 * `guests.data.anonymize`), `403 APPROVAL_REQUIRED`, e com o `X-Approval-Token` de um superior a ação é concluída e
 * auditada com a aprovação. O pedido vale para o titular inteiro: o perfil pedido e os absorvidos por ele em
 * mesclagens (a cadeia); pedido num perfil mesclado, `409 GUEST_ALREADY_MERGED` (faça-o no sobrevivente).
 *
 * - **Exportação** (H12): o pacote vai na própria resposta (nada é guardado, e o endpoint não tem `@Idempotent`),
 *   com uma seção por [GuestPersonalDataContributor] e a lista dos anexos (o conteúdo sai pela URL do `files`). A
 *   trilha registra o acesso sem os dados exportados.
 * - **Anonimização** ([Guest.anonymize]): apaga os dados pessoais, os documentos (com o índice cego) e os anexos
 *   (`FilesApi.delete`) de todos os perfis da cadeia e mantém o id, os consentimentos e os dados agregados; recusada
 *   (`409 GUEST_HAS_ACTIVE_STAY`) se algum [GuestActiveStayCheck] informar estadia ativa. Já anonimizado: `409
 *   GUEST_ANONYMIZED`. Trava o perfil pedido antes de achar a cadeia (uma mesclagem simultânea espera e depois é
 *   recusada) e os demais em ordem de id.
 */
@Service
@Suppress("LongParameterList") // as portas de que os dois casos de uso precisam
class GuestDataSubjectService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val attachments: GuestAttachmentsStore,
    private val files: FilesApi,
    private val contributors: ObjectProvider<GuestPersonalDataContributor>,
    private val activeStays: ObjectProvider<GuestActiveStayCheck>,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) : SmartInitializingSingleton {
    /** Confere no boot os nomes das seções: no formato e sem repetição. */
    override fun afterSingletonsInstantiated() {
        sortedContributors()
    }

    /** O pacote com os dados pessoais do titular (o perfil [id] e os absorvidos por ele). */
    @Audited(GuestsAudit.EXPORT_GUEST)
    @Transactional
    fun export(
        id: GuestId,
        approvalToken: String?,
    ): GuestDataExportView {
        authorization.requireOrApproval(
            SensitiveAction(GuestsAudit.EXPORT_GUEST, GuestsPermissions.DATA_EXPORT, resourceRef(id)),
            approvalToken,
        )
        val guest = store.guest(id) ?: throw guestNotFound()
        if (guest.status == GuestStatus.MERGED) throw alreadyMerged()
        val guestIds = listOf(id) + directory.absorbedChain(id)
        val sections =
            sortedContributors().mapNotNull { contributor ->
                contributor.personalData(guestIds)?.let { PersonalDataSection(contributor.section, it) }
            }
        val exported = attachmentViews(files, attachments.attachments(guestIds))
        audit.record(
            GuestsAudit.GUEST,
            id.value,
            null,
            mapOf(
                "access" to "export",
                "guestIds" to guestIds.map { it.toString() },
                "sections" to sections.map { it.name },
                "attachments" to exported.map { it.fileId.toString() },
            ),
        )
        return GuestDataExportView(id, guestIds, clock.instant(), sections, exported)
    }

    /** Anonimiza o perfil [id] e os absorvidos por ele; devolve o perfil anonimizado. */
    @Audited(GuestsAudit.ANONYMIZE_GUEST)
    @Transactional
    fun anonymize(
        id: GuestId,
        approvalToken: String?,
    ): GuestView {
        authorization.requireOrApproval(
            SensitiveAction(GuestsAudit.ANONYMIZE_GUEST, GuestsPermissions.DATA_ANONYMIZE, resourceRef(id)),
            approvalToken,
        )
        val guest = store.guestsForUpdate(listOf(id)).single() ?: throw guestNotFound()
        guest.requireActive()
        val absorbed = store.guestsForUpdate(directory.absorbedChain(id)).map { checkNotNull(it) }
        val profiles = listOf(guest) + absorbed
        val guestIds = profiles.map { it.id }
        if (activeStays.orderedStream().anyMatch { it.hasActiveStay(guestIds) }) {
            throw ConflictException(
                GuestsErrorCodes.GUEST_HAS_ACTIVE_STAY,
                "O hóspede tem estadia ativa; a anonimização espera ela terminar.",
            )
        }
        val by = currentActor.currentOrNull()?.userId
        val now = clock.instant()
        profiles.filterNot { it.anonymized }.forEach { profile ->
            val before = profile.auditState()
            val removed = profile.anonymize(by, now)
            audit.record(
                GuestsAudit.GUEST,
                profile.id.value,
                before,
                withChangedFields(profile.auditState(), ANONYMIZED_FIELDS),
            )
            removed.forEach { document ->
                audit.record(
                    GuestsAudit.GUEST_DOCUMENT,
                    document.id.value,
                    document.auditState() + ("guestId" to profile.id.toString()),
                    null,
                )
            }
        }
        attachments.attachments(guestIds).forEach { row ->
            deleteAttachment(files, attachments, row)
            audit.record(GuestsAudit.GUEST_ATTACHMENT, row.fileId.value, row.auditState(), null)
        }
        store.flush()
        return guest.view()
    }

    private fun sortedContributors(): List<GuestPersonalDataContributor> {
        val all = contributors.orderedStream().toList().sortedBy { it.section }
        all.forEach {
            check(GuestPersonalDataContributor.SECTION_PATTERN.matches(it.section)) {
                "Seção de dados pessoais fora do padrão <módulo>[.<assunto>]: '${it.section}'"
            }
        }
        val repeated = all.groupBy { it.section }.filterValues { it.size > 1 }.keys
        check(repeated.isEmpty()) { "Seções de dados pessoais repetidas: $repeated" }
        return all
    }

    private fun resourceRef(id: GuestId) = "${GuestsAudit.GUEST}:$id"

    private fun guestNotFound() = NotFoundException(GuestsErrorCodes.GUEST_NOT_FOUND, "Hóspede não encontrado.")

    private fun alreadyMerged() =
        ConflictException(
            GuestsErrorCodes.GUEST_ALREADY_MERGED,
            "O perfil já foi mesclado em outro; faça o pedido no perfil sobrevivente.",
        )

    private companion object {
        /** Os campos que a anonimização apaga (para a trilha, H10: só os nomes). */
        val ANONYMIZED_FIELDS =
            listOf(
                "fullName",
                "socialName",
                "birthDate",
                "gender",
                "email",
                "phone",
                "address",
                "preferences",
                "notes",
                "dietaryRestrictions",
                "companyId",
                "restriction",
                "sensitiveData",
                "documents",
                "attachments",
            )
    }
}
