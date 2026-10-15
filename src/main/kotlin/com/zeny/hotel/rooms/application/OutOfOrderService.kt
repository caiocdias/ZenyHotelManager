package com.zeny.hotel.rooms.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rooms.OutOfOrderAction
import com.zeny.hotel.rooms.OutOfOrderChanged
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.rooms.domain.OutOfOrder
import com.zeny.hotel.rooms.domain.OutOfOrderDetails
import com.zeny.hotel.rooms.domain.OutOfOrderRelease
import com.zeny.hotel.rooms.domain.RoomsRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * A sobreposição OOO/OOS em vigor numa UH na data operacional da [HotelApi] (RF-UH-04, RN-05), para o
 * status e a prontidão para check-in.
 */
@Component
class OutOfOrderOverlays(
    private val records: OutOfOrderRecords,
    private val hotel: HotelApi,
) {
    fun current(roomId: RoomId): OutOfOrderOverlay? = records.inEffect(roomId, hotel.currentBusinessDate())

    fun today(): LocalDate = hotel.currentBusinessDate()
}

/**
 * As escritas de OOO/OOS (RF-UH-05, RN-07), sem checar permissão, na transação de um caso de uso
 * auditado (a API ou a API pública): travam a UH (`SELECT … FOR UPDATE`, então duas escritas na mesma
 * UH se enfileiram, inclusive uma mudança de ocupação), aplicam as regras do [OutOfOrder] com a data
 * operacional, conferem a sobreposição com os outros períodos da UH (e o banco repete), descrevem a
 * mudança na trilha e publicam [OutOfOrderChanged] pelo outbox. A liberação de um período em vigor devolve
 * a UH ao status de retorno pelo [RoomStatusChanger] (histórico e [com.zeny.hotel.rooms.RoomStatusChanged]).
 */
@Component
@Suppress("LongParameterList") // as portas que as escritas usam, injetadas pelo Spring
class OutOfOrderOperations(
    private val store: RoomsStore,
    private val periods: OutOfOrderStore,
    private val changer: RoomStatusChanger,
    private val overlays: OutOfOrderOverlays,
    private val events: ApplicationEventPublisher,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun open(
        roomId: RoomId,
        kind: OutOfOrderKind,
        details: OutOfOrderDetails,
    ): OutOfOrderPeriod {
        val room = store.roomForUpdate(roomId) ?: throw roomNotFound()
        val now = clock.instant()
        val period = OutOfOrder.open(room, kind, details, overlays.today(), now, currentActor.currentOrNull()?.userId)
        OutOfOrder.requireNoOverlap(
            periods.ofRoom(roomId, period.startsOn, period.endsOn),
            period.startsOn,
            period.endsOn,
        )
        audit.created(RoomsAudit.OUT_OF_ORDER, period.id.value) { period.auditState() }
        if (!periods.tryAdd(period)) throw overlapRefused()
        publish(period, OutOfOrderAction.OPENED, null, now)
        return period.period()
    }

    fun update(
        roomId: RoomId,
        id: OutOfOrderId,
        expectedVersion: Long,
        details: OutOfOrderDetails,
    ): OutOfOrderPeriod {
        val room = store.roomForUpdate(roomId) ?: throw roomNotFound()
        val period = periods.findForUpdate(id)?.takeIf { it.roomId == roomId } ?: throw outOfOrderNotFound()
        requireCurrentVersion(expectedVersion, checkNotNull(period.version))
        val normalized = details.normalized()
        if (period.releasedOn == null) {
            // Antes de mudar a entidade: a consulta faria o Hibernate gravá-la antes de conferir.
            val others = periods.ofRoom(roomId, normalized.startsOn, normalized.endsOn).filter { it.id != id }
            OutOfOrder.requireNoOverlap(others, normalized.startsOn, normalized.endsOn)
        }
        audit.track(RoomsAudit.OUT_OF_ORDER, id.value) { period.auditState() }
        val previous = period.startsOn to period.endsOn
        val now = clock.instant()
        if (!period.update(room, normalized, overlays.today(), now)) return period.period()
        flushOrRefuseOverlap()
        publish(period, OutOfOrderAction.UPDATED, previous, now)
        return period.period()
    }

    fun release(
        id: OutOfOrderId,
        source: RoomStatusSource,
    ): OutOfOrderPeriod {
        val roomId = (periods.find(id) ?: throw outOfOrderNotFound()).roomId
        checkNotNull(store.roomForUpdate(roomId)) { "UH $roomId do período $id ausente" }
        val period = checkNotNull(periods.findForUpdate(id))
        audit.track(RoomsAudit.OUT_OF_ORDER, id.value) { period.auditState() }
        val now = clock.instant()
        val outcome = period.release(overlays.today(), now, currentActor.currentOrNull()?.userId)
        if (outcome == OutOfOrderRelease.ALREADY_RELEASED) return period.period()
        flushOrRefuseOverlap()
        if (outcome == OutOfOrderRelease.RETURNED) {
            changer.change(roomId, source) { room, at -> room.applyReturnStatus(period.returnStatus, at) }
        }
        publish(period, OutOfOrderAction.RELEASED, null, now)
        log.info("Período {} da UH {} liberado ({})", id, roomId, outcome)
        return period.period()
    }

    private fun flushOrRefuseOverlap() {
        if (!periods.tryFlush()) throw overlapRefused()
    }

    private fun publish(
        period: OutOfOrder,
        action: OutOfOrderAction,
        previous: Pair<LocalDate, LocalDate>?,
        now: Instant,
    ) {
        events.publishEvent(
            OutOfOrderChanged(
                period.id.value,
                period.roomId.value,
                period.kind,
                action,
                period.startsOn,
                period.endsOn,
                period.releasedOn,
                period.blockedUntil,
                previous?.first,
                previous?.second,
                checkNotNull(period.version),
                now,
            ),
        )
    }
}

internal fun outOfOrderNotFound() =
    NotFoundException(RoomsErrorCodes.OUT_OF_ORDER_NOT_FOUND, "Período OOO/OOS não encontrado.")

private fun overlapRefused() =
    ConflictException(
        RoomsErrorCodes.OUT_OF_ORDER_OVERLAP,
        "A UH já tem um período OOO ou OOS nessas datas; altere ou libere o outro antes.",
    )

/**
 * OOO e OOS pela API (RF-UH-05, RN-07, RF-ACS-08): consultar exige `rooms.outoforder.read`; abrir,
 * alterar e liberar um OOO exige `rooms.outoforder.manage`, **sensível**: sem ela, a ação pede a
 * aprovação de um superior (`403 APPROVAL_REQUIRED`, concluída com `X-Approval-Token`); um OOS exige
 * `rooms.outofservice.manage`, não sensível. O formato é conferido antes da autorização (400 antes do
 * 403), e as regras do período depois, já com a UH travada.
 */
@Service
class OutOfOrderService(
    private val operations: OutOfOrderOperations,
    private val periods: OutOfOrderStore,
    private val records: OutOfOrderRecords,
    private val store: RoomsStore,
    private val overlays: OutOfOrderOverlays,
    private val authorization: Authorization,
) {
    @Audited(RoomsAudit.OPEN_OUT_OF_ORDER)
    @Transactional
    fun open(
        roomId: RoomId,
        kind: OutOfOrderKind,
        command: OutOfOrderCommand,
        approvalToken: String?,
    ): OutOfOrderPeriod {
        val details = command.details.normalized()
        store.room(roomId) ?: throw roomNotFound()
        authorize(kind, RoomsAudit.OPEN_OUT_OF_ORDER, "rooms.room:$roomId", approvalToken)
        return operations.open(roomId, kind, details)
    }

    @Audited(RoomsAudit.UPDATE_OUT_OF_ORDER)
    @Transactional
    fun update(
        roomId: RoomId,
        id: OutOfOrderId,
        expectedVersion: Long,
        command: OutOfOrderCommand,
        approvalToken: String?,
    ): OutOfOrderPeriod {
        val details = command.details.normalized()
        val period = periodOf(roomId, id)
        authorize(period.kind, RoomsAudit.UPDATE_OUT_OF_ORDER, "rooms.out-of-order:$id", approvalToken)
        return operations.update(roomId, id, expectedVersion, details)
    }

    @Audited(RoomsAudit.RELEASE_OUT_OF_ORDER)
    @Transactional
    fun release(
        roomId: RoomId,
        id: OutOfOrderId,
        approvalToken: String?,
    ): OutOfOrderPeriod {
        val period = periodOf(roomId, id)
        authorize(period.kind, RoomsAudit.RELEASE_OUT_OF_ORDER, "rooms.out-of-order:$id", approvalToken)
        return operations.release(id, RoomStatusSource.MANUAL)
    }

    @Transactional(readOnly = true)
    fun get(
        roomId: RoomId,
        id: OutOfOrderId,
    ): OutOfOrderPeriod {
        authorization.require(RoomsPermissions.OUT_OF_ORDER_READ)
        return periodOf(roomId, id).period()
    }

    @Transactional(readOnly = true)
    fun list(
        from: LocalDate?,
        to: LocalDate?,
        roomId: RoomId?,
        kind: OutOfOrderKind?,
        page: PageParams,
    ): CursorPage<OutOfOrderPeriod> {
        val start = from ?: overlays.today()
        if (to != null && to.isBefore(start)) {
            throw RoomsRules.invalid(
                RoomsErrorCodes.OUT_OF_ORDER_PERIOD_INVALID,
                "to",
                "Informe `to` igual ou depois de `from`.",
            )
        }
        authorization.require(RoomsPermissions.OUT_OF_ORDER_READ)
        val after = page.decodedCursor()?.let { it.localDate(0) to it.uuid(1) }
        val rows = records.periods(start, to, roomId, kind, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.startsOn.toString(), it.id.toString()) }
    }

    private fun periodOf(
        roomId: RoomId,
        id: OutOfOrderId,
    ): OutOfOrder = periods.find(id)?.takeIf { it.roomId == roomId } ?: throw outOfOrderNotFound()

    /**
     * OOO: `requireOrApproval` (sensível; a aprovação vale para esta ação e este registro, [resourceRef]
     * sem dado pessoal); OOS: `require`.
     */
    private fun authorize(
        kind: OutOfOrderKind,
        action: String,
        resourceRef: String,
        approvalToken: String?,
    ) {
        when (kind) {
            OutOfOrderKind.OUT_OF_ORDER -> {
                authorization.requireOrApproval(
                    SensitiveAction(action, RoomsPermissions.OUT_OF_ORDER_MANAGE, resourceRef),
                    approvalToken,
                )
            }

            OutOfOrderKind.OUT_OF_SERVICE -> {
                authorization.require(RoomsPermissions.OUT_OF_SERVICE_MANAGE)
            }
        }
    }
}
