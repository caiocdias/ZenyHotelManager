package com.zeny.hotel.endofday.infrastructure

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.ExtensibleLockProvider
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.support.KeepAliveLockProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Onde o motor do fechamento roda (M6 E10): a trava `end-of-day-run` com *keep-alive* e a thread própria.
 *
 * - [endOfDayLockingTaskExecutor]: `DefaultLockingTaskExecutor(KeepAliveLockProvider(…))` sobre o `LockProvider` do
 *   `shared` (o bean do `SchedulingConfiguration`, na tabela `shared.shedlock` e com a hora do banco). É um
 *   acoplamento consciente a esse bean, pelo tipo da biblioteca, não por uma API do `shared`: a trava do fechamento é
 *   a mesma tabela dos jobs. O *keep-alive* renova a trava a cada metade do `lockAtMostFor` enquanto a execução anda,
 *   então uma execução mais longa que o prazo não perde a trava; se o processo cai, a trava vence no prazo. **O
 *   `KeepAliveLockProvider` recusa `lockAtMostFor` abaixo de 30 s** (`IllegalArgumentException` na tentativa de
 *   travar; mínimo fixo do construtor público na `shedlock-core` 7.10.1).
 * - [endOfDayExecutor]: **uma** thread, `end-of-day`, criada já no boot (não herda nada da requisição que a usar
 *   primeiro, nem o contexto de segurança): tudo o que as etapas fazem é do ator sistema. Fechada no `destroy`
 *   (`close`), que espera a etapa em curso terminar; um processo morto antes disso deixa a execução `RUNNING`, que a
 *   próxima execução retoma.
 *
 * Os executores são beans com `defaultCandidate = false`: um `Executor` ou `ScheduledExecutorService` comum no
 * contexto desligaria o `applicationTaskExecutor` (ouvintes assíncronos do Modulith) e o agendador dos jobs do Spring
 * Boot, que só se instalam sem outro bean desses tipos. Quem os usa pede pelo nome (`@Qualifier`).
 */
@Configuration(proxyBeanMethods = false)
class EndOfDayExecutionConfiguration {
    /** A thread que renova a trava do fechamento. */
    @Bean(defaultCandidate = false, destroyMethod = "shutdownNow")
    fun endOfDayKeepAliveScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            Thread
                .ofPlatform()
                .daemon()
                .name("end-of-day-keep-alive")
                .factory(),
        )

    @Bean
    fun endOfDayLockingTaskExecutor(
        lockProvider: LockProvider,
        @Qualifier("endOfDayKeepAliveScheduler") keepAlive: ScheduledExecutorService,
    ): LockingTaskExecutor {
        check(lockProvider is ExtensibleLockProvider) {
            "O keep-alive do fechamento exige um LockProvider que renove a trava (ExtensibleLockProvider)."
        }
        return DefaultLockingTaskExecutor(KeepAliveLockProvider(lockProvider, keepAlive))
    }

    /** A thread única do motor do fechamento. */
    @Bean(defaultCandidate = false, destroyMethod = "close")
    fun endOfDayExecutor(): ExecutorService =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            Thread
                .ofPlatform()
                .daemon()
                .name("end-of-day")
                .factory(),
        ).apply { prestartCoreThread() }
}
