package com.zeny.hotel.shared.infrastructure.time

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Relógio da aplicação, em UTC (RN-01). Código que precisa da hora recebe um `Clock` por
 * injeção, em vez de chamar `Instant.now()`, para que os testes fixem o tempo; um teste troca
 * o relógio declarando o seu próprio bean `Clock`.
 *
 * A data operacional do hotel não sai daqui: ela só avança no fechamento do dia (`hotel`, M1).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
