package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.FrontdeskCheckOutService
import com.zeny.hotel.frontdesk.application.FrontdeskRoomMoveService
import com.zeny.hotel.frontdesk.application.FrontdeskStayService
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

const val FRONTDESK_TAG = "frontdesk"
const val FRONTDESK_TAG_DESCRIPTION =
    "Recepção (RF-REC): check-in orquestrado numa transação, desfazer check-in, cotação de early check-in e late " +
        "check-out, walk-in (reserva, alocação e check-in numa transação) e check-out orquestrado numa transação " +
        "(cobranças da noite da chegada, late, saldo zero, cauções, contas e UH) e troca de UH do hospedado numa " +
        "transação (alocação, tarifa das noites restantes e as duas UHs)."

/**
 * A recepção sobre uma reserva (M5 E02): o check-in orquestrado (ARCHITECTURE §6.1), o desfazer check-in (sensível) e
 * a cotação de early/late (RN-11); desde a E04, o check-out orquestrado; desde a E05, a troca de UH do hospedado. Os
 * caminhos ficam sob `/reservations/{id}`, como ações da reserva.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = FRONTDESK_TAG, description = FRONTDESK_TAG_DESCRIPTION)
class StaysController(
    private val stays: FrontdeskStayService,
    private val checkOuts: FrontdeskCheckOutService,
    private val roomMoves: FrontdeskRoomMoveService,
) {
    @PostMapping("/{id}/check-in")
    @Idempotent
    @Operation(
        operationId = "checkInReservation",
        summary = "Fazer o check-in de uma reserva confirmada",
        description =
            "Numa transação só (tudo ou nada; ARCHITECTURE §6.1): confere os hóspedes, a UH e a conta, registra a " +
                "caução opcional, faz a reserva `IN_HOUSE` e ocupa a UH com o número de hóspedes (publica " +
                "`GuestCheckedIn`). Exige `frontdesk.stays.checkin` e `Idempotency-Key`; com `roomId` diferente da " +
                "UH alocada, aloca ou realoca antes e exige também `reservations.assignments.manage`. Na ordem: " +
                "inexistente 404 `RESERVATION_NOT_FOUND`; só uma confirmada (409 " +
                "`RESERVATION_TRANSITION_NOT_ALLOWED`, ação `CHECK_IN`; a pré-reserva confirma antes) já chegada " +
                "(422 " +
                "`CHECK_IN_BEFORE_ARRIVAL`); conta da reserva encerrada 409 `FOLIO_CLOSED`; hóspede inexistente 400 " +
                "`UNKNOWN_GUEST`, anonimizado 422 `GUEST_ANONYMIZED`; hóspede sem nacionalidade, data de nascimento " +
                "ou " +
                "documento aceito (CPF do brasileiro adulto; CPF ou RG do brasileiro menor; passaporte, RNM ou " +
                "identidade estrangeira do estrangeiro) 422 " +
                "`GUEST_DATA_INCOMPLETE`, com `incompleteGuests` (o hóspede, o que falta e os documentos aceitos, " +
                "sem número); anexo da comprovação do menor inexistente, de outra finalidade ou de outro perfil 422 " +
                "`MINOR_AUTHORIZATION_ATTACHMENT_INVALID`; sem UH alocada 409 `ROOM_NOT_ASSIGNED`; UH não pronta " +
                "(RN-05: vaga, fora de OOO e inspecionada, ou limpa sem inspeção pendente; conferida de novo com a " +
                "UH travada ao ocupar: dois check-ins simultâneos na mesma UH nunca passam os dois) 409 " +
                "`ROOM_NOT_READY`, com " +
                "a situação dela; as recusas do check-in da reserva (RN-24: menor sem comprovação 422 " +
                "`MINOR_AUTHORIZATION_REQUIRED`, sem data de nascimento 422 `GUEST_BIRTH_DATE_REQUIRED`, comprovação " +
                "inválida 422 `MINOR_AUTHORIZATION_INVALID`; UH desativada ou de outro tipo 422; formato 400 " +
                "`CHECK_IN_INVALID`); as recusas da caução (400 `PRE_AUTHORIZATION_INVALID` com o campo em " +
                "`preAuthorization.…`). Os hóspedes na lista de restrição vêm em `restrictionAlerts`, sem bloquear. " +
                "A caução não muda o saldo da conta. Nenhum pacote é lançado aqui. Com `earlyCheckInFee` (o valor " +
                "cotado em `GET /api/v1/reservations/{id}/early-late-quote`, RN-11), o early check-in é conferido " +
                "com a política vigente antes de qualquer escrita (409 `EARLY_LATE_FEE_CHANGED` com `currentFee`; " +
                "422 " +
                "`EARLY_LATE_NOT_OFFERED`) e lançado na conta da reserva no código `ROOM_CHARGE` (origem " +
                "`MANUAL`, descrição `Early check-in`) depois da caução, em `earlyCheckIn`; sem o campo, nada é " +
                "lançado.",
    )
    fun checkIn(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CheckInRequest,
    ): ResponseEntity<CheckInResponse> {
        val outcome = stays.checkIn(ReservationId(id), request.toCommand())
        return ResponseEntity
            .ok()
            .eTag(ETags.of(outcome.reservation.version))
            .body(CheckInResponse.of(outcome))
    }

    @PostMapping("/{id}/undo-check-in")
    @Operation(
        operationId = "undoCheckInReservation",
        summary = "Desfazer o check-in na mesma data operacional",
        description =
            "Volta a reserva a `CONFIRMED`, com a UH ainda alocada e sem os dados de check-in (a comprovação do " +
                "menor fica no histórico), e a UH a Vaga sem sujar (publica `GuestCheckInUndone`). Exige " +
                "`frontdesk.stays.checkin`; é ação sensível (`frontdesk.stays.undocheckin`): sem a permissão, 403 " +
                "`APPROVAL_REQUIRED` (repita com `X-Approval-Token`). Antes da aprovação: inexistente 404 " +
                "`RESERVATION_NOT_FOUND`; só uma hospedada (409 `RESERVATION_TRANSITION_NOT_ALLOWED`, ação " +
                "`UNDO_CHECK_IN`), na mesma data operacional do check-in (422 `UNDO_CHECK_IN_EXPIRED`). A caução " +
                "registrada e o early lançado ficam na conta: libere a caução e estorne o lançamento pelo `billing`.",
    )
    fun undoCheckIn(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<UndoCheckInResponse> {
        val outcome = stays.undoCheckIn(ReservationId(id), approvalToken)
        return ResponseEntity
            .ok()
            .eTag(ETags.of(outcome.reservation.version))
            .body(UndoCheckInResponse.of(outcome))
    }

    @PostMapping("/{id}/check-out")
    @Idempotent
    @Operation(
        operationId = "checkOutReservation",
        summary = "Fazer o check-out de uma reserva hospedada",
        description =
            "Numa transação só (tudo ou nada; ARCHITECTURE §6.1): lança as cobranças da noite da chegada (só no " +
                "day use ou no pernoite que sai no dia em que chegou: a hospedagem sem os pacotes inclusos e cada " +
                "pacote da noite, no código dele, origem `END_OF_DAY`, uma vez só por noite mesmo se o check-out " +
                "for repetido; os pacotes inclusos saem sem taxa de serviço, os adicionais seguem a regra do " +
                "código e devolvem a taxa em `serviceCharge`, que entra no saldo) e o late cotado " +
                "(`lateCheckOutFee`, no código da diária, origem `MANUAL`), confere o " +
                "saldo de todas as contas da reserva, libera as cauções não encerradas (inclusive as vencidas), " +
                "encerra as contas, faz a reserva `CHECKED_OUT` (o antecipado devolve as noites seguintes ao " +
                "inventário; a alocação é encerrada; publica `GuestCheckedOut`) e deixa a UH Vaga e Suja (RN-09). " +
                "Exige `frontdesk.stays.checkout` e `Idempotency-Key`; o corpo é opcional (`lateCheckOutFee` não " +
                "positivo ou com mais de duas casas: 400 `VALIDATION_FAILED`). Na ordem: inexistente 404 " +
                "`RESERVATION_NOT_FOUND`; só uma hospedada (409 `RESERVATION_TRANSITION_NOT_ALLOWED`, ação " +
                "`CHECK_OUT`); late não oferecido pela política vigente 422 `EARLY_LATE_NOT_OFFERED`; valor " +
                "diferente do vigente (a política mudou desde a cotação) 409 `EARLY_LATE_FEE_CHANGED`, com " +
                "`currentFee`; alguma conta da reserva com saldo diferente de zero (RN-06; uma devedora e outra " +
                "credora não se anulam) 409 `FOLIO_BALANCE_NOT_ZERO`, com `folios` (`folioId`, `number`, `status`, " +
                "`balance`) e `total`, já com as cobranças da noite da chegada e o late: pagar esse saldo libera o " +
                "check-out seguinte (com outra `Idempotency-Key`). Até o M10 o check-out só aceita saldo zero por " +
                "pagamento (sem contas a receber); a captura da caução é feita antes, pelo `billing`; a nota fiscal " +
                "é do M7.",
    )
    fun checkOut(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) request: CheckOutRequest?,
    ): ResponseEntity<CheckOutResponse> {
        val outcome = checkOuts.checkOut(ReservationId(id), (request ?: CheckOutRequest.NONE).toCommand())
        return ResponseEntity
            .ok()
            .eTag(ETags.of(outcome.reservation.version))
            .body(CheckOutResponse.of(outcome))
    }

    @PostMapping("/{id}/room-moves")
    @Idempotent
    @Operation(
        operationId = "moveReservationRoom",
        summary = "Trocar a UH de uma reserva hospedada",
        description =
            "Numa transação só (tudo ou nada; ARCHITECTURE §6.1): encerra a alocação atual na data operacional " +
                "(motivo de fim `ROOM_MOVED`) e abre outra na UH nova até a saída, com o motivo pedido; mantém " +
                "(`KEEP`, padrão) ou recalcula pela tarifa atual (`RECALCULATE`) as noites da data operacional em " +
                "diante (as passadas guardam o preço congelado, RN-14); deixa a UH de origem Vaga e Suja (RN-09) e " +
                "ocupa a de destino com o mesmo número de hóspedes; publica `RoomMoved`. Devolve a soma das noites " +
                "restantes antes e depois e a diferença (nada é lançado na conta). Exige `frontdesk.stays.move`, " +
                "`Idempotency-Key` e `If-Match` com o `ETag` da reserva (428 sem ele; 412 com outra versão, " +
                "conferida com a reserva travada). Na ordem: inexistente 404 `RESERVATION_NOT_FOUND`; só uma " +
                "hospedada (409 `RESERVATION_TRANSITION_NOT_ALLOWED`, ação `MOVE_ROOM`); UH inexistente 400 " +
                "`UNKNOWN_ROOM` (campo `roomId`); UH não pronta (RN-05: ativa, vaga, fora de OOO e inspecionada, " +
                "ou limpa sem inspeção pendente; conferida de novo com a UH travada ao ocupar: uma troca e um " +
                "check-in, ou duas trocas, simultâneos na mesma UH nunca passam os dois) 409 `ROOM_NOT_READY`, com " +
                "a situação dela; motivo `INITIAL` 400 `ROOM_MOVE_INVALID`; a versão (412 `PRECONDITION_FAILED`); " +
                "a estadia já terminada 422 `RESERVATION_STAY_ENDED`; o recálculo da reserva de um bloqueio 409 " +
                "`ROOM_BLOCK_RESERVATION_STAY_FIXED`; a mesma UH 422 `ROOM_MOVE_SAME_ROOM`; outro tipo de UH 422 " +
                "`ROOM_TYPE_MISMATCH` (a troca para outro tipo ainda não é aceita); OOO no período novo 422 " +
                "`ROOM_OUT_OF_ORDER`; outra reserva alocada na UH no período 409 `ROOM_ALREADY_ASSIGNED`. A tarefa " +
                "de limpeza da origem é da governança (M6).",
    )
    fun moveRoom(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RoomMoveRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<RoomMoveResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val outcome = roomMoves.move(ReservationId(id), request.toCommand(expected))
        return ResponseEntity
            .ok()
            .eTag(ETags.of(outcome.reservation.version))
            .body(RoomMoveResponse.of(outcome))
    }

    @GetMapping("/{id}/early-late-quote")
    @Operation(
        operationId = "getEarlyLateQuote",
        summary = "Cotar o early check-in e o late check-out de uma reserva",
        description =
            "Pela política vigente do hotel (RN-11: o preço é informado antes), no fuso do hotel: se cada um é " +
                "oferecido, o horário limite, o valor e a janela em instantes (early: da chegada no limite à chegada " +
                "no horário de check-in; late: da saída no horário de check-out à saída no limite). Não oferecido: " +
                "`offered` falso, sem horário nem valor. Exige `reservations.reservations.read`. Inexistente: 404 " +
                "`RESERVATION_NOT_FOUND`. A cobrança confere de novo o valor da política.",
    )
    fun earlyLateQuote(
        @PathVariable id: UUID,
    ): EarlyLateQuoteResponse = EarlyLateQuoteResponse.of(stays.earlyLateQuote(ReservationId(id)))
}
