package com.zeny.hotel.events

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.modulith.events.ApplicationModuleListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Evento de exemplo dos testes do outbox. */
data class ProbeEvent(
    val id: UUID,
    val note: String,
)

/**
 * Estado compartilhado pelos listeners de todos os contextos da JVM de testes. É um objeto
 * global para que o contexto "reiniciado" do teste de reprocessamento no boot veja as mesmas
 * falhas programadas e entregas.
 */
object ProbeState {
    private val failuresLeft = ConcurrentHashMap<UUID, AtomicInteger>()
    private val deliveries = ConcurrentHashMap<UUID, AtomicInteger>()
    private val invocations = ConcurrentHashMap<UUID, AtomicInteger>()

    fun failNext(
        id: UUID,
        times: Int,
    ) {
        failuresLeft[id] = AtomicInteger(times)
    }

    /** O listener falha em toda entrega do evento, até [failNext] com `times = 0`. */
    fun failAlways(id: UUID) = failNext(id, Int.MAX_VALUE)

    /** Entregas que deram certo. */
    fun deliveriesOf(id: UUID): Int = deliveries[id]?.get() ?: 0

    /** Vezes que o listener rodou para o evento, com falha ou não. */
    fun invocationsOf(id: UUID): Int = invocations[id]?.get() ?: 0

    fun reset() {
        failuresLeft.clear()
        deliveries.clear()
        invocations.clear()
    }

    /** Falha enquanto houver falhas programadas para o evento; depois registra a entrega. */
    fun receive(event: ProbeEvent) {
        invocations.computeIfAbsent(event.id) { AtomicInteger() }.incrementAndGet()
        val left = failuresLeft[event.id]
        if (left != null && left.getAndDecrement() > 0) {
            error("Falha simulada do listener para ${event.id}")
        }
        deliveries.computeIfAbsent(event.id) { AtomicInteger() }.incrementAndGet()
    }
}

/** Listener assíncrono e transacional, como os dos módulos (ARCHITECTURE §5, passo 5). */
open class ProbeListener {
    @ApplicationModuleListener
    open fun on(event: ProbeEvent) = ProbeState.receive(event)
}

@TestConfiguration(proxyBeanMethods = false)
class ProbeListenerConfiguration {
    @Bean
    fun probeListener() = ProbeListener()
}
