package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do `reservations` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido. A
 * recusa sobre um tipo de UH citado no pedido usa o código do `rooms`.
 */
object ReservationsErrorCodes {
    /**
     * 409: não há unidades disponíveis do tipo de UH em alguma data pedida (disponível = total − vendidas −
     * bloqueadas − OOO + limite de overbooking). O problema traz os tipos em `roomTypeIds` e as datas em `dates`.
     */
    val ROOM_TYPE_UNAVAILABLE = ErrorCode("ROOM_TYPE_UNAVAILABLE")

    /**
     * 422: alguma data pedida está fora do inventário mantido (antes dele ou além do horizonte de 730 dias da data
     * operacional, H3). O problema traz as datas em `dates`.
     */
    val INVENTORY_DATE_OUT_OF_HORIZON = ErrorCode("INVENTORY_DATE_OUT_OF_HORIZON")

    /** 400: período fim antes do início, ou nenhuma data nos dias da semana pedidos (campo `to` ou `weekdays`). */
    val INVENTORY_PERIOD_INVALID = ErrorCode("INVENTORY_PERIOD_INVALID")

    /** 400: período de mais de 366 dias (campo `to`). */
    val INVENTORY_PERIOD_TOO_LONG = ErrorCode("INVENTORY_PERIOD_TOO_LONG")

    /** 422: o período começa antes da data operacional. */
    val INVENTORY_PERIOD_IN_PAST = ErrorCode("INVENTORY_PERIOD_IN_PAST")

    /** 400: limite de overbooking fora de 0 a 50, ou lista de tipos de UH vazia, longa ou repetida (com o campo). */
    val OVERBOOKING_LIMIT_INVALID = ErrorCode("OVERBOOKING_LIMIT_INVALID")

    /** 400: tipo de UH inexistente (o código do `rooms`). */
    val UNKNOWN_ROOM_TYPE: ErrorCode = RoomsErrorCodes.UNKNOWN_ROOM_TYPE

    // --- Disponibilidade e cotação (M4 E06) -----------------------------------------------------------------------

    /**
     * 400: consulta de disponibilidade ou cotação com mais de 50 tipos de UH ou planos, ou com repetição (campo
     * `roomTypeIds` ou `ratePlanIds`), mais de 40 adultos (`adults`) ou mais de 20 crianças (`childAges`).
     */
    val AVAILABILITY_QUERY_INVALID = ErrorCode("AVAILABILITY_QUERY_INVALID")

    /** 400: plano tarifário inexistente (o código do `rates`; campo `ratePlanId` ou `ratePlanIds`). */
    val UNKNOWN_RATE_PLAN: ErrorCode = RatesErrorCodes.UNKNOWN_RATE_PLAN

    /**
     * 400: estadia fora do formato na cotação (o código do `rates`): saída que não é depois da chegada ou mais de 365
     * noites (campo `departure`), nenhum adulto (`adults`), idade de criança fora de 0 à idade limite do hotel
     * (`childAges[i]`).
     */
    val STAY_INVALID: ErrorCode = RatesErrorCodes.STAY_INVALID

    // --- Reservas (M4 E07) ------------------------------------------------------------------------------------------

    /**
     * 400: reserva agrupadora ou reserva fora do formato, com o campo: nenhuma reserva ou mais de 30 (`reservations`),
     * id externo fora de 1 a 60 caracteres (`externalId`), mais de 40 adultos ou 20 crianças, pedidos especiais acima
     * de 1000 caracteres, hóspedes repetidos, sem principal ou com mais de um, ou mais hóspedes que a ocupação
     * (`reservations[i].guests`).
     */
    val BOOKING_INVALID = ErrorCode("BOOKING_INVALID")

    /**
     * 400: diária editada fora do formato, com o campo (`reservations[i].nightlyRates[j]…`): data que não é noite da
     * estadia (no day use, a própria data), data repetida ou valor fora de 0,00 a 1.000.000,00.
     */
    val NIGHTLY_RATE_INVALID = ErrorCode("NIGHTLY_RATE_INVALID")

    /**
     * 422: a diária editada de uma noite fica abaixo dos pacotes inclusos nela (a hospedagem ficaria negativa); o
     * problema traz a data em `dates`.
     */
    val NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES = ErrorCode("NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES")

    /** 400: hóspede inexistente no `guests` (campo `holderGuestId` ou `reservations[i].guests[j].guestId`). */
    val UNKNOWN_GUEST = ErrorCode("UNKNOWN_GUEST")

    /** 422: o perfil do hóspede foi anonimizado (o código do `guests`) e não entra numa reserva nova. */
    val GUEST_ANONYMIZED: ErrorCode = GuestsErrorCodes.GUEST_ANONYMIZED

    /**
     * 409: um hóspede citado foi mesclado noutro perfil enquanto a reserva era criada; repita o pedido (a reserva usa
     * o sobrevivente).
     */
    val GUEST_PROFILE_CHANGED = ErrorCode("GUEST_PROFILE_CHANGED")

    /** 404: reserva agrupadora inexistente. */
    val BOOKING_NOT_FOUND = ErrorCode("BOOKING_NOT_FOUND")

    /** 404: reserva inexistente. */
    val RESERVATION_NOT_FOUND = ErrorCode("RESERVATION_NOT_FOUND")

    // --- Alteração, cancelamento e reativação (M4 E08) ----------------------------------------------------------

    /**
     * 409: a reserva não pode fazer essa transição na situação em que está (SPEC §8.4): alterar ou cancelar só uma
     * pré-reserva ou uma confirmada; reativar só uma cancelada ou no-show. O problema traz a situação em
     * `reservationStatus` e a ação recusada em `action` (`CHANGE`, `CANCEL`, `REACTIVATE`).
     */
    val RESERVATION_TRANSITION_NOT_ALLOWED = ErrorCode("RESERVATION_TRANSITION_NOT_ALLOWED")

    /**
     * 400: alteração fora do formato, com o campo: mais de 40 adultos (`adults`) ou 20 crianças (`childAges`), pedidos
     * especiais fora de 1 a 1000 caracteres (`specialRequests`) ou menos ocupação que os hóspedes da estadia
     * (`adults`). As diárias editadas fora do formato, ou numa noite que a alteração não recalcula, são
     * `NIGHTLY_RATE_INVALID` (campo `nightlyRates[i]…`).
     */
    val RESERVATION_CHANGE_INVALID = ErrorCode("RESERVATION_CHANGE_INVALID")

    /**
     * 400: cancelamento fora do formato: observação fora de 1 a 500 caracteres (campo `notes`) ou o motivo `EXPIRED`,
     * que é só do job de expiração (campo `reason`; E09).
     */
    val RESERVATION_CANCELLATION_INVALID = ErrorCode("RESERVATION_CANCELLATION_INVALID")

    /**
     * 422: a reserva cancelada ou no-show já terminou (nenhuma noite a partir da data operacional; no day use, a data
     * já passou) e não é reativada.
     */
    val RESERVATION_STAY_ENDED = ErrorCode("RESERVATION_STAY_ENDED")

    // --- Pré-reserva, garantia e expiração (M4 E09) -----------------------------------------------------------------

    /**
     * 422: a garantia informada na confirmação não é a que a política de garantia copiada na reserva pede (a política
     * de depósito só aceita `DEPOSIT`; a de cartão, só `CARD`). O problema traz o tipo da política em `guaranteeType` e
     * a garantia informada em `method`.
     */
    val GUARANTEE_METHOD_NOT_ACCEPTED = ErrorCode("GUARANTEE_METHOD_NOT_ACCEPTED")

    /**
     * 422: o total líquido de depósitos da conta da reserva (os recebimentos marcados como depósito menos os reembolsos
     * deles, pela `BillingApi`) não cobre o depósito que a política copiada exige. O problema traz `requiredDeposit` e
     * `depositReceived` (valores em texto decimal).
     */
    val DEPOSIT_INSUFFICIENT = ErrorCode("DEPOSIT_INSUFFICIENT")

    /**
     * 422: a validade da pré-reserva já passou (o instante da criação mais o prazo de expiração do hotel): ela não é
     * mais confirmada; o job de expiração a cancela.
     */
    val PROVISIONAL_RESERVATION_EXPIRED = ErrorCode("PROVISIONAL_RESERVATION_EXPIRED")

    /** 400: intervalo com o fim antes do início (campo `to`). */
    val RESERVATION_PERIOD_INVALID = ErrorCode("RESERVATION_PERIOD_INVALID")

    // --- Alocação de UH (M4 E10) -------------------------------------------------------------------------------------

    /**
     * 409: a reserva não aceita alocação, realocação ou desalocação na situação em que está: só uma pré-reserva ou uma
     * confirmada (a troca de UH do hóspede em casa é da recepção, M5). O problema traz a situação em
     * `reservationStatus`.
     */
    val ROOM_ASSIGNMENT_NOT_ALLOWED = ErrorCode("ROOM_ASSIGNMENT_NOT_ALLOWED")

    /** 400: a UH pedida não existe (campo `roomId`). */
    val UNKNOWN_ROOM = ErrorCode("UNKNOWN_ROOM")

    /** 422: a UH pedida está desativada. */
    val ROOM_INACTIVE = ErrorCode("ROOM_INACTIVE")

    /**
     * 422: a UH pedida é de outro tipo que o da reserva. Para usar uma UH de outro tipo, altere antes o tipo da
     * reserva (`PUT /api/v1/reservations/{id}`), que move o inventário e reprecifica.
     */
    val ROOM_TYPE_MISMATCH = ErrorCode("ROOM_TYPE_MISMATCH")

    /** 422: a UH pedida está em OOO em alguma noite da estadia (RN-07); o problema traz as datas em `dates`. */
    val ROOM_OUT_OF_ORDER = ErrorCode("ROOM_OUT_OF_ORDER")

    /**
     * 409: a UH já está alocada a outra reserva em parte do período (RN-04). O banco garante a regra
     * (`room_assignment_overlap_ex`), e a violação dele vira este mesmo código.
     */
    val ROOM_ALREADY_ASSIGNED = ErrorCode("ROOM_ALREADY_ASSIGNED")

    /**
     * 409: a alocação está marcada "não mover": a UH não é trocada nem solta (nem pela alteração da reserva) sem tirar
     * a marca antes (`POST /api/v1/reservations/{id}/do-not-move` com `false`).
     */
    val ROOM_ASSIGNMENT_LOCKED = ErrorCode("ROOM_ASSIGNMENT_LOCKED")

    /** 409: a reserva não tem UH alocada (desalocar ou marcar "não mover"). */
    val ROOM_NOT_ASSIGNED = ErrorCode("ROOM_NOT_ASSIGNED")

    /** 400: a consulta de sugestões fora do formato: mais de 20 características ou repetidas (campo `featureIds`). */
    val ROOM_SUGGESTION_INVALID = ErrorCode("ROOM_SUGGESTION_INVALID")

    // --- Grupos e bloqueios (M4 E11) -----------------------------------------------------------------------------

    /** 404: bloqueio de grupo inexistente. */
    val ROOM_BLOCK_NOT_FOUND = ErrorCode("ROOM_BLOCK_NOT_FOUND")

    /**
     * 400: bloqueio fora do formato, com o campo: nome fora de 1 a 120 caracteres (`name`), titular que não é
     * exatamente um hóspede ou uma empresa (`holderGuestId`), nenhuma unidade na criação ou mais de 50 faixas
     * (`allocations`), faixa com o fim antes do início ou de mais de 366 noites (`allocations[i].to`), quantidade fora
     * de 1 a 50 (`allocations[i].quantity`), tarifa fora de 0,00 a 1.000.000,00 (`allocations[i].rate`) ou duas
     * faixas do mesmo tipo de UH na mesma noite (`allocations[i]`).
     */
    val ROOM_BLOCK_INVALID = ErrorCode("ROOM_BLOCK_INVALID")

    /** 422: unidades do bloqueio numa noite antes da data operacional (o problema traz as datas em `dates`). */
    val ROOM_BLOCK_PERIOD_IN_PAST = ErrorCode("ROOM_BLOCK_PERIOD_IN_PAST")

    /** 422: a data de corte nova (na criação ou trocada na alteração) é anterior à data operacional. */
    val ROOM_BLOCK_CUTOFF_IN_PAST = ErrorCode("ROOM_BLOCK_CUTOFF_IN_PAST")

    /** 409: o bloqueio já foi liberado (`releaseUnused`) e não aceita pick-up nem alteração. */
    val ROOM_BLOCK_RELEASED = ErrorCode("ROOM_BLOCK_RELEASED")

    /** 422: a data de corte do bloqueio já passou (a data operacional é posterior a ela): não há mais pick-up. */
    val ROOM_BLOCK_CUTOFF_PASSED = ErrorCode("ROOM_BLOCK_CUTOFF_PASSED")

    /**
     * 409: a liberação do não utilizado (`ReservationsApi.releaseUnused`) foi pedida antes da data de corte (a data
     * operacional ainda é anterior a ela). O problema traz `cutoffDate`.
     */
    val ROOM_BLOCK_CUTOFF_NOT_REACHED = ErrorCode("ROOM_BLOCK_CUTOFF_NOT_REACHED")

    /**
     * 409: o bloqueio não tem mais unidades do tipo de UH em alguma noite pedida (todas já viraram reservas). O
     * problema traz os tipos em `roomTypeIds` e as datas em `dates`.
     */
    val ROOM_BLOCK_UNAVAILABLE = ErrorCode("ROOM_BLOCK_UNAVAILABLE")

    /**
     * 422: alguma noite da reserva do pick-up está fora do bloqueio (o bloqueio não tem unidades do tipo de UH nela);
     * o problema traz os tipos em `roomTypeIds` e as datas em `dates`.
     */
    val ROOM_BLOCK_DATES_NOT_COVERED = ErrorCode("ROOM_BLOCK_DATES_NOT_COVERED")

    /**
     * 422: a alteração deixa alguma noite com menos unidades que as já convertidas em reservas (o problema traz os
     * tipos em `roomTypeIds` e as datas em `dates`); cancele as reservas antes.
     */
    val ROOM_BLOCK_BELOW_PICK_UP = ErrorCode("ROOM_BLOCK_BELOW_PICK_UP")

    /**
     * 409: a reserva veio do pick-up de um bloqueio e não troca o tipo de UH, o plano nem as datas (a diária é a tarifa
     * do grupo daquelas noites); a ocupação, o segmento e os pedidos especiais mudam. Para outra estadia, cancele (a
     * unidade volta ao bloqueio) e faça outro pick-up.
     */
    val ROOM_BLOCK_RESERVATION_STAY_FIXED = ErrorCode("ROOM_BLOCK_RESERVATION_STAY_FIXED")

    /** 400: empresa titular inexistente no `guests` (o código do `guests`; campo `holderCompanyId`). */
    val UNKNOWN_COMPANY: ErrorCode = GuestsErrorCodes.UNKNOWN_COMPANY

    /** 422: a empresa titular está desativada (o código do `guests`). */
    val COMPANY_INACTIVE: ErrorCode = GuestsErrorCodes.COMPANY_INACTIVE

    /** 422: o plano tarifário do bloqueio está desativado (o código do `rates`). */
    val RATE_PLAN_INACTIVE: ErrorCode = RatesErrorCodes.RATE_PLAN_INACTIVE

    /**
     * 422: o tipo de UH de uma faixa do bloqueio não está no plano tarifário dele (o código do `rates`); o problema
     * traz o campo em `field` (`allocations[i].roomTypeId`) e o tipo em `roomTypeIds`.
     */
    val ROOM_TYPE_NOT_IN_PLAN: ErrorCode = RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN

    /** 422: o tipo de UH de uma faixa nova do bloqueio está desativado (o código do `rooms`). */
    val ROOM_TYPE_INACTIVE: ErrorCode = RoomsErrorCodes.ROOM_TYPE_INACTIVE

    // 422: a estadia não é vendável no canal da origem (`RatesApi.isSellable`). O `code` é o do primeiro motivo do
    // `rates` (`RATE_PLAN_INACTIVE`, `SALES_CHANNEL_NOT_ALLOWED`, `ROOM_TYPE_NOT_IN_PLAN`, `ROOM_TYPE_INACTIVE`,
    // `OCCUPANCY_EXCEEDS_ROOM_TYPE`, `ARRIVAL_BEFORE_BUSINESS_DATE`, `MIN_ADVANCE_NOT_MET`, `MAX_ADVANCE_EXCEEDED`,
    // `CLOSED_TO_ARRIVAL`, `CLOSED_TO_DEPARTURE`, `MIN_STAY_NOT_MET`, `MAX_STAY_EXCEEDED`, `STAY_DATE_CLOSED`,
    // `RATE_NOT_PRICED`, `OCCUPANCY_NOT_PRICED`, `CHILD_AGE_NOT_PRICED`, `INCLUDED_PACKAGES_EXCEED_RATE`), e o problema
    // traz a reserva em `reservationIndex` e todos os motivos em `refusals` (`code`, `message`, `dates`). Na alteração
    // (E08), o mesmo, sem `reservationIndex` e só com os motivos que dizem respeito ao que mudou.
}
