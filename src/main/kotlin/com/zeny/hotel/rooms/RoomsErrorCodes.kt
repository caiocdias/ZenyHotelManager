package com.zeny.hotel.rooms

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do módulo `rooms` (ARCHITECTURE §7). */
object RoomsErrorCodes {
    /** 404: tipo de UH inexistente. */
    val ROOM_TYPE_NOT_FOUND = ErrorCode("ROOM_TYPE_NOT_FOUND")

    /** 404: característica inexistente. */
    val ROOM_FEATURE_NOT_FOUND = ErrorCode("ROOM_FEATURE_NOT_FOUND")

    /** 404: UH inexistente. */
    val ROOM_NOT_FOUND = ErrorCode("ROOM_NOT_FOUND")

    /** 404: área comum inexistente. */
    val AREA_NOT_FOUND = ErrorCode("AREA_NOT_FOUND")

    /** 400: o tipo de UH informado no corpo não existe. */
    val UNKNOWN_ROOM_TYPE = ErrorCode("UNKNOWN_ROOM_TYPE")

    /** 400: alguma característica informada no corpo não existe. */
    val UNKNOWN_ROOM_FEATURE = ErrorCode("UNKNOWN_ROOM_FEATURE")

    /** 409: já existe uma UH com este número (sem diferenciar maiúsculas). */
    val ROOM_NUMBER_ALREADY_IN_USE = ErrorCode("ROOM_NUMBER_ALREADY_IN_USE")

    /** 409: já existe um tipo de UH com este código. */
    val ROOM_TYPE_CODE_ALREADY_IN_USE = ErrorCode("ROOM_TYPE_CODE_ALREADY_IN_USE")

    /** 409: já existe uma característica com este código. */
    val ROOM_FEATURE_CODE_ALREADY_IN_USE = ErrorCode("ROOM_FEATURE_CODE_ALREADY_IN_USE")

    /** 409: já existe uma área comum com este nome (sem diferenciar maiúsculas). */
    val AREA_NAME_ALREADY_IN_USE = ErrorCode("AREA_NAME_ALREADY_IN_USE")

    /** 400: ocupação máxima menor que a padrão, ou tipo sem nenhum adulto na ocupação padrão. */
    val ROOM_TYPE_OCCUPANCY_INVALID = ErrorCode("ROOM_TYPE_OCCUPANCY_INVALID")

    /** 422: o tipo de UH está desativado e não recebe UH ativa. */
    val ROOM_TYPE_INACTIVE = ErrorCode("ROOM_TYPE_INACTIVE")

    /** 422: a característica está desativada e não é atribuída de novo. */
    val ROOM_FEATURE_INACTIVE = ErrorCode("ROOM_FEATURE_INACTIVE")

    /** 422: o tipo de UH tem UHs ativas e não é desativado. */
    val ROOM_TYPE_HAS_ACTIVE_ROOMS = ErrorCode("ROOM_TYPE_HAS_ACTIVE_ROOMS")

    /** 400: código, número, nome ou texto em branco, grande demais ou fora do formato. */
    val ROOMS_DATA_INVALID = ErrorCode("ROOMS_DATA_INVALID")

    /** 422: a mudança de status de governança não está entre as transições da SPEC §8.4. */
    val HOUSEKEEPING_TRANSITION_NOT_ALLOWED = ErrorCode("HOUSEKEEPING_TRANSITION_NOT_ALLOWED")

    /** 400: número de hóspedes fora de 1 a 40, ou informado com a UH vaga. */
    val GUEST_COUNT_INVALID = ErrorCode("GUEST_COUNT_INVALID")

    /** 422: a UH está desativada (não é ocupada nem observada). */
    val ROOM_INACTIVE = ErrorCode("ROOM_INACTIVE")

    /** 422: a sinalização do hóspede só vale numa UH ocupada. */
    val ROOM_NOT_OCCUPIED = ErrorCode("ROOM_NOT_OCCUPIED")

    /** 422: a UH está ocupada e não é desativada. */
    val ROOM_OCCUPIED = ErrorCode("ROOM_OCCUPIED")
}
