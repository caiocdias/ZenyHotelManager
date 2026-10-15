package com.zeny.hotel.hotel

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do módulo `hotel` (ARCHITECTURE §7). As validações da configuração
 * saem com `400` e `errors[]` apontando o campo (o `code` do campo é o mesmo do problema).
 */
object HotelErrorCodes {
    /** 400: nome ou outro dado cadastral obrigatório em branco ou grande demais. */
    val HOTEL_DATA_INVALID = ErrorCode("HOTEL_DATA_INVALID")

    /** 400: o endereço não fecha (por exemplo, código IBGE de outra UF). */
    val INVALID_ADDRESS = ErrorCode("INVALID_ADDRESS")

    /** 400: fuso desconhecido ou que não é de uma região (deslocamento fixo, `UTC`). */
    val INVALID_TIMEZONE = ErrorCode("INVALID_TIMEZONE")

    /** 400: horário com segundos ou frações; os horários da configuração são em horas e minutos. */
    val TIME_NOT_WHOLE_MINUTE = ErrorCode("TIME_NOT_WHOLE_MINUTE")

    /** 400: o check-out precisa ser antes do check-in, no mesmo dia. */
    val CHECK_TIMES_INCOHERENT = ErrorCode("CHECK_TIMES_INCOHERENT")

    /** 400: janela de arrumação ou intervalo entre check-out e check-in acima de 3 horas (RN-11). */
    val CLEANING_WINDOW_TOO_LONG = ErrorCode("CLEANING_WINDOW_TOO_LONG")

    /** 400: janela de arrumação vazia ou maior que o intervalo entre check-out e check-in. */
    val CLEANING_WINDOW_INVALID = ErrorCode("CLEANING_WINDOW_INVALID")

    /** 400: early check-in fora de antes do check-in ou com preço negativo. */
    val EARLY_CHECK_IN_INVALID = ErrorCode("EARLY_CHECK_IN_INVALID")

    /** 400: late check-out fora de depois do check-out ou com preço negativo. */
    val LATE_CHECK_OUT_INVALID = ErrorCode("LATE_CHECK_OUT_INVALID")

    /** 400: idade de criança fora de 0 a 17 ou faixas etárias que não cobrem exatamente essa idade. */
    val CHILD_AGES_INVALID = ErrorCode("CHILD_AGES_INVALID")

    /** 400: o texto de política das confirmações (RN-11) está em branco ou grande demais. */
    val POLICY_TEXT_REQUIRED = ErrorCode("POLICY_TEXT_REQUIRED")

    /** 400: taxa de serviço fora de 0% a 100%. */
    val SERVICE_CHARGE_RATE_INVALID = ErrorCode("SERVICE_CHARGE_RATE_INVALID")

    /** 400: alíquota de ISS fora de 0% a 5% (LC 116/2003, art. 8º). */
    val ISS_RATE_INVALID = ErrorCode("ISS_RATE_INVALID")

    /** 400: taxa de turismo ligada sem valor positivo. */
    val TOURISM_FEE_INVALID = ErrorCode("TOURISM_FEE_INVALID")

    /** 400: parâmetro operacional fora da faixa aceita. */
    val OPERATIONAL_SETTING_INVALID = ErrorCode("OPERATIONAL_SETTING_INVALID")

    /** 409: a data operacional corrente não é a esperada por quem pediu o avanço. */
    val BUSINESS_DATE_MISMATCH = ErrorCode("BUSINESS_DATE_MISMATCH")
}
