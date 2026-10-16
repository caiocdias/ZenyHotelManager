package com.zeny.hotel.shared.application.realtime

import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.DeviceSessionsRevoked
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.RealtimeTopic
import com.zeny.hotel.shared.UnauthenticatedException
import com.zeny.hotel.shared.UuidV7
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * O broadcaster em processo do canal de tempo real (ARCHITECTURE §10, D10, H5 e H8 do M2), uma única
 * instância do servidor:
 *
 * - **Tópicos:** os beans [RealtimeTopic] dos módulos, conferidos no boot (nome único, permissão declarada
 *   por algum [ModulePermissions]).
 * - **Publicação** ([publish]): registrada na transação corrente e despachada só no `afterCommit`; rollback
 *   não envia nada. O despacho numera a mensagem (sequência global, crescente), guarda-a no buffer circular
 *   do tópico e a enfileira nas conexões inscritas no tópico cujo escopo a alcança (`ALL`, ou `OWN` com o
 *   usuário entre os donos).
 * - **Ids dos eventos:** `<época>-<sequência>`, com a época sorteada a cada boot: o `Last-Event-ID` de outro
 *   boot, ilegível, à frente da sequência ou de uma mensagem que já saiu do buffer de algum tópico inscrito
 *   recebe `reset` (o cliente recarrega); dentro do buffer, as mensagens perdidas são repostas em ordem.
 *   Toda conexão começa por `ready` (ou `reset`) com a posição corrente, para o cliente reconectar dela.
 * - **Envio:** cada conexão tem a sua fila, esvaziada por uma *virtual thread* de cada vez, então quem
 *   publica (no commit de uma requisição) nunca espera a rede de um cliente lento; fila longa demais
 *   encerra a conexão (o cliente volta com o `Last-Event-ID`).
 * - **Encerramento:** no `exp` do access token, na revogação da sessão ([DeviceSessionsRevoked], depois do
 *   commit), ao passar do limite de conexões da sessão (sai a mais antiga), na falha de escrita (o
 *   *heartbeat* periódico a provoca) e na parada do servidor, antes do *graceful shutdown* do servidor web.
 *
 * Nada aqui segura conexão de banco nem transação: a inscrição é autorizada antes ([StreamService]).
 */
@Service
@Suppress("TooManyFunctions") // as operações do canal e o ciclo de vida (SmartLifecycle)
class RealtimeHub(
    topics: List<RealtimeTopic>,
    modulePermissions: List<ModulePermissions>,
    private val properties: RealtimeProperties,
    private val clock: Clock,
) : RealtimePublisher,
    SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    private val topicsByName: Map<String, RealtimeTopic> = catalog(topics, modulePermissions)
    private val lock = ReentrantLock()
    private val history = RealtimeHistory(topicsByName.keys, properties.bufferSize)
    private val connections = LinkedHashMap<UUID, Connection>()
    private val recentlyRevoked = HashMap<DeviceSessionId, Instant>()
    private val maxQueuedFrames = properties.bufferSize * topicsByName.size.coerceAtLeast(1) + QUEUE_SLACK

    // Recriados a cada start(): o contexto pode parar e voltar (o cache de contextos dos testes os pausa).
    @Volatile private var sender: ExecutorService = newSender()

    @Volatile private var timer: ScheduledExecutorService = newTimer()

    @Volatile private var running = false

    /** O tópico registrado com este nome, ou `null`. */
    fun topic(name: String): RealtimeTopic? = topicsByName[name]

    /** Conexões abertas (métrica `zeny.realtime.connections`). */
    fun connectionCount(): Int = lock.withLock { connections.size }

    override fun publish(
        topic: RealtimeTopic,
        message: RealtimeMessage,
    ) {
        require(topicsByName[topic.name] == topic) { "Tópico de tempo real não registrado: $topic" }
        check(
            TransactionSynchronizationManager.isSynchronizationActive() &&
                TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "Publique no canal de tempo real dentro da transação de escrita do caso de uso" }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = log.safely("despachar a mensagem") { dispatch(topic, message) }
            },
        )
    }

    /**
     * Registra a conexão já autorizada e enfileira, em ordem, a reposição pedida pelo [lastEventId] (ou o
     * `reset`) e o `ready` com a posição corrente; dali em diante, as mensagens ao vivo. Sessão revogada
     * enquanto a inscrição era autorizada ou token já vencido: `401`. Devolve o id da conexão.
     */
    fun connect(
        subscription: StreamSubscription,
        lastEventId: String?,
        sink: StreamSink,
    ): UUID {
        val delay = Duration.between(clock.instant(), subscription.expiresAt)
        if (delay.isNegative || delay.isZero) throw sessionEnded()
        val connection = Connection(UuidV7.generate(), subscription, sink)
        val replaced =
            lock.withLock {
                if (subscription.actor.deviceSessionId in recentlyRevoked) throw sessionEnded()
                val sameSession = connections.values.filter { it.session == connection.session }
                connections[connection.id] = connection
                initialFrames(connection, lastEventId).forEach(connection::enqueue)
                sameSession.take((sameSession.size + 1 - properties.maxConnectionsPerSession).coerceAtLeast(0))
            }
        replaced.forEach { close(it, StreamCloseReason.REPLACED) }
        connection.expiry =
            timer.schedule(
                { close(connection, StreamCloseReason.TOKEN_EXPIRED) },
                delay.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        log.debug("Conexão de tempo real {} aberta em {}", connection.id, subscription.grants.keys)
        return connection.id
    }

    /** A web avisa que a resposta terminou (concluída, com erro ou no tempo limite): a conexão sai. */
    fun release(connectionId: UUID) {
        lock.withLock { connections[connectionId] }?.let {
            close(
                it,
                StreamCloseReason.DISCONNECTED,
                completeSink = false,
            )
        }
    }

    /**
     * Sessões revogadas (H8): depois do commit da revogação, as conexões delas são encerradas, e por alguns
     * minutos uma inscrição delas é recusada (cobre a inscrição autorizada antes do commit e registrada
     * depois). Fora de uma transação, na hora.
     */
    @EventListener
    fun on(event: DeviceSessionsRevoked) {
        val sessions = event.sessionIds.map(::DeviceSessionId).toSet()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = log.safely("encerrar as sessões revogadas") { closeSessions(sessions) }
                },
            )
        } else {
            closeSessions(sessions)
        }
    }

    override fun start() {
        if (sender.isShutdown) sender = newSender()
        if (timer.isShutdown) timer = newTimer()
        val interval = properties.heartbeatInterval.toMillis()
        timer.scheduleAtFixedRate(
            { log.safely("enviar o heartbeat") { heartbeat() } },
            interval,
            interval,
            TimeUnit.MILLISECONDS,
        )
        running = true
    }

    /** Antes do *graceful shutdown* do servidor web (fase maior para quem para primeiro): encerra as conexões. */
    override fun stop() {
        running = false
        lock.withLock { connections.values.toList() }.forEach { close(it, StreamCloseReason.SHUTDOWN) }
        timer.shutdownNow()
        sender.shutdown()
        sender.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun isRunning(): Boolean = running

    private fun dispatch(
        topic: RealtimeTopic,
        message: RealtimeMessage,
    ) = lock.withLock {
        val numbered = history.append(topic, message)
        connections.values.filter { it.receives(numbered) }.forEach { it.enqueue(numbered.frame) }
    }

    /** O começo da conexão: a reposição pedida e `ready`, ou só `reset`, com a posição corrente no id. */
    private fun initialFrames(
        connection: Connection,
        lastEventId: String?,
    ): List<StreamFrame> {
        val position = StreamFrame.Event(history.position, READY, EMPTY)
        val missed = if (lastEventId == null) emptyList() else history.after(connection.topics, lastEventId)
        return missed?.filter(connection::receives)?.map { it.frame }?.plus(position)
            ?: listOf(position.copy(name = RESET))
    }

    private fun closeSessions(sessions: Set<DeviceSessionId>) {
        val now = clock.instant()
        val closing =
            lock.withLock {
                recentlyRevoked.entries.removeIf { it.value.isBefore(now.minus(REVOKED_MEMORY)) }
                sessions.forEach { recentlyRevoked[it] = now }
                connections.values.filter { it.session in sessions }
            }
        closing.forEach { close(it, StreamCloseReason.SESSION_REVOKED) }
    }

    private fun heartbeat() {
        val now = clock.instant()
        val open = lock.withLock { connections.values.toList() }
        open.forEach { if (!now.isBefore(it.subscription.expiresAt)) close(it, StreamCloseReason.TOKEN_EXPIRED) }
        open.forEach { it.enqueue(HEARTBEAT) }
    }

    /** Tira a conexão do registro e, salvo quando a própria resposta já terminou, a encerra. */
    private fun close(
        connection: Connection,
        reason: StreamCloseReason,
        completeSink: Boolean = true,
    ) {
        if (!connection.closed.compareAndSet(false, true)) return
        lock.withLock { connections.remove(connection.id) }
        connection.expiry?.cancel(false)
        log.debug("Conexão de tempo real {} encerrada: {}", connection.id, reason)
        if (!completeSink) return
        // Numa virtual thread: encerrar espera a escrita em curso, e quem fecha (o commit de uma requisição,
        // o timer) não pode ficar preso à rede de um cliente lento.
        val complete = { log.safely("encerrar a resposta") { connection.sink.complete() } }
        try {
            sender.execute(complete)
        } catch (_: RejectedExecutionException) {
            complete()
        }
    }

    /** Uma conexão aberta, com a fila própria de quadros a enviar. */
    private inner class Connection(
        val id: UUID,
        val subscription: StreamSubscription,
        val sink: StreamSink,
    ) {
        val session: DeviceSessionId = subscription.actor.deviceSessionId
        val topics: Set<RealtimeTopic> = subscription.grants.keys
        val closed = AtomicBoolean(false)

        @Volatile var expiry: ScheduledFuture<*>? = null

        private val queue = ConcurrentLinkedQueue<StreamFrame>()
        private val queued = AtomicInteger()
        private val draining = AtomicBoolean(false)

        fun receives(message: NumberedMessage): Boolean =
            when (subscription.grants[message.topic]) {
                PermissionScope.ALL -> true
                PermissionScope.OWN -> subscription.actor.userId in message.message.owners
                null -> false
            }

        fun enqueue(frame: StreamFrame) {
            if (closed.get()) return
            if (queued.incrementAndGet() > maxQueuedFrames) {
                close(this, StreamCloseReason.OVERFLOW)
                return
            }
            queue.add(frame)
            scheduleDrain()
        }

        private fun scheduleDrain() {
            if (!draining.compareAndSet(false, true)) return
            try {
                sender.execute(::drain)
            } catch (_: RejectedExecutionException) {
                draining.set(false)
            }
        }

        private fun drain() {
            try {
                while (!closed.get()) {
                    val frame = queue.poll() ?: break
                    queued.decrementAndGet()
                    sink.send(frame)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // qualquer falha de escrita: o cliente se foi
            ) {
                log.debug("Falha ao escrever na conexão de tempo real {}: {}", id, e.toString())
                close(this, StreamCloseReason.DISCONNECTED)
            } finally {
                draining.set(false)
                if (!closed.get() && queue.isNotEmpty()) scheduleDrain()
            }
        }
    }

    private companion object {
        fun newSender(): ExecutorService =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("realtime-", 0).factory())

        fun newTimer(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                Thread
                    .ofPlatform()
                    .daemon()
                    .name("realtime-timer")
                    .factory(),
            )

        const val READY = "ready"
        const val RESET = "reset"
        const val EMPTY = "{}"
        const val QUEUE_SLACK = 64
        val HEARTBEAT = StreamFrame.Comment("heartbeat")
        val REVOKED_MEMORY: Duration = Duration.ofMinutes(10)
        val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(2)

        fun catalog(
            topics: List<RealtimeTopic>,
            modulePermissions: List<ModulePermissions>,
        ): Map<String, RealtimeTopic> {
            val declared = modulePermissions.flatMap { module -> module.permissions.map { it.code } }.toSet()
            val duplicated = topics.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "Tópicos de tempo real repetidos: $duplicated" }
            topics.forEach {
                require(it.personal || it.permission in declared) {
                    "Tópico ${it.name}: permissão ${it.permission} não declarada"
                }
            }
            return topics.associateBy { it.name }
        }
    }
}

private fun sessionEnded() =
    UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, "Autentique-se de novo para acompanhar.")

/** Executa [action] sem deixar a falha escapar: o canal nunca derruba o commit, o timer nem o envio. */
private fun Logger.safely(
    what: String,
    action: () -> Unit,
) {
    try {
        action()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception, // qualquer falha vai para o log, e o canal segue
    ) {
        warn("Falha ao {} no canal de tempo real", what, e)
    }
}
