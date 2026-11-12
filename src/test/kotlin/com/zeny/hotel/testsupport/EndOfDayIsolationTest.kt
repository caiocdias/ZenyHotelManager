package com.zeny.hotel.testsupport

import com.zeny.hotel.hotel.HotelApi
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
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
     * A data operacional do banco compartilhado, lida direto como dono; nula se ele ainda não foi migrado ou não tem
     * hotel (rodando esta classe sozinha, nenhum contexto compartilhado o criou).
     */
    private fun sharedBusinessDate(): LocalDate? {
        val postgres = PostgresContainer.instance
        return DriverManager
            .getConnection(postgres.jdbcUrl, PostgresContainer.ownerUsername, PostgresContainer.ownerPassword)
            .use { connection ->
                connection.createStatement().use { statement ->
                    val migrated =
                        statement.executeQuery("SELECT to_regclass('hotel.business_day')").use { rows ->
                            rows.next() && rows.getString(1) != null
                        }
                    if (!migrated) return null
                    statement.executeQuery("SELECT business_date FROM hotel.business_day").use { rows ->
                        if (rows.next()) rows.getObject(1, LocalDate::class.java) else null
                    }
                }
            }
    }
}
