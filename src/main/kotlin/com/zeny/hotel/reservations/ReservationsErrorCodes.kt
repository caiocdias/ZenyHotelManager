package com.zeny.hotel.reservations

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
}
