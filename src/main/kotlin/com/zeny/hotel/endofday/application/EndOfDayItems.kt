package com.zeny.hotel.endofday.application

import com.zeny.hotel.shared.DomainException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * O apoio das etapas do fechamento para o trabalho por item (contrato em [EndOfDayStepHandler]): cada item roda na
 * transação dele (`REQUIRES_NEW`, por [TransactionTemplate], sem `@Transactional`, como `OfflineCommands`), e a
 * transação da etapa nunca é tocada por ele.
 *
 * A recusa de negócio de um item ([DomainException]) não derruba o fechamento: depois que a transação do item desfez,
 * vira pendência, levantada na transação da etapa. Qualquer outra exceção (infraestrutura, defeito) escapa e falha a
 * etapa; a retomada repete a etapa, que por isso é idempotente pela chave natural de cada item.
 */
@Component
class EndOfDayItems(
    transactionManager: PlatformTransactionManager,
) {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /** Roda o [work] numa transação nova, confirmada ao fim dele e desfeita se ele lançar. */
    @Suppress("UNCHECKED_CAST") // o template só devolve nulo se o próprio trabalho devolver
    fun <T> inOwnTransaction(work: () -> T): T = ownTransaction.execute { work() } as T

    /**
     * Roda o [work] de cada item de [items] na transação dele. A [DomainException] de um item levanta, na transação
     * da etapa, a pendência [issueKind] com a [reference] do item e a mensagem `"<issueKind>: <código do erro>"`
     * (sem dado pessoal, RN-13). Devolve quantos itens concluíram.
     */
    fun <I> each(
        context: EndOfDayStepContext,
        issueKind: String,
        items: List<I>,
        reference: (I) -> String,
        work: (I) -> Unit,
    ): Int {
        var done = 0
        for (item in items) {
            try {
                inOwnTransaction { work(item) }
                done++
            } catch (refusal: DomainException) {
                context.issues.raise(issueKind, reference(item), "$issueKind: ${refusal.code.value}")
            }
        }
        return done
    }
}
