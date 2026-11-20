package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reporting.ReportingApi
import org.springframework.stereotype.Component

/**
 * A oitava etapa do fechamento (M6 E13, D1): grava o snapshot diário das estatísticas da data que fecha
 * ([ReportingApi.snapshotDailyStatistics]). O snapshot fica na transação da etapa e é confirmado com ela, o que a etapa
 * seguinte ([EndOfDayReportsStep]) exige: o arquivo dos PDFs abre transação própria e só vê o snapshot já confirmado.
 *
 * Idempotente pela data: o `reporting` grava uma linha por data e devolve sempre a gravada, então a retomada de uma
 * etapa `RUNNING` não cria segunda linha. Exceção do `reporting` não é capturada: falha a etapa, que a retomada repete.
 */
@Component
class DailyStatisticsStep(
    private val reporting: ReportingApi,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        reporting.snapshotDailyStatistics(context.businessDate)
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "daily-statistics"

        /** A posição: depois das UHs sujas e antes do arquivo dos PDFs. */
        const val POSITION = 800
    }
}
