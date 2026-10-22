package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.FrontdeskStayService
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
    "Recepção (RF-REC): check-in orquestrado numa transação, desfazer check-in e cotação de early check-in e late " +
        "check-out."

/**
 * A recepção sobre uma reserva (M5 E02): o check-in orquestrado (ARCHITECTURE §6.1), o desfazer check-in (sensível) e
 * a cotação de early/late (RN-11). Os caminhos ficam sob `/reservations/{id}`, como ações da reserva.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = FRONTDESK_TAG, description = FRONTDESK_TAG_DESCRIPTION)
class StaysController(
    private val stays: FrontdeskStayService,
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
                "A caução não muda o saldo da conta. Nenhum pacote nem o early check-in é lançado aqui.",
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
