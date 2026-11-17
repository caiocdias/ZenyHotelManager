package com.zeny.hotel.shared.infrastructure.scheduling

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
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
 *
 * O agendador é o [taskScheduler], um [JobErrorLoggingTaskScheduler] que loga a falha de qualquer job só pelas classes
 * (§13 e §16). Ele é montado pelo builder do Boot, que aplica o `spring.task.scheduling.*` (pool, prefixo das threads,
 * espera no desligamento). É um bean com `defaultCandidate = false`, como os executores do fechamento: um agendador
 * comum é um `Executor` e desligaria o `applicationTaskExecutor` do Boot (o dos `@Async` e dos ouvintes do Modulith).
 * O `@Scheduled` o acha assim mesmo, por ser o único `TaskScheduler`, e o nome `taskScheduler` impede o Boot de montar
 * um segundo. Quem quiser injetá-lo pede pelo nome (`@Qualifier("taskScheduler")`).
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

    @Bean(defaultCandidate = false)
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder): JobErrorLoggingTaskScheduler =
        builder.configure(JobErrorLoggingTaskScheduler())

    private companion object {
        const val LOCK_TABLE = "shared.shedlock"
    }
}
