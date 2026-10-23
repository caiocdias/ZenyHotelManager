package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.RoomMoveCommand
import com.zeny.hotel.frontdesk.application.RoomMoveOutcome
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomMoveTariff
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

// --- Pedido ---------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "A troca de UH do hóspede hospedado: a UH nova (do mesmo tipo da reserva, vaga e pronta), o motivo e a " +
            "tarifa das noites restantes.",
)
data class RoomMoveRequest(
    @field:NotNull
    val roomId: UUID?,
    @field:Schema(
        description =
            "O motivo da troca: `GUEST_REQUEST`, `OUT_OF_ORDER`, `ROOM_ISSUE` ou `OPERATIONAL` (`INITIAL` é da " +
                "primeira alocação: 400 `ROOM_MOVE_INVALID`).",
    )
    @field:NotNull
    val reason: RoomAssignmentReason?,
    @field:Schema(
        description =
            "A tarifa das noites restantes: `KEEP` (padrão) mantém o preço congelado; `RECALCULATE` reprecifica as " +
                "noites da data operacional em diante pela tarifa atual (as passadas nunca mudam, RN-14).",
        nullable = true,
    )
    val tariff: RoomMoveTariff? = null,
) {
    fun toCommand(expectedVersion: Long) =
        RoomMoveCommand(
            roomId = RoomId(checkNotNull(roomId)),
            reason = checkNotNull(reason),
            tariff = tariff ?: RoomMoveTariff.KEEP,
            expectedVersion = expectedVersion,
        )
}

// --- Resposta -------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "A troca feita: a reserva na UH nova, a UH de origem Vaga e Suja, a de destino ocupada com o mesmo número de " +
            "hóspedes, a tarifa aplicada e a soma das noites restantes (da data operacional em diante) antes e " +
            "depois, com a diferença. Nenhum dado do hóspede.",
)
data class RoomMoveResponse(
    val reservation: StayReservationResponse,
    val fromRoom: StayRoomResponse,
    val toRoom: StayRoomResponse,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`KEEP` ou `RECALCULATE`. A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem " +
                "falhar.",
    )
    val tariff: RoomMoveTariff,
    @field:Schema(description = "A soma das noites restantes antes da troca (diária mais pacotes adicionais).")
    val remainingBefore: Money,
    @field:Schema(description = "A soma das noites restantes depois da troca.")
    val remainingAfter: Money,
    @field:Schema(
        description =
            "`remainingAfter` − `remainingBefore`: positiva se a estadia ficou mais cara, negativa se mais barata, " +
                "zero com `KEEP`. Nada é lançado na conta: as diárias são do fechamento do dia (M6).",
    )
    val difference: Money,
) {
    companion object {
        fun of(outcome: RoomMoveOutcome) =
            RoomMoveResponse(
                StayReservationResponse.of(outcome.reservation),
                StayRoomResponse.of(outcome.fromRoom, outcome.fromRoomNumber),
                StayRoomResponse.of(outcome.toRoom, outcome.toRoomNumber),
                outcome.tariff,
                outcome.remainingBefore,
                outcome.remainingAfter,
                outcome.difference,
            )
    }
}
