package com.zeny.hotel.testsupport

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import java.sql.DriverManager

/**
 * Base dos testes de integração do fechamento do dia (M6 E10, H10): igual a [IntegrationTest] (mesmas propriedades,
 * profile, `MockMvc`, métricas e inicializadores), mas ligada a um banco próprio, [EndOfDayDatabase], dentro do mesmo
 * container ([PostgresContainer]).
 *
 * O motivo: o banco dos `@IntegrationTest` é compartilhado por todos os testes, e o fechamento real (da E11 em diante)
 * avança a data operacional e age sobre o hotel inteiro (hospedagens, contas, reservas, UHs). Rodado ali, ele
 * quebraria os testes que dependem da data e do estado que outros deixaram. Aqui o banco é compartilhado só entre os
 * testes desta anotação.
 *
 * **Regra do banco isolado:** todo teste termina com a data operacional corrente sem execução de fechamento ou com
 * execução `COMPLETED` (use `EndOfDayTestRuns.settle()` no `finally`), devolve o [MutableClock] ao original e desfaz o
 * que mudou na configuração do hotel (fuso, horário). Como o fechamento real age sobre tudo o que os testes anteriores
 * deixaram no banco, as asserções olham só os dados do próprio teste.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    properties = [
        TEST_MIGRATIONS,
        RESUBMISSION_JOB_DEFERRED,
        OUTBOX_PURGE_DEFERRED,
        FILES_PURGE_DEFERRED,
        GUESTS_REKEY_DEFERRED,
        INVENTORY_HORIZON_DEFERRED,
        PROVISIONAL_EXPIRY_DEFERRED,
        FNRH_SEND_DEFERRED,
        PRINTING_MAINTENANCE_DEFERRED,
    ],
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ContextConfiguration(
    initializers = [
        EndOfDayDatabaseInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
annotation class EndOfDayIntegrationTest

/**
 * O banco do fechamento do dia: criado uma vez por JVM no container compartilhado. Os papéis são do cluster, então o
 * papel da aplicação já existe; o Flyway migra o banco como dono e as migrações dão os privilégios. Só o que o script
 * `docker/postgres/initdb/10-create-app-role.sh` faz por banco é repetido aqui.
 */
object EndOfDayDatabase {
    const val NAME = "zeny_endofday"

    val jdbcUrl: String by lazy {
        val postgres = PostgresContainer.instance
        val baseUrl = postgres.jdbcUrl
        val url = baseUrl.substringBeforeLast('/') + "/" + NAME
        DriverManager.getConnection(baseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $NAME") }
        }
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("REVOKE TEMPORARY ON DATABASE $NAME FROM PUBLIC")
                statement.execute("GRANT CONNECT ON DATABASE $NAME TO ${PostgresContainer.APP_USERNAME}")
                statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
            }
        }
        url
    }
}

/** Como [PostgresInitializer], mas apontando para o [EndOfDayDatabase]. */
class EndOfDayDatabaseInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertyValues
            .of(
                "spring.datasource.url=${EndOfDayDatabase.jdbcUrl}",
                "spring.datasource.username=${PostgresContainer.APP_USERNAME}",
                "spring.datasource.password=${PostgresContainer.appPassword}",
                "spring.flyway.user=${PostgresContainer.ownerUsername}",
                "spring.flyway.password=${PostgresContainer.ownerPassword}",
            ).applyTo(context)
    }
}
