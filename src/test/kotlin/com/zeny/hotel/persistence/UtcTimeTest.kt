package com.zeny.hotel.persistence

import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TimeProbe
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Instantes ficam em `timestamptz` UTC (RN-01, D16), seja qual for o fuso da JVM. O Gradle
 * roda os testes em `America/Sao_Paulo` de propósito; o instante usado (01:30 UTC) cai no dia
 * anterior em São Paulo, então qualquer conversão pelo fuso da JVM ou da sessão aparece.
 */
@IntegrationTest
class UtcTimeTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
) {
    private val instant = Instant.parse("2026-03-10T01:30:00Z")

    @Test
    fun `tests run with a non-UTC JVM time zone`() {
        // Pré-condição dos demais testes: sem ela, eles passariam mesmo com a configuração errada.
        ZoneId.systemDefault().normalized() shouldNotBe ZoneOffset.UTC
    }

    @Test
    fun `database sessions use UTC`() {
        jdbc.sql("SELECT current_setting('TimeZone')").query(String::class.java).single() shouldBe "UTC"
        // A data de um instante calculada no banco segue o UTC, não o fuso da JVM.
        jdbc
            .sql("SELECT (TIMESTAMPTZ '2026-03-10 01:30:00+00')::date::text")
            .query(String::class.java)
            .single() shouldBe "2026-03-10"
    }

    @Test
    fun `JDBC writes and reads instants as UTC`() {
        val id = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            jdbc
                .sql("INSERT INTO test_support.time_probe (id, recorded_at) VALUES (:id, :at)")
                .param("id", id)
                .param("at", instant.atOffset(ZoneOffset.UTC))
                .update()
        }

        storedUtcWallClock(id) shouldBe "2026-03-10T01:30:00"
        jdbc
            .sql("SELECT recorded_at FROM test_support.time_probe WHERE id = :id")
            .param("id", id)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant() shouldBe instant
    }

    @Test
    fun `Hibernate writes and reads instants as UTC`() {
        val id = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            entityManagerFactory.createEntityManager().use { em ->
                em.joinTransaction()
                em.persist(TimeProbe(id, instant))
                em.flush()
            }
        }

        storedUtcWallClock(id) shouldBe "2026-03-10T01:30:00"
        entityManagerFactory.createEntityManager().use { em ->
            em.find(TimeProbe::class.java, id).recordedAt shouldBe instant
        }
    }

    /** O valor gravado, lido pelo próprio banco como hora de parede em UTC. */
    private fun storedUtcWallClock(id: UUID): String =
        jdbc
            .sql(
                """
                SELECT to_char(recorded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
                FROM test_support.time_probe WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .query(String::class.java)
            .single()
}
