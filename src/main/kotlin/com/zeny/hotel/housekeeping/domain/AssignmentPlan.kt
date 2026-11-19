package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.AssignmentPlanId
import com.zeny.hotel.housekeeping.AssignmentPlanStatus
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate

/**
 * Um item do plano: a tarefa, a camareira ([attendant]; `null`, a tarefa fica no plano sem camareira) e a posição na
 * ordem de execução dela (de 1 a n; nas sem camareira, a ordem da sugestão).
 */
data class AssignmentPlanItem(
    val taskId: HousekeepingTaskId,
    val attendant: AttendantId?,
    val position: Int,
) {
    init {
        require(position >= 1) { "Posição fora do plano: $position" }
    }
}

/**
 * Uma mudança do ajuste (D6-E03): a tarefa vai para a camareira [attendant] (`null`: fica no plano sem camareira) na
 * [position] (de 1 em diante; sem ela, ou além do fim, no fim da lista dela). Mover e acrescentar são a mesma mudança.
 */
data class AssignmentChange(
    val taskId: HousekeepingTaskId,
    val attendant: AttendantId?,
    val position: Int?,
)

/**
 * O plano do dia da distribuição (RF-GOV-03, D6-E03): um rascunho por data operacional, com os itens (cada tarefa no
 * máximo uma vez). A sugestão refaz os itens inteiros e o ajuste os muda; os dois voltam o plano a `DRAFT` e sobem a
 * [version] (a concorrência otimista do `If-Match`). As tarefas só mudam na publicação ([publish]), que deixa o plano
 * `PUBLISHED` e também sobe a versão. O plano nunca é apagado.
 */
@Suppress("LongParameterList") // os campos do plano, lidos da persistência
class AssignmentPlan(
    val id: AssignmentPlanId,
    val businessDate: LocalDate,
    status: AssignmentPlanStatus,
    version: Long,
    items: List<AssignmentPlanItem>,
    val createdAt: Instant,
    updatedAt: Instant,
    publishedAt: Instant?,
    publishedBy: UserId?,
) {
    var status: AssignmentPlanStatus = status
        private set

    var version: Long = version
        private set

    var items: List<AssignmentPlanItem> = items
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Quando foi a última publicação; `null` se nunca foi publicado. */
    var publishedAt: Instant? = publishedAt
        private set

    /** Quem fez a última publicação; `null` se nunca foi publicado (ou foi o sistema). */
    var publishedBy: UserId? = publishedBy
        private set

    /** Os itens da sugestão: os de cada camareira, na ordem dela, e as sem camareira, na ordem da sugestão. */
    fun replace(
        suggestion: Suggestion,
        now: Instant,
    ) {
        val assigned =
            suggestion.byAttendant.flatMap { (attendant, tasks) ->
                tasks.mapIndexed { index, task -> AssignmentPlanItem(task, attendant, index + 1) }
            }
        val unassigned = suggestion.unassigned.mapIndexed { index, task -> AssignmentPlanItem(task, null, index + 1) }
        changed(assigned + unassigned, now)
    }

    /**
     * Tira do plano as tarefas de [remove] (as fora dele, nada) e aplica as [changes] na ordem; depois, as posições de
     * cada camareira voltam a ir de 1 a n, na ordem resultante.
     */
    fun adjust(
        changes: List<AssignmentChange>,
        remove: Set<HousekeepingTaskId>,
        now: Instant,
    ) {
        val lists =
            items
                .filter { it.taskId !in remove }
                .groupBy { it.attendant }
                .mapValues { (_, list) -> list.sortedBy { it.position }.map { it.taskId }.toMutableList() }
                .toMutableMap()
        changes.forEach { change ->
            lists.values.forEach { it.remove(change.taskId) }
            val target = lists.getOrPut(change.attendant) { mutableListOf() }
            val index = change.position?.let { (it - 1).coerceAtMost(target.size) } ?: target.size
            target.add(index, change.taskId)
        }
        changed(renumbered(lists), now)
    }

    /**
     * Na publicação (D12 da E04), tira do plano os itens das tarefas [taskIds] que já terminaram, e as posições de cada
     * camareira voltam a ir de 1 a n; não muda o status nem a versão, que a publicação ([publish]) sobe.
     */
    fun dropFinished(taskIds: Set<HousekeepingTaskId>) {
        if (items.none { it.taskId in taskIds }) return
        items =
            renumbered(
                items
                    .filter { it.taskId !in taskIds }
                    .groupBy { it.attendant }
                    .mapValues { (_, list) -> list.sortedBy { it.position }.map { it.taskId } },
            )
    }

    /** Marca o plano publicado por [by] (os itens já foram gravados nas tarefas) e sobe a versão. */
    fun publish(
        now: Instant,
        by: UserId?,
    ) {
        status = AssignmentPlanStatus.PUBLISHED
        version += 1
        updatedAt = now
        publishedAt = now
        publishedBy = by
    }

    fun contains(taskId: HousekeepingTaskId): Boolean = items.any { it.taskId == taskId }

    /** Os itens das listas de cada camareira, com as posições de 1 a n na ordem de cada lista. */
    private fun renumbered(lists: Map<AttendantId?, List<HousekeepingTaskId>>): List<AssignmentPlanItem> =
        lists.flatMap { (attendant, tasks) ->
            tasks.mapIndexed { index, task -> AssignmentPlanItem(task, attendant, index + 1) }
        }

    private fun changed(
        newItems: List<AssignmentPlanItem>,
        now: Instant,
    ) {
        items = newItems
        status = AssignmentPlanStatus.DRAFT
        version += 1
        updatedAt = now
    }

    override fun toString(): String = "AssignmentPlan(id=$id, date=$businessDate, status=$status, version=$version)"

    companion object {
        /** Um plano novo e vazio, `DRAFT`, na versão 0. */
        fun create(
            date: LocalDate,
            now: Instant,
        ): AssignmentPlan =
            AssignmentPlan(
                AssignmentPlanId.new(),
                date,
                AssignmentPlanStatus.DRAFT,
                0,
                emptyList(),
                now,
                now,
                null,
                null,
            )
    }
}
