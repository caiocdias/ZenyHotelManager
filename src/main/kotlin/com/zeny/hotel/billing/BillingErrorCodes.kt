package com.zeny.hotel.billing

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do `billing` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido. */
object BillingErrorCodes {
    /** 400: texto, código curto ou valor fora do formato (com o campo em `errors[]`). */
    val BILLING_DATA_INVALID = ErrorCode("BILLING_DATA_INVALID")

    /** 404: código de lançamento inexistente. */
    val TRANSACTION_CODE_NOT_FOUND = ErrorCode("TRANSACTION_CODE_NOT_FOUND")

    /** 400: o lançamento cita um código inexistente (campo `transactionCodeId`). */
    val UNKNOWN_TRANSACTION_CODE = ErrorCode("UNKNOWN_TRANSACTION_CODE")

    /** 422: código de lançamento desativado. */
    val TRANSACTION_CODE_INACTIVE = ErrorCode("TRANSACTION_CODE_INACTIVE")

    /** 409: outro código já usa o código curto (inclusive desativado). */
    val TRANSACTION_CODE_ALREADY_IN_USE = ErrorCode("TRANSACTION_CODE_ALREADY_IN_USE")

    /** 400: taxa de serviço incoerente com o tipo do código, ou percentual fora de 0 a 100. */
    val TRANSACTION_CODE_INVALID = ErrorCode("TRANSACTION_CODE_INVALID")

    /** 404: conta inexistente. */
    val FOLIO_NOT_FOUND = ErrorCode("FOLIO_NOT_FOUND")

    /** 409: conta encerrada não recebe lançamento nem correção. */
    val FOLIO_CLOSED = ErrorCode("FOLIO_CLOSED")

    /** 400: dono da conta sem id (só `WALK_IN` aceita sem). */
    val FOLIO_OWNER_INVALID = ErrorCode("FOLIO_OWNER_INVALID")

    /** 400: empresa (dona ou pagadora) inexistente. */
    val UNKNOWN_COMPANY = ErrorCode("UNKNOWN_COMPANY")

    /** 422: empresa (dona ou pagadora) desativada. */
    val COMPANY_INACTIVE = ErrorCode("COMPANY_INACTIVE")

    /** 404: lançamento inexistente nesta conta. */
    val POSTING_NOT_FOUND = ErrorCode("POSTING_NOT_FOUND")

    /** 400: quantidade, valor unitário ou descrição fora da faixa, ou valor que arredonda a zero. */
    val POSTING_INVALID = ErrorCode("POSTING_INVALID")

    /** 422: só um lançamento normal é estornado ou ajustado (nunca um estorno nem um ajuste). */
    val POSTING_NOT_CORRECTABLE = ErrorCode("POSTING_NOT_CORRECTABLE")

    /** 409: o lançamento já foi estornado (não aceita outro estorno nem ajuste). */
    val POSTING_ALREADY_REVERSED = ErrorCode("POSTING_ALREADY_REVERSED")

    /** 422: os ajustes já zeraram o lançamento; não resta nada a estornar. */
    val POSTING_NOTHING_TO_REVERSE = ErrorCode("POSTING_NOTHING_TO_REVERSE")

    /** 422: o ajuste passa do que ainda resta do lançamento. */
    val ADJUSTMENT_EXCEEDS_REMAINING = ErrorCode("ADJUSTMENT_EXCEEDS_REMAINING")
}
