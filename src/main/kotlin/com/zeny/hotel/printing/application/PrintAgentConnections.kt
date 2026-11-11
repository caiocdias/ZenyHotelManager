package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.infrastructure.PrintingProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** O tipo da conexão: o stream (no máximo um por agente) e o long-poll (até `max-connections-per-agent`). */
enum class ConnectionKind { STREAM, LONG_POLL }

/**
 * Uma conexão aberta de um agente (long-poll ou stream). Termina por [close] (a resposta acabou), por despejo (passou
 * do limite do agente) ou pela parada do servidor; [isDone] vale nos três casos e quem espera acorda na hora.
 */
class AgentConnection internal constructor(
    val agentId: UUID,
    val kind: ConnectionKind,
    private val onEvict: () -> Unit,
    private val release: (AgentConnection) -> Unit,
) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val evictionClaimed = AtomicBoolean(false)

    @Volatile var evicted: Boolean = false
        private set

    val isDone: Boolean get() = done.count == 0L

    /** Espera até [timeout] o fim da conexão; devolve `true` se ela já terminou. É a espera do laço de entrega. */
    fun awaitDone(timeout: Duration): Boolean =
        try {
            done.await(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }

    /**
     * Despeja a conexão. Atômico: só a primeira chamada age (o despejo pelo limite e o `stop()` podem correr juntos) e
     * só ela roda o `onEvict`, uma vez. Uma conexão já fechada por [close] também não é despejada.
     */
    internal fun evict() {
        if (done.count == 0L || !evictionClaimed.compareAndSet(false, true)) return
        evicted = true
        done.countDown()
        onEvict()
    }

    /** A resposta terminou: sai do registro. Repetido, não faz nada. */
    override fun close() {
        done.countDown()
        release(this)
    }
}

/**
 * O registro das conexões abertas por agente (D-E10-6), no modelo do `RealtimeHub`: por agente,
 * no máximo um stream (o novo despeja o anterior) e `zeny.printing.max-connections-per-agent` long-polls (o novo
 * despeja o mais antigo), e todas encerradas na parada do
 * servidor, antes do *graceful shutdown* do servidor web. Em memória: uma instância do servidor.
 */
@Component
class PrintAgentConnections(
    private val properties: PrintingProperties,
) : SmartLifecycle {
    private val lock = ReentrantLock()
    private val byAgent = HashMap<UUID, ArrayDeque<AgentConnection>>()

    @Volatile private var running = false

    /**
     * Registra uma conexão do [agentId]. Acima do limite do [kind], as mais antigas do mesmo tipo
     * são despejadas (acordam e respondem na hora);
     * com o servidor parando, a conexão já nasce encerrada. [onEvict] roda uma vez, no despejo ou na parada.
     */
    fun open(
        agentId: UUID,
        kind: ConnectionKind = ConnectionKind.LONG_POLL,
        onEvict: () -> Unit = {},
    ): AgentConnection {
        val connection = AgentConnection(agentId, kind, onEvict, ::remove)
        val evicted =
            lock.withLock {
                val queue = byAgent.getOrPut(agentId) { ArrayDeque() }
                queue.addLast(connection)
                val limit = if (kind == ConnectionKind.STREAM) 1 else properties.maxConnectionsPerAgent
                val sameKind = queue.filter { it.kind == kind }
                sameKind.take((sameKind.size - limit).coerceAtLeast(0)).also { queue.removeAll(it.toSet()) }
            }
        evicted.forEach(AgentConnection::evict)
        if (!running) {
            remove(connection)
            connection.evict()
        }
        return connection
    }

    /** Quantas conexões o [agentId] mantém abertas. */
    fun openCount(agentId: UUID): Int = lock.withLock { byAgent[agentId]?.size ?: 0 }

    private fun remove(connection: AgentConnection) =
        lock.withLock {
            val queue = byAgent[connection.agentId] ?: return@withLock
            queue.remove(connection)
            if (queue.isEmpty()) byAgent.remove(connection.agentId)
        }

    override fun start() {
        running = true
    }

    /** Antes do *graceful shutdown* do servidor web: encerra todas as conexões. */
    override fun stop() {
        running = false
        val open = lock.withLock { byAgent.values.flatten().also { byAgent.clear() } }
        open.forEach(AgentConnection::evict)
    }

    override fun isRunning(): Boolean = running
}
