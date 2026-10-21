package com.zeny.hotel.reservations.application

import com.zeny.hotel.reservations.AvailabilityChanged
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryLedger
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.rooms.RoomTypeId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Ações e entidade do inventário na trilha de auditoria. */
internal object ReservationsAudit {
    const val ROOM_TYPE_INVENTORY = "reservations.room-type-inventory"

    const val REFRESH_INVENTORY = "reservations.inventory.refresh"
    const val EXTEND_INVENTORY_HORIZON = "reservations.inventory.extend-horizon"
    const val RECOUNT_INVENTORY = "reservations.inventory.recount"
    const val SET_OVERBOOKING_LIMIT = "reservations.inventory.set-overbooking-limit"

    private const val MAX_AUDITED_VALUES = 20

    /**
     * O retrato de um conjunto de linhas de um tipo: o período, quantas e os valores distintos de cada contador (até
     * [MAX_AUDITED_VALUES]); sem as linhas uma a uma (uma recontagem toca até 730).
     */
    fun snapshot(rows: Map<InventoryKey, InventoryCounters>): Map<String, Any?> =
        mapOf(
            "from" to rows.keys.minOfOrNull { it.date }?.toString(),
            "to" to rows.keys.maxOfOrNull { it.date }?.toString(),
            "rows" to rows.size,
            "total" to distinct(rows.values.map { it.total }),
            "sold" to distinct(rows.values.map { it.sold }),
            "blocked" to distinct(rows.values.map { it.blocked }),
            "outOfOrder" to distinct(rows.values.map { it.outOfOrder }),
            "overbookingLimit" to distinct(rows.values.map { it.overbookingLimit }),
        )

    private fun distinct(values: List<Int>): List<Int> = values.distinct().sorted().take(MAX_AUDITED_VALUES)
}

/** Publica [AvailabilityChanged] na transação: um evento por tipo, com a primeira e a última data mudada. */
@Component
class AvailabilityEvents(
    private val events: ApplicationEventPublisher,
) {
    fun publish(
        keys: Collection<InventoryKey>,
        now: Instant,
    ) {
        keys
            .groupBy { it.roomTypeId }
            .toSortedMap(compareBy { it.value.toString() })
            .forEach { (type, typeKeys) ->
                events.publishEvent(
                    AvailabilityChanged(type.value, typeKeys.minOf { it.date }, typeKeys.maxOf { it.date }, now),
                )
            }
    }
}

/**
 * Tomada e devolução de unidades do inventário (RF-TAR-08 e 09, Q-09, ARCHITECTURE §5 e §11), para as reservas (E07)
 * e os bloqueios (E11), **na transação de escrita de quem chama** (fora dela, ou numa só de leitura,
 * `IllegalStateException`): o caso de uso de quem chama é o auditado.
 *
 * Trava as linhas das datas envolvidas com `SELECT … FOR UPDATE` em ordem de (tipo de UH, data), então duas tomadas
 * simultâneas da última unidade se enfileiram e só a primeira passa (a segunda vê o contador já mudado e recebe `409
 * ROOM_TYPE_UNAVAILABLE`), e intervalos sobrepostos pedidos em qualquer ordem nunca dão deadlock. As regras ficam no
 * [InventoryLedger]: a linha cujo uso cresce precisa continuar com o disponível ≥ 0 (o limite de overbooking entra na
 * conta); a devolução passa sempre. As travas duram até o fim da transação de quem chama, que deve ser curta. Publica
 * [AvailabilityChanged] por tipo, pelo outbox, quando algo mudou.
 */
@Component
class RoomTypeInventory(
    private val store: InventoryStore,
    private val availability: AvailabilityEvents,
    private val clock: Clock,
) {
    /** Aplica as mudanças juntas (por exemplo, devolver as datas antigas e tomar as novas de uma alteração). */
    fun apply(changes: List<InventoryChange>) {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "O inventário só muda dentro da transação de escrita de quem chama" }
        if (changes.isEmpty()) return
        val current = store.lockRows(InventoryLedger.keys(changes))
        val changed = InventoryLedger.apply(current, changes)
        if (changed.isEmpty()) return
        val now = clock.instant()
        store.update(changed, now)
        availability.publish(changed.keys, now)
    }

    /** Toma [quantity] unidades do tipo nas noites de [from] até a véspera de [until]. */
    fun take(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        until: LocalDate,
        quantity: Int = 1,
        use: InventoryUse = InventoryUse.SOLD,
    ) = apply(listOf(InventoryChange.take(roomTypeId, from, until, quantity, use)))

    /** Devolve [quantity] unidades do tipo nas noites de [from] até a véspera de [until]. */
    fun release(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        until: LocalDate,
        quantity: Int = 1,
        use: InventoryUse = InventoryUse.SOLD,
    ) = apply(listOf(InventoryChange.release(roomTypeId, from, until, quantity, use)))
}
