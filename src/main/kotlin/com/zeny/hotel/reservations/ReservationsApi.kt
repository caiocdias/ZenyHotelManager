package com.zeny.hotel.reservations

import com.zeny.hotel.rooms.RoomTypeId
import java.time.LocalDate

/**
 * API pública do `reservations` para os outros módulos (sem checar permissão: quem chama já se autorizou).
 *
 * Inventário (M4 E05): a leitura dos contadores e a recontagem, que o fechamento do dia (M6) chama para reparar
 * qualquer divergência (uma publicação do outbox abandonada, por exemplo).
 */
interface ReservationsApi {
    /**
     * Os contadores do tipo de UH [roomTypeId] de [from] a [to] (inclusive, até 366 dias), em ordem de data; só as
     * datas mantidas no inventário (as de fora não aparecem).
     */
    fun inventory(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay>

    /**
     * Recontagem (auditada `reservations.inventory.recount`): refaz, de [from] (padrão e mínimo: a data operacional) a
     * [to] (padrão e máximo: o fim do horizonte), o total e o OOO pela `RoomsApi` e as vendidas e bloqueadas pelas
     * reservas e bloqueios, de todos os tipos de UH ativos e dos que já têm inventário; cria as datas que faltam.
     * Publica [AvailabilityChanged] nos tipos que mudaram. Precisa de uma transação de escrita de quem chama ou abre a
     * própria.
     */
    fun recountInventory(
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): InventoryRecount
}

/**
 * Os contadores de um tipo de UH numa data (RF-TAR-08 e 09): [total] de UHs ativas, [sold] a reservas, [blocked] a
 * bloqueios, [outOfOrder] UHs em OOO (o OOS não conta) e o [overbookingLimit]. [available] pode ser negativo (a data
 * está sobrevendida).
 */
data class InventoryDay(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
) {
    val available: Int get() = total - sold - blocked - outOfOrder + overbookingLimit
}

/** O resultado de uma recontagem: o período recontado, os tipos de UH e as linhas que mudaram. */
data class InventoryRecount(
    val from: LocalDate,
    val to: LocalDate,
    val roomTypes: Int,
    val changedRows: Int,
)
