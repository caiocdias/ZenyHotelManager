package com.zeny.hotel.testsupport

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Um quadro recebido do stream: um evento (com `id`, `event` e `data`) ou um comentário (*heartbeat*). */
data class SseFrame(
    val id: String?,
    val event: String?,
    val data: String?,
    val comment: String? = null,
) {
    val isComment: Boolean get() = comment != null
}

/**
 * Cliente SSE de verdade para os testes do canal de tempo real (`GET /api/v1/stream`), sobre o
 * `HttpClient` do JDK num servidor de porta aleatória: lê a resposta aos poucos, numa thread própria,
 * e entrega cada quadro numa fila. Com status diferente de 200, guarda o corpo (o problema RFC 9457).
 */
class SseTestClient private constructor(
    val status: Int,
    val contentType: String?,
    val headers: Map<String, List<String>>,
    private val body: InputStream,
) : AutoCloseable {
    private val frames = LinkedBlockingQueue<SseFrame>()
    private val ended = CountDownLatch(1)

    /** O corpo inteiro, só numa resposta que não é o stream (erro). */
    val errorBody: String? = if (status == OK) null else body.readAllBytes().toString(Charsets.UTF_8)

    init {
        if (status == OK) {
            thread(isDaemon = true, name = "sse-test-client") { read() }
        } else {
            ended.countDown()
        }
    }

    /** O próximo quadro em até [timeout], ou `null`. */
    fun next(timeout: Duration = DEFAULT_TIMEOUT): SseFrame? = frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)

    /** O próximo evento (pula os comentários) em até [timeout], ou `null`. */
    fun nextEvent(timeout: Duration = DEFAULT_TIMEOUT): SseFrame? {
        val deadline = System.nanoTime() + timeout.toNanos()
        var frame: SseFrame? = null
        while (frame == null || frame.isComment) {
            val left = deadline - System.nanoTime()
            frame = if (left > 0) frames.poll(left, TimeUnit.NANOSECONDS) else null
            if (frame == null) break
        }
        return frame
    }

    /** Os eventos (sem comentários) que chegarem em [window]. */
    fun eventsWithin(window: Duration): List<SseFrame> {
        val deadline = System.nanoTime() + window.toNanos()
        val events = mutableListOf<SseFrame>()
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return events
            val frame = frames.poll(left, TimeUnit.NANOSECONDS) ?: return events
            if (!frame.isComment) events += frame
        }
    }

    /** Se o servidor encerrou o stream em até [timeout]. */
    fun awaitEnd(timeout: Duration = DEFAULT_TIMEOUT): Boolean = ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    val isEnded: Boolean get() = ended.count == 0L

    override fun close() {
        runCatching { body.close() }
    }

    private fun read() {
        try {
            BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
                val parser = FrameParser()
                generateSequence(reader::readLine).forEach { line -> parser.accept(line)?.let(frames::add) }
            }
        } catch (_: java.io.IOException) {
            // Conexão encerrada (pelo servidor ou pelo close do teste).
        } finally {
            ended.countDown()
        }
    }

    /** Junta as linhas de um quadro SSE (`id:`, `event:`, `data:`, comentário) até a linha em branco. */
    private class FrameParser {
        private var id: String? = null
        private var event: String? = null
        private val data = mutableListOf<String>()

        fun accept(line: String): SseFrame? =
            when {
                line.isEmpty() -> {
                    dispatch()
                }

                line.startsWith(":") -> {
                    SseFrame(null, null, null, line.removePrefix(":").trim())
                }

                else -> {
                    field(line.substringBefore(':'), line.substringAfter(':', "").removePrefix(" "))
                    null
                }
            }

        private fun field(
            name: String,
            value: String,
        ) {
            when (name) {
                "id" -> id = value
                "event" -> event = value
                "data" -> data += value
            }
        }

        private fun dispatch(): SseFrame? {
            val frame =
                if (id != null || event != null || data.isNotEmpty()) {
                    SseFrame(id, event, data.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                } else {
                    null
                }
            id = null
            event = null
            data.clear()
            return frame
        }
    }

    companion object {
        private const val OK = 200
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)

        private val http: HttpClient =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        /** Abre `GET /api/v1/stream?topics=…` em [baseUrl] com o access token e o `Last-Event-ID`, se houver. */
        fun open(
            baseUrl: String,
            accessToken: String?,
            topics: String?,
            lastEventId: String? = null,
        ): SseTestClient {
            val query = topics?.let { "?topics=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}" }.orEmpty()
            val request =
                HttpRequest
                    .newBuilder(URI.create("$baseUrl/api/v1/stream$query"))
                    .header("Accept", "text/event-stream")
                    .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
                    .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            return SseTestClient(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.headers().map(),
                response.body(),
            )
        }
    }
}
