package com.zeny.hotel.maintenance.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.maintenance.MaintenanceErrorCodes
import com.zeny.hotel.maintenance.MaintenanceFilePurposes
import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.maintenance.domain.WorkOrderAction
import com.zeny.hotel.maintenance.domain.WorkOrderRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Um vínculo de uma ordem com um anexo do `files`; [detachedAt] nulo = vigente. */
data class WorkOrderAttachmentRow(
    val id: UUID,
    val workOrderId: WorkOrderId,
    val fileId: FileId,
    val purpose: FilePurposeCode,
    val attachedBy: UserId,
    val attachedAt: Instant,
    val detachedBy: UserId? = null,
    val detachedAt: Instant? = null,
) {
    /** O retrato da trilha: ids, finalidade e datas (nenhum texto livre, nem o nome do arquivo). */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "workOrderId" to workOrderId.toString(),
            "fileId" to fileId.toString(),
            "purpose" to purpose.value,
            "attachedAt" to attachedAt.toString(),
            "detachedAt" to detachedAt?.toString(),
        )
}

/** Persistência dos vínculos das ordens com os anexos (D5-E07), na transação corrente. */
interface WorkOrderAttachmentStore {
    /** Os vínculos vigentes de cada ordem de [orderIds], em ordem de vínculo; ordem sem anexo fica fora do mapa. */
    fun active(orderIds: Collection<WorkOrderId>): Map<WorkOrderId, List<WorkOrderAttachmentRow>>

    /** O vínculo vigente do arquivo, de qualquer ordem, ou `null`. */
    fun activeOf(fileId: FileId): WorkOrderAttachmentRow?

    fun add(row: WorkOrderAttachmentRow)

    /** Marca o vínculo vigente do arquivo como desfeito. */
    fun markDetached(
        fileId: FileId,
        by: UserId,
        at: Instant,
    )
}

/**
 * Anexos das ordens de serviço (D5-E07): a foto (`maintenance.work-order-photo`) e o PDF
 * (`maintenance.work-order-document`) são enviados pelo `POST /api/v1/files` e vinculados aqui pela `FilesApi.attach`
 * (o `files` guarda o id da ordem como dono); o download é pela URL assinada do `files`. Nenhuma das duas ações pede
 * `If-Match` nem muda a versão da ordem (a versão e o `ETag` não cobrem a lista de anexos).
 *
 * - Vincular: com `maintenance.workorders.manage` (no escopo dele) ou, sem ela, com `maintenance.workorders.request`
 *   na ordem que o próprio ator abriu; a ordem alheia é `403`. Arquivo inexistente: `400 UNKNOWN_FILE`; de outra
 *   finalidade: `422 WORK_ORDER_ATTACHMENT_INVALID`; já de outro registro: `409 FILE_ALREADY_ATTACHED` (do `files`).
 *   Vincular de novo o mesmo arquivo à mesma ordem não muda nada.
 * - Desvincular: só com `manage`. O anexo volta a órfão no `files` (o expurgo o apaga depois da retenção). Não vigente
 *   nesta ordem: `404 WORK_ORDER_ATTACHMENT_NOT_FOUND`.
 * - Ordem final (`DONE` ou `CANCELLED`): `409 WORK_ORDER_TRANSITION_NOT_ALLOWED` nas duas.
 */
@Service
@Suppress("LongParameterList") // as portas de que o vínculo precisa
class WorkOrderAttachments(
    private val workOrders: WorkOrdersService,
    private val store: WorkOrderAttachmentStore,
    private val files: FilesApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(MaintenanceAudit.ATTACH_FILE)
    @Transactional
    fun attach(
        id: WorkOrderId,
        fileId: FileId,
    ): WorkOrderView {
        val order = loadForAttach(id)
        order.requireAttachable(WorkOrderAction.ATTACH)
        val summary =
            files.find(fileId)
                ?: throw WorkOrderRules.invalid("fileId", "O anexo não existe.", MaintenanceErrorCodes.UNKNOWN_FILE)
        if (summary.purpose !in MaintenanceFilePurposes.codes) {
            throw BusinessRuleException(
                MaintenanceErrorCodes.WORK_ORDER_ATTACHMENT_INVALID,
                "O anexo precisa ser de uma finalidade de ordem de serviço (foto ou PDF).",
            )
        }
        if (store.activeOf(fileId)?.workOrderId == id) {
            audit.noChanges()
            return workOrders.viewOf(order)
        }
        // O `files` recusa (409 FILE_ALREADY_ATTACHED) o arquivo que já é de outro registro.
        files.attach(fileId, summary.purpose, id.toString())
        val row =
            WorkOrderAttachmentRow(
                UUID.randomUUID(),
                id,
                fileId,
                summary.purpose,
                actor.current().userId,
                // Na precisão do banco, para a resposta valer o que a leitura devolve.
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        store.add(row)
        audit.created(MaintenanceAudit.WORK_ORDER_ATTACHMENT, fileId.value) { row.auditState() }
        return workOrders.viewOf(order)
    }

    @Audited(MaintenanceAudit.DETACH_FILE)
    @Transactional
    fun detach(
        id: WorkOrderId,
        fileId: FileId,
    ): WorkOrderView {
        val order = workOrders.loadLocked(id, MaintenancePermissions.MANAGE)
        order.requireAttachable(WorkOrderAction.DETACH)
        val row =
            store.activeOf(fileId)?.takeIf { it.workOrderId == id }
                ?: throw NotFoundException(
                    MaintenanceErrorCodes.WORK_ORDER_ATTACHMENT_NOT_FOUND,
                    "O anexo não está vinculado a esta ordem de serviço.",
                )
        files.detach(fileId, row.purpose, id.toString())
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val by = actor.current().userId
        store.markDetached(fileId, by, now)
        audit.record(
            MaintenanceAudit.WORK_ORDER_ATTACHMENT,
            fileId.value,
            row.auditState(),
            row.copy(detachedBy = by, detachedAt = now).auditState(),
        )
        return workOrders.viewOf(order)
    }

    /**
     * A ordem travada para vincular: quem tem `manage` a carrega no escopo dele; quem não tem exige `request` e só a
     * ordem que abriu (a alheia dá o `403` de quem não tem `manage`).
     */
    private fun loadForAttach(id: WorkOrderId): WorkOrder {
        if (authorization.grantOrNull(MaintenancePermissions.MANAGE) != null) {
            return workOrders.loadLocked(id, MaintenancePermissions.MANAGE)
        }
        authorization.require(MaintenancePermissions.REQUEST)
        val order = workOrders.lockedOrNotFound(id)
        if (order.openedBy != actor.current().userId) authorization.require(MaintenancePermissions.MANAGE)
        return order
    }
}
