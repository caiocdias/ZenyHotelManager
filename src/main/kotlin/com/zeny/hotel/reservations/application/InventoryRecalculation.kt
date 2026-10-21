package com.zeny.hotel.reservations.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.InventoryRecount
import com.zeny.hotel.reservations.domain.BlockedRange
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryHorizon
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.reservations.domain.RoomCounting
import com.zeny.hotel.rooms.OutOfOrderChanged
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomRegistryChanged
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate

/**
 * Refaz os contadores a partir das fontes da verdade (ARCHITECTURE §11, H4 e H10 do M4), na transação de escrita de
 * quem chama: o total e o OOO pela [RoomsApi] (UHs ativas do tipo e os períodos `OUT_OF_ORDER` delas; o OOS não conta)
 * e, na recontagem, as vendidas e bloqueadas pelas [InventoryUsageSource] (nenhuma até a E07: zero). Nunca soma
 * deltas, então refazer de novo (o evento reentregue, o job, a recontagem) não muda o resultado.
 *
 * Só refaz da data operacional até o fim do horizonte (o passado fica como estava) e cria as linhas que faltam nesse
 * intervalo. Toma antes a trava consultiva de cada tipo (em ordem de id) e só então lê a `RoomsApi`: dois refazimentos
 * do mesmo tipo se enfileiram e o segundo lê o que o primeiro já viu confirmado, então nenhum grava um total velho
 * depois de um novo. Depois trava as linhas em ordem de (tipo, data), como a tomada de unidades, que nunca toma a trava
 * consultiva (sem deadlock).
 */
@Component
@Suppress("LongParameterList") // as fontes da verdade e as portas de que o refazimento precisa
class InventoryRefresher(
    private val store: InventoryStore,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val usageSources: ObjectProvider<InventoryUsageSource>,
    private val availability: AvailabilityEvents,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** O resultado: o período efetivamente refeito (nulo se vazio) e as linhas que mudaram. */
    data class Outcome(
        val from: LocalDate?,
        val to: LocalDate?,
        val roomTypes: Int,
        val changedRows: Int,
    )

    fun refresh(
        roomTypeIds: Set<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
        recountUsage: Boolean,
    ): Outcome {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "O inventário só é refeito numa transação de escrita" }
        val today = hotel.currentBusinessDate()
        val start = maxOf(from, today)
        val end = minOf(to, InventoryHorizon.end(today))
        if (end.isBefore(start) || roomTypeIds.isEmpty()) {
            audit.noChanges()
            return Outcome(null, null, 0, 0)
        }
        val types = roomTypeIds.sortedBy { it.value.toString() }
        store.lockRoomTypes(types)
        val now = clock.instant()
        val created = store.createMissing(types, start, end, now)
        val current = store.lockRows(types, start, end)
        val usage = if (recountUsage) usage(types, start, end) else null
        val after =
            types
                .flatMap { type ->
                    val counts = roomCounts(type, start, end)
                    counts.map { (date, roomCounts) ->
                        val key = InventoryKey(type, date)
                        val before = current[key] ?: InventoryCounters.EMPTY
                        var refreshed = before.withRooms(roomCounts)
                        if (usage != null) refreshed = refreshed.withUsage(usage[key] ?: InventoryUsage.NONE)
                        key to refreshed
                    }
                }.toMap()
        val changed = after.filter { (key, counters) -> counters != (current[key] ?: InventoryCounters.EMPTY) }
        store.update(changed, now)
        describe(types, current, changed, created)
        availability.publish(changed.keys, now)
        return Outcome(start, end, types.size, changed.size)
    }

    /**
     * A recontagem de todos os tipos (os ativos e os que já têm inventário da data operacional em diante), com o uso
     * refeito, de [from] (padrão e mínimo: a data operacional) a [to] (padrão e máximo: o fim do horizonte).
     */
    fun recount(
        from: LocalDate?,
        to: LocalDate?,
    ): InventoryRecount {
        val today = hotel.currentBusinessDate()
        val start = maxOf(from ?: today, today)
        val end = minOf(to ?: InventoryHorizon.end(today), InventoryHorizon.end(today))
        val types = rooms.activeRoomTypes().map { it.id }.toSet() + store.roomTypesFrom(today)
        val outcome = refresh(types, start, end, recountUsage = true)
        return InventoryRecount(start, end, outcome.roomTypes, outcome.changedRows)
    }

    private fun roomCounts(
        type: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ) = rooms.activeRooms(type).let { active ->
        val outOfOrder =
            active.flatMap { room ->
                rooms
                    .outOfOrderPeriods(from, to, room.id)
                    .filter { it.kind == OutOfOrderKind.OUT_OF_ORDER }
                    .mapNotNull { period -> period.blockedUntil?.let { BlockedRange(room.id, period.startsOn, it) } }
            }
        RoomCounting.perDate(active.map { it.id }.toSet(), outOfOrder, from, to)
    }

    private fun usage(
        types: List<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage> =
        usageSources
            .orderedStream()
            .toList()
            .flatMap { it.usage(types, from, to).entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, list) -> InventoryUsage(list.sumOf { it.sold }, list.sumOf { it.blocked }) }

    private fun describe(
        types: List<RoomTypeId>,
        current: Map<InventoryKey, InventoryCounters>,
        changed: Map<InventoryKey, InventoryCounters>,
        created: Set<InventoryKey>,
    ) {
        if (changed.isEmpty() && created.isEmpty()) {
            audit.noChanges()
            return
        }
        types.forEach { type ->
            val typeChanged = changed.filterKeys { it.roomTypeId == type }
            val typeCreated = created.count { it.roomTypeId == type }
            if (typeChanged.isEmpty() && typeCreated == 0) return@forEach
            val before = typeChanged.keys.filter { it !in created }.associateWith { current.getValue(it) }
            audit.record(
                ReservationsAudit.ROOM_TYPE_INVENTORY,
                type.value,
                ReservationsAudit.snapshot(before),
                ReservationsAudit.snapshot(typeChanged) + ("createdRows" to typeCreated),
            )
        }
    }
}

/**
 * Os refazimentos do inventário feitos pelo sistema, cada um numa transação própria e auditado com o ator sistema:
 * pelos listeners do cadastro de UH e do OOO ([refresh]), pelo job do horizonte ([extendHorizon]) e pela recontagem
 * da API pública ([recount], para o fechamento do dia do M6).
 */
@Service
class InventoryRecalculation(
    private val refresher: InventoryRefresher,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
) {
    /** Refaz o total e o OOO dos tipos de [from] a [to] (cortado no horizonte); devolve as linhas que mudaram. */
    @Audited(ReservationsAudit.REFRESH_INVENTORY)
    @Transactional
    fun refresh(
        roomTypeIds: Set<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Int = refresher.refresh(roomTypeIds, from, to, recountUsage = false).changedRows

    /** Os tipos de UH cujo horizonte o job estende: os ativos. */
    @Transactional(readOnly = true)
    fun horizonRoomTypes(): List<RoomTypeId> = rooms.activeRoomTypes().map { it.id }

    /** Cria as datas que faltam no horizonte do tipo e refaz o total e o OOO dele; devolve as linhas que mudaram. */
    @Audited(ReservationsAudit.EXTEND_INVENTORY_HORIZON)
    @Transactional
    fun extendHorizon(roomTypeId: RoomTypeId): Int {
        val today = hotel.currentBusinessDate()
        return refresher
            .refresh(
                setOf(roomTypeId),
                today,
                InventoryHorizon.end(today),
                recountUsage = false,
            ).changedRows
    }

    /** A recontagem de todos os tipos (os ativos e os que já têm inventário), com o uso refeito. */
    @Audited(ReservationsAudit.RECOUNT_INVENTORY)
    @Transactional
    fun recount(
        from: LocalDate?,
        to: LocalDate?,
    ): InventoryRecount = refresher.recount(from, to)
}

/**
 * Os primeiros consumidores de produção de [OutOfOrderChanged] e de [RoomRegistryChanged] (SPEC §8.3, H4 e H10 do M4):
 * assíncronos, cada um numa transação própria, e reentregues pelo outbox se falharem. Idempotentes porque **refazem**
 * as datas afetadas a partir da [RoomsApi] (a fonte da verdade), nunca somam deltas: o evento reentregue, ou dois fora
 * de ordem, deixam o mesmo resultado. Entre o commit do OOO (ou do cadastro) e o listener há uma janela de segundos,
 * aceita pela H10; a recontagem e o job do horizonte reparam uma publicação abandonada.
 */
@Component
class InventoryListeners(
    private val recalculation: InventoryRecalculation,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Criação, troca de tipo, desativação ou reativação de UH: refaz o horizonte inteiro dos tipos afetados. */
    @ApplicationModuleListener
    fun on(event: RoomRegistryChanged) {
        val types = setOfNotNull(RoomTypeId(event.roomTypeId), event.previousRoomTypeId?.let(::RoomTypeId))
        val today = hotel.currentBusinessDate()
        val changed = recalculation.refresh(types, today, InventoryHorizon.end(today))
        log.info("Inventário refeito pelo cadastro da UH {} ({}): {} linhas", event.roomId, event.change, changed)
    }

    /**
     * Abertura, alteração ou liberação de um OOO: refaz as datas do período (e as do anterior, numa alteração) do tipo
     * atual da UH. Um OOS não mexe no inventário (continua vendável) e é ignorado.
     */
    @ApplicationModuleListener
    fun on(event: OutOfOrderChanged) {
        if (event.kind != OutOfOrderKind.OUT_OF_ORDER) return
        val room = rooms.room(RoomId(event.roomId))
        if (room == null) {
            log.warn("OOO {} de uma UH inexistente ({}); nada a refazer", event.outOfOrderId, event.roomId)
            return
        }
        val from = listOfNotNull(event.startsOn, event.previousStartsOn).min()
        val to = listOfNotNull(event.endsOn, event.previousEndsOn).max()
        val changed = recalculation.refresh(setOf(room.roomTypeId), from, to)
        log.info("Inventário refeito pelo OOO {} ({}): {} linhas", event.outOfOrderId, event.action, changed)
    }
}
