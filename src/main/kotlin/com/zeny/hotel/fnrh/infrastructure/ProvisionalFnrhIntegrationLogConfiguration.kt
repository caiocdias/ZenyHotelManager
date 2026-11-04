package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * PROVISÓRIO, a ser substituído na Task 5 da E07 (registro de integração em `fnrh.integration_call`): só escreve a
 * linha de log, para o contexto subir enquanto o registro de verdade não existe. Sem texto livre: a operação, o
 * resultado, o status HTTP, a duração e as referências opacas.
 */
@Configuration(proxyBeanMethods = false)
class ProvisionalFnrhIntegrationLogConfiguration {
    @Bean
    @ConditionalOnMissingBean(FnrhIntegrationLog::class)
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
