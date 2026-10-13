package com.zeny.hotel.testsupport

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Um único PostgreSQL 18 (D4) para todos os testes de integração da JVM. O container sobe
 * na primeira vez que um contexto Spring o pede e é derrubado pelo Ryuk do Testcontainers
 * quando a JVM de testes termina.
 */
object PostgresContainer {
    const val IMAGE = "postgres:18"

    val instance: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(IMAGE)).also { it.start() }
    }
}

/**
 * Aponta o datasource do contexto para o container compartilhado. As propriedades entram
 * acima do `application.yaml`. É um inicializador, e não um bean `@ServiceConnection`, porque
 * o Spring Boot para o container de um bean quando o contexto fecha, e o teste de reinício
 * abre e fecha um segundo contexto sobre o mesmo banco.
 */
class PostgresInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val postgres = PostgresContainer.instance
        TestPropertyValues
            .of(
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.password=${postgres.password}",
            ).applyTo(context)
    }
}
