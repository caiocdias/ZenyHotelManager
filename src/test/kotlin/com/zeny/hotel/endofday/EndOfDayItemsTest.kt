package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * O trabalho por item do fechamento em transação própria (M6 E11 Task 9): a recusa de negócio de um item vira
 * pendência levantada na transação da etapa; qualquer outra falha derruba a etapa e os itens já confirmados ficam.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class, EndOfDayItemsTest.ItemsProbeConfiguration::class)
@Suppress("LongParameterList") // o motor, a execução e os apoios de transação e JDBC
class EndOfDayItemsTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
    @Autowired private val probe: ItemsProbe,
    @Autowired private val context: ApplicationContext,
) {
    /** O plano da etapa `items-probe`: qual item é recusado e qual falha por defeito. */
    class ItemsProbe {
        @Volatile var refused: Int? = null

        @Volatile var broken: Int? = null

        @Volatile var completed: Int? = null

        fun reset() {
            refused = null
            broken = null
            completed = null
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ItemsProbeConfiguration {
        @Bean
        fun itemsProbe(): ItemsProbe = ItemsProbe()

        @Bean
        fun itemsProbeStep(
            items: EndOfDayItems,
            probe: ItemsProbe,
        ): EndOfDayStepHandler =
            object : EndOfDayStepHandler {
                override val name = STEP
                override val position = POSITION

                override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
                    probe.completed =
                        items.each(context, "TEST_ITEM_REFUSED", listOf(1, 2, 3), { "item-$it" }) { item: Int ->
                            context.issues.raise("TEST_ITEM_DONE", "item-$item", "Item confirmado.")
                            if (item ==
                                probe.refused
                            ) {
                                throw BusinessRuleException(ErrorCode("TEST_REFUSED"), "Recusado.")
                            }
                            check(item != probe.broken) { "Defeito de teste." }
                        }
                    return EndOfDayStepOutcome.DONE
                }
            }

        private companion object {
            const val POSITION = 25
        }
    }

    private fun issues(
        date: LocalDate,
        kind: String,
    ): List<Pair<String?, String>> =
        jdbc
            .sql(
                "SELECT i.reference, i.message FROM endofday.run_issue i JOIN endofday.run r ON r.id = i.run_id " +
                    "WHERE r.business_date = :date AND i.kind = :kind ORDER BY i.reference",
            ).param("date", date)
            .param("kind", kind)
            .query { rs, _ -> rs.getString("reference") to rs.getString("message") }
            .list()

    private fun executeCurrentDate() {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val run =
            tx
                .execute {
                    runs.save(
                        EndOfDayRun.start(
                            hotelApi.currentBusinessDate(),
                            RunTrigger.AUTOMATIC,
                            null,
                            registry.slots,
                            now,
                        ),
                    )
                }.shouldNotBeNull()
        engine.execute(run.id, WAIT)
    }

    @Test
    fun `a refused item becomes an issue and the others are committed`() {
        val date = hotelApi.currentBusinessDate()
        try {
            probe.refused = 2
            executeCurrentDate()

            probe.completed shouldBe 2
            // O item recusado desfez a própria pendência de efeito; o 1º e o 3º ficaram.
            issues(date, "TEST_ITEM_DONE").map { it.first } shouldContainExactly listOf("item-1", "item-3")
            issues(date, "TEST_ITEM_REFUSED") shouldContainExactly listOf("item-2" to "TEST_ITEM_REFUSED: TEST_REFUSED")
        } finally {
            probe.reset()
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an unexpected failure of an item fails the step and keeps the committed items`() {
        val date = hotelApi.currentBusinessDate()
        try {
            probe.broken = 3
            executeCurrentDate()

            probe.completed shouldBe null
            issues(date, "TEST_ITEM_DONE").map { it.first } shouldContainExactly listOf("item-1", "item-2")
            issues(date, "STEP_FAILED").map { it.first } shouldContainExactly listOf(STEP)
            issues(date, "TEST_ITEM_REFUSED") shouldContainExactly emptyList()
        } finally {
            probe.reset()
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        const val STEP = "items-probe"
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
