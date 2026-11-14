package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import org.springframework.stereotype.Component

/**
 * A sétima etapa do fechamento (RF-FEC-01, RN-09): marca como Suja cada UH ocupada que ainda não está, para a
 * governança limpar a de hóspede em estada. Os itens são as UHs ativas Ocupadas e não Sujas lidas no início; cada uma
 * roda na transação dela ([EndOfDayItems.each]) e a chamada [RoomsApi.markDirty] (origem
 * [RoomStatusSource.END_OF_DAY]) trava a UH e reconfere o status, então uma UH vaga e suja entre a leitura e o item
 * (check-out) não dá erro nem pendência. A recusa de uma UH vira a pendência [ISSUE_KIND] com o id dela.
 *
 * Idempotente pela data: a UH já Suja deixa de ser item, e marcar de novo não muda nada. O fechamento não chama a
 * governança: ela escuta `BusinessDateAdvanced`.
 */
@Component
class OccupiedRoomsDirtyStep(
    private val rooms: RoomsApi,
    private val items: EndOfDayItems,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val toMark =
            rooms
                .activeRooms()
                .map { it.id }
                .filter { id ->
                    val status = rooms.status(id)
                    status != null &&
                        status.occupancy == OccupancyStatus.OCCUPIED &&
                        status.housekeepingStatus != HousekeepingStatus.DIRTY
                }
        if (toMark.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        items.each(context, ISSUE_KIND, toMark, { it.value.toString() }) {
            rooms.markDirty(it, RoomStatusSource.END_OF_DAY)
        }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "occupied-rooms-dirty"

        /** A posição: depois das pré-autorizações e antes do avanço da data. */
        const val POSITION = 700

        /** A pendência de uma UH que não pôde ser marcada Suja; a referência é o id da UH. */
        const val ISSUE_KIND = "ROOM_DIRTY_FAILED"
    }
}
