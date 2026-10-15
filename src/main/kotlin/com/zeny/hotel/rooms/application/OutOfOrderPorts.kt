package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.domain.OutOfOrder
import com.zeny.hotel.rooms.domain.OutOfOrderDetails
import java.time.LocalDate
import java.util.UUID

/** Os dados de um período OOO/OOS como chegam da API (RF-UH-05). */
data class OutOfOrderCommand(
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: String,
    val responsible: String,
    val returnStatus: HousekeepingStatus,
) {
    internal val details: OutOfOrderDetails
        get() = OutOfOrderDetails(startsOn, endsOn, reason, responsible, returnStatus)
}

/**
 * A sobreposição OOO/OOS em vigor numa UH na data operacional (RF-UH-04), como o painel e o status a
 * mostram: o período, o tipo e as datas planejadas.
 */
data class OutOfOrderOverlay(
    val id: OutOfOrderId,
    val kind: OutOfOrderKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

/** Os períodos OOO/OOS para escrita (JPA), na transação corrente. */
interface OutOfOrderStore {
    fun find(id: OutOfOrderId): OutOfOrder?

    /**
     * O período travado para escrita (`SELECT … FOR UPDATE`) e relido do banco. Quem escreve trava antes
     * a UH dele, sempre nessa ordem.
     */
    fun findForUpdate(id: OutOfOrderId): OutOfOrder?

    /** Os períodos da UH cujo planejado cruza de [from] a [to] (liberados ou não; o domínio confere). */
    fun ofRoom(
        roomId: RoomId,
        from: LocalDate,
        to: LocalDate,
    ): List<OutOfOrder>

    /** Grava o período novo; `false` se o banco recusar a sobreposição (`out_of_order_overlap_ex`). */
    fun tryAdd(period: OutOfOrder): Boolean

    /** Grava as mudanças pendentes; `false` se o banco recusar a sobreposição. */
    fun tryFlush(): Boolean
}

/** Leituras dos períodos OOO/OOS (`JdbcClient`, ARCHITECTURE §11). */
interface OutOfOrderRecords {
    /** A sobreposição que bloqueia a UH na data [date], se houver (no máximo uma: não se sobrepõem). */
    fun inEffect(
        roomId: RoomId,
        date: LocalDate,
    ): OutOfOrderOverlay?

    /**
     * Uma página dos períodos que bloqueiam algum dia de [from] a [to] (sem [to], de [from] em diante),
     * filtrados pela UH e pelo tipo, em ordem de início e id, depois de [after].
     */
    @Suppress("LongParameterList") // os filtros e a página
    fun periods(
        from: LocalDate,
        to: LocalDate?,
        roomId: RoomId?,
        kind: OutOfOrderKind?,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<OutOfOrderPeriod>
}

internal fun OutOfOrder.period() =
    OutOfOrderPeriod(
        id,
        roomId,
        kind,
        startsOn,
        endsOn,
        reason,
        responsible,
        returnStatus,
        openedAt,
        openedBy,
        updatedAt,
        releasedOn,
        releasedAt,
        releasedBy,
        blockedUntil,
        checkNotNull(version),
    )
