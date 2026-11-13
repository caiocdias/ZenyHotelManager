package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.EndOfDayRunStep
import com.zeny.hotel.endofday.domain.StepSlot
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Uma etapa de negócio do fechamento do dia (SPEC RF-FEC-02, RN-21). Cada bean desta interface entra no
 * [EndOfDayStepRegistry] e o motor a roda, em ordem de [position], uma vez por data operacional. **É o contrato que
 * as etapas reais (E11, E13) seguem:**
 *
 * - **Transação:** [execute] roda dentro da transação que marca a etapa concluída
 *   ([EndOfDayRunTransitions.complete]), com a linha da execução (`endofday.run`) travada por ela. Uma exceção que
 *   escapa de [execute] falha a etapa e desfaz tudo o que ela fez nessa transação, inclusive as pendências e os avisos
 *   levantados nela.
 * - **Idempotência:** [execute] pode rodar de novo para a mesma data (uma retomada depois de falha ou de queda repete
 *   a etapa que não chegou a concluir). Precisa ser idempotente, com a chave pela **data**
 *   ([EndOfDayStepContext.businessDate]), **nunca** pelo `runId`.
 * - **Trabalho por item que tolera falha:** cada item roda na transação dele (`REQUIRES_NEW`, por outro bean com
 *   `@Transactional` próprio, nunca por `this.`), idempotente pela chave natural do item; a falha do item vira
 *   pendência ([EndOfDayStepContext.issues]) e não derruba a etapa. A transação do item **nunca altera a linha da
 *   execução** (`endofday.run`), que a transação da etapa mantém travada: a thread esperaria por ela mesma. Inserir
 *   pendência pode (a trava é `FOR NO KEY UPDATE`).
 * - **Efeito fora do banco** (arquivo no S3, na E13): também idempotente, pela data; o rollback não o desfaz.
 * - **Nome estável:** [name] é um código (`[a-z][a-z0-9-]{0,59}`), gravado em cada execução. Renomear ou remover uma
 *   etapa já publicada a faz ser pulada (`UNREGISTERED`) nas execuções abertas; renomear ou remover a etapa de avanço
 *   da data é **proibido** (a execução concluiria sem avançar a data).
 * - **Posição:** [position] é positiva e única. As etapas de produção ficam espaçadas de 100 em 100, na ordem da
 *   RF-FEC-02, para caber etapa nova entre duas.
 * - **Dados pessoais:** o resultado, o `kind`, a referência e a mensagem de uma pendência nunca levam dado pessoal de
 *   hóspede (RN-13); a referência é opaca (um id).
 */
interface EndOfDayStepHandler {
    val name: String
    val position: Int

    /** Executa a etapa para a [EndOfDayStepContext.businessDate] e devolve o resultado gravado na etapa. */
    fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome
}

/** O que a etapa recebe: a execução, a data operacional que fecha e onde levantar pendências. */
data class EndOfDayStepContext(
    val runId: EndOfDayRunId,
    val businessDate: LocalDate,
    val issues: EndOfDayIssueSink,
)

/**
 * Onde a etapa levanta uma pendência da execução: [kind] é um código `UPPER_SNAKE` (até 60), [reference] é opaca
 * (até 120) e [message] (até 500) nunca leva dado pessoal de hóspede (RN-13). A pendência fica na transação de quem
 * chama: levantada na transação da etapa, é desfeita com ela.
 */
fun interface EndOfDayIssueSink {
    fun raise(
        kind: String,
        reference: String?,
        message: String,
    )
}

/** O resultado de uma etapa concluída (um código, até 200), gravado na etapa. */
data class EndOfDayStepOutcome(
    val result: String,
) {
    companion object {
        /** A etapa fez o trabalho dela. */
        val DONE = EndOfDayStepOutcome("DONE")

        /** Não havia o que fazer nesta data. */
        val NOT_APPLICABLE = EndOfDayStepOutcome("NOT_APPLICABLE")
    }
}

/**
 * O registro das etapas do fechamento, em ordem de posição: todos os beans [EndOfDayStepHandler] do contexto. Um nome
 * fora do padrão, uma posição não positiva ou um nome ou posição repetidos falham o boot (`IllegalStateException`).
 */
@Component
class EndOfDayStepRegistry(
    handlers: List<EndOfDayStepHandler>,
) {
    /** As etapas, em ordem de posição. */
    val steps: List<EndOfDayStepHandler> = handlers.sortedBy { it.position }

    /** As etapas como a execução as grava: nome e posição. */
    val slots: List<StepSlot> = steps.map { StepSlot(it.name, it.position) }

    private val byName: Map<String, EndOfDayStepHandler> = steps.associateBy { it.name }

    init {
        steps.forEach {
            check(NAME.matches(it.name)) { "Nome de etapa do fechamento fora do padrão ${NAME.pattern}: '${it.name}'." }
            check(it.position > 0) { "A posição da etapa ${it.name} deve ser positiva." }
        }
        check(byName.size == steps.size) { "Nome de etapa do fechamento repetido." }
        check(steps.map { it.position }.toSet().size == steps.size) { "Posição de etapa do fechamento repetida." }
    }

    /** A etapa registrada com o [name], ou nula. */
    fun handlerOrNull(name: String): EndOfDayStepHandler? = byName[name]

    private companion object {
        val NAME = Regex("[a-z][a-z0-9-]{0,${EndOfDayRunStep.NAME_LENGTH - 1}}")
    }
}
