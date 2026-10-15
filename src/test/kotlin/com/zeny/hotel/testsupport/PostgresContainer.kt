package com.zeny.hotel.testsupport

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Um único PostgreSQL 18 (D4) para todos os testes de integração da JVM. O container sobe
 * na primeira vez que um contexto Spring o pede e é derrubado pelo Ryuk do Testcontainers
 * quando a JVM de testes termina.
 *
 * Tem os dois papéis da produção (H4): o usuário padrão do Testcontainers ([ownerUsername]) é o
 * dono dos schemas, usado pelo Flyway, e o papel da aplicação ([APP_USERNAME]) é criado pelo
 * mesmo script de inicialização do compose (`docker/postgres/initdb/`), com senha sorteada por
 * JVM. Os testes conectam com o papel da aplicação: só DML, sem DDL nem `UPDATE`/`DELETE` nas
 * tabelas append-only.
 */
object PostgresContainer {
    const val IMAGE = "postgres:18"
    const val APP_USERNAME = "zeny_app"

    private val INIT_SCRIPT = Path.of("docker", "postgres", "initdb", "10-create-app-role.sh")
    private const val EXECUTABLE = 0b111_101_101 // 0755
    private const val PASSWORD_BYTES = 18
    private const val MAX_CONNECTIONS = 400

    /** Senha do papel da aplicação, sorteada por JVM (nunca escrita no código). */
    val appPassword: String by lazy {
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes),
        )
    }

    val instance: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(IMAGE))
            .withCopyFileToContainer(
                MountableFile.forHostPath(INIT_SCRIPT, EXECUTABLE),
                "/docker-entrypoint-initdb.d/${INIT_SCRIPT.fileName}",
            ).withEnv("ZENY_DB_USERNAME", APP_USERNAME)
            .withEnv("ZENY_DB_PASSWORD", appPassword)
            // Cada contexto Spring em cache mantém o seu pool do Hikari (até 10 conexões); com os
            // contextos de todas as classes de teste, o padrão de 100 conexões do PostgreSQL acaba.
            // O fsync desligado é o padrão do Testcontainers, repetido porque o comando é trocado.
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=$MAX_CONNECTIONS")
            .also { it.start() }
    }

    /** O dono dos schemas (o usuário padrão do container), só para o Flyway e para testes de privilégio. */
    val ownerUsername: String get() = instance.username
    val ownerPassword: String get() = instance.password
}

/**
 * Aponta o datasource do contexto para o container compartilhado, com o papel da aplicação, e o
 * Flyway para o mesmo banco com o dono dos schemas. As propriedades entram acima do
 * `application.yaml`. É um inicializador, e não um bean `@ServiceConnection`, porque o Spring
 * Boot para o container de um bean quando o contexto fecha, e o teste de reinício abre e fecha
 * um segundo contexto sobre o mesmo banco.
 */
class PostgresInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val postgres = PostgresContainer.instance
        TestPropertyValues
            .of(
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${PostgresContainer.APP_USERNAME}",
                "spring.datasource.password=${PostgresContainer.appPassword}",
                "spring.flyway.user=${PostgresContainer.ownerUsername}",
                "spring.flyway.password=${PostgresContainer.ownerPassword}",
            ).applyTo(context)
    }
}
