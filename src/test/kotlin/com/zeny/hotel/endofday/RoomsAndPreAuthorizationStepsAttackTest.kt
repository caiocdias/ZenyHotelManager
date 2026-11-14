package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.steps.CardPreAuthorizationsStep
import com.zeny.hotel.endofday.application.steps.OccupiedRoomsDirtyStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O ataque às etapas `card-pre-authorizations` (600) e `occupied-rooms-dirty` (700, RN-09) do M6 E11, Task 16: a
 * escrita de status da UH concorrendo, com threads de verdade, com o check-out real do `frontdesk` e com a
 * governança; UH em OOS, OOO e inativa; UH já Suja; repetição da etapa para a mesma data; a falha de um item que não
 * barra os outros; e a etapa das pré-autorizações que não toca em nada. Contexto das etapas reais no banco isolado;
 * cada teste afirma só sobre as próprias UHs e termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class RoomsAndPreAuthorizationStepsAttackTest(
    @Autowired private val rooms: RoomsApi,
    @Autowired private val probe: RoomsMarkProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val hotel: HotelApi,
    @Autowired private val dirtyStep: OccupiedRoomsDirtyStep,
    @Autowired private val preAuthStep: CardPreAuthorizationsStep,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing = support.billing
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, rooms, jsonMapper)
    private val today get() = hotel.currentBusinessDate()

    private data class Change(
        val field: String,
        val to: String?,
        val source: String,
    )

    // --- Check-out real concorrendo -----------------------------------------------------------------------------

    @Test
    fun `a real check-out finished before the item takes the room leaves it vacant, dirty by check-out, no issue`() {
        val pool = Executors.newCachedThreadPool()
        val stay = frontdesk.stay()
        val booked = readyToLeave(stay)
        val room = RoomId(stay.room)
        probe.beforeMarkDirty { _, id ->
            if (id == room) pool.submit(Callable { checkOut(booked.id) }).get(HOOK_LIMIT, TimeUnit.SECONDS)
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            runStatus(run) shouldBe "COMPLETED"
            issues(run, room) shouldBe 0
            rooms.status(room)!!.occupancy shouldBe OccupancyStatus.VACANT
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            housekeeping(room).filter { it.to == "DIRTY" }.map { it.source } shouldBe listOf("CHECK_OUT")
        } finally {
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a real check-out arriving while the item holds the room waits, succeeds and leaves one dirty by end of day`() {
        val pool = Executors.newCachedThreadPool()
        val stay = frontdesk.stay()
        val booked = readyToLeave(stay)
        val room = RoomId(stay.room)
        val checkout = AtomicReference<Future<MockHttpServletResponse>>()
        probe.beforeMarkDirty { api, id ->
            if (id == room) {
                api.markDirty(id, RoomStatusSource.END_OF_DAY) // trava a UH na transação do item
                checkout.set(pool.submit(Callable { checkOut(booked.id) }))
                Thread.sleep(HOLD_MILLIS) // o check-out enfileira na trava da UH
            }
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            val response = checkout.get().get(HOOK_LIMIT, TimeUnit.SECONDS)
            with(billing) { response.expect(200) }
            runStatus(run) shouldBe "COMPLETED"
            issues(run, room) shouldBe 0
            rooms.status(room)!!.occupancy shouldBe OccupancyStatus.VACANT
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            housekeeping(room).filter { it.to == "DIRTY" }.map { it.source } shouldBe listOf("END_OF_DAY")
        } finally {
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Governança concorrendo ---------------------------------------------------------------------------------

    @Test
    fun `housekeeping inspecting the room before the item takes it does not stop the room from becoming dirty`() {
        val room = occupiedClean()
        val pool = Executors.newCachedThreadPool()
        probe.beforeMarkDirty { _, id ->
            if (id == room) {
                pool
                    .submit { rooms.markInspected(room, RoomStatusSource.MANUAL) }
                    .get(HOOK_LIMIT, TimeUnit.SECONDS)
            }
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            issues(run, room) shouldBe 0
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            housekeeping(room).filter { it.to == "INSPECTED" || it.to == "DIRTY" }.map { it.to to it.source } shouldBe
                listOf("INSPECTED" to "MANUAL", "DIRTY" to "END_OF_DAY")
        } finally {
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `housekeeping cleaning the room while the item holds it waits and runs after, no issue, final clean`() {
        val room = occupiedClean()
        val pool = Executors.newCachedThreadPool()
        val cleaning = AtomicReference<Future<*>>()
        probe.beforeMarkDirty { api, id ->
            if (id == room) {
                api.markDirty(id, RoomStatusSource.END_OF_DAY)
                cleaning.set(pool.submit { rooms.markClean(room, false, RoomStatusSource.MANUAL) })
                Thread.sleep(HOLD_MILLIS)
            }
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            cleaning.get().get(HOOK_LIMIT, TimeUnit.SECONDS)
            issues(run, room) shouldBe 0
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            housekeeping(room).filter { it.to == "DIRTY" || it.to == "CLEAN" }.map { it.to to it.source } shouldBe
                listOf("CLEAN" to "MANUAL", "DIRTY" to "END_OF_DAY", "CLEAN" to "MANUAL") // o 1º é o preparo da UH
        } finally {
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Estados especiais da UH --------------------------------------------------------------------------------

    @Test
    fun `an occupied room out of service and one forced occupied under out of order are both marked dirty`() {
        val type = support.inventory.rooms.createRoomType()
        val oos = occupiedClean(type)
        val ooo = RoomId(support.inventory.rooms.createRoom(type))
        rooms.markClean(ooo, false, RoomStatusSource.MANUAL)
        openPeriod(oos, "OUT_OF_SERVICE").expectCreated()
        openPeriod(ooo, "OUT_OF_ORDER").expectCreated()
        rooms.markOccupied(ooo, 1, RoomStatusSource.CHECK_IN) // o RN-07 barra a API; o dado preso existe
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            issues(run, oos) + issues(run, ooo) shouldBe 0
            rooms.status(oos)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            rooms.status(ooo)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            housekeeping(oos).last().source shouldBe "END_OF_DAY"
            housekeeping(ooo).last().source shouldBe "END_OF_DAY"
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an inactive occupied room is not an item and keeps its status without history`() {
        val room = occupiedClean()
        jdbc.sql("UPDATE rooms.room SET active = false WHERE id = :id").param("id", room.value).update()
        val before = housekeeping(room)
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            issues(run, room) shouldBe 0
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            housekeeping(room) shouldBe before
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an occupied room that is already dirty gets no new history and a repeat of the step adds none`() {
        val room = occupiedClean()
        rooms.markDirty(room, RoomStatusSource.MANUAL)
        val before = housekeeping(room)
        try {
            val first = executeStep()
            val second = executeStep()

            first.second.shouldBeEmpty()
            second.second.shouldBeEmpty()
            housekeeping(room) shouldBe before
            // As UHs de outros testes também podem ser item na primeira passada; na segunda, nenhuma é.
            second.first shouldBe EndOfDayStepOutcome.NOT_APPLICABLE
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `two closes in a row mark an occupied room once with one end-of-day history entry`() {
        val room = occupiedClean()
        try {
            EndOfDayTestRuns.closeCurrentDate(context)
            val second = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(second, "occupied-rooms-dirty").first shouldBe "COMPLETED"
            housekeeping(room).count { it.source == "END_OF_DAY" } shouldBe 1
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Falha de item ------------------------------------------------------------------------------------------

    @Test
    fun `a refused room becomes a ROOM_DIRTY_FAILED issue with the room id and the other rooms are still marked`() {
        val refused = occupiedClean()
        val fine = occupiedClean()
        probe.beforeMarkDirty { _, id ->
            if (id == refused) throw BusinessRuleException(RoomsErrorCodes.ROOM_INACTIVE, "Recusa forçada pelo teste.")
        }
        try {
            val (outcome, raised) = executeStep()

            outcome shouldBe EndOfDayStepOutcome.DONE
            val mine = raised.filter { it.second == refused.value.toString() }
            mine shouldBe
                listOf(Triple("ROOM_DIRTY_FAILED", refused.value.toString(), "ROOM_DIRTY_FAILED: ROOM_INACTIVE"))
            raised.filter { it.second == fine.value.toString() }.shouldBeEmpty()
            mine.single().toString() shouldNotContain "Recusa forçada"
            rooms.status(refused)!!.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            housekeeping(refused).none { it.source == "END_OF_DAY" } shouldBe true
            rooms.status(fine)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Pré-autorizações ---------------------------------------------------------------------------------------

    @Test
    fun `the pre-authorization step is not applicable, raises nothing and leaves a held authorization untouched`() {
        val stay = frontdesk.stay()
        val booked = stay.checkedIn(preAuthorization = frontdesk.preAuthorization())
        val before = frontdesk.preAuthorizations(booked.folio).single()
        val raised = mutableListOf<Triple<String, String, String>>()
        try {
            val outcome =
                tx.execute { _ ->
                    preAuthStep.execute(
                        EndOfDayStepContext(EndOfDayRunId(UuidV7.generate()), today) { k, r, m ->
                            raised.add(Triple(k, r.orEmpty(), m))
                        },
                    )
                }
            outcome shouldBe EndOfDayStepOutcome.NOT_APPLICABLE
            raised.shouldBeEmpty()
            val run = EndOfDayTestRuns.closeCurrentDate(context)
            stepOf(run, "card-pre-authorizations") shouldBe ("COMPLETED" to "NOT_APPLICABLE")
            frontdesk.preAuthorizations(booked.folio).single() shouldBe before
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Apoio --------------------------------------------------------------------------------------------------

    private fun readyToLeave(stay: FrontdeskTestSupport.Stay): FrontdeskTestSupport.Booked {
        // Sai no dia da chegada pagando só a diária de 400,00, que o fechamento e o check-out lançam pela mesma chave.
        val booked = stay.checkedIn(departure = today.plusDays(3))
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")
        return booked
    }

    private fun checkOut(id: UUID): MockHttpServletResponse =
        billing.call(POST, "/api/v1/reservations/$id/check-out", iam.asAdmin(), null, UuidV7.generate().toString())

    private fun occupiedClean(type: UUID = support.inventory.rooms.createRoomType()): RoomId {
        val room = RoomId(support.inventory.rooms.createRoom(type))
        rooms.markClean(room, false, RoomStatusSource.MANUAL)
        rooms.markOccupied(room, 1, RoomStatusSource.CHECK_IN)
        return room
    }

    private fun openPeriod(
        room: RoomId,
        kind: String,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/rooms/${room.value}/out-of-order",
            iam.asAdmin(),
            mapOf(
                "kind" to kind,
                "startsOn" to today,
                "endsOn" to today.plusDays(1),
                "reason" to "Teste de ataque",
                "responsible" to "Manutenção",
                "returnStatus" to "DIRTY",
            ),
        )

    private fun MockHttpServletResponse.expectCreated() {
        with(billing) { expect(201) }
    }

    /** Roda a etapa de verdade, numa transação, e devolve o resultado e as pendências levantadas (tipo, ref, msg). */
    private fun executeStep(): Pair<EndOfDayStepOutcome, List<Triple<String, String, String>>> {
        val raised = mutableListOf<Triple<String, String, String>>()
        val outcome =
            tx.execute { _ ->
                dirtyStep.execute(
                    EndOfDayStepContext(EndOfDayRunId(UuidV7.generate()), today) { k, r, m ->
                        raised.add(Triple(k, r.orEmpty(), m))
                    },
                )
            }
        return outcome to raised
    }

    private fun housekeeping(room: RoomId): List<Change> =
        jdbc
            .sql(
                "SELECT field, to_value, source FROM rooms.room_status_change WHERE room_id = :id " +
                    "AND field = 'HOUSEKEEPING_STATUS' ORDER BY changed_at, id",
            ).param("id", room.value)
            .query { rs, _ -> Change(rs.getString("field"), rs.getString("to_value"), rs.getString("source")) }
            .list()

    private fun stepOf(
        run: EndOfDayRunId,
        name: String,
    ): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", name)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun runStatus(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun issues(
        run: EndOfDayRunId,
        room: RoomId,
    ): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'ROOM_DIRTY_FAILED' " +
                    "AND reference = :ref",
            ).param("id", run.value)
            .param("ref", room.value.toString())
            .query(Int::class.java)
            .single()

    private companion object {
        const val HOOK_LIMIT = 30L
        const val HOLD_MILLIS = 1500L
    }
}
