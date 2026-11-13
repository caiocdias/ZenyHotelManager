package com.zeny.hotel.testsupport

import com.zeny.hotel.ZenyHotelApplication
import com.zeny.hotel.hotel.HotelApi
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import javax.sql.DataSource

/**
 * O banco isolado do fechamento do dia (M6 E10, H10): o contexto de [EndOfDayIntegrationTest] não toca no banco
 * compartilhado, e o papel da aplicação continua sem DDL nele.
 */
@EndOfDayIntegrationTest
class EndOfDayIsolationTest(
    @Autowired private val dataSource: DataSource,
    @Autowired private val hotelApi: HotelApi,
) {
    @Test
    fun `the context is connected to the isolated database`() {
        dataSource.connection.use { connection ->
            connection.metaData.url shouldEndWith "/${EndOfDayDatabase.NAME}"
            connection.metaData.userName shouldBe PostgresContainer.APP_USERNAME
        }
    }

    @Test
    fun `advancing the business date here does not change the shared database`() {
        val sharedBefore = sharedBusinessDate()
        val isolatedBefore = hotelApi.currentBusinessDate()

        hotelApi.advanceBusinessDate(isolatedBefore)

        hotelApi.currentBusinessDate() shouldBe isolatedBefore.plusDays(1)
        sharedBusinessDate() shouldBe sharedBefore
    }

    @Test
    fun `the app role has no DDL in the isolated database`() {
        dataSource.connection.use { connection ->
            assertThrows<SQLException> {
                connection.createStatement().use { it.execute("CREATE TABLE public.x(i int)") }
            }
            assertThrows<SQLException> {
                connection.createStatement().use { it.execute("CREATE TEMP TABLE y(i int)") }
            }
        }
    }

    @Test
    fun `both integration annotations carry the same test properties`() {
        fun propertiesOf(annotated: Class<*>) = annotated.getAnnotation(SpringBootTest::class.java).properties.toList()

        propertiesOf(EndOfDayIntegrationTest::class.java) shouldBe propertiesOf(IntegrationTest::class.java)
    }

    /**
     * A data operacional do banco compartilhado, lida direto como dono. Antes de ler, garante que o banco compartilhado
     * existe migrado e com o hotel inicial: rodando esta classe sozinha nenhum contexto compartilhado o criou, e a
     * comparação seria nula com nula.
     */
    private fun sharedBusinessDate(): LocalDate {
        seedSharedDatabase()
        val postgres = PostgresContainer.instance
        return DriverManager
            .getConnection(postgres.jdbcUrl, PostgresContainer.ownerUsername, PostgresContainer.ownerPassword)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT business_date FROM hotel.business_day").use { rows ->
                        rows.next() shouldBe true
                        rows.getObject(1, LocalDate::class.java).shouldNotBeNull()
                    }
                }
            }
    }

    private companion object {
        /**
         * Sobe e fecha um contexto sobre o banco compartilhado (como o do `EventPublicationRegistryTest`): o boot migra
         * o banco e cria o hotel inicial; num banco que outro contexto já preparou, não muda nada.
         */
        val sharedDatabaseSeeded: Unit by lazy {
            SpringApplicationBuilder(ZenyHotelApplication::class.java)
                .profiles("local")
                .initializers(
                    PostgresInitializer(),
                    TestEncryptionKeysInitializer(),
                    TestAuthInitializer(),
                    TestStorageInitializer(),
                )
                // Como argumentos de linha de comando: `properties()` teria prioridade abaixo do application.yaml.
                .run(
                    "--$TEST_MIGRATIONS",
                    "--$RESUBMISSION_JOB_DEFERRED",
                    "--$OUTBOX_PURGE_DEFERRED",
                    "--$FILES_PURGE_DEFERRED",
                    "--$GUESTS_REKEY_DEFERRED",
                    "--$INVENTORY_HORIZON_DEFERRED",
                    "--$PROVISIONAL_EXPIRY_DEFERRED",
                    "--$FNRH_SEND_DEFERRED",
                    "--$PRINTING_MAINTENANCE_DEFERRED",
                    "--server.port=0",
                ).close()
        }

        fun seedSharedDatabase() = sharedDatabaseSeeded
    }
}
