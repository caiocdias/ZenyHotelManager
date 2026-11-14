package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayIssueSink
import com.zeny.hotel.endofday.application.EndOfDayItems
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.steps.BlockReleaseStep
import com.zeny.hotel.endofday.application.steps.PendingDeparturesStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lacunas das etapas `pending-departures` (200) e `block-release` (400) do M6 E11, apontadas pelas revisões: o
 * resultado `NOT_APPLICABLE`, a fronteira da data da saída, a recusa de um bloqueio (pendência com o id, sem dado
 * pessoal, o outro bloqueio segue) e a segunda execução sem efeito duplicado. As etapas rodam direto sobre o banco
 * isolado, com a API real do `reservations`; a recusa é uma de verdade (corte ainda não alcançado), obtida listando
 * um bloqueio fora do vencimento. Asserções só sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayStageGapsTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val reservations: ReservationsApi,
    @Autowired private val items: EndOfDayItems,
    @Autowired private val departures: PendingDeparturesStep,
    @Autowired iam: IamTestSupport,
    @Autowired roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, roomsApi, jsonMapper)

    private data class Raised(
        val kind: String,
        val reference: String?,
        val message: String,
    )

    private class Sink : EndOfDayIssueSink {
        val issues = CopyOnWriteArrayList<Raised>()

        override fun raise(
            kind: String,
            reference: String?,
            message: String,
        ) {
            issues += Raised(kind, reference, message)
        }
    }

    private fun contextOf(
        sink: Sink,
        date: LocalDate = hotel.currentBusinessDate(),
    ) = EndOfDayStepContext(EndOfDayRunId(UUID.randomUUID()), date, sink)

    // --- pending-departures ---------------------------------------------------------------------------------------

    @Test
    fun `pending-departures with nothing due is not applicable and raises nothing`() {
        val sink = Sink()

        // uma data em que nenhuma hospedada pode ter saída pendente, seja qual for o que o banco isolado já tem
        departures.execute(contextOf(sink, LocalDate.of(2000, 1, 1))) shouldBe EndOfDayStepOutcome.NOT_APPLICABLE

        sink.issues.shouldBeEmpty()
    }

    @Test
    fun `pending-departures ignores a stay leaving after the date and one that never checked in`() {
        val date = hotel.currentBusinessDate()
        val stay = frontdesk.stay(rooms = 3)
        val tomorrow = stay.checkedIn(departure = date.plusDays(1), room = stay.rooms[0])
        val due = stay.checkedIn(departure = date, room = stay.rooms[1])
        // sai na data, mas ainda não fez o check-in: não é hospedada, então não é saída pendente
        val gone = stay.book(frontdesk.guest(), departure = date, room = stay.rooms[2])
        val sink = Sink()

        departures.execute(contextOf(sink)) shouldBe EndOfDayStepOutcome.DONE

        val own = sink.issues.filter { it.reference in setOf(tomorrow.id, due.id, gone.id).map(UUID::toString) }
        own.map { it.reference } shouldContainExactly listOf(due.id.toString())
        support.status(tomorrow.id) shouldBe "IN_HOUSE"
    }

    // --- block-release --------------------------------------------------------------------------------------------

    @Test
    fun `block-release refusal of one block keeps the other released and raises an issue with the id only`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(6, date)
        val plan = support.plan(listOf(type))
        val night = date.plusDays(7)
        val due = block(plan.id, type, night, 2, cutoff = date)
        val notYet = block(plan.id, type, night, 3, cutoff = date.plusDays(1))
        val also = block(plan.id, type, night, 1, cutoff = date)
        val blockedBefore = inventory.row(type, night)!!.blocked
        val sink = Sink()

        // o corte do `notYet` ainda não chegou: a recusa é a do domínio (ROOM_BLOCK_CUTOFF_NOT_REACHED)
        val outcome = stepOver(listOf(notYet, due, also)).execute(contextOf(sink))

        outcome shouldBe EndOfDayStepOutcome.DONE
        statusOf(due) shouldBe "RELEASED"
        statusOf(also) shouldBe "RELEASED"
        statusOf(notYet) shouldBe "ACTIVE"
        support.allocationRow(notYet, type, night) shouldBe Triple(3, 0, 0)
        inventory.row(type, night)!!.blocked shouldBe blockedBefore - 2 - 1
        sink.issues shouldContainExactly
            listOf(
                Raised(
                    "BLOCK_RELEASE_FAILED",
                    notYet.toString(),
                    "BLOCK_RELEASE_FAILED: ROOM_BLOCK_CUTOFF_NOT_REACHED",
                ),
            )
        val message = sink.issues.single().message
        message shouldMatch Regex("^BLOCK_RELEASE_FAILED: [A-Z_]+$")
        message shouldNotContain notYet.toString()
        val name = jdbc.sql("SELECT name FROM reservations.room_block WHERE id = :id").param("id", notYet)
        message shouldNotContain name.query(String::class.java).single()
    }

    @Test
    fun `block-release run twice for the same date releases once and changes nothing the second time`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(5, date)
        val plan = support.plan(listOf(type))
        val night = date.plusDays(7)
        val due = block(plan.id, type, night, 3, cutoff = date)
        val first = Sink()
        val second = Sink()

        stepOver(listOf(due)).execute(contextOf(first)) shouldBe EndOfDayStepOutcome.DONE
        val afterFirst = Triple(support.allocationRow(due, type, night), inventory.row(type, night), releasedAt(due))
        stepOver(listOf(due)).execute(contextOf(second)) shouldBe EndOfDayStepOutcome.DONE

        first.issues.shouldBeEmpty()
        second.issues.shouldBeEmpty()
        Triple(support.allocationRow(due, type, night), inventory.row(type, night), releasedAt(due)) shouldBe afterFirst
        support.allocationRow(due, type, night) shouldBe Triple(3, 0, 3)
        statusOf(due) shouldBe "RELEASED"
    }

    @Test
    fun `block-release the real step run twice never lists a released block again`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(3, date)
        val plan = support.plan(listOf(type))
        val night = date.plusDays(7)
        val due = block(plan.id, type, night, 2, cutoff = date)
        val step = BlockReleaseStep(reservations, items)

        step.execute(contextOf(Sink()))
        reservations.blocksDueForRelease().map { it.value }.contains(due) shouldBe false
        val sink = Sink()
        step.execute(contextOf(sink))

        sink.issues.map { it.reference }.contains(due.toString()) shouldBe false
        statusOf(due) shouldBe "RELEASED"
    }

    @Test
    fun `block-release with no due block is not applicable`() {
        val none =
            object : ReservationsApi by reservations {
                override fun blocksDueForRelease(): List<RoomBlockId> = emptyList()
            }
        val sink = Sink()

        BlockReleaseStep(none, items).execute(contextOf(sink)) shouldBe EndOfDayStepOutcome.NOT_APPLICABLE

        sink.issues.shouldBeEmpty()
    }

    /** A etapa real sobre uma lista fixa de bloqueios; a liberação de cada um continua a real. */
    private fun stepOver(ids: List<UUID>): BlockReleaseStep {
        val fixed =
            object : ReservationsApi by reservations {
                override fun blocksDueForRelease(): List<RoomBlockId> = ids.map { RoomBlockId(it) }
            }
        return BlockReleaseStep(fixed, items)
    }

    private fun block(
        plan: UUID,
        type: UUID,
        night: LocalDate,
        quantity: Int,
        cutoff: LocalDate,
    ): UUID =
        UUID.fromString(
            support
                .createdBlock(
                    support.blockBody(
                        plan,
                        cutoff,
                        support.allocation(type, night, night.plusDays(1), quantity),
                        holderGuest = support.guest(),
                    ),
                )["id"]
                .asString(),
        )

    private fun statusOf(block: UUID): String =
        jdbc
            .sql("SELECT status FROM reservations.room_block WHERE id = :id")
            .param("id", block)
            .query(String::class.java)
            .single()

    private fun releasedAt(block: UUID): Any? =
        jdbc
            .sql("SELECT released_at FROM reservations.room_block WHERE id = :id")
            .param("id", block)
            .query { rs, _ -> rs.getTimestamp("released_at") }
            .single()
}
