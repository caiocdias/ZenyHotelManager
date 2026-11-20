package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ReportingErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.StorableRange
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O período máximo de um relatório, em dias, as duas pontas inclusive. */
const val MAX_REPORT_DAYS = 366L

/**
 * Confere o período de um relatório: datas dentro do intervalo guardável, fim não antes do início e no máximo
 * [MAX_REPORT_DAYS] dias (as duas pontas inclusive); senão `400 REPORT_PERIOD_INVALID` no campo `to`.
 */
internal fun requireValidReportPeriod(
    from: LocalDate,
    to: LocalDate,
) {
    val message =
        when {
            !StorableRange.contains(from) || !StorableRange.contains(to) -> {
                "Data fora do intervalo aceito."
            }

            to.isBefore(from) -> {
                "O fim do período não pode ser antes do início."
            }

            ChronoUnit.DAYS.between(from, to) + 1 > MAX_REPORT_DAYS -> {
                "O período não pode passar de $MAX_REPORT_DAYS dias."
            }

            else -> {
                return
            }
        }
    val code = ReportingErrorCodes.REPORT_PERIOD_INVALID
    throw InvalidInputException(code, message, listOf(FieldViolation("to", code.value, message)))
}
