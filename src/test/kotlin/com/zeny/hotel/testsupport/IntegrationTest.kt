package com.zeny.hotel.testsupport

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

/**
 * Base dos testes de integração: contexto completo com o profile `local`, ligado ao
 * PostgreSQL 18 e ao MinIO ([MinioContainer]) do Testcontainers, a uma chave de cifragem e a
 * chaves JWT sorteadas por JVM e ao administrador inicial dos testes ([TestAdmin]). As classes com
 * esta anotação e sem configuração extra compartilham o mesmo contexto (cache do Spring);
 * todas compartilham o mesmo container. O contexto é web (servlet simulado) e expõe um
 * `MockMvc` com a cadeia do Spring Security, para os testes de API. As métricas ficam ligadas
 * como em produção (`@AutoConfigureMetrics`), com o registro do Prometheus.
 *
 * As propriedades de teste acrescentam as migrações só de teste (`db/testmigration`) e adiam
 * os jobs de reprocessamento e de expurgo do outbox, para que os testes os disparem quando
 * quiserem. Para autenticar uma requisição do `MockMvc` com um usuário de id conhecido, use [asUser].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(properties = [TEST_MIGRATIONS, RESUBMISSION_JOB_DEFERRED, OUTBOX_PURGE_DEFERRED])
@ActiveProfiles("local")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
annotation class IntegrationTest

/** Migrações de produção mais as tabelas de apoio usadas só pelos testes. */
const val TEST_MIGRATIONS = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration"

/** O job de reprocessamento só roda quando o teste o chama. */
const val RESUBMISSION_JOB_DEFERRED = "zeny.events.resubmission.initial-delay=PT24H"

/**
 * O expurgo das publicações concluídas só roda quando o teste o chama: um contexto em cache com o relógio
 * adiantado ([MutableClock]) apagaria, no banco compartilhado, as publicações que outro teste espera ver.
 */
const val OUTBOX_PURGE_DEFERRED = "zeny.events.purge.initial-delay=PT24H"
