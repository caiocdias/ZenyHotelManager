package com.zeny.hotel.reservations.domain

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.BlockAllocationRange
import com.zeny.hotel.reservations.BlockAllocationSummary
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.RoomBlockStatus
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** As unidades pedidas de um tipo de UH numa noite do bloqueio: a quantidade e a tarifa do grupo. */
data class AllocationTerms(
    val quantity: Int,
    val rate: Money,
)

/**
 * As regras de formato de um bloqueio de grupo (M4 E11; RF-RES-09): nome de 1 a [MAX_NAME] caracteres, titular que é
 * exatamente um hóspede ou uma empresa, até [MAX_RANGES] faixas de unidades (ao menos uma na criação), cada uma com o
 * fim não antes do início e até [MAX_NIGHTS] noites, de 1 a [MAX_QUANTITY] UHs por noite e a tarifa de 0,00 a
 * [MAX_RATE], sem duas faixas do mesmo tipo de UH na mesma noite. Junta as recusas (`400 ROOM_BLOCK_INVALID`) com o
 * campo de cada uma.
 */
object RoomBlockRules {
    const val MAX_NAME = 120
    const val MAX_RANGES = 50
    const val MAX_QUANTITY = 50
    const val MAX_NIGHTS = 366L
    val MAX_RATE: Money = Money.of("1000000.00")

    /** Confere o formato e devolve as unidades por tipo de UH e noite, em ordem de (tipo, data). */
    @Suppress("CyclomaticComplexMethod") // as recusas de formato juntas, com o campo de cada uma
    fun requireValid(
        name: String,
        holder: Pair<GuestId?, CompanyId?>?,
        allocations: List<BlockAllocationRange>,
        requireAllocations: Boolean,
    ): Map<InventoryKey, AllocationTerms> {
        val violations = mutableListOf<FieldViolation>()

        fun violation(
            field: String,
            message: String,
        ) {
            violations += FieldViolation(field, ReservationsErrorCodes.ROOM_BLOCK_INVALID.value, message)
        }
        if (name.isBlank() || name.length > MAX_NAME || name.trim() != name) {
            violation("name", "O nome do grupo tem de 1 a $MAX_NAME caracteres, sem espaços nas pontas.")
        }
        if (holder != null && (holder.first == null) == (holder.second == null)) {
            violation(
                "holderGuestId",
                "Informe o titular: um hóspede (holderGuestId) ou uma empresa (holderCompanyId).",
            )
        }
        if ((requireAllocations && allocations.isEmpty()) || allocations.size > MAX_RANGES) {
            violation(
                "allocations",
                "Informe de ${if (requireAllocations) 1 else 0} a $MAX_RANGES faixas de unidades.",
            )
        }
        val units = sortedMapOf<InventoryKey, AllocationTerms>(INVENTORY_ORDER)
        allocations.forEachIndexed { index, range ->
            val path = "allocations[$index]"
            val rangeViolations = rangeViolations(range, path)
            violations += rangeViolations
            if (rangeViolations.isEmpty()) {
                val keys =
                    range.from
                        .datesUntil(
                            range.to.plusDays(1),
                        ).map { InventoryKey(range.roomTypeId, it) }
                        .toList()
                if (keys.any { it in units }) {
                    violation(path, "Duas faixas do mesmo tipo de UH cobrem a mesma noite.")
                } else {
                    keys.forEach { units[it] = AllocationTerms(range.quantity, range.rate) }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(
                ReservationsErrorCodes.ROOM_BLOCK_INVALID,
                violations.first().message,
                violations,
            )
        }
        return units
    }

    private fun rangeViolations(
        range: BlockAllocationRange,
        path: String,
    ): List<FieldViolation> =
        buildList {
            val code = ReservationsErrorCodes.ROOM_BLOCK_INVALID.value
            if (range.to.isBefore(range.from) || ChronoUnit.DAYS.between(range.from, range.to) + 1 > MAX_NIGHTS) {
                add(
                    FieldViolation(
                        "$path.to",
                        code,
                        "A faixa vai de 1 a $MAX_NIGHTS noites (fim não antes do início).",
                    ),
                )
            }
            if (range.quantity !in 1..MAX_QUANTITY) {
                add(FieldViolation("$path.quantity", code, "Informe de 1 a $MAX_QUANTITY UHs por noite."))
            }
            if (range.rate < Money.ZERO || range.rate > MAX_RATE) {
                add(FieldViolation("$path.rate", code, "Informe a tarifa do grupo de 0,00 a 1.000.000,00."))
            }
        }
}

/**
 * A data de corte (RN-16, H15; decidida pelo orquestrador em nome do usuário na E11): o pick-up vale enquanto o
 * bloqueio está ativo e a data operacional não passou da data de corte (inclusive o próprio dia); a liberação do não
 * utilizado vale a partir da data de corte (inclusive: o fechamento do dia de corte a chama, M6) e é recusada antes
 * (`409 ROOM_BLOCK_CUTOFF_NOT_REACHED`).
 */
object RoomBlockCutoff {
    fun pickUpOpen(
        cutoffDate: LocalDate,
        businessDate: LocalDate,
    ): Boolean = !businessDate.isAfter(cutoffDate)

    fun releasable(
        cutoffDate: LocalDate,
        businessDate: LocalDate,
    ): Boolean = !businessDate.isBefore(cutoffDate)

    fun requirePickUpOpen(
        block: RoomBlockSummary,
        businessDate: LocalDate,
    ) {
        requireActive(block)
        if (!pickUpOpen(block.cutoffDate, businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.ROOM_BLOCK_CUTOFF_PASSED,
                "A data de corte do bloqueio ${block.code} já passou: não há mais pick-up.",
            )
        }
    }

    fun requireActive(block: RoomBlockSummary) {
        if (block.status != RoomBlockStatus.ACTIVE) {
            throw ConflictException(
                ReservationsErrorCodes.ROOM_BLOCK_RELEASED,
                "O bloqueio ${block.code} já foi liberado e não muda mais.",
            )
        }
    }
}

/**
 * As unidades do bloqueio por tipo de UH e noite e o que cada operação faz com elas (M4 E11). Num bloqueio ativo, cada
 * linha segura `quantity - pickedUp` unidades no contador `blocked` do inventário; o pick-up passa uma unidade de
 * `blocked` para `sold` (a linha do inventário fica com o uso igual) e a conta em `pickedUp`; o cancelamento de uma
 * reserva do pick-up a devolve ao bloqueio enquanto ele está ativo; a liberação devolve ao inventário o que sobra.
 */
object BlockAllocations {
    /** As linhas gravadas indexadas pela chave do inventário. */
    fun byKey(allocations: List<BlockAllocationSummary>): Map<InventoryKey, BlockAllocationSummary> =
        allocations.associateBy { InventoryKey(it.roomTypeId, it.date) }

    /** O resultado de uma alteração das unidades: as linhas novas ou mudadas, as que saem e o inventário. */
    data class Replacement(
        val saved: List<BlockAllocationSummary>,
        val removed: List<InventoryKey>,
        val inventoryChanges: List<InventoryChange>,
    ) {
        val empty: Boolean get() = saved.isEmpty() && removed.isEmpty()
    }

    /**
     * As unidades pedidas [requested] substituem as gravadas das noites a partir da data operacional [businessDate];
     * as anteriores ficam como estão (pedir uma delas é `422 ROOM_BLOCK_PERIOD_IN_PAST`). Uma noite não fica com menos
     * unidades que as já convertidas em reservas (`422 ROOM_BLOCK_BELOW_PICK_UP`). Cada diferença de quantidade vira
     * uma mudança de `blocked` no inventário naquela noite (tomar quando cresce, devolver quando diminui).
     */
    fun replace(
        current: List<BlockAllocationSummary>,
        requested: Map<InventoryKey, AllocationTerms>,
        businessDate: LocalDate,
    ): Replacement {
        val past = requested.keys.filter { it.date.isBefore(businessDate) }
        if (past.isNotEmpty()) throw RoomBlockPeriodInPastException(past.map { it.date })
        val rows = byKey(current).filterKeys { !it.date.isBefore(businessDate) }
        val below =
            rows.filter { (key, row) -> row.pickedUp > 0 && (requested[key]?.quantity ?: 0) < row.pickedUp }.keys
        if (below.isNotEmpty()) throw RoomBlockBelowPickUpException(below.toList())
        val saved = mutableListOf<BlockAllocationSummary>()
        val changes = mutableListOf<InventoryChange>()
        requested.forEach { (key, terms) ->
            val row = rows[key]
            val delta = terms.quantity - (row?.quantity ?: 0)
            if (row == null || delta != 0 || row.rate != terms.rate) {
                saved +=
                    BlockAllocationSummary(key.roomTypeId, key.date, terms.quantity, row?.pickedUp ?: 0, 0, terms.rate)
            }
            change(key, delta)?.let(changes::add)
        }
        val removed = rows.keys.filter { it !in requested }.sortedWith(INVENTORY_ORDER)
        removed.forEach { key -> change(key, -rows.getValue(key).quantity)?.let(changes::add) }
        return Replacement(saved, removed, changes)
    }

    private fun change(
        key: InventoryKey,
        delta: Int,
    ): InventoryChange? =
        when {
            delta > 0 -> {
                InventoryChange.take(
                    key.roomTypeId,
                    key.date,
                    key.date.plusDays(1),
                    delta,
                    InventoryUse.BLOCKED,
                )
            }

            delta < 0 -> {
                InventoryChange.release(
                    key.roomTypeId,
                    key.date,
                    key.date.plusDays(1),
                    -delta,
                    InventoryUse.BLOCKED,
                )
            }

            else -> {
                null
            }
        }

    /**
     * As linhas depois do pick-up de [demand] unidades por tipo e noite: cada noite precisa estar no bloqueio
     * (`422 ROOM_BLOCK_DATES_NOT_COVERED`) e ter unidades ainda não convertidas (`409 ROOM_BLOCK_UNAVAILABLE`).
     */
    fun pickUp(
        rows: Map<InventoryKey, BlockAllocationSummary>,
        demand: Map<InventoryKey, Int>,
    ): List<BlockAllocationSummary> {
        val uncovered = demand.keys.filter { it !in rows }
        if (uncovered.isNotEmpty()) throw RoomBlockDatesNotCoveredException(uncovered.sortedWith(INVENTORY_ORDER))
        val exhausted = demand.filter { (key, count) -> rows.getValue(key).remaining < count }.keys
        if (exhausted.isNotEmpty()) throw RoomBlockUnavailableException(exhausted.sortedWith(INVENTORY_ORDER))
        return demand.entries
            .sortedWith(compareBy(INVENTORY_ORDER) { it.key })
            .map { (key, count) -> rows.getValue(key).let { it.copy(pickedUp = it.pickedUp + count) } }
    }

    /**
     * As linhas depois de uma reserva do pick-up deixar de usar as noites [nights] (cancelamento ou expiração): a
     * unidade volta ao bloqueio (`pickedUp` − 1).
     */
    fun giveBack(
        rows: Map<InventoryKey, BlockAllocationSummary>,
        nights: List<InventoryKey>,
    ): List<BlockAllocationSummary> =
        nights.map { key ->
            val row = checkNotNull(rows[key]) { "Reserva do pick-up numa noite fora do bloqueio: $key" }
            check(row.pickedUp > 0) { "Devolução ao bloqueio acima do pick-up em $key" }
            row.copy(pickedUp = row.pickedUp - 1)
        }

    /**
     * Num bloqueio já liberado, a reserva do pick-up cancelada devolve a unidade ao inventário geral: as noites
     * [nights] que estão no bloqueio passam de `pickedUp` para `released` (as que saíram do bloqueio ficam como estão).
     */
    fun toInventory(
        rows: Map<InventoryKey, BlockAllocationSummary>,
        nights: List<InventoryKey>,
    ): List<BlockAllocationSummary> =
        nights.mapNotNull { rows[it] }.filter { it.pickedUp > 0 }.map {
            it.copy(pickedUp = it.pickedUp - 1, released = it.released + 1)
        }

    /**
     * Num bloqueio já liberado, a reserva do pick-up reativada toma a unidade do inventário geral: as noites [nights]
     * que estão no bloqueio com unidade liberada passam de `released` para `pickedUp`.
     */
    fun fromInventory(
        rows: Map<InventoryKey, BlockAllocationSummary>,
        nights: List<InventoryKey>,
    ): List<BlockAllocationSummary> =
        nights.mapNotNull { rows[it] }.filter { it.released > 0 }.map {
            it.copy(pickedUp = it.pickedUp + 1, released = it.released - 1)
        }

    /**
     * A liberação do não utilizado (RN-16): nas noites a partir da data operacional, o que não virou reserva volta ao
     * inventário ([Release.inventoryChanges], de `blocked`) e fica em `released`; as noites anteriores ficam.
     */
    data class Release(
        val saved: List<BlockAllocationSummary>,
        val inventoryChanges: List<InventoryChange>,
        val units: Int,
    )

    fun release(
        current: List<BlockAllocationSummary>,
        businessDate: LocalDate,
    ): Release {
        val unused = current.filter { !it.date.isBefore(businessDate) && it.remaining > 0 }
        return Release(
            saved = unused.map { it.copy(released = it.released + it.remaining) },
            inventoryChanges =
                unused.map {
                    InventoryChange.release(
                        it.roomTypeId,
                        it.date,
                        it.date.plusDays(1),
                        it.remaining,
                        InventoryUse.BLOCKED,
                    )
                },
            units = unused.sumOf { it.remaining },
        )
    }

    /** As noites de uma estadia (da chegada à véspera da saída) como chaves do inventário do tipo. */
    fun nights(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        until: LocalDate,
    ): List<InventoryKey> = from.datesUntil(until).map { InventoryKey(roomTypeId, it) }.toList()

    /**
     * O preço congelado de cada noite do pick-up (RN-14): a tarifa do grupo da noite é a diária (sem pacotes nem
     * desconto; a mesma diária calculada e gravada).
     */
    fun nightlyRates(
        rows: Map<InventoryKey, BlockAllocationSummary>,
        nights: List<InventoryKey>,
        ratePlanId: RatePlanId,
    ): List<NightlyRateSummary> =
        nights.map { key ->
            val rate = rows.getValue(key).rate
            NightlyRateSummary(
                date = key.date,
                ratePlanId = ratePlanId,
                calculatedRoomAmount = rate,
                roomAmount = rate,
                includedPackagesAmount = Money.ZERO,
                additionalPackagesAmount = Money.ZERO,
                amount = rate,
                overridden = false,
                discountPercent = null,
                packages = emptyList(),
            )
        }
}

/** As chaves (tipos e datas) de um problema do bloqueio. */
private fun blockProblem(keys: List<InventoryKey>): Map<String, Any> =
    mapOf(
        "roomTypeIds" to keys.map { it.roomTypeId.value.toString() }.distinct(),
        "dates" to keys.map { it.date.toString() }.distinct().sorted(),
    )

/** `409 ROOM_BLOCK_UNAVAILABLE`: as noites [keys] já não têm unidade do bloqueio para converter. */
class RoomBlockUnavailableException(
    val keys: List<InventoryKey>,
) : ConflictException(
        ReservationsErrorCodes.ROOM_BLOCK_UNAVAILABLE,
        "O bloqueio não tem mais unidades do tipo de UH em ${keys.size} noite(s) pedida(s).",
    ) {
    override val problemProperties: Map<String, Any> get() = blockProblem(keys)
}

/** `422 ROOM_BLOCK_DATES_NOT_COVERED`: as noites [keys] estão fora do bloqueio. */
class RoomBlockDatesNotCoveredException(
    val keys: List<InventoryKey>,
) : BusinessRuleException(
        ReservationsErrorCodes.ROOM_BLOCK_DATES_NOT_COVERED,
        "O bloqueio não tem unidades do tipo de UH em ${keys.size} noite(s) da reserva.",
    ) {
    override val problemProperties: Map<String, Any> get() = blockProblem(keys)
}

/** `422 ROOM_BLOCK_BELOW_PICK_UP`: a alteração deixaria as noites [keys] com menos unidades que as reservas. */
class RoomBlockBelowPickUpException(
    val keys: List<InventoryKey>,
) : BusinessRuleException(
        ReservationsErrorCodes.ROOM_BLOCK_BELOW_PICK_UP,
        "A alteração deixa ${keys.size} noite(s) com menos unidades que as já convertidas em reservas.",
    ) {
    override val problemProperties: Map<String, Any> get() = blockProblem(keys)
}

/** `422 ROOM_BLOCK_PERIOD_IN_PAST`: unidades pedidas em noites antes da data operacional. */
class RoomBlockPeriodInPastException(
    val dates: List<LocalDate>,
) : BusinessRuleException(
        ReservationsErrorCodes.ROOM_BLOCK_PERIOD_IN_PAST,
        "O bloqueio não recebe unidades em noites antes da data operacional.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("dates" to dates.map { it.toString() }.distinct().sorted())
}

/** `409 ROOM_BLOCK_CUTOFF_NOT_REACHED`: a liberação pedida antes da data de corte (`cutoffDate` no problema). */
class RoomBlockCutoffNotReachedException(
    code: String,
    val cutoffDate: LocalDate,
) : ConflictException(
        ReservationsErrorCodes.ROOM_BLOCK_CUTOFF_NOT_REACHED,
        "O bloqueio $code só libera o não utilizado a partir da data de corte.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("cutoffDate" to cutoffDate.toString())
}

/**
 * `422 ROOM_TYPE_NOT_IN_PLAN`: o tipo de UH de uma faixa não está no plano do bloqueio (o campo em `field`, o tipo em
 * `roomTypeIds`).
 */
class RoomTypeNotInBlockPlanException(
    val fieldPath: String,
    val roomTypeId: RoomTypeId,
) : BusinessRuleException(
        ReservationsErrorCodes.ROOM_TYPE_NOT_IN_PLAN,
        "O tipo de UH não está no plano tarifário do bloqueio.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("field" to fieldPath, "roomTypeIds" to listOf(roomTypeId.value.toString()))
}
