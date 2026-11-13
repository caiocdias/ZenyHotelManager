package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.hotel.HotelApi
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * A última etapa do fechamento (RF-FEC-02, RN-01): avança a data operacional da data que fecha para a seguinte
 * ([HotelApi.advanceBusinessDate], que publica `BusinessDateAdvanced` na transação da etapa). **O nome é publicado e é
 * proibido mudá-lo ou remover a etapa**: a execução concluiria sem avançar a data. A posição fica depois de todas as
 * outras etapas de negócio.
 *
 * Idempotente pela data: o avanço só sai da [EndOfDayStepContext.businessDate]; se ela já não é a corrente, falha com
 * `409 BUSINESS_DATE_MISMATCH` sem efeito.
 *
 * O avanço (`FOR UPDATE` em `hotel.business_day`) espera os lançamentos em curso, que travam a data com `FOR SHARE`
 * até o commit (RF-FEC-05, [HotelApi.lockBusinessDate]); travas `FOR SHARE` pedidas durante a espera também são
 * atendidas. Para não esperar sem fim atrás de um lançamento preso ou de travas sobrepostas, a etapa limita a espera
 * na transação dela (`SET LOCAL`): [LOCK_TIMEOUT] vale por tentativa de trava, e [STATEMENT_TIMEOUT] limita o total de
 * cada comando. Esgotado o prazo, a exceção falha a etapa, que fica retomável (`STEP_FAILED`).
 */
@Component
class AdvanceBusinessDateStep(
    private val hotel: HotelApi,
    private val jdbc: JdbcClient,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        jdbc.sql("SET LOCAL statement_timeout = '$STATEMENT_TIMEOUT'").update()
        jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
        hotel.advanceBusinessDate(context.businessDate)
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "advance-business-date"

        /** A posição: depois de todas as etapas de negócio. */
        const val POSITION = 1000

        /** O máximo de cada espera por trava do avanço. */
        const val LOCK_TIMEOUT = "10s"

        /** O máximo de cada comando do avanço, somadas as esperas. */
        const val STATEMENT_TIMEOUT = "30s"
    }
}
