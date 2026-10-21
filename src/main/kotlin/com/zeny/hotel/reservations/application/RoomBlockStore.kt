package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.reservations.BlockAllocationSummary
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.RoomBlockStatus
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import java.time.Instant
import java.time.LocalDate

/**
 * Os bloqueios de grupo (`reservations.room_block` e `block_allocation`, M4 E11), sempre na transação de quem chama.
 *
 * **Travas:** toda escrita num bloqueio (alteração, pick-up, liberação e a devolução da unidade pelo
 * cancelamento de uma reserva do pick-up) começa travando a linha do bloqueio ([blockForUpdate],
 * `SELECT … FOR UPDATE`); as unidades só mudam com ela travada, então duas escritas no mesmo bloqueio se enfileiram e
 * a segunda vê a primeira. A ordem com as outras travas é sempre: os hóspedes (a trava consultiva do pick-up), a
 * reserva (no cancelamento), o bloqueio e por
 * fim as linhas do inventário.
 */
interface RoomBlockStore {
    /** Grava o bloqueio com as unidades. */
    fun insert(block: RoomBlockSummary)

    fun block(id: RoomBlockId): RoomBlockSummary?

    /** O bloqueio com a linha travada até o fim da transação; `null` se não existe. */
    fun blockForUpdate(id: RoomBlockId): RoomBlockSummary?

    /**
     * Grava o cabeçalho do bloqueio ([block], com a versão já avançada pelo chamador: confere a anterior), as linhas
     * [saved] (novas ou mudadas) e retira as [removed].
     */
    fun update(
        block: RoomBlockSummary,
        saved: List<BlockAllocationSummary>,
        removed: List<InventoryKey>,
        now: Instant,
    )

    /** Os bloqueios em ordem do mais novo ao mais antigo (pelo id), depois de [after], até [limit]; filtro opcional. */
    fun page(
        status: RoomBlockStatus?,
        after: RoomBlockId?,
        limit: Int,
    ): List<RoomBlockId>

    /**
     * As unidades seguras pelos bloqueios ativos por tipo e noite (de [from] a [to], inclusive): o que ainda não virou
     * reserva nem foi liberado (`quantity - picked_up - released`; num bloqueio ativo `released` é zero), para a
     * recontagem.
     */
    fun blockedUnits(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage>

    /** Reponta o titular hóspede [merged] dos bloqueios para [target], avançando a versão; devolve os mudados. */
    fun repointHolder(
        merged: GuestId,
        target: GuestId,
        now: Instant,
    ): List<RoomBlockId>

    /** Os bloqueios cujo titular é algum dos hóspedes, em ordem de criação. */
    fun blocksHeldBy(guestIds: Collection<GuestId>): List<RoomBlockId>

    /** Os bloqueios ativos com a data de corte igual ou anterior a [businessDate], em ordem de corte e id. */
    fun dueForRelease(businessDate: LocalDate): List<RoomBlockId>
}
