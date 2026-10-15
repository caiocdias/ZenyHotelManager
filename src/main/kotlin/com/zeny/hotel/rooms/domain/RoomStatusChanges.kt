package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.RoomStatusField

/**
 * Um campo do status da UH que mudou numa operação (RF-UH-06): de [from] para [to], como texto (o nome do
 * enum, o número ou `true`/`false`; nulo = sem valor, como o número de hóspedes de uma UH vaga). Quem,
 * quando e a origem são da operação inteira, e o caso de uso os acrescenta ao gravar o histórico.
 */
data class RoomStatusFieldChange(
    val field: RoomStatusField,
    val from: String?,
    val to: String?,
)

/**
 * RN-05: a UH está ativa, Vaga, fora de OOO e Inspecionada, ou Limpa sem inspeção pendente; OOS não
 * impede ([outOfOrder] é a sobreposição em vigor na data operacional). Usada pela entidade e pelas
 * leituras do painel, que não carregam a entidade.
 */
object CheckInReadiness {
    fun isReady(
        active: Boolean,
        occupancy: OccupancyStatus,
        housekeepingStatus: HousekeepingStatus,
        inspectionPending: Boolean,
        outOfOrder: OutOfOrderKind?,
    ): Boolean =
        active &&
            occupancy == OccupancyStatus.VACANT &&
            outOfOrder != OutOfOrderKind.OUT_OF_ORDER &&
            (
                housekeepingStatus == HousekeepingStatus.INSPECTED ||
                    (housekeepingStatus == HousekeepingStatus.CLEAN && !inspectionPending)
            )
}
