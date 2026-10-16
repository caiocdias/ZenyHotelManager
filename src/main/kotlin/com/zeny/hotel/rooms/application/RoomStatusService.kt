package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusChanged
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.rooms.RoomsRealtime
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomStatusFieldChange
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Aplica uma mudança de status a uma UH, na transação de um caso de uso auditado (a API pública ou a
 * mudança manual): trava a linha da UH (`SELECT … FOR UPDATE`; duas mudanças simultâneas se enfileiram,
 * e a segunda vê o resultado da primeira), confere a versão esperada (se houver), aplica a operação do
 * domínio e, se algo mudou, grava a UH (versão nova), um registro do histórico por campo (RF-UH-06, com o
 * ator da requisição ou o sistema) e publica [RoomStatusChanged] pelo outbox e a mensagem
 * [RoomsRealtime.ROOM_STATUS] no tópico `rooms` do canal de tempo real (enviada depois do commit). Sem
 * mudança, não grava nem publica nada.
 */
@Component
@Suppress("LongParameterList") // as portas de uma mudança de status, injetadas pelo Spring
class RoomStatusChanger(
    private val store: RoomsStore,
    private val records: RoomStatusRecords,
    private val events: ApplicationEventPublisher,
    private val realtime: RealtimePublisher,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val overlays: OutOfOrderOverlays,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun change(
        id: RoomId,
        source: RoomStatusSource,
        expectedVersion: Long? = null,
        operation: (Room, Instant) -> List<RoomStatusFieldChange>,
    ): RoomStatusView {
        val room = store.roomForUpdate(id) ?: throw roomNotFound()
        if (expectedVersion != null) requireCurrentVersion(expectedVersion, checkNotNull(room.version))
        audit.track(RoomsAudit.ROOM_STATUS, id.value) { room.statusAuditState() }
        val now = clock.instant()
        val changes = operation(room, now)
        if (changes.isEmpty()) return room.statusView(overlays.current(id))
        store.flush()
        val view = room.statusView(overlays.current(id))
        records.appendChanges(id, changes, now, currentActor.currentOrNull()?.userId, source, view.version)
        events.publishEvent(
            RoomStatusChanged(
                id.value,
                view.occupancy,
                view.housekeepingStatus,
                view.inspectionPending,
                view.serviceFlag,
                view.version,
                view.changedAt,
            ),
        )
        realtime.publish(RoomsRealtime.TOPIC, RealtimeMessage(RoomsRealtime.ROOM_STATUS, id.value, view.version))
        log.info("Status da UH {} mudou ({}): {}", id, source, changes.map { it.field })
        return view
    }
}

internal fun roomNotFound() = NotFoundException(RoomsErrorCodes.ROOM_NOT_FOUND, "UH não encontrada.")

/**
 * Status das UHs pela API (RF-UH-04 a 08): o painel, o histórico e as discrepâncias (`rooms.status.read`),
 * a mudança manual do status de governança (`rooms.status.manage`, com a versão da última leitura, RN-08),
 * a sinalização do hóspede (`rooms.status.flag`) e o registro do que a governança encontrou na UH
 * (`rooms.status.observe`). As mudanças passam pelo [RoomStatusChanger] com a origem
 * [RoomStatusSource.MANUAL] e o usuário da requisição como autor.
 */
@Service
@Suppress("LongParameterList") // as portas que os casos de uso de status usam, injetadas pelo Spring
class RoomStatusService(
    private val changer: RoomStatusChanger,
    private val store: RoomsStore,
    private val records: RoomStatusRecords,
    private val overlays: OutOfOrderOverlays,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun board(
        occupancy: OccupancyStatus?,
        housekeepingStatus: HousekeepingStatus?,
        page: PageParams,
    ): CursorPage<RoomStatusView> {
        authorization.require(RoomsPermissions.STATUS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = records.board(overlays.today(), occupancy, housekeepingStatus, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.number.lowercase(), it.roomId.toString()) }
    }

    @Transactional(readOnly = true)
    fun status(id: RoomId): RoomStatusView {
        authorization.require(RoomsPermissions.STATUS_READ)
        return (store.room(id) ?: throw roomNotFound()).statusView(overlays.current(id))
    }

    @Transactional(readOnly = true)
    fun history(
        id: RoomId,
        page: PageParams,
    ): CursorPage<RoomStatusChangeView> {
        authorization.require(RoomsPermissions.STATUS_READ)
        store.room(id) ?: throw roomNotFound()
        val after = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        val rows = records.history(id, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.changedAt.toString(), it.id.toString()) }
    }

    @Audited(RoomsAudit.CHANGE_HOUSEKEEPING)
    @Transactional
    fun changeHousekeeping(
        id: RoomId,
        expectedVersion: Long,
        transition: HousekeepingTransition,
        requiresInspection: Boolean,
    ): RoomStatusView {
        authorization.require(RoomsPermissions.STATUS_MANAGE)
        return changer.change(id, RoomStatusSource.MANUAL, expectedVersion) { room, now ->
            when (transition) {
                HousekeepingTransition.MARK_DIRTY -> room.markDirty(now)
                HousekeepingTransition.MARK_CLEAN -> room.markClean(requiresInspection, now)
                HousekeepingTransition.MARK_INSPECTED -> room.markInspected(now)
                HousekeepingTransition.REJECT_INSPECTION -> room.rejectInspection(now)
            }
        }
    }

    @Audited(RoomsAudit.SET_SERVICE_FLAG)
    @Transactional
    fun setServiceFlag(
        id: RoomId,
        flag: GuestServiceFlag,
    ): RoomStatusView {
        authorization.require(RoomsPermissions.STATUS_FLAG)
        return changer.change(id, RoomStatusSource.MANUAL) { room, now -> room.setServiceFlag(flag, now) }
    }

    @Audited(RoomsAudit.RECORD_OBSERVATION)
    @Transactional
    fun recordObservation(
        id: RoomId,
        occupancy: OccupancyStatus,
        guestCount: Int?,
    ): HousekeepingObservationView {
        authorization.require(RoomsPermissions.STATUS_OBSERVE)
        val actor = currentActor.current()
        val room = store.room(id) ?: throw roomNotFound()
        if (!room.active) {
            throw BusinessRuleException(RoomsErrorCodes.ROOM_INACTIVE, "A UH está desativada.")
        }
        Room.requireObservedGuestCount(occupancy, guestCount)
        val observation =
            HousekeepingObservationView(UuidV7.generate(), id, occupancy, guestCount, clock.instant(), actor.userId)
        audit.created(RoomsAudit.HOUSEKEEPING_OBSERVATION, observation.id) { observation.auditState() }
        records.appendObservation(observation)
        return observation
    }

    @Transactional(readOnly = true)
    fun discrepancies(page: PageParams): CursorPage<RoomDiscrepancyView> {
        authorization.require(RoomsPermissions.STATUS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = records.discrepancies(after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.number.lowercase(), it.roomId.toString()) }
    }
}
