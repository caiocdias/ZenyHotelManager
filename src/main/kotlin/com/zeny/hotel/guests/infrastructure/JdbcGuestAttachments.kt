package com.zeny.hotel.guests.infrastructure

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.application.GuestAttachmentRow
import com.zeny.hotel.guests.application.GuestAttachmentsStore
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Os vínculos dos perfis com os anexos (`guests.guest_attachment`), pelo `JdbcClient`, na transação corrente. */
@Component
class JdbcGuestAttachments(
    private val jdbc: JdbcClient,
) : GuestAttachmentsStore {
    override fun attachments(guestIds: Collection<GuestId>): List<GuestAttachmentRow> =
        if (guestIds.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("$SELECT WHERE guest_id IN (:ids) ORDER BY attached_at, file_id")
                .param("ids", guestIds.map { it.value })
                .query { rs, _ -> row(rs) }
                .list()
        }

    override fun attachment(fileId: FileId): GuestAttachmentRow? =
        jdbc
            .sql("$SELECT WHERE file_id = :id")
            .param("id", fileId.value)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    override fun add(row: GuestAttachmentRow) {
        jdbc
            .sql(
                "INSERT INTO guests.guest_attachment (file_id, guest_id, purpose, attached_at, attached_by) " +
                    "VALUES (:file, :guest, :purpose, :at, :by)",
            ).param("file", row.fileId.value)
            .param("guest", row.guestId.value)
            .param("purpose", row.purpose.value)
            .param("at", row.attachedAt.atOffset(ZoneOffset.UTC))
            .param("by", row.attachedBy?.value)
            .update()
    }

    override fun remove(fileId: FileId) {
        jdbc.sql("DELETE FROM guests.guest_attachment WHERE file_id = :id").param("id", fileId.value).update()
    }

    private fun row(rs: ResultSet) =
        GuestAttachmentRow(
            fileId = FileId(rs.getObject("file_id", UUID::class.java)),
            guestId = GuestId(rs.getObject("guest_id", UUID::class.java)),
            purpose = FilePurposeCode(rs.getString("purpose")),
            attachedAt = rs.getObject("attached_at", OffsetDateTime::class.java).toInstant(),
            attachedBy = rs.getObject("attached_by", UUID::class.java)?.let(::UserId),
        )

    private companion object {
        const val SELECT =
            "SELECT file_id, guest_id, purpose, attached_at, attached_by FROM guests.guest_attachment"
    }
}
