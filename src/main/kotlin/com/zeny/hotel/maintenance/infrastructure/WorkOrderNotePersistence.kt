package com.zeny.hotel.maintenance.infrastructure

import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.application.WorkOrderNoteRow
import com.zeny.hotel.maintenance.application.WorkOrderNoteStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As observações das ordens (`maintenance.work_order_note`) por `JdbcClient`, sempre parametrizado e na transação
 * corrente. A tabela é só de inserção: nunca atualiza nem apaga.
 */
@Component
class WorkOrderNotePersistence(
    private val jdbc: JdbcClient,
) : WorkOrderNoteStore {
    override fun of(orderIds: Collection<WorkOrderId>): Map<WorkOrderId, List<WorkOrderNoteRow>> =
        if (orderIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql(
                    "SELECT id, work_order_id, text, author_id, created_at FROM $TABLE " +
                        "WHERE work_order_id IN (:ids) ORDER BY created_at, id",
                ).param("ids", orderIds.map { it.value })
                .query { rs, _ -> row(rs) }
                .list()
                .groupBy { it.workOrderId }
        }

    override fun exists(id: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM $TABLE WHERE id = :id)")
            .param("id", id)
            .query(Boolean::class.java)
            .single()

    override fun add(row: WorkOrderNoteRow) {
        jdbc
            .sql(
                "INSERT INTO $TABLE (id, work_order_id, text, author_id, created_at) " +
                    "VALUES (:id, :order, :text, :author, :at)",
            ).param("id", row.id)
            .param("order", row.workOrderId.value)
            .param("text", row.text)
            .param("author", row.authorId)
            .param("at", row.createdAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun row(rs: ResultSet) =
        WorkOrderNoteRow(
            rs.getObject("id", UUID::class.java),
            WorkOrderId(rs.getObject("work_order_id", UUID::class.java)),
            rs.getString("text"),
            rs.getObject("author_id", UUID::class.java),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private companion object {
        const val TABLE = "maintenance.work_order_note"
    }
}
