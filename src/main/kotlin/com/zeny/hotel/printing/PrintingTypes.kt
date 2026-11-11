package com.zeny.hotel.printing

import com.zeny.hotel.shared.ErrorCode

/** O tipo de impressora, que decide o tipo de mídia do conteúdo do job (D-E10-5). */
enum class PrinterKind(
    val mediaType: String,
) {
    THERMAL_ESCPOS("application/octet-stream"),
    A4_PDF("application/pdf"),
}

/** O ciclo do job de impressão (D-E10-4): `PENDING` → `SENT` → `PRINTED` ou `FAILED`. */
enum class PrintJobStatus { PENDING, SENT, PRINTED, FAILED }

/**
 * Códigos de erro (de problema) do `printing` (ARCHITECTURE §7). Um código publicado não muda de nome nem de sentido.
 */
object PrintingErrorCodes {
    /** 422: o agente está desativado. */
    val PRINT_AGENT_INACTIVE = ErrorCode("PRINT_AGENT_INACTIVE")

    /** 422: a impressora está desativada. */
    val PRINTER_INACTIVE = ErrorCode("PRINTER_INACTIVE")

    /** 400: o agente referido não existe. */
    val UNKNOWN_PRINT_AGENT = ErrorCode("UNKNOWN_PRINT_AGENT")

    /** 400: a impressora referida não existe. */
    val UNKNOWN_PRINTER = ErrorCode("UNKNOWN_PRINTER")

    /** 404: o job não existe ou não é do agente que pede. */
    val PRINT_JOB_NOT_FOUND = ErrorCode("PRINT_JOB_NOT_FOUND")

    /** 409: o job do agente já não está em `SENT` e não pode ser confirmado. */
    val PRINT_JOB_NOT_ACKNOWLEDGEABLE = ErrorCode("PRINT_JOB_NOT_ACKNOWLEDGEABLE")

    /** 422: o conteúdo passa do teto. */
    val PRINT_CONTENT_TOO_LARGE = ErrorCode("PRINT_CONTENT_TOO_LARGE")

    /** 422: o conteúdo é vazio. */
    val PRINT_CONTENT_EMPTY = ErrorCode("PRINT_CONTENT_EMPTY")

    /** 422: a impressora é A4_PDF e o conteúdo não começa com `%PDF-`. */
    val PRINT_CONTENT_NOT_PDF = ErrorCode("PRINT_CONTENT_NOT_PDF")

    /** 400: o código de falha do ack não segue `[A-Z][A-Z0-9_]{0,49}`. */
    val INVALID_FAILURE_CODE = ErrorCode("INVALID_FAILURE_CODE")
}
