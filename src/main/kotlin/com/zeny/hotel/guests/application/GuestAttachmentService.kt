package com.zeny.hotel.guests.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsFilePurposes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Anexos do hóspede (RF-HOS-08, RNF-09, H11 do M3): o arquivo é enviado pelo `POST /api/v1/files` com uma das
 * finalidades sensíveis de [GuestsFilePurposes] e vinculado aqui ao perfil (o `files` guarda o id do perfil como
 * dono); o download é pelo `GET /api/v1/files/{id}/download-url`, com a permissão da finalidade, e o `files` grava na
 * trilha cada URL emitida e cada download. Listar exige `guests.attachments.read`; vincular e retirar,
 * `guests.attachments.manage`. Retirar apaga o anexo (`FilesApi.delete`: o objeto sai do armazenamento depois do
 * commit). As escritas travam o perfil, então não correm com a anonimização.
 */
@Service
@Suppress("LongParameterList") // as portas de que o vínculo precisa
class GuestAttachmentService(
    private val store: GuestsStore,
    private val attachments: GuestAttachmentsStore,
    private val files: FilesApi,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Os anexos do perfil, em ordem de vínculo. */
    @Transactional(readOnly = true)
    fun attachments(id: GuestId): List<GuestAttachmentView> {
        authorization.require(GuestsPermissions.ATTACHMENTS_READ)
        store.guest(id) ?: throw guestNotFound()
        return attachmentViews(files, attachments.attachments(listOf(id)))
    }

    /**
     * Vincula o anexo [fileId] ao perfil. Já vinculado a ele: devolve-o sem mudar nada. Inexistente (ou apagado), de
     * uma finalidade que não é de hóspede ou de outro registro: `422 GUEST_ATTACHMENT_INVALID`. Perfil mesclado ou
     * anonimizado: `409`.
     */
    @Audited(GuestsAudit.ATTACH_FILE)
    @Transactional
    fun attach(
        id: GuestId,
        fileId: FileId,
    ): GuestAttachmentResult {
        authorization.require(GuestsPermissions.ATTACHMENTS_MANAGE)
        val guest = locked(id)
        guest.requireActive()
        val owner = id.value.toString()
        val summary = files.find(fileId)
        val existing = attachments.attachment(fileId)
        val usable =
            when {
                summary == null -> false
                summary.purpose !in GuestsFilePurposes.codes -> false
                summary.ownerEntityId != null && summary.ownerEntityId != owner -> false
                else -> existing == null || existing.guestId == id
            }
        if (summary == null || !usable) {
            throw BusinessRuleException(
                GuestsErrorCodes.GUEST_ATTACHMENT_INVALID,
                "O anexo precisa existir, ser de uma finalidade de hóspede (foto ou PDF de documento) e não " +
                    "pertencer a outro registro.",
            )
        }
        if (existing != null) {
            audit.noChanges()
            return GuestAttachmentResult(attachmentViews(files, listOf(existing)).single(), created = false)
        }
        files.attach(fileId, summary.purpose, owner)
        val row =
            GuestAttachmentRow(
                fileId,
                id,
                summary.purpose,
                // Na precisão do banco, para a resposta ser a mesma da listagem.
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                currentActor.currentOrNull()?.userId,
            )
        attachments.add(row)
        audit.created(GuestsAudit.GUEST_ATTACHMENT, fileId.value) { row.auditState() }
        return GuestAttachmentResult(attachmentViews(files, listOf(row)).single(), created = true)
    }

    /**
     * Retira o anexo do perfil e o apaga (`FilesApi.delete`). Não vinculado a este perfil: `404
     * GUEST_ATTACHMENT_NOT_FOUND`. Vale também num perfil mesclado (apagar dado pessoal nunca é recusado); o
     * anonimizado já não tem anexos.
     */
    @Audited(GuestsAudit.REMOVE_ATTACHMENT)
    @Transactional
    fun remove(
        id: GuestId,
        fileId: FileId,
    ) {
        authorization.require(GuestsPermissions.ATTACHMENTS_MANAGE)
        locked(id)
        val row =
            attachments.attachment(fileId)?.takeIf { it.guestId == id }
                ?: throw NotFoundException(
                    GuestsErrorCodes.GUEST_ATTACHMENT_NOT_FOUND,
                    "O anexo não está vinculado a este perfil.",
                )
        deleteAttachment(files, attachments, row)
        audit.record(GuestsAudit.GUEST_ATTACHMENT, fileId.value, row.auditState(), null)
    }

    private fun locked(id: GuestId): Guest = store.guestsForUpdate(listOf(id)).single() ?: throw guestNotFound()

    private fun guestNotFound() = NotFoundException(GuestsErrorCodes.GUEST_NOT_FOUND, "Hóspede não encontrado.")
}

/** Os anexos com os metadados do `files` (os que o `files` já não tem ficam de fora). */
internal fun attachmentViews(
    files: FilesApi,
    rows: List<GuestAttachmentRow>,
): List<GuestAttachmentView> =
    rows.mapNotNull { row ->
        files.find(row.fileId)?.let {
            GuestAttachmentView(
                fileId = row.fileId,
                guestId = row.guestId,
                purpose = row.purpose,
                contentType = it.contentType,
                sizeBytes = it.sizeBytes,
                uploadedBy = it.createdBy,
                uploadedAt = it.createdAt,
                attachedAt = row.attachedAt,
                attachedBy = row.attachedBy,
            )
        }
    }

/** Apaga o anexo no `files` (se ele ainda existe) e o vínculo. */
internal fun deleteAttachment(
    files: FilesApi,
    attachments: GuestAttachmentsStore,
    row: GuestAttachmentRow,
) {
    if (files.find(row.fileId) != null) files.delete(row.fileId, row.purpose, row.guestId.value.toString())
    attachments.remove(row.fileId)
}
