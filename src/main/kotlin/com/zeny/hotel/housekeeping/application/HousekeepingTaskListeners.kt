package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.rooms.RoomId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * Escuta os eventos que pedem limpeza (RF-GOV-02, M6 E02) e delega à [HousekeepingTaskGeneration], que grava as tarefas
 * com o ator sistema: a saída e a troca de UH (a tarefa de saída) e a virada da data operacional (as tarefas do dia).
 * Assíncrono, em transação própria e reentregue pelo outbox se falhar: idempotente pela unicidade (local, data, tipo)
 * da tarefa. Cada listener tem a sua publicação no outbox, e a falha de um não impede outro (como as rotinas de
 * checklist, que também escutam a virada da data).
 */
@Component
class HousekeepingTaskListeners(
    private val generation: HousekeepingTaskGeneration,
    private val hotel: HotelApi,
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

    /**
     * A virada da data operacional: as tarefas do dia da data nova. Um avanço atrasado, com a data nova anterior à
     * operacional (o evento reentregue depois de outro fechamento), é ignorado com log `WARN` (D10-E02).
     */
    @ApplicationModuleListener
    fun on(event: BusinessDateAdvanced) {
        val businessDate = hotel.currentBusinessDate()
        if (event.current.isBefore(businessDate)) {
            log.warn(
                "Avanço da data para {} ignorado pela governança: a data operacional já é {}",
                event.current,
                businessDate,
            )
            return
        }
        val result = generation.generateForDate(event.current)
        log.info(
            "Tarefas de governança de {}: {} de faxinas programadas, {} permanências e {} pré-chegadas",
            event.current,
            result.scheduled,
            result.stayovers,
            result.preArrivals,
        )
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HousekeepingTaskListeners::class.java)
    }
}
