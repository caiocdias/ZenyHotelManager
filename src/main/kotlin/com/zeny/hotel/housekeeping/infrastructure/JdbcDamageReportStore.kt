package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.DamageReportStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.application.DamageReportPageKey
import com.zeny.hotel.housekeeping.application.DamageReportStore
import com.zeny.hotel.housekeeping.domain.DamageReport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os danos (D4-E06) por `JdbcClient`, sempre parametrizados e só em `housekeeping.damage_report`: `INSERT`, a leitura
 * (com a linha travada, `FOR UPDATE`, nas ações), o `UPDATE` da revisão, da cobrança e da dispensa com a versão e a
 * página da listagem, com o escopo na própria consulta. Nunca apaga.
 */
@Component
class JdbcDamageReportStore(
    private val jdbc: JdbcClient,
) : DamageReportStore {
    override fun insert(report: DamageReport) {
        jdbc
            .sql(
                """
                INSERT INTO $DAMAGE_REPORT_TABLE (id, room_id, area_id, description, estimated_cost, occurred_at,
                    reported_by, source, checklist_execution_id, checklist_item_key, reservation_id, status,
                    created_at, updated_at, version)
                VALUES (:id, :room, :area, :description, :estimatedCost, :occurredAt, :reportedBy, :source,
                    :execution, :itemKey, :reservation, :status, :createdAt, :updatedAt, :version)
                """.trimIndent(),
            ).param("id", report.id.value)
            .param("room", report.roomId?.value)
            .param("area", report.areaId?.value)
            .param("description", report.description)
            .param("estimatedCost", report.estimatedCost?.amount)
            .param("occurredAt", utc(report.occurredAt))
            .param("reportedBy", report.reportedBy.value)
            .param("source", report.source.name)
            .param("execution", report.checklistExecutionId?.value)
            .param("itemKey", report.checklistItemKey)
            .param("reservation", report.reservationId?.value)
            .param("status", report.status.name)
            .param("createdAt", utc(report.createdAt))
            .param("updatedAt", utc(report.updatedAt))
            .param("version", report.version)
            .update()
    }

    override fun find(id: DamageReportId): DamageReport? = byId(id, lock = false)

    override fun findForUpdate(id: DamageReportId): DamageReport? = byId(id, lock = true)

    override fun findByChecklistItem(
        execution: ChecklistExecutionId,
        itemKey: String,
    ): DamageReport? =
        jdbc
            .sql(
                "SELECT * FROM $DAMAGE_REPORT_TABLE WHERE checklist_execution_id = :execution " +
                    "AND checklist_item_key = :itemKey",
            ).param("execution", execution.value)
            .param("itemKey", itemKey)
            .query { rs, _ -> report(rs) }
            .optional()
            .orElse(null)

    override fun update(report: DamageReport) {
        val review = report.review
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE $DAMAGE_REPORT_TABLE SET reservation_id = :reservation, status = :status,
                        final_amount = :finalAmount, transaction_code_id = :code, reviewed_at = :reviewedAt,
                        reviewed_by = :reviewedBy, waiver_reason = :waiverReason, folio_id = :folio,
                        posting_id = :posting, resolved_at = :resolvedAt, resolved_by = :resolvedBy,
                        updated_at = :updatedAt, version = version + 1
                    WHERE id = :id AND version = :version
                    """.trimIndent(),
                ).param("id", report.id.value)
                .param("reservation", report.reservationId?.value)
                .param("status", report.status.name)
                .param("finalAmount", review?.finalAmount?.amount)
                .param("code", review?.transactionCodeId?.value)
                .param("reviewedAt", review?.reviewedAt?.let(::utc))
                .param("reviewedBy", review?.reviewedBy?.value)
                .param("waiverReason", report.waiverReason)
                .param("folio", report.charge?.folioId?.value)
                .param("posting", report.charge?.postingId?.value)
                .param("resolvedAt", report.resolvedAt?.let(::utc))
                .param("resolvedBy", report.resolvedBy?.value)
                .param("updatedAt", utc(report.updatedAt))
                .param("version", report.version)
                .update()
        check(updated == 1) { "O dano ${report.id} mudou sem trava (versão ${report.version})" }
        report.version += 1
    }

    override fun page(
        ownerId: UUID?,
        after: DamageReportPageKey?,
        limit: Int,
    ): List<DamageReport> {
        val conditions = mutableListOf<String>()
        if (ownerId != null) conditions += "reported_by = :owner"
        if (after != null) conditions += "(occurred_at, id) < (:afterAt, :afterId)"
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        var statement =
            jdbc
                .sql("SELECT * FROM $DAMAGE_REPORT_TABLE $where ORDER BY occurred_at DESC, id DESC LIMIT :limit")
                .param("limit", limit)
        if (ownerId != null) statement = statement.param("owner", ownerId)
        if (after != null) statement = statement.param("afterAt", utc(after.occurredAt)).param("afterId", after.id)
        return statement.query { rs, _ -> report(rs) }.list()
    }

    private fun byId(
        id: DamageReportId,
        lock: Boolean,
    ): DamageReport? =
        jdbc
            .sql("SELECT * FROM $DAMAGE_REPORT_TABLE WHERE id = :id" + if (lock) " FOR UPDATE" else "")
            .param("id", id.value)
            .query { rs, _ -> report(rs) }
            .optional()
            .orElse(null)

    private fun report(rs: ResultSet): DamageReport {
        val review =
            rs.getBigDecimal("final_amount")?.let {
                DamageReport.Review(
                    Money.of(it),
                    TransactionCodeId(rs.getObject("transaction_code_id", UUID::class.java)),
                    UserId(rs.getObject("reviewed_by", UUID::class.java)),
                    checkNotNull(instant(rs, "reviewed_at")),
                )
            }
        val charge =
            rs.getObject("posting_id", UUID::class.java)?.let {
                DamageReport.Charge(FolioId(rs.getObject("folio_id", UUID::class.java)), PostingId(it))
            }
        return DamageReport.restore(
            DamageReportId(rs.getObject("id", UUID::class.java)),
            rs.getObject("room_id", UUID::class.java)?.let(::RoomId),
            rs.getObject("area_id", UUID::class.java)?.let(::AreaId),
            rs.getString("description"),
            rs.getBigDecimal("estimated_cost")?.let(Money::of),
            checkNotNull(instant(rs, "occurred_at")),
            UserId(rs.getObject("reported_by", UUID::class.java)),
            OccurrenceSource.valueOf(rs.getString("source")),
            rs.getObject("checklist_execution_id", UUID::class.java)?.let(::ChecklistExecutionId),
            rs.getString("checklist_item_key"),
            rs.getObject("reservation_id", UUID::class.java)?.let(::ReservationId),
            DamageReportStatus.valueOf(rs.getString("status")),
            review,
            rs.getString("waiver_reason"),
            charge,
            instant(rs, "resolved_at"),
            rs.getObject("resolved_by", UUID::class.java)?.let(::UserId),
            checkNotNull(instant(rs, "created_at")),
            checkNotNull(instant(rs, "updated_at")),
            rs.getLong("version"),
        )
    }

    private fun instant(
        rs: ResultSet,
        column: String,
    ): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}

/** Os danos (D4-E06): alterados pela revisão, cobrança e dispensa, nunca apagados. */
internal const val DAMAGE_REPORT_TABLE = "housekeeping.damage_report"
