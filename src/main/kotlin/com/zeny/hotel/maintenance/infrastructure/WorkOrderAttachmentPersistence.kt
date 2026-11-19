package com.zeny.hotel.maintenance.infrastructure

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.application.WorkOrderAttachmentRow
import com.zeny.hotel.maintenance.application.WorkOrderAttachmentStore
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os vínculos das ordens com os anexos (`maintenance.work_order_attachment`) por `JdbcClient`, sempre parametrizado e
 * na transação corrente. Desvincular marca `detached_at` e `detached_by`; nunca apaga.
 */
@Component
class WorkOrderAttachmentPersistence(
    private val jdbc: JdbcClient,
) : WorkOrderAttachmentStore {
    override fun active(orderIds: Collection<WorkOrderId>): Map<WorkOrderId, List<WorkOrderAttachmentRow>> =
        if (orderIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql("$SELECT WHERE work_order_id IN (:ids) AND detached_at IS NULL ORDER BY attached_at, id")
                .param("ids", orderIds.map { it.value })
                .query { rs, _ -> row(rs) }
                .list()
                .groupBy { it.workOrderId }
        }

    override fun activeOf(fileId: FileId): WorkOrderAttachmentRow? =
        jdbc
            .sql("$SELECT WHERE file_id = :file AND detached_at IS NULL")
            .param("file", fileId.value)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    override fun add(row: WorkOrderAttachmentRow) {
        jdbc
            .sql(
                "INSERT INTO $TABLE (id, work_order_id, file_id, purpose, attached_by, attached_at) " +
                    "VALUES (:id, :order, :file, :purpose, :by, :at)",
            ).param("id", row.id)
            .param("order", row.workOrderId.value)
            .param("file", row.fileId.value)
            .param("purpose", row.purpose.value)
            .param("by", row.attachedBy.value)
            .param("at", row.attachedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun markDetached(
        fileId: FileId,
        by: UserId,
        at: Instant,
    ) {
        jdbc
            .sql("UPDATE $TABLE SET detached_by = :by, detached_at = :at WHERE file_id = :file AND detached_at IS NULL")
            .param("by", by.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .param("file", fileId.value)
            .update()
    }

    private fun row(rs: ResultSet) =
        WorkOrderAttachmentRow(
            rs.getObject("id", UUID::class.java),
            WorkOrderId(rs.getObject("work_order_id", UUID::class.java)),
            FileId(rs.getObject("file_id", UUID::class.java)),
            FilePurposeCode(rs.getString("purpose")),
            UserId(rs.getObject("attached_by", UUID::class.java)),
            rs.getObject("attached_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("detached_by", UUID::class.java)?.let(::UserId),
            rs.getObject("detached_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private companion object {
        const val TABLE = "maintenance.work_order_attachment"
        const val SELECT =
            "SELECT id, work_order_id, file_id, purpose, attached_by, attached_at, detached_by, detached_at FROM $TABLE"
    }
}
