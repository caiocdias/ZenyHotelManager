package com.zeny.hotel.rooms.infrastructure

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomsOffline
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

/**
 * A UH como fonte do download offline ([RoomsOffline.SOURCE]). A posição de cada UH (`change_xid`,
 * `change_seq`) é gravada pelo trigger `rooms.room_offline_change` a cada mudança de cadastro ou de status,
 * qualquer que seja o caminho de escrita. A fonte não tem dono (só `ALL`).
 */
@Component
class RoomsOfflineSource(
    private val jdbc: JdbcClient,
) : OfflineChangeSource {
    override val source = RoomsOffline.SOURCE

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> =
        jdbc
            .sql(
                """
                SELECT r.id, r.number, r.floor, r.occupancy, r.housekeeping_status, r.inspection_pending,
                       r.service_flag, r.active, r.version, ${OfflineChangeRequest.positionColumns("r")}
                FROM rooms.room r
                WHERE ${OfflineChangeRequest.window("r")}
                ORDER BY r.change_xid, r.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .query { rs, _ -> toChange(rs) }
            .list()

    private fun toChange(rs: ResultSet): OfflineChange {
        val position = OfflinePosition.from(rs)
        val id = rs.getObject("id", UUID::class.java)
        val version = rs.getLong("version")
        if (!rs.getBoolean("active")) return OfflineChange.tombstone(position, id, version)
        return OfflineChange.upsert(
            position,
            id,
            version,
            OfflineRoom(
                number = rs.getString("number"),
                floor = rs.getString("floor"),
                occupancy = OccupancyStatus.valueOf(rs.getString("occupancy")),
                housekeepingStatus = HousekeepingStatus.valueOf(rs.getString("housekeeping_status")),
                inspectionPending = rs.getBoolean("inspection_pending"),
                serviceFlag = GuestServiceFlag.valueOf(rs.getString("service_flag")),
            ),
        )
    }

    /** O que o app recebe de cada UH (RN-13): sem número de hóspedes nem observações. */
    data class OfflineRoom(
        val number: String,
        val floor: String?,
        val occupancy: OccupancyStatus,
        val housekeepingStatus: HousekeepingStatus,
        val inspectionPending: Boolean,
        val serviceFlag: GuestServiceFlag,
    )
}
