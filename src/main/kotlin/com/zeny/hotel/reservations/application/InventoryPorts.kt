package com.zeny.hotel.reservations.application

import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import java.time.Instant
import java.time.LocalDate

/**
 * O inventário por tipo de UH e data (`reservations.room_type_inventory`), sempre na transação de quem chama. As travas
 * saem sempre na mesma ordem, para nenhuma combinação dar deadlock: primeiro a trava consultiva de cada tipo
 * ([lockRoomTypes], só quem refaz o total e o OOO a toma), depois as linhas em ordem de (tipo, data) ([lockRows]).
 */
interface InventoryStore {
    /**
     * Trava consultiva da transação (`pg_advisory_xact_lock`) de cada tipo, em ordem de id: serializa quem refaz o
     * total e o OOO de um tipo (listeners, job, recontagem), para cada um ler a `RoomsApi` depois do anterior
     * confirmar. A tomada e a devolução de uso não a tomam.
     */
    fun lockRoomTypes(roomTypeIds: Collection<RoomTypeId>)

    /** Cria, com tudo zero, as linhas que faltam dos tipos de [from] a [to]; devolve as criadas. */
    fun createMissing(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
        now: Instant,
    ): Set<InventoryKey>

    /** Trava (`SELECT … FOR UPDATE`, em ordem de tipo e data) e lê as linhas [keys] que existem. */
    fun lockRows(keys: Collection<InventoryKey>): Map<InventoryKey, InventoryCounters>

    /** Trava e lê as linhas dos tipos de [from] a [to] (inclusive), em ordem de tipo e data. */
    fun lockRows(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryCounters>

    /** Lê, sem travar, as linhas do tipo de [from] a [to] (inclusive), em ordem de data. */
    fun rows(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryCounters>

    /** Grava os contadores das linhas [rows] (que existem e estão travadas), num só comando. */
    fun update(
        rows: Map<InventoryKey, InventoryCounters>,
        now: Instant,
    )

    /** Os tipos de UH que têm alguma linha de [from] em diante. */
    fun roomTypesFrom(from: LocalDate): Set<RoomTypeId>
}

/**
 * Fonte do uso do inventário para a recontagem: as reservas (E07) e os bloqueios (E11) dizem quantas unidades usam de
 * cada tipo em cada data. Sem nenhuma fonte (até a E07), o uso recontado é zero.
 */
interface InventoryUsageSource {
    /** O uso dos tipos de [from] a [to] (inclusive); a linha que não aparece não tem uso desta fonte. */
    fun usage(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage>
}
