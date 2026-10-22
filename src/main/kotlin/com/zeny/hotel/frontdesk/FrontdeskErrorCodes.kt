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

    // --- Reexportados -----------------------------------------------------------------------------------------------

    val RESERVATION_NOT_FOUND: ErrorCode = ReservationsErrorCodes.RESERVATION_NOT_FOUND
    val RESERVATION_TRANSITION_NOT_ALLOWED: ErrorCode = ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED
    val CHECK_IN_BEFORE_ARRIVAL: ErrorCode = ReservationsErrorCodes.CHECK_IN_BEFORE_ARRIVAL
    val ROOM_NOT_ASSIGNED: ErrorCode = ReservationsErrorCodes.ROOM_NOT_ASSIGNED
    val UNDO_CHECK_IN_EXPIRED: ErrorCode = ReservationsErrorCodes.UNDO_CHECK_IN_EXPIRED
    val UNKNOWN_GUEST: ErrorCode = ReservationsErrorCodes.UNKNOWN_GUEST
    val UNKNOWN_ROOM: ErrorCode = ReservationsErrorCodes.UNKNOWN_ROOM
    val GUEST_ANONYMIZED: ErrorCode = ReservationsErrorCodes.GUEST_ANONYMIZED
    val FOLIO_CLOSED: ErrorCode = BillingErrorCodes.FOLIO_CLOSED
    val FOLIO_NOT_FOUND: ErrorCode = BillingErrorCodes.FOLIO_NOT_FOUND
}
