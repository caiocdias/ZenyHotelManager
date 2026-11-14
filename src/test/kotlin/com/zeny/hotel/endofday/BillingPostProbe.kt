package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSummary
import org.springframework.beans.factory.config.BeanPostProcessor
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A sonda do `BillingApi.post` no contexto das etapas reais do fechamento ([EndOfDayRealStepsTestConfiguration]):
 * embrulha o bean `BillingApi` (por fora do proxy transacional dele: sem ordem, este pós-processador roda depois do
 * criador de proxies do Spring) e, por conta, falha ou segura o lançamento. Inerte para as contas não armadas.
 */
class BillingPostProbe : BeanPostProcessor {
    private val failures: MutableSet<FolioId> = ConcurrentHashMap.newKeySet()
    private val holds = ConcurrentHashMap<FolioId, Pair<CountDownLatch, () -> Unit>>()

    /**
     * Todo `post` na conta [folioId] lança `IllegalStateException` antes de lançar: não é `DomainException`, então
     * falha a etapa que lança (em vez de virar pendência).
     */
    fun failOn(folioId: FolioId) {
        failures += folioId
    }

    /**
     * Todo `post` na conta [folioId] espera o [gate] abrir (no máximo [HOLD_LIMIT]) antes de lançar. Antes de esperar,
     * roda [onHold] na thread (e na transação) de quem lança: por exemplo, para o teste ler o pid da sessão segurada.
     */
    fun holdOn(
        folioId: FolioId,
        gate: CountDownLatch,
        onHold: () -> Unit = {},
    ) {
        holds[folioId] = gate to onHold
    }

    /** Desarma tudo (o `EndOfDayTestRuns.settle` o chama). */
    fun reset() {
        failures.clear()
        holds.clear()
    }

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is BillingApi) ProbedBillingApi(bean, this) else bean

    internal fun beforePost(folioId: FolioId) {
        holds[folioId]?.let { (gate, onHold) ->
            onHold()
            gate.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
        }
        check(folioId !in failures) { "Falha forçada pelo teste" }
    }

    companion object {
        /** O máximo que um lançamento espera pelo portão do [holdOn], para um teste quebrado não travar a thread. */
        val HOLD_LIMIT: Duration = Duration.ofSeconds(30)
    }
}

/** O `BillingApi` com o `post` passando antes pela [probe]; o resto delega direto. */
private class ProbedBillingApi(
    private val target: BillingApi,
    private val probe: BillingPostProbe,
) : BillingApi by target {
    override fun post(
        folioId: FolioId,
        posting: NewPosting,
    ): PostingSummary {
        probe.beforePost(folioId)
        return target.post(folioId, posting)
    }
}
