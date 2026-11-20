package com.zeny.hotel.reporting

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do `reporting` (ARCHITECTURE §7). Um código publicado não muda. */
object ReportingErrorCodes {
    /** 400: o período do relatório termina antes de começar ou passa de 366 dias (campo `to`). */
    val REPORT_PERIOD_INVALID = ErrorCode("REPORT_PERIOD_INVALID")
}
