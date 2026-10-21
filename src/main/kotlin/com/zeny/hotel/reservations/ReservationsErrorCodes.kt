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

    // 422: a estadia não é vendável no canal da origem (`RatesApi.isSellable`). O `code` é o do primeiro motivo do
    // `rates` (`RATE_PLAN_INACTIVE`, `SALES_CHANNEL_NOT_ALLOWED`, `ROOM_TYPE_NOT_IN_PLAN`, `ROOM_TYPE_INACTIVE`,
    // `OCCUPANCY_EXCEEDS_ROOM_TYPE`, `ARRIVAL_BEFORE_BUSINESS_DATE`, `MIN_ADVANCE_NOT_MET`, `MAX_ADVANCE_EXCEEDED`,
    // `CLOSED_TO_ARRIVAL`, `CLOSED_TO_DEPARTURE`, `MIN_STAY_NOT_MET`, `MAX_STAY_EXCEEDED`, `STAY_DATE_CLOSED`,
    // `RATE_NOT_PRICED`, `OCCUPANCY_NOT_PRICED`, `CHILD_AGE_NOT_PRICED`, `INCLUDED_PACKAGES_EXCEED_RATE`), e o problema
    // traz a reserva em `reservationIndex` e todos os motivos em `refusals` (`code`, `message`, `dates`).
}
