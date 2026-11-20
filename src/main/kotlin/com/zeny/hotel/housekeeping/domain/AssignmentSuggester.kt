package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.TaskSource
import java.time.LocalTime
import java.util.UUID

/**
 * Id de uma camareira na sugestão da distribuição: próprio do `domain`, para o algoritmo não depender do `staff`. A
 * `application` o converte de e para o `EmployeeId` do colaborador escalado.
 */
@JvmInline
value class AttendantId(
    val value: UUID,
)

/**
 * Uma tarefa elegível para a sugestão, com o que o algoritmo usa: o local (o número da UH ou o nome da área), o andar
 * (texto livre do cadastro; `null` sem andar), os créditos, a prioridade, a chegada prevista (recalculada pela
 * `application`), a hora prevista dessa chegada ([arrivalTime], a menor da UH; `null` sem hora), se é um early
 * check-in ([earlyCheckIn]: hora estritamente antes do `checkInTime` da política, decidido pela `application`), a
 * origem e se a UH está em "Não perturbe" no momento da sugestão. Os créditos nunca são negativos: a prova de término
 * de [AssignmentSuggester] depende disso. O early exige chegada prevista e hora.
 */
data class SuggestionTask(
    val taskId: HousekeepingTaskId,
    val roomNumber: String?,
    val areaName: String?,
    val floor: String?,
    val credits: Int,
    val priority: Int,
    val arrivalExpected: Boolean,
    val source: TaskSource,
    val doNotDisturb: Boolean,
    val arrivalTime: LocalTime? = null,
    val earlyCheckIn: Boolean = false,
) {
    init {
        require(credits >= 0) { "Créditos negativos: $credits" }
        require(!earlyCheckIn || (arrivalExpected && arrivalTime != null)) {
            "Early check-in sem chegada prevista ou sem hora"
        }
    }
}

/** Uma camareira que pode receber tarefas, com o nome usado no desempate. */
data class Attendant(
    val id: AttendantId,
    val name: String,
)

/**
 * A distribuição sugerida: as tarefas de cada camareira, na ordem de execução (todas as camareiras dadas, na ordem de
 * nome e id, inclusive as sem tarefa), e as que ficaram sem camareira, na ordem da sugestão.
 */
data class Suggestion(
    val byAttendant: Map<AttendantId, List<HousekeepingTaskId>>,
    val unassigned: List<HousekeepingTaskId>,
)

/**
 * A sugestão da distribuição das tarefas de governança (RF-GOV-03, D4-E03 e D5-E03): uma heurística gulosa e
 * determinística, não ótima, que balanceia os créditos entre as camareiras e agrupa por andar.
 *
 * 1. **DND:** a `STAYOVER` de UH em "Não perturbe" fica sem camareira; as outras origens entram normalmente.
 * 2. **Ordem:** as tarefas são ordenadas por early check-in (desc), chegada prevista (desc), hora da chegada
 *    (crescente, sem hora por último; só pesa entre chegadas), prioridade (desc), andar em ordem natural (números
 *    como números, depois texto, depois sem andar), número da UH ou nome da área (também em ordem natural) e id.
 * 3. **Andares inteiros:** os grupos por andar (o "sem andar" é um grupo) vão, do maior total de créditos para o
 *    menor (desempate pela ordem natural do andar), cada um para a camareira menos carregada (desempate por nome e
 *    depois id).
 * 4. **Rebalanceamento:** enquanto a diferença entre a mais carregada (M) e a menos carregada (m) passar do maior
 *    crédito de uma tarefa, a tarefa de menor crédito maior que 0 da mais carregada (desempate: a última na ordem do
 *    passo 2) vai para a menos carregada. Cada movimento usa um crédito c com 0 < c ≤ maior crédito < M − m, e a soma
 *    dos quadrados das cargas muda em 2c(c − (M − m)) < 0: ela é um inteiro não negativo que cai a cada passo, então o
 *    laço termina. Ao terminar, a diferença é ≤ o maior crédito; se o passo 3 já a cumpria, nenhum andar é dividido.
 * 5. **Ordem de execução:** a de cada camareira segue a do passo 2: o early vem primeiro, depois as chegadas por hora
 *    (as sem hora no fim delas) e então as demais tarefas.
 *
 * A saída não depende da ordem das listas de entrada. Sem camareira, todas as tarefas ficam sem camareira.
 */
object AssignmentSuggester {
    fun suggest(
        tasks: List<SuggestionTask>,
        attendants: List<Attendant>,
    ): Suggestion {
        val ordered = tasks.sortedWith(EXECUTION_ORDER)
        val crew = attendants.sortedWith(ATTENDANT_ORDER).distinctBy { it.id }
        val (left, distributable) = ordered.partition { crew.isEmpty() || it.isDisturbed() }
        val loads = crew.associateWith { mutableListOf<SuggestionTask>() }
        if (crew.isNotEmpty()) {
            assignWholeFloors(distributable, loads)
            rebalance(loads, maxCredit = distributable.maxOfOrNull { it.credits } ?: 0)
        }
        return Suggestion(
            loads.entries.associate { (attendant, assigned) ->
                attendant.id to assigned.sortedWith(EXECUTION_ORDER).map { it.taskId }
            },
            left.map { it.taskId },
        )
    }

    private fun SuggestionTask.isDisturbed() = doNotDisturb && source == TaskSource.STAYOVER

    /** Passo 3: cada andar inteiro para a camareira menos carregada, do andar de maior total para o de menor. */
    private fun assignWholeFloors(
        tasks: List<SuggestionTask>,
        loads: Map<Attendant, MutableList<SuggestionTask>>,
    ) {
        tasks
            .groupBy { it.floor }
            .entries
            .sortedWith(FLOOR_ORDER)
            .forEach { (_, floor) -> loads.getValue(leastLoaded(loads)) += floor }
    }

    /** Passo 4: move a menor tarefa com crédito da mais para a menos carregada até cumprir o limite. */
    private fun rebalance(
        loads: Map<Attendant, MutableList<SuggestionTask>>,
        maxCredit: Int,
    ) {
        while (true) {
            val most = mostLoaded(loads)
            val least = leastLoaded(loads)
            if (loads.getValue(most).credits() - loads.getValue(least).credits() <= maxCredit) return
            val moved =
                loads
                    .getValue(most)
                    .filter { it.credits > 0 }
                    .minWith(compareBy<SuggestionTask> { it.credits }.then(EXECUTION_ORDER.reversed()))
            loads.getValue(most) -= moved
            loads.getValue(least) += moved
        }
    }

    private fun leastLoaded(loads: Map<Attendant, List<SuggestionTask>>): Attendant =
        loads.keys.minWith(compareBy<Attendant> { loads.getValue(it).credits() }.then(ATTENDANT_ORDER))

    private fun mostLoaded(loads: Map<Attendant, List<SuggestionTask>>): Attendant =
        loads.keys.minWith(compareByDescending<Attendant> { loads.getValue(it).credits() }.then(ATTENDANT_ORDER))

    private fun List<SuggestionTask>.credits() = sumOf { it.credits }

    /**
     * Ordem natural de um texto do cadastro: números como números (`"2"` antes de `"10"`), depois texto, depois
     * `null`; empate pelo próprio texto.
     */
    private val NATURAL: Comparator<String?> =
        compareBy<String?>({ it == null }, { it?.toBigIntegerOrNull() == null }, { it?.toBigIntegerOrNull() }, { it })

    private val EXECUTION_ORDER: Comparator<SuggestionTask> =
        compareByDescending<SuggestionTask> { it.earlyCheckIn }
            .thenByDescending { it.arrivalExpected }
            .thenBy(nullsLast()) { it.arrivalTime }
            .thenByDescending { it.priority }
            .thenBy(NATURAL) { it.floor }
            .thenBy(NATURAL) { it.roomNumber ?: it.areaName }
            .thenBy { it.taskId.value }

    /** Os andares do maior total de créditos para o menor; empate pela ordem natural do andar. */
    private val FLOOR_ORDER: Comparator<Map.Entry<String?, List<SuggestionTask>>> =
        compareByDescending<Map.Entry<String?, List<SuggestionTask>>> { it.value.credits() }.thenBy(NATURAL) { it.key }

    private val ATTENDANT_ORDER: Comparator<Attendant> = compareBy<Attendant>({ it.name }, { it.id.value })
}
