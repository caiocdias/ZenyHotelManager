package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import org.slf4j.LoggerFactory

/**
 * Apoio de teste, não um bean: o `SerproFnrhGatewayAttackTest` (do testador) ainda cria por aqui um registro que só
 * escreve a linha de log, como fazia a implementação provisória da Task 4, apagada de `src/main` na Task 5 (agora o
 * registro de verdade é o `JdbcFnrhIntegrationLog`). Sem `@Configuration`, a varredura não o encontra.
 */
class ProvisionalFnrhIntegrationLogConfiguration {
    fun provisionalFnrhIntegrationLog(): FnrhIntegrationLog =
        FnrhIntegrationLog { call ->
            LoggerFactory
                .getLogger(ProvisionalFnrhIntegrationLogConfiguration::class.java)
                .info(
                    "FNRH {} {} {} HTTP {} {} ms ref {} remoto {}",
                    call.operation,
                    call.environment,
                    call.outcome,
                    call.httpStatus ?: "-",
                    call.durationMs,
                    call.correlationRef ?: "-",
                    call.remoteId ?: "-",
                )
        }
}
