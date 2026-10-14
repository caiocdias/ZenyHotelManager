package com.zeny.hotel.testsupport

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

/**
 * Base dos testes de integração: contexto completo com o profile `local`, ligado ao
 * PostgreSQL 18 do Testcontainers e a uma chave de cifragem sorteada por JVM. As classes com
 * esta anotação e sem configuração extra compartilham o mesmo contexto (cache do Spring);
 * todas compartilham o mesmo container. O contexto é web (servlet simulado) e expõe um
 * `MockMvc` com a cadeia do Spring Security, para os testes de API.
 *
 * As propriedades de teste acrescentam as migrações só de teste (`db/testmigration`) e adiam
 * o job de reprocessamento do outbox, para que os testes o disparem quando quiserem.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(properties = [TEST_MIGRATIONS, RESUBMISSION_JOB_DEFERRED])
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = [PostgresInitializer::class, TestEncryptionKeysInitializer::class])
annotation class IntegrationTest

/** Migrações de produção mais as tabelas de apoio usadas só pelos testes. */
const val TEST_MIGRATIONS = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration"

/** O job de reprocessamento só roda quando o teste o chama. */
const val RESUBMISSION_JOB_DEFERRED = "zeny.events.resubmission.initial-delay=PT24H"
