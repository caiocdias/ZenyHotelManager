package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.infrastructure.PrintingProperties
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UnauthenticatedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Duration

/** Para onde o laço do stream escreve (a web liga ao `SseEmitter`). Nenhum método recebe o conteúdo do job. */
interface AgentStreamSink {
    /** Um job entregue: o `id` do evento é a posição. */
    fun job(job: DeliveredJob)

    /** O comentário `: ping`, escrito antes de cada reivindicação: se falhar, o cliente sumiu e nada é reivindicado. */
    fun ping()

    /** O comentário `: heartbeat`. */
    fun heartbeat()

    /** O stream acabou; chamado uma vez, pelo laço. */
    fun complete()
}

/**
 * O laço do stream SSE do agente (D-E10-6), numa *virtual thread* por conexão: ao abrir, reenvia os `SENT` sem ack de
 * posição maior que o `Last-Event-ID` e reivindica os `PENDING` (sem filtro de posição); depois reivindica a cada
 * `delivery-poll-interval` e manda `: heartbeat` a cada `stream-heartbeat`. Termina na desconexão ou no despejo da
 * conexão (um stream por agente, parada do servidor), depois de `stream-max-duration` e quando o agente deixa de ser
 * válido (inativo ou token trocado: `401` do [PrintAgentJobsService]). Cada job é reivindicado e escrito
 * um por vez, depois de um `ping`. Nenhuma transação fica aberta durante a
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
        val connection = connections.open(principal.id, ConnectionKind.STREAM)
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
        } catch (_: IOException) {
            // inclui o AsyncRequestNotUsableException do Spring
            log.debug("Stream do agente {} encerrado: cliente saiu", principal.id)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // o stream acaba, seja qual for a falha
        ) {
            // Sem a mensagem nem a causa: o texto de uma falha de banco pode trazer dados.
            log.warn("Stream do agente {} encerrado por falha: {}", principal.id, e.javaClass.simpleName)
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
        if (!connection.isDone) delivery.unacknowledged(principal, afterPosition).forEach(sink::job)
        while (!connection.isDone) {
            claimAndSend(principal, sink, connection)
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

    /**
     * Reivindica e escreve um job por vez, cada um depois de um `ping`: a queda do cliente só aparece numa escrita, e
     * reivindicar sem ela deixaria o job `SENT` a um stream morto. Se o envio do job falha, ele volta a `PENDING`.
     */
    private fun claimAndSend(
        principal: ServicePrincipal,
        sink: AgentStreamSink,
        connection: AgentConnection,
    ) {
        repeat(PrintAgentJobsService.CLAIM_BATCH) {
            if (connection.isDone) return
            sink.ping()
            val job = delivery.claim(principal, 1).firstOrNull() ?: return
            try {
                sink.job(job)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // qualquer falha de escrita devolve o job
            ) {
                try {
                    delivery.release(principal, job.id)
                } catch (
                    @Suppress("TooGenericExceptionCaught") releaseFailure: Exception, // o job volta pelo ack-timeout
                ) {
                    log.warn("Stream: o job {} não voltou a PENDING ({})", job.id, releaseFailure.javaClass.simpleName)
                }
                throw e
            }
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PrintAgentStreams::class.java)
        val TIMEOUT_SLACK: Duration = Duration.ofSeconds(30)
        const val BEFORE_ANY_POSITION = -1L
    }
}
