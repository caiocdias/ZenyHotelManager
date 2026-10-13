package com.zeny.hotel.shared.infrastructure.scheduling

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Jobs agendados (ARCHITECTURE §13): `@Scheduled` com trava do ShedLock na tabela
 * `shared.shedlock`, para que um job nunca rode em dobro, nem depois de um restart no meio da
 * execução. Todo job leva `@SchedulerLock(name = ...)` com nome único.
 *
 * A trava usa a hora do banco (`usingDbTime()`), em UTC, e não o relógio da JVM.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfiguration {
    @Bean
    fun lockProvider(jdbcTemplate: JdbcTemplate): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName(LOCK_TABLE)
                .usingDbTime()
                .build(),
        )

    private companion object {
        const val LOCK_TABLE = "shared.shedlock"
    }
}
