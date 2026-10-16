package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamService
import com.zeny.hotel.shared.application.realtime.StreamSink
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * O canal de tempo real por SSE (ARCHITECTURE §10, D10, RNF-04). A resposta fica aberta até o `exp` do
 * access token, a revogação da sessão ou a desconexão; o tempo limite da requisição assíncrona é o do token,
 * com uma folga (o canal a encerra antes).
 */
@ApiV1Controller
@RequestMapping("/stream")
@Tag(name = "stream", description = "Canal de tempo real (SSE).")
class StreamController(
    private val streams: StreamService,
    private val clock: Clock,
) {
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        operationId = "openStream",
        summary = "Abre o canal de tempo real",
        description = STREAM_DESCRIPTION,
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Stream SSE (`text/event-stream`).",
                content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"))],
            ),
        ],
    )
    fun stream(
        @Parameter(description = "Tópicos separados por vírgula, como `rooms`.", example = "rooms")
        @RequestParam(required = false)
        topics: String?,
        @Parameter(description = "Id do último evento recebido, para repor o que foi perdido.")
        @RequestHeader(LAST_EVENT_ID, required = false)
        lastEventId: String?,
    ): ResponseEntity<SseEmitter> {
        val expiresAt = tokenExpiresAt()
        val timeout = expiresAt?.let { Duration.between(clock.instant(), it).plus(TIMEOUT_SLACK) } ?: TIMEOUT_SLACK
        val emitter = SseEmitter(timeout.toMillis().coerceAtLeast(1))
        // Os avisos de fim antes da inscrição: o que terminar a resposta sempre tira a conexão do canal.
        val connection = AtomicReference<UUID>()
        val release = Runnable { connection.get()?.let(streams::release) }
        emitter.onCompletion(release)
        emitter.onError { release.run() }
        emitter.onTimeout {
            release.run()
            emitter.complete()
        }
        connection.set(streams.subscribe(topics, lastEventId, expiresAt, EmitterSink(emitter)))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            // Proxies que entendem o cabeçalho (nginx) não seguram os eventos num buffer.
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    /** O `exp` do access token validado pelo Resource Server, ou `null` sem ele. */
    private fun tokenExpiresAt(): Instant? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.expiresAt

    /** Escreve os quadros do canal na resposta SSE. */
    private class EmitterSink(
        private val emitter: SseEmitter,
    ) : StreamSink {
        override fun send(frame: StreamFrame) {
            when (frame) {
                is StreamFrame.Event -> {
                    emitter.send(
                        SseEmitter
                            .event()
                            .id(frame.id)
                            .name(frame.name)
                            .data(frame.data, MediaType.TEXT_PLAIN),
                    )
                }

                is StreamFrame.Comment -> {
                    emitter.send(SseEmitter.event().comment(frame.text))
                }
            }
        }

        override fun complete() = emitter.complete()
    }

    private companion object {
        const val LAST_EVENT_ID = "Last-Event-ID"
        val TIMEOUT_SLACK: Duration = Duration.ofSeconds(30)

        const val STREAM_DESCRIPTION =
            "Exige a permissão de cada tópico (sem ela, 403 PERMISSION_DENIED; tópico desconhecido, " +
                "400 UNKNOWN_STREAM_TOPIC). Cada mensagem é um evento com o nome do tópico, `id` " +
                "`<época>-<sequência>` e `data` JSON com só `type`, `id` e `version`: o cliente aplica a " +
                "mudança ou rebusca o recurso. A conexão começa por `ready` (sem `Last-Event-ID`, ou depois " +
                "de repor as mensagens perdidas) ou por `reset` (o `Last-Event-ID` saiu do buffer ou é de " +
                "outro boot: recarregue o estado); os dois trazem a posição corrente no `id`. Comentários " +
                "`: heartbeat` periódicos mantêm a conexão viva. O servidor encerra a conexão no vencimento " +
                "do access token (renove-o e reconecte com o `Last-Event-ID`) e na revogação da sessão."
    }
}
