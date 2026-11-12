package com.zeny.hotel.testsupport

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import javax.sql.DataSource

/** Configuração vazia: só força um segundo contexto Spring (fora do cache) sobre o banco isolado. */
@TestConfiguration
class EndOfDaySecondContextConfiguration

/**
 * Ataques ao apoio do fechamento (H10): privilégios do banco isolado iguais aos do banco compartilhado, o papel da
 * aplicação sem criação de nada e um segundo contexto na mesma JVM sobre o mesmo banco.
 */
@EndOfDayIntegrationTest
@Import(EndOfDaySecondContextConfiguration::class)
class EndOfDayDatabaseHardeningTest(
    @Autowired private val dataSource: DataSource,
) {
    @Test
    fun `a second context in the same JVM boots against the same isolated database`() {
        dataSource.connection.use { it.metaData.url shouldEndWith "/${EndOfDayDatabase.NAME}" }
        ownerConnection(null).use { connection ->
            val names =
                connection.queryColumn("SELECT datname FROM pg_database WHERE datname = '${EndOfDayDatabase.NAME}'")
            names shouldContainExactly listOf(EndOfDayDatabase.NAME)
        }
    }

    @Test
    fun `database level privileges of the isolated database equal those of the shared one`() {
        val shared = PostgresContainer.instance.databaseName
        val query = { db: String ->
            """
            SELECT coalesce(r.rolname, 'PUBLIC') || ':' || a.privilege_type
            FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a
            LEFT JOIN pg_roles r ON r.oid = a.grantee
            WHERE d.datname = '$db' ORDER BY 1
            """.trimIndent()
        }
        ownerConnection(null).use { connection ->
            val isolatedAcl = connection.queryColumn(query(EndOfDayDatabase.NAME))
            isolatedAcl shouldContainExactly connection.queryColumn(query(shared))
        }
    }

    @Test
    fun `public schema privileges of the isolated database equal those of the shared one`() {
        val query =
            """
            SELECT coalesce(r.rolname, 'PUBLIC') || ':' || a.privilege_type
            FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) a
            LEFT JOIN pg_roles r ON r.oid = a.grantee
            WHERE n.nspname = 'public' ORDER BY 1
            """.trimIndent()
        val shared = ownerConnection(PostgresContainer.instance.databaseName).use { it.queryColumn(query) }
        val isolated = ownerConnection(EndOfDayDatabase.NAME).use { it.queryColumn(query) }
        isolated shouldContainExactly shared
    }

    @Test
    fun `the app role only has CONNECT on the isolated database`() {
        val privileges = listOf("CONNECT", "CREATE", "TEMPORARY")
        ownerConnection(null).use { connection ->
            val granted =
                privileges.filter {
                    connection
                        .queryColumn(
                            "SELECT has_database_privilege('${PostgresContainer.APP_USERNAME}', " +
                                "'${EndOfDayDatabase.NAME}', '$it')::text",
                        ).single() == "true"
                }
            granted shouldContainExactly listOf("CONNECT")
        }
    }

    @Test
    fun `the app role cannot create schemas nor objects in public`() {
        dataSource.connection.use { connection ->
            assertThrows<SQLException> { connection.createStatement().use { it.execute("CREATE SCHEMA intruder") } }
            assertThrows<SQLException> {
                connection.createStatement().use { it.execute("CREATE TABLE public.z(i int)") }
            }
        }
    }

    @Test
    fun `the initializer sets the same properties as the shared one except the database url`() {
        val keys =
            listOf(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.flyway.user",
                "spring.flyway.password",
            )

        fun propertiesOf(initializer: ApplicationContextInitializer<ConfigurableApplicationContext>) =
            GenericApplicationContext().also(initializer::initialize).environment.let { env ->
                keys.associateWith { env.getProperty(it) }
            }
        val isolated = propertiesOf(EndOfDayDatabaseInitializer())
        val shared = propertiesOf(PostgresInitializer())
        isolated.filterKeys { it != "spring.datasource.url" } shouldBe
            shared.filterKeys { it != "spring.datasource.url" }
        isolated.getValue("spring.datasource.url").shouldNotBeNull() shouldEndWith "/${EndOfDayDatabase.NAME}"
    }

    @Test
    fun `both annotations carry the same profiles and initializers except the database one`() {
        EndOfDayIntegrationTest::class.java
            .getAnnotation(ActiveProfiles::class.java)
            .profiles
            .toList() shouldBe
            IntegrationTest::class.java
                .getAnnotation(ActiveProfiles::class.java)
                .profiles
                .toList()
        val isolated = EndOfDayIntegrationTest::class.java.getAnnotation(ContextConfiguration::class.java).initializers
        val shared = IntegrationTest::class.java.getAnnotation(ContextConfiguration::class.java).initializers
        isolated.toList().drop(1) shouldBe shared.toList().drop(1)
        isolated.first() shouldBe EndOfDayDatabaseInitializer::class
        shared.first() shouldBe PostgresInitializer::class
    }

    private fun ownerConnection(database: String?): Connection {
        val postgres = PostgresContainer.instance
        val url = if (database == null) postgres.jdbcUrl else postgres.jdbcUrl.substringBeforeLast('/') + "/" + database
        return DriverManager.getConnection(url, PostgresContainer.ownerUsername, PostgresContainer.ownerPassword)
    }

    private fun Connection.queryColumn(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
}
