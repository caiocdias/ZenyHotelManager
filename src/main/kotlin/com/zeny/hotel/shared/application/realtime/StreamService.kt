package com.zeny.hotel.shared.application.realtime

import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DeviceSessionStatus
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.RealtimeTopic
import com.zeny.hotel.shared.UnauthenticatedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Inscrição no canal de tempo real (`GET /api/v1/stream?topics=…`, ARCHITECTURE §10, H8 do M2), nesta
 * ordem: os tópicos pedidos existem (`400 VALIDATION_FAILED` sem nenhum, `400 UNKNOWN_STREAM_TOPIC` com um
 * desconhecido), a sessão do access token não foi revogada (`401`; o token de uma sessão revogada ainda vale
 * até vencer) e o ator tem a permissão de cada tópico (`403 PERMISSION_DENIED`), cujo escopo fica guardado
 * na conexão. A transação (só leitura) termina aqui: a conexão longa não segura conexão de banco.
 */
@Service
class StreamService(
    private val hub: RealtimeHub,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val sessions: DeviceSessionStatus,
) {
    @Transactional(readOnly = true)
    fun subscribe(
        topics: String?,
        lastEventId: String?,
        tokenExpiresAt: Instant?,
        sink: StreamSink,
    ): UUID {
        val requested = topicsOf(topics)
        val actor = currentActor.current()
        if (tokenExpiresAt == null || sessions.isRevoked(actor.deviceSessionId)) throw sessionEnded()
        val grants = requested.associateWith { authorization.require(it.permission).scope }
        return hub.connect(StreamSubscription(actor, grants, tokenExpiresAt), lastEventId, sink)
    }

    /** A resposta terminou (concluída, com erro ou no tempo limite): a conexão sai do canal. */
    fun release(connectionId: UUID) = hub.release(connectionId)

    private fun topicsOf(topics: String?): List<RealtimeTopic> {
        val names =
            topics
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        if (names.isEmpty()) {
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                "Informe ao menos um tópico.",
                listOf(FieldViolation(TOPICS, FieldViolationCodes.REQUIRED, "Informe ao menos um tópico.")),
            )
        }
        val unknown = names.filter { hub.topic(it) == null }
        if (unknown.isNotEmpty()) {
            val message = "Tópico desconhecido: ${unknown.joinToString().take(MAX_ECHO)}."
            throw InvalidInputException(
                ErrorCodes.UNKNOWN_STREAM_TOPIC,
                message,
                listOf(FieldViolation(TOPICS, ErrorCodes.UNKNOWN_STREAM_TOPIC.value, message)),
            )
        }
        return names.map { checkNotNull(hub.topic(it)) }
    }

    private fun sessionEnded() =
        UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, "Sessão encerrada. Entre de novo para acompanhar.")

    private companion object {
        const val TOPICS = "topics"
        const val MAX_ECHO = 120
    }
}
