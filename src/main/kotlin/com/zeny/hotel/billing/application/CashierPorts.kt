package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.domain.CashMovement
import com.zeny.hotel.billing.domain.CashPoint
import com.zeny.hotel.billing.domain.CashierShift
import com.zeny.hotel.billing.domain.ShiftCount
import com.zeny.hotel.shared.UserId
import java.util.UUID

/** Os pontos e turnos de caixa (agregados mutáveis), na transação corrente (M3 E07). */
@Suppress("TooManyFunctions") // os dois agregados do caixa, na mesma unidade de persistência
interface CashierStore {
    fun cashPoint(id: CashPointId): CashPoint?

    fun cashPoints(ids: Collection<UUID>): List<CashPoint>

    /** Grava um ponto novo; `false` se o nome já existe (`cash_point_name_uk`). */
    fun tryAdd(cashPoint: CashPoint): Boolean

    /** Grava as mudanças pendentes; `false` se o nome do ponto ficou repetido. */
    fun tryFlushCashPoint(): Boolean

    fun shift(id: CashierShiftId): CashierShift?

    fun shifts(ids: List<UUID>): List<CashierShift>

    /** O turno com a linha travada (`SELECT … FOR UPDATE`): movimentos, fechamento e recebimentos se enfileiram. */
    fun shiftForUpdate(id: CashierShiftId): CashierShift?

    /** O turno aberto do operador, sem trava; `null` se não tem. */
    fun openShiftOf(operator: UserId): CashierShift?

    /**
     * O turno aberto do operador com a linha travada; `null` se não tem (ou se ele fechou enquanto se esperava a
     * trava).
     */
    fun openShiftOfForUpdate(operator: UserId): CashierShift?

    /** Grava um turno novo; `false` se o operador já tem um aberto (`cashier_shift_open_operator_uk`). */
    fun tryAdd(shift: CashierShift): Boolean

    /** Avança a versão do turno já (a linha continua travada): o `ETag` muda a cada movimento. */
    fun markChanged(shift: CashierShift)

    fun flush()
}

/** Os movimentos de caixa e as contagens do fechamento, só inseridos e lidos (RN-03). */
interface CashierLedger {
    fun add(movement: CashMovement)

    /** Os movimentos do turno, em ordem de registro. */
    fun movementsOf(shiftId: CashierShiftId): List<CashMovement>

    fun addCounts(
        shiftId: CashierShiftId,
        counts: List<ShiftCount>,
    )

    /** As contagens do fechamento, na ordem das formas de pagamento; vazia num turno aberto. */
    fun countsOf(shiftId: CashierShiftId): List<ShiftCount>
}

/** As listagens do caixa (só as chaves do cursor e os ids). */
interface CashierDirectory {
    fun cashPoints(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Os turnos, do mais novo para o mais antigo; [owner] não nulo restringe ao operador (escopo `OWN`). */
    fun shifts(
        filter: CashierShiftFilter,
        owner: UUID?,
        afterId: UUID?,
        limit: Int,
    ): List<UUID>
}

data class CashierShiftFilter(
    val status: CashierShiftStatus? = null,
    val cashPointId: CashPointId? = null,
    val operatorId: UserId? = null,
)
