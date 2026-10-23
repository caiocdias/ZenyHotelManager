package com.zeny.hotel.frontdesk

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro do `frontdesk` (ARCHITECTURE §7). Um código publicado não muda de nome nem de sentido. Os de outros
 * módulos que a recepção devolve por conta própria (antes de chamar o módulo dono) são os mesmos, reexportados aqui.
 */
object FrontdeskErrorCodes {
    // --- Check-in (E02) ---------------------------------------------------------------------------------------------

    /** 400: pedido de check-in incoerente (número de hóspedes abaixo da lista), com o campo. */
    val CHECK_IN_REQUEST_INVALID = ErrorCode("CHECK_IN_REQUEST_INVALID")

    /**
     * 422: hóspede sem os dados mínimos do check-in (H11): nacionalidade, data de nascimento ou um documento aceito
     * (CPF do brasileiro adulto; CPF ou RG do brasileiro menor; passaporte, RNM ou identidade estrangeira do
     * estrangeiro). O problema traz `incompleteGuests`
     * com o hóspede e o que falta, **sem** número de documento.
     */
    val GUEST_DATA_INCOMPLETE = ErrorCode("GUEST_DATA_INCOMPLETE")

    /**
     * 422: o anexo da comprovação do menor não existe, foi apagado, é de outra finalidade (só
     * `guests.minor-authorization-photo` e `guests.document-pdf`) ou não está vinculado ao perfil do menor; com
     * `guestId` e o campo.
     */
    val MINOR_AUTHORIZATION_ATTACHMENT_INVALID = ErrorCode("MINOR_AUTHORIZATION_ATTACHMENT_INVALID")

    /**
     * 409: a UH alocada não está pronta para o check-in (RN-05: ativa, Vaga, fora de OOO na data operacional e
     * Inspecionada, ou Limpa sem inspeção pendente), com a situação dela no problema.
     */
    val ROOM_NOT_READY = ErrorCode("ROOM_NOT_READY")

    // --- Check-out (E04) --------------------------------------------------------------------------------------------

    /**
     * 422: o pedido traz o valor do late check-out, mas a política vigente do hotel não oferece late check-out (D4).
     * Nada é lançado.
     */
    val EARLY_LATE_NOT_OFFERED = ErrorCode("EARLY_LATE_NOT_OFFERED")

    /**
     * 409: o valor do late check-out do pedido difere do da política vigente (ela mudou entre a cotação e o
     * check-out); o problema traz `currentFee` (texto, duas casas). Nada é lançado.
     */
    val EARLY_LATE_FEE_CHANGED = ErrorCode("EARLY_LATE_FEE_CHANGED")

    /**
     * 409: a reserva mudou durante a operação (outra recepção alterou, trocou a UH ou fez o check-out); leia de novo e
     * repita. O check-out não tem `If-Match`: confere a versão que leu com a reserva travada (M5 E05) e, diferente,
     * desfaz tudo.
     */
    val RESERVATION_CHANGED = ErrorCode("RESERVATION_CHANGED")

    // --- Recados (E06) ----------------------------------------------------------------------------------------------

    /**
     * 400: recado incoerente, com o campo e a regra, **nunca** o texto recebido (RN-13): texto vazio ou acima de 500
     * caracteres (`text`), vencimento anterior à data operacional (`dueOn`), reserva e UH juntas ou nenhuma delas
     * (`reservationId`).
     */
    val TRACE_INVALID = ErrorCode("TRACE_INVALID")

    /** 404: recado inexistente. */
    val TRACE_NOT_FOUND = ErrorCode("TRACE_NOT_FOUND")

    /** 409: o recado já foi resolvido; não se resolve nem se altera de novo. */
    val TRACE_ALREADY_RESOLVED = ErrorCode("TRACE_ALREADY_RESOLVED")

    /** 400: o setor do recado não existe (campo `departmentId`). */
    val UNKNOWN_DEPARTMENT = ErrorCode("UNKNOWN_DEPARTMENT")

    /** 400: o responsável não é um usuário vinculado a um colaborador (campo `assigneeUserId`). */
    val UNKNOWN_ASSIGNEE = ErrorCode("UNKNOWN_ASSIGNEE")

    /** 400: a reserva do recado não existe (campo `reservationId`). */
    val UNKNOWN_RESERVATION = ErrorCode("UNKNOWN_RESERVATION")

    // --- Reexportados -----------------------------------------------------------------------------------------------

    val RESERVATION_NOT_FOUND: ErrorCode = ReservationsErrorCodes.RESERVATION_NOT_FOUND
    val RESERVATION_TRANSITION_NOT_ALLOWED: ErrorCode = ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED
    val CHECK_IN_BEFORE_ARRIVAL: ErrorCode = ReservationsErrorCodes.CHECK_IN_BEFORE_ARRIVAL
    val ROOM_NOT_ASSIGNED: ErrorCode = ReservationsErrorCodes.ROOM_NOT_ASSIGNED
    val UNDO_CHECK_IN_EXPIRED: ErrorCode = ReservationsErrorCodes.UNDO_CHECK_IN_EXPIRED
    val UNKNOWN_GUEST: ErrorCode = ReservationsErrorCodes.UNKNOWN_GUEST
    val UNKNOWN_ROOM: ErrorCode = ReservationsErrorCodes.UNKNOWN_ROOM
    val ROOM_INACTIVE: ErrorCode = ReservationsErrorCodes.ROOM_INACTIVE
    val GUEST_ANONYMIZED: ErrorCode = ReservationsErrorCodes.GUEST_ANONYMIZED

    /**
     * 409: alguma conta da reserva ficou com saldo diferente de zero no check-out (RN-06), depois das cobranças
     * pendentes e do late; o problema traz `folios` (`folioId`, `number`, `status`, `balance`) e `total`.
     */
    val FOLIO_BALANCE_NOT_ZERO: ErrorCode = BillingErrorCodes.FOLIO_BALANCE_NOT_ZERO
    val FOLIO_CLOSED: ErrorCode = BillingErrorCodes.FOLIO_CLOSED
    val FOLIO_NOT_FOUND: ErrorCode = BillingErrorCodes.FOLIO_NOT_FOUND
}
