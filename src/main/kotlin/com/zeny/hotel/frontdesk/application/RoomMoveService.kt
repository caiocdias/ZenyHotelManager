package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.domain.RoomMoveTariffs
import com.zeny.hotel.frontdesk.domain.RoomNotReadyException
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.reservations.RoomMoveTariff
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.StaleVersionException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A troca de UH pedida à recepção: a UH nova, o motivo (um código, nunca texto livre; RN-13), a tarifa das noites
 * restantes e a versão da reserva que o cliente leu (o `If-Match`, conferida com a reserva travada; D5).
 */
data class RoomMoveCommand(
    val roomId: RoomId,
    val reason: RoomAssignmentReason,
    val tariff: RoomMoveTariff,
    val expectedVersion: Long,
)

/**
 * O resultado da troca: a reserva na UH nova, a origem Vaga e Suja, o destino ocupado, a tarifa pedida e a soma das
 * noites restantes (da data operacional em diante) antes e depois, com a [difference] (depois − antes; zero no `KEEP`,
 * D8).
 */
data class RoomMoveOutcome(
    val reservation: ReservationSummary,
    val fromRoomNumber: String,
    val fromRoom: RoomStatus,
    val toRoomNumber: String,
    val toRoom: RoomStatus,
    val tariff: RoomMoveTariff,
    val remainingBefore: Money,
    val remainingAfter: Money,
    val difference: Money,
)

/**
 * A troca de UH do hospedado orquestrada pela recepção (M5 E05; RF-REC-04, RN-05, RN-09, RN-14, ARCHITECTURE §6.1)
 * **numa transação só**: qualquer recusa desfaz tudo (a alocação nova e a encerrada, as noites recalculadas, a origem
 * vaga, o destino ocupado, as entradas da trilha e os eventos do outbox).
 *
 * **Ordem** (e das travas): a permissão; a reserva lida sem trava (inexistente `404`; só uma hospedada,
 * `409 RESERVATION_TRANSITION_NOT_ALLOWED`, ação `MOVE_ROOM`); a UH nova existente (`400 UNKNOWN_ROOM`, campo
 * `roomId`) e pronta pela RN-05 sem trava (`RoomsApi.isReadyForCheckIn`, `409 ROOM_NOT_READY` com a situação dela; a
 * própria UH atual passa adiante, para o `reservations` recusar com `422 ROOM_MOVE_SAME_ROOM`); a origem é a UH
 * alocada lida, e o número de hóspedes dela (`RoomsApi.status`) é o que o destino recebe; a troca
 * (`ReservationsApi.moveRoom`, que **trava a reserva**, confere a versão do `If-Match` com ela travada, `412`, D5, e
 * as recusas dele: outro tipo `422 ROOM_TYPE_MISMATCH`, que ainda não é aceito, D1; OOO no período `422
 * ROOM_OUT_OF_ORDER`; outra reserva na UH `409 ROOM_ALREADY_ASSIGNED`; motivo `INITIAL` `400 ROOM_MOVE_INVALID`;
 * estadia já terminada `422 RESERVATION_STAY_ENDED`; a tarifa recalculada da reserva de um bloqueio `409
 * ROOM_BLOCK_RESERVATION_STAY_FIXED`); as duas UHs, que **travam cada UH**, em **ordem crescente de id** (sem deadlock
 * com uma troca no sentido contrário): a origem Vaga e Suja (`RoomsApi.markVacant` com a origem `CHECK_OUT`, RN-09, D4)
 * e o destino ocupado (`RoomsApi.markOccupiedIfReady` com a origem `CHECK_IN`, que confere a RN-05 de novo com a UH
 * travada: uma troca e um check-in, ou duas trocas, simultâneos na mesma UH nunca passam os dois, e o perdedor recebe
 * `409 ROOM_NOT_READY`, Review Focus 1); a diferença da tarifa ([RoomMoveTariffs], D8) sobre as noites antes e depois
 * da troca a partir da data operacional (RN-01, RN-14); e a hospedagem na trilha (`frontdesk.stay`, só ids, códigos,
 * datas e valores; RN-13). A tarefa de limpeza da origem é do M6, pelo `RoomMoved`.
 */
@Service
class FrontdeskRoomMoveService(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val steps: CheckInSteps,
) {
    @Audited(FrontdeskAudit.ROOM_MOVE)
    @Transactional
    fun move(
        id: ReservationId,
        command: RoomMoveCommand,
    ): RoomMoveOutcome {
        authorization.require(FrontdeskPermissions.STAYS_MOVE)
        val reservation = steps.reservation(id)
        StayRules.requireMoveRoom(reservation)
        val destination = room(command.roomId)
        val origin = checkNotNull(reservation.roomAssignment) { "Hospedada sem UH alocada" }.roomId
        val guestCount = guestCount(reservation, origin)
        if (destination.id != origin && !rooms.isReadyForCheckIn(destination.id)) {
            throw notReady(checkNotNull(rooms.status(destination.id)))
        }
        val businessDate = hotel.currentBusinessDate()

        val moved =
            reservations.moveRoom(id, RoomMove(destination.id, command.reason, command.tariff), command.expectedVersion)
        // A reserva travada é a lida (a origem e as noites de antes valem): outra alteração entre as duas é 412.
        if (moved.version != reservation.version + 1) {
            throw StaleVersionException(reservation.version, moved.version - 1)
        }
        val statuses =
            listOf(origin, destination.id).sortedBy { it.value.toString() }.associateWith { roomId ->
                if (roomId == origin) {
                    rooms.markVacant(origin, RoomStatusSource.CHECK_OUT)
                } else {
                    occupy(roomId, guestCount)
                }
            }
        val remainingBefore = RoomMoveTariffs.remaining(reservation.nightlyRates, businessDate)
        val remainingAfter = RoomMoveTariffs.remaining(moved.nightlyRates, businessDate)
        val difference = remainingAfter - remainingBefore
        audit.record(
            FrontdeskAudit.STAY,
            id.value,
            mapOf("status" to reservation.status.name, "roomId" to origin.value),
            mapOf(
                "status" to moved.status.name,
                "roomId" to destination.id.value,
                "fromRoomId" to origin.value,
                "toRoomId" to destination.id.value,
                "reason" to command.reason.name,
                "tariff" to command.tariff.name,
                "guestCount" to guestCount,
                "difference" to difference.amount.toPlainString(),
                "businessDate" to businessDate,
            ),
        )
        return RoomMoveOutcome(
            reservation = moved,
            fromRoomNumber = steps.roomNumber(origin),
            fromRoom = statuses.getValue(origin),
            toRoomNumber = destination.number,
            toRoom = statuses.getValue(destination.id),
            tariff = command.tariff,
            remainingBefore = remainingBefore,
            remainingAfter = remainingAfter,
            difference = difference,
        )
    }

    /** A UH nova; inexistente `400 UNKNOWN_ROOM`, no campo `roomId`. */
    private fun room(roomId: RoomId): RoomSummary =
        rooms.room(roomId)
            ?: throw InvalidInputException(
                FrontdeskErrorCodes.UNKNOWN_ROOM,
                "A UH informada não existe.",
                listOf(FieldViolation("roomId", FrontdeskErrorCodes.UNKNOWN_ROOM.value, "A UH não existe.")),
            )

    /**
     * O número de hóspedes que o destino recebe: o da origem ocupada, lido antes de qualquer escrita. Sem ele (a origem
     * foi deixada vaga à mão no meio da estadia), a ocupação da reserva (adultos e crianças).
     */
    private fun guestCount(
        reservation: ReservationSummary,
        origin: RoomId,
    ): Int = rooms.status(origin)?.guestCount ?: (reservation.adults + reservation.childAges.size)

    /**
     * Ocupa o destino pela `RoomsApi.markOccupiedIfReady`, que confere a RN-05 **com a UH travada**: ocupado por um
     * check-in ou por outra troca confirmados depois da conferência sem trava (ou sujo, ou com OOO aberto), recusa com
     * `409 ROOM_NOT_READY` e a transação inteira é desfeita (a alocação nova, as noites e a origem vaga).
     */
    private fun occupy(
        roomId: RoomId,
        guestCount: Int,
    ): RoomStatus {
        val occupation = rooms.markOccupiedIfReady(roomId, guestCount, RoomStatusSource.CHECK_IN)
        if (!occupation.ready) throw notReady(occupation.status)
        return occupation.status
    }

    private fun notReady(status: RoomStatus) =
        RoomNotReadyException(
            status,
            "A UH escolhida não está pronta para receber o hóspede (precisa estar vaga, fora de OOO e inspecionada, " +
                "ou limpa sem inspeção pendente).",
        )
}
