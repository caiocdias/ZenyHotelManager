package com.zeny.hotel.endofday

import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.RenderedDocument
import org.springframework.beans.factory.config.BeanPostProcessor
import java.util.concurrent.atomic.AtomicLong

/**
 * A sonda do `PrintingApi.render` no contexto das etapas reais do fechamento ([EndOfDayRealStepsTestConfiguration]):
 * embrulha o bean `PrintingApi` e, armada por [failRender], faz o render lançar (a falha do PDF no meio da etapa
 * `end-of-day-reports`). Desarmada só repassa, medindo o tempo do render em [renderNanos]. Inerte até ser armada.
 */
class PrintingRenderProbe : BeanPostProcessor {
    @Volatile
    private var failing = false
    private val nanos = AtomicLong()

    /** O tempo total gasto nos renders que passaram pela sonda desde o último [reset], em nanossegundos. */
    val renderNanos: Long get() = nanos.get()

    /** Todo `render` lança `IllegalStateException` (não é `DomainException`) até o [reset]. */
    fun failRender() {
        failing = true
    }

    /** Desarma e zera a medição (o `EndOfDayTestRuns.settle` o chama). */
    fun reset() {
        failing = false
        nanos.set(0)
    }

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is PrintingApi) ProbedPrintingApi(bean, this) else bean

    internal fun render(
        target: PrintingApi,
        document: DocumentRequest,
        format: DocumentFormat,
    ): RenderedDocument {
        check(!failing) { "Falha forçada do render pela sonda do teste." }
        val start = System.nanoTime()
        try {
            return target.render(document, format)
        } finally {
            nanos.addAndGet(System.nanoTime() - start)
        }
    }
}

/** O `PrintingApi` com o `render` passando antes pela [probe]; o resto delega direto. */
private class ProbedPrintingApi(
    private val target: PrintingApi,
    private val probe: PrintingRenderProbe,
) : PrintingApi by target {
    override fun render(
        document: DocumentRequest,
        format: DocumentFormat,
    ): RenderedDocument = probe.render(target, document, format)
}
