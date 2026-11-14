package com.zeny.hotel.endofday.application.steps

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import org.springframework.stereotype.Component

/**
 * A sexta etapa do fechamento (RF-FEC-01): renovar as pré-autorizações de cartão que vencem. A pré-autorização
 * existe desde o M5 (E02), mas a **renovação** só chega no M7; até lá a etapa sempre termina
 * [EndOfDayStepOutcome.NOT_APPLICABLE]. O M7 implementa a renovação aqui
 * com `BillingApi.cardPreAuthorizationsExpiring`.
 *
 * Idempotente pela data: não tem efeito.
 */
@Component
class CardPreAuthorizationsStep : EndOfDayStepHandler {
    override val name: String = NAME
    override val position: Int = POSITION

    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome = EndOfDayStepOutcome.NOT_APPLICABLE

    companion object {
        /** O nome publicado da etapa: nunca muda. */
        const val NAME = "card-pre-authorizations"

        /** A posição: depois da expiração das provisórias. */
        const val POSITION = 600
    }
}
