package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.reporting.ReportingApi
import org.springframework.stereotype.Component

/**
 * A etapa das pendências da RF-FEC-04 (M6 E13, D3): levanta uma pendência por item de
 * [ReportingApi.endOfDayPendingItems] (conta com saldo, caixa aberto e FNRH pendente), com o tipo do item como `kind`,
 * a referência opaca (um id) e a mensagem `"<tipo>: <detalhe>"`, só com número, código e status (RN-13). O aviso sai
 * agrupado por tipo, como o das demais etapas. Fica fora do arquivo dos PDFs (900) para ter resultado próprio e para a
 * falha da leitura não repetir o arquivo.
 *
 * Idempotente pela data: só lê e levanta pendência, sem efeito no domínio; uma pendência que persiste aparece em cada
 * fechamento. Sem itens, a etapa é [EndOfDayStepOutcome.NOT_APPLICABLE].
 */
@Component
class EndOfDayPendingStep(
    private val reporting: ReportingApi,
) : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
        val items = reporting.endOfDayPendingItems()
        if (items.isEmpty()) return EndOfDayStepOutcome.NOT_APPLICABLE
        items.forEach { context.issues.raise(it.kind.name, it.reference.toString(), "${it.kind.name}: ${it.detail}") }
        return EndOfDayStepOutcome.DONE
    }

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "end-of-day-pending"

        /** A posição: depois do arquivo dos PDFs e antes do avanço da data. */
        const val POSITION = 950
    }
}
