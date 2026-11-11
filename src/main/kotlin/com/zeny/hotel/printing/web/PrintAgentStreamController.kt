package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.application.AgentStreamSink
import com.zeny.hotel.printing.application.DeliveredJob
import com.zeny.hotel.printing.application.PrintAgentStreams
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ServicePrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.json.JsonMapper

private const val LAST_EVENT_ID = "Last-Event-ID"

/**
 * O stream SSE de entrega ao agente (D-E10-6), autenticado pelo token do agente. O controller só liga o `SseEmitter` ao
 * laço do [PrintAgentStreams]; o evento leva os dados da entrega e nunca o conteúdo do job.
 */
@ApiV1Controller
@RequestMapping("/print-agents/me/jobs/stream")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintAgentStreamController(
    private val streams: PrintAgentStreams,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        operationId = "streamPrintAgentJobs",
        summary = "Receber os jobs de impressão por SSE",
        description =
            "Autenticado pelo token do agente. Ao conectar, reenvia os jobs `SENT` do agente ainda sem " +
                "confirmação de posição maior que o `Last-Event-ID` (todos, sem ele) e entrega os `PENDING` das " +
                "impressoras ativas dele (que passam a `SENT`), sem filtro de posição; depois entrega os novos. " +
                "Evento `job`, com o `id` igual à posição do job e `data` JSON com `id`, `printerId`, " +
                "`documentType`, `mediaType`, `attempts` e `createdAt` (o conteúdo se busca em `/content`). " +
                "Comentários `: heartbeat` e `: ping` mantêm a conexão viva (ignore-os). O servidor encerra o stream " +
                "depois do tempo máximo, quando o token deixa de valer (agente desativado ou token trocado), " +
                "quando o agente abre outro stream (só há um por agente: o anterior acaba) e na parada: " +
                "reconecte com o `Last-Event-ID`.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Stream SSE (`text/event-stream`).",
        content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"))],
    )
    fun stream(
        @AuthenticationPrincipal principal: ServicePrincipal,
        @Parameter(description = "A posição (`id`) do último evento recebido.")
        @RequestHeader(LAST_EVENT_ID, required = false)
        lastEventId: String?,
    ): ResponseEntity<SseEmitter> {
        val emitter = SseEmitter(streams.requestTimeout.toMillis())
        val connection = streams.open(principal, lastEventId?.trim()?.toLongOrNull(), EmitterSink(emitter, jsonMapper))
        emitter.onCompletion(connection::close)
        emitter.onError { connection.close() }
        emitter.onTimeout {
            connection.close()
            emitter.complete()
        }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            // Proxies que entendem o cabeçalho (nginx) não seguram os eventos num buffer.
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    private class EmitterSink(
        private val emitter: SseEmitter,
        private val jsonMapper: JsonMapper,
    ) : AgentStreamSink {
        override fun job(job: DeliveredJob) {
            emitter.send(
                SseEmitter
                    .event()
                    .id(job.position.toString())
                    .name("job")
                    .data(jsonMapper.writeValueAsString(DeliveredJobResponse.of(job)), MediaType.TEXT_PLAIN),
            )
        }

        override fun ping() = emitter.send(SseEmitter.event().comment("ping"))

        override fun heartbeat() = emitter.send(SseEmitter.event().comment("heartbeat"))

        override fun complete() = emitter.complete()
    }
}
