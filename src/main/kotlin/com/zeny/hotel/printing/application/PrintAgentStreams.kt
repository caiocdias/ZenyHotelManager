package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.infrastructure.PrintingProperties
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UnauthenticatedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/** Para onde o laço do stream escreve (a web liga ao `SseEmitter`). Nenhum método recebe o conteúdo do job. */
interface AgentStreamSink {
    /** Um job entregue: o `id` do evento é a posição. */
    fun job(job: DeliveredJob)

    /** O comentário `: heartbeat`. */
    fun heartbeat()

    /** O stream acabou; chamado uma vez, pelo laço. */
    fun complete()
}

/**
 * O laço do stream SSE do agente (D-E10-6), numa *virtual thread* por conexão: ao abrir, reenvia os `SENT` sem ack de
 * posição maior que o `Last-Event-ID` e reivindica os `PENDING` (sem filtro de posição); depois reivindica a cada
 * `delivery-poll-interval` e manda `: heartbeat` a cada `stream-heartbeat`. Termina na desconexão ou no despejo da
 * conexão (limite por agente, parada do servidor), depois de `stream-max-duration` e quando o agente deixa de ser
 * válido (inativo ou token trocado: `401` do [PrintAgentJobsService]). Nenhuma transação fica aberta durante a
 * espera, e nada do conteúdo ou do token vai ao log.
 */
@Component
class PrintAgentStreams(
    private val delivery: PrintAgentJobsService,
    private val connections: PrintAgentConnections,
    private val properties: PrintingProperties,
) {
    /** O tempo limite da requisição assíncrona: o do laço, com folga (o laço acaba a resposta antes). */
    val requestTimeout: Duration get() = properties.streamMaxDuration.plus(TIMEOUT_SLACK)

    /**
     * Abre o stream do [principal] e devolve a conexão: o chamador a fecha quando a resposta acaba (desconexão do
     * cliente), o que encerra o laço. [lastEventId] é a posição do último evento que o agente recebeu, se houver.
     */
    fun open(
        principal: ServicePrincipal,
        lastEventId: Long?,
        sink: AgentStreamSink,
    ): AgentConnection {
        val connection = connections.open(principal.id)
        Thread.ofVirtual().name("print-agent-stream").start { run(principal, lastEventId, sink, connection) }
        return connection
    }

    private fun run(
        principal: ServicePrincipal,
        lastEventId: Long?,
        sink: AgentStreamSink,
        connection: AgentConnection,
    ) {
        try {
            loop(principal, lastEventId ?: BEFORE_ANY_POSITION, sink, connection)
        } catch (_: UnauthenticatedException) {
            // O agente foi revogado ou teve o token trocado: o stream acaba.
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // cliente que saiu, banco fora: o stream acaba
        ) {
            log.debug("Stream do agente {} encerrado: {}", principal.id, e.javaClass.simpleName)
        } finally {
            connection.close()
            runCatching(sink::complete)
        }
    }

    private fun loop(
        principal: ServicePrincipal,
        afterPosition: Long,
        sink: AgentStreamSink,
        connection: AgentConnection,
    ) {
        val deadline = System.nanoTime() + properties.streamMaxDuration.toNanos()
        var nextHeartbeat = System.nanoTime() + properties.streamHeartbeat.toNanos()
        delivery.unacknowledged(principal, afterPosition).forEach(sink::job)
        while (!connection.isDone) {
            delivery.claim(principal, CLAIM_BATCH).forEach(sink::job)
            val now = System.nanoTime()
            if (now >= deadline) return
            if (now >= nextHeartbeat) {
                sink.heartbeat()
                nextHeartbeat = now + properties.streamHeartbeat.toNanos()
            }
            val pause = minOf(properties.deliveryPollInterval.toNanos(), nextHeartbeat - now, deadline - now)
            if (connection.awaitDone(Duration.ofNanos(pause.coerceAtLeast(1)))) return
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PrintAgentStreams::class.java)
        val TIMEOUT_SLACK: Duration = Duration.ofSeconds(30)
        const val CLAIM_BATCH = 20
        const val BEFORE_ANY_POSITION = -1L
    }
}
