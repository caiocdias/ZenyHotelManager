package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.ServiceUnavailableException
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ataque ao [EndOfDayItems] (M6 E11 Task 9): sobrevivência da pendência de item, rollback junto com a etapa,
 * hierarquia de [com.zeny.hotel.shared.DomainException], falhas não-domínio, lista vazia, RN-13, a trava da linha da
 * execução e a retomada.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class, EndOfDayItemsAttackTest.AttackConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // o motor, a execução e os apoios; um teste por ataque
class EndOfDayItemsAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
    @Autowired private val plan: AttackPlan,
    @Autowired private val items: EndOfDayItems,
    @Autowired private val context: ApplicationContext,
) {
    /** O corpo da etapa `items-attack`, trocado por teste. */
    class AttackPlan {
        @Volatile var body: (EndOfDayStepContext) -> Unit = {}

        val runs = AtomicInteger()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AttackConfiguration {
        @Bean
        fun attackPlan(): AttackPlan = AttackPlan()

        @Bean
        fun itemsAttackStep(plan: AttackPlan): EndOfDayStepHandler =
            object : EndOfDayStepHandler {
                override val name = STEP
                override val position = POSITION

                override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome {
                    plan.runs.incrementAndGet()
                    plan.body(context)
                    return EndOfDayStepOutcome.DONE
                }
            }

        private companion object {
            const val POSITION = 25
        }
    }

    private class Boom(
        message: String,
    ) : Error(message)

    private fun rows(
        date: LocalDate,
        kind: String,
    ): List<Pair<String?, String>> =
        jdbc
            .sql(
                "SELECT i.reference, i.message FROM endofday.run_issue i JOIN endofday.run r ON r.id = i.run_id " +
                    "WHERE r.business_date = :date AND i.kind = :kind ORDER BY i.reference, i.message",
            ).param("date", date)
            .param("kind", kind)
            .query { rs, _ -> rs.getString("reference") to rs.getString("message") }
            .list()

    private fun statusOf(date: LocalDate): RunStatus = runs.findByBusinessDate(date).shouldNotBeNull().status

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

    private fun scenario(
        body: (EndOfDayStepContext) -> Unit,
        check: (LocalDate) -> Unit,
    ) {
        val date = hotelApi.currentBusinessDate()
        try {
            plan.runs.set(0)
            plan.body = body
            executeCurrentDate()
            check(date)
        } finally {
            plan.body = {}
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a refused item issue survives when the step completes afterwards`() =
        scenario(
            body = { ctx ->
                items.each(ctx, "ATTACK_REFUSED", listOf(1, 2), { "item-$it" }) {
                    if (it == 1) throw BusinessRuleException(ErrorCode("ATTACK_NO"), "Não.")
                }
            },
            check = { date ->
                statusOf(date) shouldBe RunStatus.COMPLETED
                rows(date, "ATTACK_REFUSED") shouldContainExactly listOf("item-1" to "ATTACK_REFUSED: ATTACK_NO")
            },
        )

    @Test
    fun `when the step fails after the items the refused issue goes away but confirmed effects stay`() {
        val date = hotelApi.currentBusinessDate()
        try {
            plan.runs.set(0)
            plan.body = { ctx ->
                items.each(ctx, "ATTACK_REFUSED", listOf(1, 2, 3), { "item-$it" }) {
                    ctx.issues.raise("ATTACK_EFFECT", "item-$it", "Efeito do item.")
                    if (it == 2) throw ConflictException(ErrorCode("ATTACK_CONFLICT"), "Conflito.")
                }
                error("A etapa falhou depois dos itens.")
            }
            executeCurrentDate()

            statusOf(date) shouldBe RunStatus.FAILED
            // A pendência do item recusado era da transação da etapa: sumiu com ela.
            rows(date, "ATTACK_REFUSED") shouldContainExactly emptyList()
            // Os efeitos dos itens confirmados (1 e 3) ficaram; o do recusado (2) desfez com a transação dele.
            rows(date, "ATTACK_EFFECT").map { it.first } shouldContainExactly listOf("item-1", "item-3")
            rows(date, "STEP_FAILED").map { it.first } shouldContainExactly listOf(STEP)
        } finally {
            plan.body = {}
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `every DomainException subclass becomes an issue and the loop continues`() =
        scenario(
            body = { ctx ->
                val refusals: List<() -> Nothing> =
                    listOf(
                        { throw ConflictException(ErrorCode("ATTACK_A"), "a") },
                        { throw BusinessRuleException(ErrorCode("ATTACK_B"), "b") },
                        { throw NotFoundException(ErrorCode("ATTACK_C"), "c") },
                        { throw PermissionDeniedException(ErrorCode("ATTACK_D"), "d") },
                        { throw InvalidInputException(ErrorCode("ATTACK_E"), "e") },
                        { throw ServiceUnavailableException(ErrorCode("ATTACK_F"), "f") },
                    )
                val done = items.each(ctx, "ATTACK_KIND", refusals + { }, { "r-${refusals.indexOf(it)}" }) { it() }
                check(done == 1) { "Esperava 1 item concluído e vieram $done." }
            },
            check = { date ->
                statusOf(date) shouldBe RunStatus.COMPLETED
                rows(date, "ATTACK_KIND").map { it.second } shouldContainExactlyInAnyOrder
                    listOf("A", "B", "C", "D", "E", "F").map { "ATTACK_KIND: ATTACK_$it" }
            },
        )

    @Test
    fun `a non-domain RuntimeException from an item fails the step and no issue is made for it`() =
        scenario(
            body = { ctx ->
                items.each(ctx, "ATTACK_KIND", listOf(1, 2), { "item-$it" }) {
                    require(it != 2) { "defeito" }
                }
            },
            check = { date ->
                statusOf(date) shouldBe RunStatus.FAILED
                rows(date, "ATTACK_KIND") shouldContainExactly emptyList()
                rows(date, "STEP_FAILED").map { it.first } shouldContainExactly listOf(STEP)
            },
        )

    @Test
    fun `an Error from an item fails the step instead of becoming an issue`() =
        scenario(
            body = { ctx ->
                items.each(ctx, "ATTACK_KIND", listOf(1), { "item-$it" }) { throw Boom("erro grave") }
            },
            check = { date ->
                statusOf(date) shouldBe RunStatus.FAILED
                rows(date, "ATTACK_KIND") shouldContainExactly emptyList()
            },
        )

    @Test
    fun `an empty list returns zero and raises nothing`() {
        val result = AtomicInteger(-1)
        scenario(
            body = { ctx ->
                result.set(items.each(ctx, "ATTACK_KIND", emptyList<Int>(), { "item-$it" }) { error("nunca") })
            },
            check = { date ->
                result.get() shouldBe 0
                statusOf(date) shouldBe RunStatus.COMPLETED
                rows(date, "ATTACK_KIND") shouldContainExactly emptyList()
            },
        )
    }

    @Test
    fun `the issue carries only the kind and the code and never the exception message`() =
        scenario(
            body = { ctx ->
                items.each(ctx, "ATTACK_KIND", listOf(1), { "res-$it" }) {
                    throw BusinessRuleException(ErrorCode("ATTACK_PII"), "João da Silva, CPF 123.456.789-09")
                }
            },
            check = { date ->
                val issue = rows(date, "ATTACK_KIND").single()
                issue.second shouldBe "ATTACK_KIND: ATTACK_PII"
                issue.second shouldNotContain "João"
                issue.second shouldNotContain "123.456"
                issue.first shouldBe "res-1"
            },
        )

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `an item that touches the run row does not hang the step`() {
        val outcome = AtomicInteger(0)
        scenario(
            body = { ctx ->
                // lock_timeout só protege o teste: sem ele, com a trava da etapa, a thread esperaria para sempre.
                items.each(ctx, "ATTACK_KIND", listOf(1), { "item-$it" }) {
                    jdbc.sql("SET LOCAL lock_timeout = '3s'").update()
                    jdbc
                        .sql("UPDATE endofday.run SET updated_at = updated_at WHERE id = :id")
                        .param("id", ctx.runId.value)
                        .update()
                    outcome.set(1)
                }
            },
            check = { date ->
                // Terminou ou falhou com o timeout da trava; nunca ficou RUNNING.
                (statusOf(date) in setOf(RunStatus.COMPLETED, RunStatus.FAILED)) shouldBe true
            },
        )
    }

    @Test
    fun `resuming a failed step runs the items again`() {
        val date = hotelApi.currentBusinessDate()
        val worked = AtomicInteger()
        try {
            plan.runs.set(0)
            plan.body = { ctx ->
                items.each(ctx, "ATTACK_KIND", listOf(1, 2), { "item-$it" }) { worked.incrementAndGet() }
                check(plan.runs.get() > 1) { "Falha só na primeira tentativa." }
            }
            executeCurrentDate()
            statusOf(date) shouldBe RunStatus.FAILED
            worked.get() shouldBe 2
        } finally {
            // O settle retoma a execução FAILED: a etapa roda de novo e os itens também (idempotência é de quem chama).
            plan.body = { ctx ->
                items.each(ctx, "ATTACK_KIND", listOf(1, 2), { "item-$it" }) { worked.incrementAndGet() }
            }
            EndOfDayTestRuns.settle(context)
        }
        worked.get() shouldBe 4
        plan.runs.get() shouldBe 2
    }

    private companion object {
        const val STEP = "items-attack"
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
