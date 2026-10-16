package com.zeny.hotel.shared.application.realtime

import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimeTopic
import java.security.SecureRandom
import java.util.HexFormat

/** Uma mensagem numerada pelo [RealtimeHistory], com o quadro SSE pronto. */
class NumberedMessage(
    val sequence: Long,
    val topic: RealtimeTopic,
    val message: RealtimeMessage,
    val frame: StreamFrame.Event,
)

/**
 * A numeração e os buffers circulares do canal de tempo real (H5 do M2): cada mensagem recebe a sequência
 * global seguinte e fica no buffer do tópico até sair pelo tamanho. O id de evento é `<época>-<sequência>`,
 * com a época sorteada a cada boot. Não é thread-safe: o [RealtimeHub] o usa sob a sua trava.
 */
class RealtimeHistory(
    topicNames: Set<String>,
    private val bufferSize: Int,
    val epoch: String = newEpoch(),
) {
    private val buffers = topicNames.associateWith { ArrayDeque<NumberedMessage>() }
    private val evictedUpTo = topicNames.associateWith { 0L }.toMutableMap()
    private var lastSequence = 0L

    /** A posição corrente (o id da última mensagem numerada; `<época>-0` antes da primeira). */
    val position: String get() = eventId(lastSequence)

    fun append(
        topic: RealtimeTopic,
        message: RealtimeMessage,
    ): NumberedMessage {
        val sequence = ++lastSequence
        // O payload mínimo (ARCHITECTURE §10): só `type`, `id` e `version`; o tipo já é seguro para JSON.
        val data = """{"type":"${message.type}","id":"${message.id}","version":${message.version}}"""
        val numbered = NumberedMessage(sequence, topic, message, StreamFrame.Event(eventId(sequence), topic.name, data))
        val buffer = buffers.getValue(topic.name)
        buffer.addLast(numbered)
        while (buffer.size > bufferSize) evictedUpTo[topic.name] = buffer.removeFirst().sequence
        return numbered
    }

    /**
     * As mensagens dos [topics] depois de [lastEventId], em ordem; `null` (o cliente recebe `reset`) se o id é
     * de outro boot, ilegível, à frente da posição ou anterior a uma mensagem que já saiu do buffer de algum
     * dos tópicos.
     */
    fun after(
        topics: Collection<RealtimeTopic>,
        lastEventId: String,
    ): List<NumberedMessage>? {
        val after = sequenceOf(lastEventId)
        val names = topics.map { it.name }
        if (after == null || names.any { evictedUpTo.getValue(it) > after }) return null
        return names.flatMap { name -> buffers.getValue(name).filter { it.sequence > after } }.sortedBy { it.sequence }
    }

    private fun sequenceOf(lastEventId: String): Long? {
        val parts = lastEventId.trim().split(SEPARATOR)
        return parts
            .takeIf { it.size == 2 && it[0] == epoch }
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it in 0..lastSequence }
    }

    private fun eventId(sequence: Long) = "$epoch$SEPARATOR$sequence"

    private companion object {
        const val SEPARATOR = "-"
        const val EPOCH_BYTES = 6

        /** Época do boot: 12 dígitos hexadecimais aleatórios (sem hífen). */
        fun newEpoch(): String = HexFormat.of().formatHex(ByteArray(EPOCH_BYTES).also(SecureRandom()::nextBytes))
    }
}
