package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaSummary
import com.zeny.hotel.rooms.CheckInOccupation
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomFeatureSummary
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomTypeSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.domain.RoomFeature
import com.zeny.hotel.shared.Audited
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * A API pública do `rooms` ([RoomsApi]) para os outros módulos, sem checar permissão: leituras, as
 * mudanças de status, cada uma um caso de uso auditado que passa pelo [RoomStatusChanger] (trava,
 * máquina de estados, histórico e evento), e a abertura e a liberação de OOO/OOS, pelas
 * [OutOfOrderOperations] (as mesmas regras e o mesmo evento da API, sem o step-up, que é de quem chama).
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // uma operação por mudança; as portas injetadas pelo Spring
class RoomsApiService(
    private val store: RoomsStore,
    private val directory: RoomsDirectory,
    private val changer: RoomStatusChanger,
    private val overlays: OutOfOrderOverlays,
    private val outOfOrders: OutOfOrderOperations,
    private val outOfOrderStore: OutOfOrderStore,
    private val outOfOrderRecords: OutOfOrderRecords,
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

    // --- Status ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun status(id: RoomId): RoomStatus? = store.room(id)?.statusView(overlays.current(id))?.status()

    @Audited(RoomsAudit.MARK_DIRTY)
    @Transactional
    override fun markDirty(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.markDirty(now) }.status()

    @Audited(RoomsAudit.MARK_CLEAN)
    @Transactional
    override fun markClean(
        id: RoomId,
        requiresInspection: Boolean,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.markClean(requiresInspection, now) }.status()

    @Audited(RoomsAudit.MARK_INSPECTED)
    @Transactional
    override fun markInspected(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.markInspected(now) }.status()

    @Audited(RoomsAudit.REJECT_INSPECTION)
    @Transactional
    override fun rejectInspection(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.rejectInspection(now) }.status()

    @Audited(RoomsAudit.MARK_OCCUPIED)
    @Transactional
    override fun markOccupied(
        id: RoomId,
        guestCount: Int,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.markOccupied(guestCount, now) }.status()

    @Audited(RoomsAudit.MARK_OCCUPIED)
    @Transactional
    override fun markOccupiedIfReady(
        id: RoomId,
        guestCount: Int,
        source: RoomStatusSource,
    ): CheckInOccupation {
        var ready = false
        // A RN-05 conferida com a linha travada pelo changer (a mudança concorrente já confirmada é vista).
        val view =
            changer.change(id, source) { room, now ->
                ready = room.isReadyForCheckIn(overlays.current(id)?.kind)
                if (ready) room.markOccupied(guestCount, now) else emptyList()
            }
        return CheckInOccupation(ready, view.status())
    }

    @Audited(RoomsAudit.MARK_VACANT)
    @Transactional
    override fun markVacant(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.markVacant(source, now) }.status()

    @Audited(RoomsAudit.SET_SERVICE_FLAG)
    @Transactional
    override fun setServiceFlag(
        id: RoomId,
        flag: GuestServiceFlag,
        source: RoomStatusSource,
    ): RoomStatus = changer.change(id, source) { room, now -> room.setServiceFlag(flag, now) }.status()

    @Transactional(readOnly = true)
    override fun isReadyForCheckIn(id: RoomId): Boolean =
        (store.room(id) ?: throw roomNotFound()).isReadyForCheckIn(overlays.current(id)?.kind)

    // --- OOO e OOS ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun outOfOrder(id: OutOfOrderId): OutOfOrderPeriod? = outOfOrderStore.find(id)?.period()

    @Transactional(readOnly = true)
    override fun outOfOrderPeriods(
        from: LocalDate,
        to: LocalDate,
        roomId: RoomId?,
    ): List<OutOfOrderPeriod> {
        require(!to.isBefore(from)) { "Intervalo de datas invertido: $from a $to" }
        return generateSequence(outOfOrderRecords.periods(from, to, roomId, null, null, PAGE)) { page ->
            if (page.size < PAGE) {
                null
            } else {
                page.last().let { outOfOrderRecords.periods(from, to, roomId, null, it.startsOn to it.id.value, PAGE) }
            }
        }.flatten().toList()
    }

    @Audited(RoomsAudit.OPEN_OUT_OF_ORDER)
    @Transactional
    override fun openOutOfOrder(
        roomId: RoomId,
        kind: OutOfOrderKind,
        request: OutOfOrderRequest,
    ): OutOfOrderPeriod =
        outOfOrders.open(
            roomId,
            kind,
            OutOfOrderCommand(
                request.startsOn,
                request.endsOn,
                request.reason,
                request.responsible,
                request.returnStatus,
            ).details,
        )

    @Audited(RoomsAudit.RELEASE_OUT_OF_ORDER)
    @Transactional
    override fun releaseOutOfOrder(
        id: OutOfOrderId,
        source: RoomStatusSource,
    ): OutOfOrderPeriod = outOfOrders.release(id, source)

    private fun RoomFeature.summary() = RoomFeatureSummary(id, key, code, name, active)

    private fun RoomView.summary() = RoomSummary(id, number, floor, roomTypeId, featureIds, active)

    private fun RoomTypeView.summary() = RoomTypeSummary(id, code, name, occupancy, displayOrder, featureIds, active)

    private companion object {
        const val PAGE = 200
    }
}
