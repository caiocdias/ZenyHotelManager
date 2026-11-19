package com.zeny.hotel.shared.infrastructure.scheduling

import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.event.ApplicationPreparedEvent
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.Ordered

/**
 * Instala o [ShedLockLogFilter] no contexto do Logback. Um `TurboFilter` registrado no contexto some quando o Boot
 * reinicializa o logging (o `LoggingApplicationListener` zera o contexto ao ler o `Environment`), e o
 * `logback-spring.xml` não serve: o `console-appender.xml` do Boot só sabe o padrão em texto e perderia a saída
 * estruturada (ECS) do `prod`.
 * Por isso o registro é repetido depois da inicialização do logging, em dois eventos do Boot (idempotente), como
 * listener de `META-INF/spring.factories`. Sem Logback no classpath, não faz nada.
 */
class ShedLockLogFilterInstaller :
    ApplicationListener<ApplicationEvent>,
    Ordered {
    // Depois do LoggingApplicationListener (HIGHEST_PRECEDENCE + 20), que reinicializa o logging.
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun onApplicationEvent(event: ApplicationEvent) {
        if (event is ApplicationEnvironmentPreparedEvent || event is ApplicationPreparedEvent) install()
    }

    private fun install() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        if (context.turboFilterList.none { it is ShedLockLogFilter }) {
            context.addTurboFilter(ShedLockLogFilter().apply { start() })
        }
    }
}
