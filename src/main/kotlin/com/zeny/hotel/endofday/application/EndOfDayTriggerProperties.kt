package com.zeny.hotel.endofday.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * A configuração do gatilho automático do fechamento (`zeny.endofday.trigger`, M6 E10), conferida no boot: um valor
 * inválido impede a partida. O [interval] e a [catchUpWindow] são positivos, e a janela vai até [MAX_CATCH_UP_WINDOW]:
 * uma janela maior deixaria o automático fechar sozinho datas atrasadas por dias (no-show com multa, envio à FNRH),
 * justamente o que a janela existe para evitar. A [wait] é positiva e vai até [MAX_WAIT] (M6 E11): o gatilho roda na
 * thread do agendador do Spring, a mesma dos demais jobs, e uma espera longa os pararia. Quem usa os valores os lê
 * pela propriedade (`@Value`); esta classe só os valida.
 */
@ConfigurationProperties("zeny.endofday.trigger")
data class EndOfDayTriggerProperties(
    /** Espera entre o fim de uma rodada e o início da seguinte. */
    val interval: Duration,
    /** Espera da primeira rodada depois do boot. */
    val initialDelay: Duration,
    /** Por quanto tempo depois do vencimento o fechamento ainda roda sozinho. */
    val catchUpWindow: Duration,
    /** Quanto a rodada espera o motor antes de voltar com a execução ainda em curso. */
    val wait: Duration,
) {
    init {
        require(interval.isPositive) { "zeny.endofday.trigger.interval precisa ser positivo" }
        require(catchUpWindow.isPositive && catchUpWindow <= MAX_CATCH_UP_WINDOW) {
            "zeny.endofday.trigger.catch-up-window precisa ser positiva e de no máximo $MAX_CATCH_UP_WINDOW"
        }
        require(wait.isPositive && wait <= MAX_WAIT) {
            "zeny.endofday.trigger.wait precisa ser positiva e de no máximo $MAX_WAIT"
        }
    }

    companion object {
        /** A maior janela aceita: 7 dias. */
        val MAX_CATCH_UP_WINDOW: Duration = Duration.ofDays(7)

        /** A maior espera do motor aceita: 1 minuto. */
        val MAX_WAIT: Duration = Duration.ofMinutes(1)
    }
}
