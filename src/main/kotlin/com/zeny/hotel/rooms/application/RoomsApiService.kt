package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaSummary
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomFeatureSummary
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomTypeSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.domain.RoomFeature
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A API pública do `rooms` ([RoomsApi]) para os outros módulos: leituras sem checar permissão. */
@Service
class RoomsApiService(
    private val store: RoomsStore,
    private val directory: RoomsDirectory,
) : RoomsApi {
    @Transactional(readOnly = true)
    override fun room(id: RoomId): RoomSummary? = store.room(id)?.view()?.summary()

    @Transactional(readOnly = true)
    override fun roomType(id: RoomTypeId): RoomTypeSummary? = store.roomType(id)?.view()?.summary()

    @Transactional(readOnly = true)
    override fun area(id: AreaId): AreaSummary? =
        store.area(id)?.let { AreaSummary(it.id, it.name, it.kind, it.active) }

    @Transactional(readOnly = true)
    override fun activeRooms(roomTypeId: RoomTypeId?): List<RoomSummary> =
        generateSequence(directory.rooms(roomTypeId, true, null, PAGE)) { page ->
            if (page.size < PAGE) {
                null
            } else {
                page.last().let { directory.rooms(roomTypeId, true, it.number.lowercase() to it.id.value, PAGE) }
            }
        }.flatten()
            .map { it.summary() }
            .toList()

    @Transactional(readOnly = true)
    override fun activeRoomTypes(): List<RoomTypeSummary> =
        generateSequence(directory.roomTypes(true, null, PAGE)) { page ->
            if (page.size < PAGE) {
                null
            } else {
                page.last().let { directory.roomTypes(true, it.displayOrder.toLong() to it.id.value, PAGE) }
            }
        }.flatten()
            .map { it.summary() }
            .toList()

    @Transactional(readOnly = true)
    override fun feature(id: RoomFeatureId): RoomFeatureSummary? = store.feature(id)?.summary()

    @Transactional(readOnly = true)
    override fun feature(key: RoomFeatureKey): RoomFeatureSummary =
        checkNotNull(store.feature(key)) { "Característica pré-carregada $key ausente" }.summary()

    private fun RoomFeature.summary() = RoomFeatureSummary(id, key, code, name, active)

    private fun RoomView.summary() = RoomSummary(id, number, floor, roomTypeId, featureIds, active)

    private fun RoomTypeView.summary() = RoomTypeSummary(id, code, name, occupancy, displayOrder, featureIds, active)

    private companion object {
        const val PAGE = 200
    }
}
