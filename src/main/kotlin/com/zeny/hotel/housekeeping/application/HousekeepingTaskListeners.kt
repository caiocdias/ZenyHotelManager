package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.rooms.RoomId
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * Escuta os eventos que deixam uma UH vaga para limpeza (RF-GOV-02, M6 E02) e delega à [HousekeepingTaskGeneration],
 * que grava a tarefa de saída com o ator sistema. Assíncrono, em transação própria e reentregue pelo outbox se falhar:
 * idempotente pela unicidade (local, data, tipo) da tarefa.
 */
@Component
class HousekeepingTaskListeners(
    private val generation: HousekeepingTaskGeneration,
) {
    /** O check-out: a `CHECKOUT` na UH, na data operacional do check-out. */
    @ApplicationModuleListener
    fun on(event: GuestCheckedOut) {
        generation.generateCheckout(RoomId(event.roomId), event.businessDate, TaskSource.CHECKOUT)
    }

    /** A troca de UH: a `CHECKOUT` na UH de origem, na data operacional da troca. */
    @ApplicationModuleListener
    fun on(event: RoomMoved) {
        generation.generateCheckout(RoomId(event.fromRoomId), event.businessDate, TaskSource.ROOM_MOVE)
    }
}
