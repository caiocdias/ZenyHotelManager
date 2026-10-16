package com.zeny.hotel.shared.infrastructure.realtime

import com.zeny.hotel.shared.application.realtime.RealtimeHub
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Conexões abertas no canal de tempo real (`zeny_realtime_connections` no Prometheus, ARCHITECTURE §16): o
 * número que cresce sem voltar denuncia conexões que vazam.
 */
@Component
class RealtimeMetrics(
    private val hub: RealtimeHub,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("zeny.realtime.connections") { hub.connectionCount().toDouble() }
            .description("Conexões abertas no canal de tempo real (GET /api/v1/stream)")
            .register(registry)
    }
}
