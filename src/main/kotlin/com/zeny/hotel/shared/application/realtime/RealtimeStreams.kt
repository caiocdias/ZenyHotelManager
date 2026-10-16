package com.zeny.hotel.shared.application.realtime

import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RealtimeTopic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant

/**
 * Configuração do canal de tempo real (`zeny.realtime.*` no `application.yaml`, ARCHITECTURE §10).
 *
 * @property bufferSize mensagens guardadas por tópico para a reconexão com `Last-Event-ID` (H5): quem volta
 *   depois de perder mais que isso recebe `reset`.
 * @property heartbeatInterval intervalo do comentário de *heartbeat*, que mantém a conexão viva nos proxies
 *   e faz o servidor notar o cliente que sumiu (na escrita).
 * @property maxConnectionsPerSession conexões abertas ao mesmo tempo por sessão de dispositivo; a mais nova
 *   encerra a mais antiga (o cliente que reconecta depois de cair não espera a velha morrer).
 */
@ConfigurationProperties("zeny.realtime")
class RealtimeProperties(
    val bufferSize: Int,
    val heartbeatInterval: Duration,
    val maxConnectionsPerSession: Int,
) {
    init {
        require(bufferSize in 1..MAX_BUFFER_SIZE) { "zeny.realtime.buffer-size fora de 1..$MAX_BUFFER_SIZE" }
        require(heartbeatInterval >= MIN_HEARTBEAT && heartbeatInterval <= MAX_HEARTBEAT) {
            "zeny.realtime.heartbeat-interval fora de $MIN_HEARTBEAT..$MAX_HEARTBEAT"
        }
        require(maxConnectionsPerSession in 1..MAX_CONNECTIONS_PER_SESSION) {
            "zeny.realtime.max-connections-per-session fora de 1..$MAX_CONNECTIONS_PER_SESSION"
        }
    }

    private companion object {
        const val MAX_BUFFER_SIZE = 100_000
        const val MAX_CONNECTIONS_PER_SESSION = 20
        val MIN_HEARTBEAT: Duration = Duration.ofSeconds(1)
        val MAX_HEARTBEAT: Duration = Duration.ofMinutes(5)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties::class)
class RealtimeConfiguration

/** Um quadro do stream SSE: um evento ou um comentário (*heartbeat*). */
sealed interface StreamFrame {
    /** Evento `id`/`event`/`data`; [data] é JSON pronto. */
    data class Event(
        val id: String,
        val name: String,
        val data: String,
    ) : StreamFrame

    /** Comentário (`: texto`), que o cliente ignora. */
    data class Comment(
        val text: String,
    ) : StreamFrame
}

/**
 * O lado da web de uma conexão (a resposta SSE): o canal escreve os quadros, em ordem, por uma única thread
 * de cada vez, e a encerra. [send] falha (exceção) quando o cliente se foi.
 */
interface StreamSink {
    fun send(frame: StreamFrame)

    fun complete()
}

/**
 * Uma inscrição já autorizada: quem conecta, com que escopo em cada tópico (avaliado na inscrição, H8: uma
 * permissão retirada só vale na reconexão) e até quando o access token vale.
 */
data class StreamSubscription(
    val actor: Actor,
    val grants: Map<RealtimeTopic, PermissionScope>,
    val expiresAt: Instant,
)

/** Por que o servidor encerrou uma conexão (só para o log). */
enum class StreamCloseReason {
    /** O access token venceu: o cliente renova o token e reconecta com o `Last-Event-ID`. */
    TOKEN_EXPIRED,

    /** A sessão foi revogada (ou o usuário desativado). */
    SESSION_REVOKED,

    /** Outra conexão da mesma sessão passou do limite e esta, a mais antiga, saiu. */
    REPLACED,

    /** O cliente não acompanha: mensagens demais esperando envio. */
    OVERFLOW,

    /** O cliente se foi (falha na escrita, conexão encerrada pelo contêiner). */
    DISCONNECTED,

    /** O servidor está parando. */
    SHUTDOWN,
}
