package com.zeny.hotel.hotel

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/** Os eventos `BusinessDateAdvanced` recebidos pelo listener de teste, como um módulo os receberia. */
object BusinessDateEvents {
    val received: MutableList<BusinessDateAdvanced> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class BusinessDateProbeListener {
    @ApplicationModuleListener
    open fun on(event: BusinessDateAdvanced) {
        BusinessDateEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class BusinessDateListenerConfiguration {
    @Bean
    fun businessDateProbeListener() = BusinessDateProbeListener()
}

/**
 * Data operacional (RN-01, RF-CFG-05, M1 E06): só a API pública `HotelApi.advanceBusinessDate`
 * a avança, exatamente um dia e só a partir da data esperada, publicando `BusinessDateAdvanced`
 * pelo outbox (Event Publication Registry) na mesma transação; o banco também recusa qualquer
 * outro salto.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, BusinessDateListenerConfiguration::class)
class BusinessDateTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
) {
    @Test
    fun `a wrong expected date fails without effect`() {
        val current = hotel.currentBusinessDate()
        val publications = publicationCount()
        val audited = auditCount()

        for (expected in listOf(current.minusDays(1), current.plusDays(1))) {
            val error = shouldThrow<ConflictException> { hotel.advanceBusinessDate(expected) }
            error.code shouldBe ErrorCode("BUSINESS_DATE_MISMATCH")
        }

        hotel.currentBusinessDate() shouldBe current
        publicationCount() shouldBe publications
        auditCount() shouldBe audited
    }

    @Test
    fun `the right expected date advances exactly one day and publishes BusinessDateAdvanced`(scenario: Scenario) {
        val current = hotel.currentBusinessDate()

        scenario
            .stimulate(Supplier { hotel.advanceBusinessDate(current) })
            .andWaitForEventOfType(BusinessDateAdvanced::class.java)
            .matching { it.previous == current }
            .toArriveAndVerify { event, result ->
                event.current shouldBe current.plusDays(1)
                result shouldBe event
            }

        hotel.currentBusinessDate() shouldBe current.plusDays(1)
        storedBusinessDate() shouldBe current.plusDays(1)
        lastAuditAction() shouldBe "hotel.business-date.advance"
    }

    @Test
    fun `the event goes through the outbox in the advancing transaction`() {
        val current = hotel.currentBusinessDate()

        transactions.executeWithoutResult { _ ->
            hotel.advanceBusinessDate(current)
            // Ainda na transação: a publicação já está gravada e o listener não a recebeu.
            publicationStatus(current).shouldNotBeNull() shouldBe "PUBLISHED"
            BusinessDateEvents.received.none { it.previous == current } shouldBe true
        }

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { publicationStatus(current) == "COMPLETED" }
        BusinessDateEvents.received.map { it.previous } shouldContain current
    }

    @Test
    fun `a rolled back advance neither changes the date nor publishes the event`() {
        val current = hotel.currentBusinessDate()

        transactions.executeWithoutResult { status ->
            hotel.advanceBusinessDate(current)
            status.setRollbackOnly()
        }

        hotel.currentBusinessDate() shouldBe current
        publicationStatus(current) shouldBe null
    }

    @Test
    fun `the database refuses to move the business date other than one day forward`() {
        val current = storedBusinessDate()

        for (target in listOf(current.plusDays(2), current.minusDays(1), current)) {
            val updated =
                runCatching {
                    jdbc
                        .sql("UPDATE hotel.business_day SET business_date = :date, version = version + 1")
                        .param("date", target)
                        .update()
                }
            if (target == current) {
                updated.isSuccess shouldBe true
            } else {
                updated.exceptionOrNull().shouldNotBeNull()
            }
        }
        storedBusinessDate() shouldBe current
    }

    private fun storedBusinessDate(): LocalDate =
        jdbc.sql("SELECT business_date FROM hotel.business_day").query(LocalDate::class.java).single()

    private fun publicationCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM shared.event_publication WHERE event_type = :type")
            .param("type", BusinessDateAdvanced::class.java.name)
            .query(Int::class.java)
            .single()

    private fun publicationStatus(previous: LocalDate): String? =
        jdbc
            .sql(
                "SELECT status FROM shared.event_publication " +
                    "WHERE event_type = :type AND serialized_event LIKE :previous AND listener_id LIKE :listener",
            ).param("type", BusinessDateAdvanced::class.java.name)
            // Outros listeners (as rotinas de checklist, M2 E07) também recebem o evento: confere a do de teste.
            .param("listener", "%BusinessDateProbeListener%")
            .param("previous", "%\"previous\":\"$previous\"%")
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun auditCount(): Int = jdbc.sql("SELECT count(*) FROM audit.audit_entry").query(Int::class.java).single()

    private fun lastAuditAction(): String =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = 'hotel.business-day' " +
                    "ORDER BY occurred_at DESC LIMIT 1",
            ).query(String::class.java)
            .single()

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
