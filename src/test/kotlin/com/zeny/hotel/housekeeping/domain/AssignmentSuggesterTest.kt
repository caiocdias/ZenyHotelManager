package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.TaskSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalTime
import java.util.UUID
import kotlin.random.Random

/**
 * A sugestão da distribuição (M6 E03, Task 1; D4-E03, D5-E03 e Review Focus 2), sem Spring: só as camareiras dadas
 * recebem tarefas; a diferença de créditos entre a mais e a menos carregada nunca passa do maior crédito de uma tarefa;
 * os andares inteiros ficam com uma camareira quando isso já cumpre o limite; as chegadas vêm primeiro; o DND tira só a
 * `STAYOVER`; a saída não depende da ordem da entrada; e o algoritmo termina em todos os casos (`@Timeout` em outra
 * thread, que interrompe o teste mesmo com o laço sem ponto de interrupção).
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AssignmentSuggesterTest {
    private var nextTask = 0L

    private val ana = attendant(1, "Ana")
    private val bia = attendant(2, "Bia")
    private val cris = attendant(3, "Cris")
    private val dani = attendant(4, "Dani")
    private val eva = attendant(5, "Eva")

    @Test
    fun `only the given attendants receive tasks, each task exactly once`() {
        val tasks = floor("1", 4, 4, 2) + floor("2", 3, 3) + area("Lobby")

        val suggestion = AssignmentSuggester.suggest(tasks, listOf(bia, ana))

        suggestion.byAttendant.keys shouldBe setOf(ana.id, bia.id)
        suggestion.byAttendant.values
            .flatten()
            .shouldContainExactlyInAnyOrder(tasks.map { it.taskId })
        suggestion.unassigned.shouldBeEmpty()
    }

    @Test
    fun `the credit spread never exceeds the largest task credit`() {
        val scenarios =
            mapOf(
                "andar maior que a média" to
                    (floor("1", *IntArray(10) { 3 }) + floor("2", 3, 3) to listOf(ana, bia, cris)),
                "créditos 0 misturados" to
                    (floor("1", 0, 0, 0, 5) + floor("2", 0, 0) + area("Lobby") + area("Piscina") to listOf(ana, bia)),
                "todas as tarefas com 0" to (floor("1", 0, 0, 0) + floor("2", 0, 0, 0) to listOf(ana, bia, cris)),
                "uma só camareira" to (floor("1", 6, 4) + floor("2", 2, 2, 2) to listOf(ana)),
                "mais camareiras que tarefas" to (floor("1", 4, 4, 4) to listOf(ana, bia, cris, dani, eva)),
                "30 UHs em 3 andares" to (thirtyRooms() to listOf(ana, bia, cris, dani)),
                "créditos iguais" to
                    (floor("1", *IntArray(6) { 2 }) + floor("2", 2, 2, 2, 2) + floor("3", 2, 2) to crew(3)),
                "contraexemplo 5/4/3/3/3 com andares 2 e 10" to
                    (floor("2", 5, 4, 3) + floor("10", 3, 3) to listOf(ana, bia)),
                "um andar só, pesado" to (floor("1", 6, 6, 4, 2, 2, 1) to listOf(ana, bia, cris)),
            )

        scenarios.forEach { (name, input) ->
            withClue(name) { assertBalanced(input.first, input.second) }
        }
    }

    @Test
    fun `a spread exactly equal to the largest credit keeps the whole floors`() {
        val first = floor("1", 5, 5)
        val second = floor("2", 3, 2)

        val suggestion = AssignmentSuggester.suggest(first + second, listOf(ana, bia))

        suggestion.byAttendant shouldBe
            mapOf(ana.id to first.map { it.taskId }, bia.id to second.map { it.taskId })
    }

    @Test
    fun `random inputs always end balanced`() {
        val random = Random(20261118)
        repeat(RANDOM_CASES) { case ->
            val floors = listOf("1", "2", "3", "10", "Térreo", null)
            val tasks =
                List(random.nextInt(0, 31)) {
                    task(
                        floor = floors.random(random),
                        credits = random.nextInt(0, 9),
                        priority = random.nextInt(1, 6),
                        arrival = random.nextBoolean(),
                    )
                }
            val attendants = List(random.nextInt(1, 7)) { attendant(100L + it, "C$it") }
            withClue("caso $case") { assertBalanced(tasks, attendants) }
        }
    }

    @Test
    fun `when the whole floors already meet the limit no floor is split, the heaviest to the first by name`() {
        val first = floor("1", 4, 4)
        val second = floor("2", 3, 3, 2)
        val third = floor("3", 2, 2)
        val fourth = floor("4", 2, 2)

        val suggestion = AssignmentSuggester.suggest(first + second + third + fourth, listOf(bia, ana))

        listOf(first, second, third, fourth).forEach { group ->
            withClue("andar ${group.first().floor}") {
                suggestion.byAttendant.values.count { list -> group.any { it.taskId in list } } shouldBe 1
            }
        }
        suggestion.byAttendant.getValue(ana.id).take(2) shouldContainExactly first.map { it.taskId }
    }

    @Test
    fun `the counterexample splits only what is needed and keeps the natural floor order`() {
        val two = floor("2", 5, 4, 3)
        val ten = floor("10", 3, 3)

        val suggestion = AssignmentSuggester.suggest(two + ten, listOf(ana, bia))

        val loads = suggestion.byAttendant.mapValues { (_, ids) -> ids.sumOf { id -> (two + ten).credits(id) } }
        loads shouldBe mapOf(ana.id to 9, bia.id to 9)
        suggestion.byAttendant.getValue(bia.id) shouldContainExactly
            listOf(two[2].taskId, ten[0].taskId, ten[1].taskId)
    }

    @Test
    fun `arrivals come first in each attendant's order, then priority, natural floor and number`() {
        val plain = task(floor = "1", credits = 2, priority = 5, room = "101")
        val lateArrival = task(floor = "3", credits = 2, priority = 1, arrival = true, room = "301")
        val urgentArrival = task(floor = "2", credits = 2, priority = 3, arrival = true, room = "201")
        val tenth = task(floor = "10", credits = 2, priority = 2, room = "1001")
        val second = task(floor = "2", credits = 2, priority = 2, room = "202")
        val textFloor = task(floor = "Térreo", credits = 2, priority = 2, room = "T1")
        val noFloor = task(floor = null, credits = 0, priority = 2, room = null, area = "Lobby")
        val roomNine = task(floor = "2", credits = 2, priority = 2, room = "9")

        val suggestion =
            AssignmentSuggester.suggest(
                listOf(noFloor, textFloor, tenth, second, plain, lateArrival, roomNine, urgentArrival),
                listOf(ana),
            )

        suggestion.byAttendant.getValue(ana.id) shouldContainExactly
            listOf(urgentArrival, lateArrival, plain, roomNine, second, tenth, textFloor, noFloor).map { it.taskId }
    }

    @Test
    fun `do not disturb leaves out only the stayover of the room`() {
        val stayoverDnd = task(floor = "1", credits = 2, source = TaskSource.STAYOVER, dnd = true)
        val checkoutDnd = task(floor = "1", credits = 4, source = TaskSource.CHECKOUT, dnd = true)
        val stayover = task(floor = "1", credits = 2, source = TaskSource.STAYOVER)

        val suggestion = AssignmentSuggester.suggest(listOf(stayoverDnd, checkoutDnd, stayover), listOf(ana, bia))

        suggestion.unassigned shouldContainExactly listOf(stayoverDnd.taskId)
        suggestion.byAttendant.values
            .flatten()
            .shouldContainExactlyInAnyOrder(checkoutDnd.taskId, stayover.taskId)
    }

    @Test
    fun `the same input shuffled gives the same output`() {
        val tasks = thirtyRooms() + area("Lobby") + area("Piscina") + task(floor = "2", credits = 3, arrival = true)
        val attendants = listOf(ana, bia, cris, dani)
        val expected = AssignmentSuggester.suggest(tasks, attendants)

        val random = Random(7)
        repeat(SHUFFLES) {
            val shuffled = AssignmentSuggester.suggest(tasks.shuffled(random), attendants.shuffled(random))
            shuffled shouldBe expected
            shuffled.byAttendant.keys.toList() shouldBe expected.byAttendant.keys.toList()
        }
    }

    @Test
    fun `two tasks in the same room are ordered by id, whatever the input order`() {
        val first = task(floor = "1", credits = 2, source = TaskSource.STAYOVER, room = "101")
        val second = task(floor = "1", credits = 3, source = TaskSource.SCHEDULED, room = "101")
        val other = task(floor = "2", credits = 4, room = "201")
        val tasks = listOf(second, other, first)
        val expected = AssignmentSuggester.suggest(tasks, listOf(ana, bia))

        expected.byAttendant.values.single { first.taskId in it } shouldContainExactly
            listOf(first.taskId, second.taskId)
        val random = Random(11)
        repeat(SHUFFLES) {
            AssignmentSuggester.suggest(tasks.shuffled(random), listOf(bia, ana)) shouldBe expected
        }
    }

    @Test
    fun `a repeated attendant id is one attendant, and every task is still assigned`() {
        val tasks = floor("1", 4, 2) + floor("2", 3)

        val suggestion = AssignmentSuggester.suggest(tasks, listOf(ana, ana.copy(name = "Zuleica")))

        suggestion.byAttendant.keys shouldBe setOf(ana.id)
        suggestion.byAttendant.getValue(ana.id).shouldContainExactlyInAnyOrder(tasks.map { it.taskId })
        suggestion.unassigned.shouldBeEmpty()
    }

    @Test
    fun `without attendants every task is unassigned, in the suggestion order`() {
        val plain = task(floor = "1", credits = 2, priority = 2, room = "101")
        val arrival = task(floor = "2", credits = 2, priority = 2, arrival = true, room = "201")
        val dnd = task(floor = "1", credits = 2, priority = 4, source = TaskSource.STAYOVER, dnd = true, room = "102")

        val suggestion = AssignmentSuggester.suggest(listOf(plain, dnd, arrival), emptyList())

        suggestion.byAttendant shouldBe emptyMap()
        suggestion.unassigned shouldContainExactly listOf(arrival.taskId, dnd.taskId, plain.taskId)
    }

    @Test
    fun `a suggestion task never has negative credits, which the termination proof relies on`() {
        shouldThrow<IllegalArgumentException> { task(floor = "1", credits = -1) }
    }

    @Test
    fun `early comes before other arrivals, which come before other tasks`() {
        val tasks = earlyScenario()

        val suggestion = AssignmentSuggester.suggest(tasks, listOf(ana))

        suggestion.byAttendant.getValue(ana.id) shouldContainExactly tasks.map { it.taskId }
    }

    @Test
    fun `two early arrivals are ordered by time`() {
        val eleven = task(floor = "1", credits = 2, arrival = true, arrivalTime = LocalTime.of(11, 0), early = true)
        val ten = task(floor = "1", credits = 2, arrival = true, arrivalTime = LocalTime.of(10, 0), early = true)

        val suggestion = AssignmentSuggester.suggest(listOf(eleven, ten), listOf(ana))

        suggestion.byAttendant.getValue(ana.id) shouldContainExactly listOf(ten.taskId, eleven.taskId)
    }

    @Test
    fun `an early check-in comes before a non-early arrival with an earlier time`() {
        // A precedência é do early, não da hora: 08:00 sem early vem depois de 12:00 com early.
        val earlier = task(floor = "1", credits = 2, arrival = true, arrivalTime = LocalTime.of(8, 0))
        val early = task(floor = "1", credits = 2, arrival = true, arrivalTime = LocalTime.of(12, 0), early = true)

        AssignmentSuggester
            .suggest(listOf(earlier, early), listOf(ana))
            .byAttendant
            .getValue(ana.id) shouldContainExactly listOf(early.taskId, earlier.taskId)
        AssignmentSuggester
            .suggest(listOf(early, earlier), listOf(ana))
            .byAttendant
            .getValue(ana.id) shouldContainExactly listOf(early.taskId, earlier.taskId)
    }

    @Test
    fun `a time without an expected arrival does not reorder a task that is not an arrival`() {
        // `arrivalTime` só pesa entre chegadas (KDoc): a saída com hora anômala não passa na frente da chegada.
        val arrivalNoTime = task(floor = "1", credits = 2, arrival = true)
        val checkout = task(floor = "1", credits = 2, arrival = false, arrivalTime = LocalTime.of(6, 0))

        AssignmentSuggester
            .suggest(listOf(checkout, arrivalNoTime), listOf(ana))
            .byAttendant
            .getValue(ana.id) shouldContainExactly listOf(arrivalNoTime.taskId, checkout.taskId)
    }

    @Test
    fun `the order is the same for any permutation of the input`() {
        val tasks = earlyScenario()
        val expected = AssignmentSuggester.suggest(tasks, listOf(ana))

        repeat(SHUFFLES_WITH_TIMES) { seed ->
            AssignmentSuggester.suggest(tasks.shuffled(Random(seed)), listOf(ana)) shouldBe expected
        }
    }

    @Test
    fun `an early check-in without an expected arrival or without a time is refused`() {
        shouldThrow<IllegalArgumentException> {
            task(floor = "1", credits = 1, arrival = false, arrivalTime = LocalTime.of(9, 0), early = true)
        }
        shouldThrow<IllegalArgumentException> {
            task(floor = "1", credits = 1, arrival = true, arrivalTime = null, early = true)
        }
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    private fun assertBalanced(
        tasks: List<SuggestionTask>,
        attendants: List<Attendant>,
    ) {
        val suggestion = AssignmentSuggester.suggest(tasks, attendants)
        suggestion.byAttendant.keys shouldBe attendants.map { it.id }.toSet()
        suggestion.byAttendant.values
            .flatten()
            .shouldContainExactlyInAnyOrder(tasks.map { it.taskId })
        val loads = suggestion.byAttendant.values.map { ids -> ids.sumOf { tasks.credits(it) } }
        val largest = tasks.maxOfOrNull { it.credits } ?: 0
        (loads.max() - loads.min()) shouldBeLessThanOrEqual largest
    }

    /** Já na ordem esperada: early 09:00, chegadas 13:00 e 15:00, chegada sem hora, saída, permanência. */
    private fun earlyScenario(): List<SuggestionTask> =
        listOf(
            task("1", 2, arrival = true, arrivalTime = LocalTime.of(9, 0), early = true),
            task("1", 2, arrival = true, arrivalTime = LocalTime.of(13, 0)),
            task("1", 2, arrival = true, arrivalTime = LocalTime.of(15, 0)),
            task("1", 2, arrival = true),
            task("1", 2, priority = 5, source = TaskSource.CHECKOUT),
            task("1", 2, priority = 3, source = TaskSource.STAYOVER),
        )

    private fun crew(size: Int) = listOf(ana, bia, cris, dani, eva).take(size)

    private fun List<SuggestionTask>.credits(id: HousekeepingTaskId) = single { it.taskId == id }.credits

    /** 30 UHs, 10 por andar, com créditos de 1 a 6 em padrões diferentes por andar. */
    private fun thirtyRooms(): List<SuggestionTask> =
        floor("1", 6, 6, 6, 4, 4, 4, 2, 2, 2, 2) +
            floor("2", 6, 4, 2, 6, 4, 2, 6, 4, 2, 1) +
            floor("3", 2, 2, 2, 2, 2, 2, 3, 3, 3, 5)

    private fun floor(
        name: String,
        vararg credits: Int,
    ): List<SuggestionTask> = credits.mapIndexed { index, c -> task(floor = name, credits = c, room = "$name$index") }

    private fun area(name: String) = task(floor = null, credits = 0, room = null, area = name)

    @Suppress("LongParameterList") // os campos da tarefa sugerida, com padrões
    private fun task(
        floor: String?,
        credits: Int,
        priority: Int = 3,
        arrival: Boolean = false,
        arrivalTime: LocalTime? = null,
        early: Boolean = false,
        source: TaskSource = TaskSource.CHECKOUT,
        dnd: Boolean = false,
        room: String? = "R${nextTask + 1}",
        area: String? = null,
    ) = SuggestionTask(
        taskId = HousekeepingTaskId(UUID(0, ++nextTask)),
        roomNumber = room,
        areaName = area,
        floor = floor,
        credits = credits,
        priority = priority,
        arrivalExpected = arrival,
        arrivalTime = arrivalTime,
        earlyCheckIn = early,
        source = source,
        doNotDisturb = dnd,
    )

    private fun attendant(
        n: Long,
        name: String,
    ) = Attendant(AttendantId(UUID(1, n)), name)

    private companion object {
        const val RANDOM_CASES = 1000
        const val SHUFFLES = 20
        const val SHUFFLES_WITH_TIMES = 50
    }
}
