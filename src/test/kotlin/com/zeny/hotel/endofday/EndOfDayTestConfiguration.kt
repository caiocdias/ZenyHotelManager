package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.testsupport.MutableClockConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * O **único** contexto dos testes do motor do fechamento (M6 E10, Task 5 em diante), com o
 * `@EndOfDayIntegrationTest`: relógio mutável, o apoio do `iam` e quatro etapas falsas, `fake-one` (10), `fake-two`
 * (20), `fake-three` (30) e `fake-advance` (40, que avança a data operacional como a etapa real de avanço da E11),
 * comandadas pelo [FakeStepsProbe].
 *
 * Como toda execução completa avança a data operacional do banco isolado, cada teste fecha a data corrente e deixa a
 * seguinte sem execução (`EndOfDayTestRuns.settle`).
 *
 * **Revisar na E11:** quando as etapas reais forem registradas (posições de 100 em 100), este contexto passa a ter as
 * reais e as falsas juntas, e as reais agem sobre todo o banco isolado (nota do handoff).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(MutableClockConfiguration::class, IamTestConfiguration::class)
class EndOfDayTestConfiguration {
    @Bean
    fun fakeItemWork(): FakeItemWork = FakeItemWork()

    @Bean
    fun fakeStepsProbe(itemWork: FakeItemWork): FakeStepsProbe = FakeStepsProbe(itemWork)

    @Bean
    fun fakeOneStep(probe: FakeStepsProbe): EndOfDayStepHandler = FakeStep(FakeStepsProbe.ONE, ONE_POSITION, probe)

    @Bean
    fun fakeTwoStep(probe: FakeStepsProbe): EndOfDayStepHandler = FakeStep(FakeStepsProbe.TWO, TWO_POSITION, probe)

    @Bean
    fun fakeThreeStep(probe: FakeStepsProbe): EndOfDayStepHandler =
        FakeStep(FakeStepsProbe.THREE, THREE_POSITION, probe)

    @Bean
    fun fakeAdvanceStep(
        probe: FakeStepsProbe,
        hotelApi: HotelApi,
    ): EndOfDayStepHandler =
        FakeStep(FakeStepsProbe.ADVANCE, ADVANCE_POSITION, probe) { hotelApi.advanceBusinessDate(it.businessDate) }

    private companion object {
        const val ONE_POSITION = 10
        const val TWO_POSITION = 20
        const val THREE_POSITION = 30
        const val ADVANCE_POSITION = 40
    }
}

/** Uma etapa falsa: conta, falha, dorme ou levanta pendência pelo [probe] e, depois, faz o [then]. */
private class FakeStep(
    override val name: String,
    override val position: Int,
    private val probe: FakeStepsProbe,
    private val then: (EndOfDayStepContext) -> Unit = {},
) : EndOfDayStepHandler {
    override fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome =
        probe.run(name, context).also { then(context) }
}

/**
 * O comando e a contagem das etapas falsas do [EndOfDayTestConfiguration]. As contagens são por etapa e data
 * operacional, então um teste lê só as da própria execução. [reset] limpa tudo (o `settle` o chama).
 */
class FakeStepsProbe(
    private val itemWork: FakeItemWork,
) {
    private val calls = ConcurrentHashMap<Pair<String, LocalDate>, AtomicInteger>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val issueKinds = ConcurrentHashMap<String, String>()
    private val itemPlans = ConcurrentHashMap<String, ItemPlan>()
    private val pauses = ConcurrentHashMap<String, Duration>()

    private data class ItemPlan(
        val items: Int,
        val failingItem: Int?,
    )

    /** Quantas vezes a etapa [step] rodou para a [businessDate]. */
    fun invocations(
        step: String,
        businessDate: LocalDate,
    ): Int = calls[step to businessDate]?.get() ?: 0

    /** As próximas [times] execuções da etapa [step] lançam `IllegalStateException` (depois do resto do trabalho). */
    fun failNext(
        step: String,
        times: Int,
    ) {
        failures[step] = AtomicInteger(times)
    }

    /** A etapa [step] levanta uma pendência [kind] (referência = a etapa) na transação dela. */
    fun raiseIssueOn(
        step: String,
        kind: String,
    ) {
        issueKinds[step] = kind
    }

    /**
     * A etapa [step] processa [items] itens, cada um na transação dele ([FakeItemWork], `REQUIRES_NEW`), que levanta a
     * pendência [TEST_ITEM]; o item [failingItem] lança uma `DomainException`, que a etapa captura e registra como
     * pendência [TEST_ITEM] na transação dela, como manda o contrato de etapa.
     */
    fun itemsOn(
        step: String,
        items: Int,
        failingItem: Int?,
    ) {
        itemPlans[step] = ItemPlan(items, failingItem)
    }

    /** A etapa [step] dorme [duration] antes de terminar. */
    fun sleepOn(
        step: String,
        duration: Duration,
    ) {
        pauses[step] = duration
    }

    /** Esquece contagens e comandos. */
    fun reset() {
        calls.clear()
        failures.clear()
        issueKinds.clear()
        itemPlans.clear()
        pauses.clear()
    }

    internal fun run(
        step: String,
        context: EndOfDayStepContext,
    ): EndOfDayStepOutcome {
        calls.computeIfAbsent(step to context.businessDate) { AtomicInteger() }.incrementAndGet()
        pauses[step]?.let { Thread.sleep(it) }
        issueKinds[step]?.let { context.issues.raise(it, step, "Pendência de teste.") }
        itemPlans[step]?.let { plan -> (1..plan.items).forEach { process(context, it, it == plan.failingItem) } }
        check((failures[step]?.getAndDecrement() ?: 0) <= 0) { "Falha de teste na etapa $step." }
        return EndOfDayStepOutcome.DONE
    }

    private fun process(
        context: EndOfDayStepContext,
        item: Int,
        fails: Boolean,
    ) {
        try {
            itemWork.process(context, item, fails)
        } catch (e: DomainException) {
            context.issues.raise(TEST_ITEM, itemReference(item), "Item falhou: ${e.code.value}")
        }
    }

    companion object {
        const val ONE = "fake-one"
        const val TWO = "fake-two"
        const val THREE = "fake-three"
        const val ADVANCE = "fake-advance"

        /** As etapas falsas, em ordem de posição. */
        val STEPS = listOf(ONE, TWO, THREE, ADVANCE)

        /** A pendência do trabalho por item. */
        const val TEST_ITEM = "TEST_ITEM"

        /** O código da falha de um item. */
        val ITEM_FAILED = ErrorCode("TEST_ITEM_FAILED")

        fun itemReference(item: Int): String = "item-$item"
    }
}

/**
 * O trabalho de um item, na transação dele (`REQUIRES_NEW`, por este bean, nunca por `this.`): levanta a pendência
 * [FakeStepsProbe.TEST_ITEM] ou falha com [FakeStepsProbe.ITEM_FAILED]. A classe é aberta pelo allopen do Spring
 * (`@Transactional` na classe), para o proxy.
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
class FakeItemWork {
    fun process(
        context: EndOfDayStepContext,
        item: Int,
        fails: Boolean,
    ) {
        if (fails) throw BusinessRuleException(FakeStepsProbe.ITEM_FAILED, "O item de teste falhou.")
        context.issues.raise(FakeStepsProbe.TEST_ITEM, FakeStepsProbe.itemReference(item), "Item processado.")
    }
}
