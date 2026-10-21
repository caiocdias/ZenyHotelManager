package com.zeny.hotel.reservations.domain

import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A linha do inventário: um tipo de UH numa data. */
data class InventoryKey(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
)

/**
 * Os contadores de uma linha do inventário (RF-TAR-08 e 09, ARCHITECTURE §11): [total] de UHs ativas do tipo, [sold] a
 * reservas, [blocked] a bloqueios, [outOfOrder] UHs ativas em OOO e o [overbookingLimit]. O disponível ([available])
 * pode ser negativo: uma UH desativada ou um OOO aberto depois das vendas deixam a data sobrevendida.
 */
data class InventoryCounters(
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
) {
    val available: Int get() = total - sold - blocked - outOfOrder + overbookingLimit

    /** Os contadores com as UHs refeitas pela `RoomsApi` (o uso e o limite ficam). */
    fun withRooms(rooms: RoomCounts): InventoryCounters = copy(total = rooms.total, outOfOrder = rooms.outOfOrder)

    /** Os contadores com o uso refeito pelas reservas e bloqueios (a recontagem). */
    fun withUsage(usage: InventoryUsage): InventoryCounters = copy(sold = usage.sold, blocked = usage.blocked)

    companion object {
        /** Uma linha nova: tudo zero, inclusive o limite de overbooking (Q-09). */
        val EMPTY = InventoryCounters(0, 0, 0, 0, 0)
    }
}

/** O que a `RoomsApi` diz de um tipo numa data: as UHs ativas e quantas delas estão em OOO. */
data class RoomCounts(
    val total: Int,
    val outOfOrder: Int,
)

/** O uso de um tipo numa data pelas reservas ([sold]) e pelos bloqueios ([blocked]). */
data class InventoryUsage(
    val sold: Int,
    val blocked: Int,
) {
    companion object {
        val NONE = InventoryUsage(0, 0)
    }
}

/** A que o uso de uma unidade se destina: uma reserva ([SOLD]) ou um bloqueio de grupo ([BLOCKED]). */
enum class InventoryUse {
    SOLD,
    BLOCKED,
}

/**
 * Uma mudança de uso pedida por uma reserva ou um bloqueio: [quantity] unidades do tipo [roomTypeId] em cada noite de
 * [from] até a véspera de [until] (a saída não é noite), positiva para tomar e negativa para devolver.
 */
data class InventoryChange(
    val roomTypeId: RoomTypeId,
    val from: LocalDate,
    val until: LocalDate,
    val quantity: Int,
    val use: InventoryUse,
) {
    init {
        require(until.isAfter(from)) { "A mudança precisa de ao menos uma noite: $from a $until" }
        require(quantity != 0) { "Quantidade zero" }
    }

    /** As noites da mudança, em ordem. */
    fun dates(): List<LocalDate> = from.datesUntil(until).toList()

    companion object {
        fun take(
            roomTypeId: RoomTypeId,
            from: LocalDate,
            until: LocalDate,
            quantity: Int = 1,
            use: InventoryUse = InventoryUse.SOLD,
        ): InventoryChange {
            require(quantity > 0) { "Tomar exige quantidade positiva" }
            return InventoryChange(roomTypeId, from, until, quantity, use)
        }

        fun release(
            roomTypeId: RoomTypeId,
            from: LocalDate,
            until: LocalDate,
            quantity: Int = 1,
            use: InventoryUse = InventoryUse.SOLD,
        ): InventoryChange {
            require(quantity > 0) { "Devolver exige quantidade positiva" }
            return InventoryChange(roomTypeId, from, until, -quantity, use)
        }
    }
}

/**
 * As regras de uma mudança de uso sobre as linhas travadas (o serviço trava, aqui só se decide). As mudanças de uma
 * mesma transação valem juntas, linha a linha (a soma das vendidas e bloqueadas de cada uma): uma linha cujo uso
 * **cresce** precisa continuar com o disponível ≥ 0 depois, senão a mudança inteira é recusada (`409
 * ROOM_TYPE_UNAVAILABLE`); uma linha cujo uso diminui ou fica igual (devolução, ou bloqueio que vira reserva) passa
 * sempre, mesmo sobrevendida. Tomar numa data sem linha (fora do horizonte) é `422 INVENTORY_DATE_OUT_OF_HORIZON`;
 * devolver numa data sem linha, ou mais do que foi tomado, é erro de programação (`IllegalStateException`).
 */
object InventoryLedger {
    fun apply(
        current: Map<InventoryKey, InventoryCounters>,
        changes: List<InventoryChange>,
    ): Map<InventoryKey, InventoryCounters> {
        val deltas = deltas(changes)
        val missing = deltas.filter { (key, delta) -> key !in current && delta.consumption > 0 }.keys
        if (missing.isNotEmpty()) throw InventoryOutOfHorizonException(missing.map { it.date })
        val unavailable = mutableListOf<InventoryKey>()
        val result =
            deltas.mapValues { (key, delta) ->
                val before = checkNotNull(current[key]) { "Devolução numa data sem inventário: $key" }
                val after = before.copy(sold = before.sold + delta.sold, blocked = before.blocked + delta.blocked)
                check(after.sold >= 0 && after.blocked >= 0) { "Devolução acima do tomado em $key" }
                if (delta.consumption > 0 && after.available < 0) unavailable += key
                after
            }
        if (unavailable.isNotEmpty()) throw RoomTypeUnavailableException(unavailable)
        return result.filter { (key, after) -> after != current[key] }
    }

    /** As linhas que as mudanças tocam, em ordem de (tipo, data). */
    fun keys(changes: List<InventoryChange>): List<InventoryKey> = deltas(changes).keys.toList()

    private data class Delta(
        val sold: Int,
        val blocked: Int,
    ) {
        val consumption: Int get() = sold + blocked

        operator fun plus(other: Delta) = Delta(sold + other.sold, blocked + other.blocked)
    }

    private fun deltas(changes: List<InventoryChange>): Map<InventoryKey, Delta> =
        changes
            .flatMap { change ->
                val delta =
                    when (change.use) {
                        InventoryUse.SOLD -> Delta(change.quantity, 0)
                        InventoryUse.BLOCKED -> Delta(0, change.quantity)
                    }
                change.dates().map { InventoryKey(change.roomTypeId, it) to delta }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.reduce(Delta::plus) }
            .toSortedMap(INVENTORY_ORDER)
}

/** A ordem determinística das linhas (tipo de UH pelo texto do id, depois a data), a mesma das travas no banco. */
val INVENTORY_ORDER: Comparator<InventoryKey> =
    compareBy<InventoryKey> { it.roomTypeId.value.toString() }.thenBy { it.date }

/**
 * O horizonte do inventário (H3 do M4): [DAYS] datas a partir da data operacional. O inventário de datas anteriores
 * fica como estava (não é refeito); além do fim, não há linha.
 */
object InventoryHorizon {
    const val DAYS = 730L

    fun end(businessDate: LocalDate): LocalDate = businessDate.plusDays(DAYS - 1)
}

/** Um período em OOO de uma UH: os dias de [from] a [until] (inclusive) que ele bloqueia. */
data class BlockedRange(
    val roomId: RoomId,
    val from: LocalDate,
    val until: LocalDate,
)

/**
 * As UHs que a `RoomsApi` mostra para um tipo: as ativas ([activeRooms]) e os períodos OOO (só `OUT_OF_ORDER`) que
 * bloqueiam alguma data. O OOO de uma UH inativa não conta (ela já está fora do total).
 */
object RoomCounting {
    fun perDate(
        activeRooms: Set<RoomId>,
        outOfOrder: List<BlockedRange>,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, RoomCounts> {
        val counted = outOfOrder.filter { it.roomId in activeRooms }
        return from.datesUntil(to.plusDays(1)).toList().associateWith { date ->
            val inOutOfOrder = counted.filter { !date.isBefore(it.from) && !date.isAfter(it.until) }.map { it.roomId }
            RoomCounts(activeRooms.size, inOutOfOrder.toSet().size)
        }
    }
}

/** Um período de datas de negócio (inclusive) e os dias da semana dele (vazio = todos). */
data class InventoryPeriod(
    val from: LocalDate,
    val to: LocalDate,
    val weekdays: Set<DayOfWeek> = emptySet(),
) {
    /** As datas do período nos dias da semana pedidos. */
    fun dates(): List<LocalDate> =
        from.datesUntil(to.plusDays(1)).filter { weekdays.isEmpty() || it.dayOfWeek in weekdays }.toList()

    companion object {
        const val MAX_DAYS = 366L

        /**
         * Fim antes do início `400 INVENTORY_PERIOD_INVALID`; mais de [MAX_DAYS] dias `400 INVENTORY_PERIOD_TOO_LONG`.
         */
        fun of(
            from: LocalDate,
            to: LocalDate,
            weekdays: Set<DayOfWeek> = emptySet(),
        ): InventoryPeriod {
            if (to.isBefore(from)) {
                throw InventoryRules.invalid(
                    ReservationsErrorCodes.INVENTORY_PERIOD_INVALID,
                    "to",
                    "O fim do período não pode ser antes do início.",
                )
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw InventoryRules.invalid(
                    ReservationsErrorCodes.INVENTORY_PERIOD_TOO_LONG,
                    "to",
                    "Informe até $MAX_DAYS dias por vez; divida períodos maiores.",
                )
            }
            return InventoryPeriod(from, to, weekdays)
        }
    }
}

/** O limite de overbooking (RF-TAR-09): de [MIN] a [MAX] unidades além do disponível; nasce zero (Q-09). */
object OverbookingLimit {
    const val MIN = 0
    const val MAX = 50

    fun of(value: Int): Int {
        if (value !in MIN..MAX) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.OVERBOOKING_LIMIT_INVALID,
                "limit",
                "Informe o limite de overbooking de $MIN a $MAX.",
            )
        }
        return value
    }
}

internal object InventoryRules {
    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

/** Tomada recusada: o tipo não tem unidades disponíveis em [keys] (`409 ROOM_TYPE_UNAVAILABLE`). */
class RoomTypeUnavailableException(
    val keys: List<InventoryKey>,
) : ConflictException(
        ReservationsErrorCodes.ROOM_TYPE_UNAVAILABLE,
        "Não há UH disponível do tipo pedido em ${keys.size} data(s) do período.",
    ) {
    override val problemProperties: Map<String, Any>
        get() =
            mapOf(
                "roomTypeIds" to keys.map { it.roomTypeId.value.toString() }.distinct(),
                "dates" to keys.map { it.date.toString() }.distinct().sorted(),
            )
}

/** Tomada numa data sem inventário (`422 INVENTORY_DATE_OUT_OF_HORIZON`, as datas em `dates`). */
class InventoryOutOfHorizonException(
    val dates: List<LocalDate>,
) : BusinessRuleException(
        ReservationsErrorCodes.INVENTORY_DATE_OUT_OF_HORIZON,
        "Há datas fora do inventário mantido (até ${InventoryHorizon.DAYS} dias da data operacional).",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("dates" to dates.map { it.toString() }.distinct().sorted())
}
