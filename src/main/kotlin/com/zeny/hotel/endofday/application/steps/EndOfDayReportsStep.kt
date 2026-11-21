package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reporting.ReportingApi
import org.springframework.stereotype.Component

/**
 * A nona etapa do fechamento (M6 E13, D1): arquiva os documentos da data que fecha, hoje o PDF do dia
 * ([ReportingApi.archiveDayReports]). Fica separada da [DailyStatisticsStep] porque o arquivo abre transação própria
 * por tipo (`REQUIRES_NEW`) e só vê o snapshot que a etapa anterior já confirmou; na mesma transação ele falharia por
 * `lock_timeout` em toda tentativa.
 *
 * Idempotente pela data: o `reporting` mantém um anexo por data e tipo, então a retomada não duplica. Um tipo que
 * falhou (render ou gravação) não deixa anexo vinculado; a exceção não é capturada, a etapa falha e a retomada a
 * repete.
 */
@Component
class EndOfDayReportsStep(
    private val reporting: ReportingApi,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        reporting.archiveDayReports(context.businessDate)
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "end-of-day-reports"

        /** A posição: depois do snapshot e antes do avanço da data. */
        const val POSITION = 900
    }
}
