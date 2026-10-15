package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.domain.Area
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomFeature
import com.zeny.hotel.rooms.domain.RoomType

/** Tipos de entidade, ações e retratos do `rooms` na trilha de auditoria (RF-ACS-11). Nenhum dado pessoal. */
internal object RoomsAudit {
    const val ROOM_FEATURE = "rooms.room-feature"
    const val ROOM_TYPE = "rooms.room-type"
    const val ROOM = "rooms.room"
    const val AREA = "rooms.area"
    const val ROOM_STATUS = "rooms.room-status"
    const val HOUSEKEEPING_OBSERVATION = "rooms.housekeeping-observation"

    const val CREATE_FEATURE = "rooms.room-feature.create"
    const val UPDATE_FEATURE = "rooms.room-feature.update"
    const val DEACTIVATE_FEATURE = "rooms.room-feature.deactivate"
    const val REACTIVATE_FEATURE = "rooms.room-feature.reactivate"
    const val CREATE_ROOM_TYPE = "rooms.room-type.create"
    const val UPDATE_ROOM_TYPE = "rooms.room-type.update"
    const val DEACTIVATE_ROOM_TYPE = "rooms.room-type.deactivate"
    const val REACTIVATE_ROOM_TYPE = "rooms.room-type.reactivate"
    const val CREATE_ROOM = "rooms.room.create"
    const val UPDATE_ROOM = "rooms.room.update"
    const val DEACTIVATE_ROOM = "rooms.room.deactivate"
    const val REACTIVATE_ROOM = "rooms.room.reactivate"
    const val CREATE_AREA = "rooms.area.create"
    const val UPDATE_AREA = "rooms.area.update"
    const val DEACTIVATE_AREA = "rooms.area.deactivate"
    const val REACTIVATE_AREA = "rooms.area.reactivate"

    // Status (M2 E02): a mudança manual e a sinalização pela API, e cada operação da API pública.
    const val CHANGE_HOUSEKEEPING = "rooms.room-status.change-housekeeping"
    const val SET_SERVICE_FLAG = "rooms.room-status.set-service-flag"
    const val MARK_DIRTY = "rooms.room-status.mark-dirty"
    const val MARK_CLEAN = "rooms.room-status.mark-clean"
    const val MARK_INSPECTED = "rooms.room-status.mark-inspected"
    const val REJECT_INSPECTION = "rooms.room-status.reject-inspection"
    const val MARK_OCCUPIED = "rooms.room-status.mark-occupied"
    const val MARK_VACANT = "rooms.room-status.mark-vacant"
    const val RECORD_OBSERVATION = "rooms.housekeeping-observation.record"
}

internal fun RoomFeature.auditState(): Map<String, Any?> =
    mapOf("key" to key, "code" to code, "name" to name, "active" to active)

internal fun RoomType.auditState(): Map<String, Any?> =
    mapOf(
        "code" to code,
        "name" to name,
        "description" to description,
        "standardAdults" to standardAdults,
        "standardChildren" to standardChildren,
        "maxAdults" to maxAdults,
        "maxChildren" to maxChildren,
        "bedSetup" to bedSetup,
        "displayOrder" to displayOrder,
        "featureIds" to featureIds.map { it.toString() }.sorted(),
        "active" to active,
    )

internal fun Room.auditState(): Map<String, Any?> =
    mapOf(
        "number" to number,
        "floor" to floor,
        "roomTypeId" to roomTypeId.toString(),
        "featureIds" to featureIds.map { it.toString() }.sorted(),
        "notes" to notes,
        "active" to active,
    )

internal fun Area.auditState(): Map<String, Any?> =
    mapOf("name" to name, "kind" to kind.name, "description" to description, "active" to active)

/** O status da UH na trilha: as dimensões, sem dado de hóspede (só o número de hóspedes). */
internal fun Room.statusAuditState(): Map<String, Any?> =
    mapOf(
        "occupancy" to occupancy.name,
        "guestCount" to guestCount,
        "housekeepingStatus" to housekeepingStatus.name,
        "inspectionPending" to inspectionPending,
        "serviceFlag" to serviceFlag.name,
    )

internal fun HousekeepingObservationView.auditState(): Map<String, Any?> =
    mapOf("roomId" to roomId.toString(), "occupancy" to occupancy.name, "guestCount" to guestCount)
