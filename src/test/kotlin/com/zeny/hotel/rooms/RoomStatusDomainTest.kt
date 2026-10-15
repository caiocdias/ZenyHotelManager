package com.zeny.hotel.rooms

import com.zeny.hotel.rooms.GuestServiceFlag.DO_NOT_DISTURB
import com.zeny.hotel.rooms.GuestServiceFlag.MAKE_UP_ROOM
import com.zeny.hotel.rooms.GuestServiceFlag.NONE
import com.zeny.hotel.rooms.HousekeepingStatus.CLEAN
import com.zeny.hotel.rooms.HousekeepingStatus.DIRTY
import com.zeny.hotel.rooms.HousekeepingStatus.INSPECTED
import com.zeny.hotel.rooms.OccupancyStatus.OCCUPIED
import com.zeny.hotel.rooms.OccupancyStatus.VACANT
import com.zeny.hotel.rooms.RoomStatusField.GUEST_COUNT
import com.zeny.hotel.rooms.RoomStatusField.HOUSEKEEPING_STATUS
import com.zeny.hotel.rooms.RoomStatusField.INSPECTION_PENDING
import com.zeny.hotel.rooms.RoomStatusField.OCCUPANCY
import com.zeny.hotel.rooms.RoomStatusField.SERVICE_FLAG
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomDetails
import com.zeny.hotel.rooms.domain.RoomStatusFieldChange
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Máquina de estados do status de governança da UH (SPEC §8.4, RN-08), as outras dimensões do status
 * (RF-UH-04: ocupação, número de hóspedes e sinalização do hóspede), a sujeira automática do check-out
 * (RN-09) e a UH pronta para check-in (RN-05), no domínio, sem Spring.
 *
 * Os quatro estados da máquina são Sujo, Limpo, Limpo com inspeção pendente e Inspecionado. Cada
 * operação de governança é testada a partir de cada um deles: ou é uma transição do diagrama (e
 * devolve as mudanças de cada campo), ou já está no estado de destino (nada muda), ou é recusada com
 * `422 HOUSEKEEPING_TRANSITION_NOT_ALLOWED` sem mudar nada.
 */
class RoomStatusDomainTest {
    private val t0 = Instant.parse("2026-10-15T12:00:00Z")
    private val t1 = Instant.parse("2026-10-15T13:00:00Z")

    /** Os estados da máquina da SPEC §8.4. */
    private enum class State(
        val status: HousekeepingStatus,
        val pending: Boolean,
    ) {
        DIRTY_(DIRTY, false),
        CLEAN_(CLEAN, false),
        CLEAN_PENDING(CLEAN, true),
        INSPECTED_(INSPECTED, false),
    }

    /** As operações de governança da API pública. */
    private enum class Operation {
        MARK_DIRTY,
        MARK_CLEAN,
        MARK_CLEAN_WITH_INSPECTION,
        MARK_INSPECTED,
        REJECT_INSPECTION,
    }

    private sealed interface Outcome

    private data class Moves(
        val to: State,
    ) : Outcome

    private data object Unchanged : Outcome

    private data object Refused : Outcome

    /** A tabela completa: estado × operação → resultado. */
    private val machine: Map<Pair<State, Operation>, Outcome> =
        mapOf(
            // Sujo
            (State.DIRTY_ to Operation.MARK_DIRTY) to Unchanged,
            (State.DIRTY_ to Operation.MARK_CLEAN) to Moves(State.CLEAN_),
            (State.DIRTY_ to Operation.MARK_CLEAN_WITH_INSPECTION) to Moves(State.CLEAN_PENDING),
            (State.DIRTY_ to Operation.MARK_INSPECTED) to Refused, // Sujo → Inspecionado direto: proibido
            (State.DIRTY_ to Operation.REJECT_INSPECTION) to Unchanged,
            // Limpo (sem inspeção pendente)
            (State.CLEAN_ to Operation.MARK_DIRTY) to Moves(State.DIRTY_),
            (State.CLEAN_ to Operation.MARK_CLEAN) to Unchanged,
            (State.CLEAN_ to Operation.MARK_CLEAN_WITH_INSPECTION) to Refused,
            (State.CLEAN_ to Operation.MARK_INSPECTED) to Moves(State.INSPECTED_), // inspeção voluntária
            (State.CLEAN_ to Operation.REJECT_INSPECTION) to Refused, // não há inspeção pendente
            // Limpo — inspeção pendente
            (State.CLEAN_PENDING to Operation.MARK_DIRTY) to Moves(State.DIRTY_),
            (State.CLEAN_PENDING to Operation.MARK_CLEAN) to Refused, // pularia a inspeção
            (State.CLEAN_PENDING to Operation.MARK_CLEAN_WITH_INSPECTION) to Unchanged,
            (State.CLEAN_PENDING to Operation.MARK_INSPECTED) to Moves(State.INSPECTED_), // inspeção aprovada
            (State.CLEAN_PENDING to Operation.REJECT_INSPECTION) to Moves(State.DIRTY_), // inspeção reprovada
            // Inspecionado
            (State.INSPECTED_ to Operation.MARK_DIRTY) to Moves(State.DIRTY_),
            (State.INSPECTED_ to Operation.MARK_CLEAN) to Refused,
            (State.INSPECTED_ to Operation.MARK_CLEAN_WITH_INSPECTION) to Refused,
            (State.INSPECTED_ to Operation.MARK_INSPECTED) to Unchanged,
            (State.INSPECTED_ to Operation.REJECT_INSPECTION) to Refused,
        )

    @Test
    fun `the table covers every state and operation, and its transitions are exactly those of SPEC 8_4`() {
        machine.keys shouldContainExactlyInAnyOrder
            State.entries.flatMap { state -> Operation.entries.map { state to it } }
        val transitions =
            machine.mapNotNull { (key, outcome) -> (outcome as? Moves)?.let { key.first to it.to } }.toSet()
        transitions shouldBe
            setOf(
                State.DIRTY_ to State.CLEAN_, // limpeza concluída (tipo sem inspeção)
                State.DIRTY_ to State.CLEAN_PENDING, // limpeza concluída (tipo com inspeção)
                State.CLEAN_PENDING to State.INSPECTED_, // inspeção aprovada
                State.CLEAN_PENDING to State.DIRTY_, // inspeção reprovada (e check-out, fechamento, uso)
                State.CLEAN_ to State.INSPECTED_, // inspeção voluntária
                State.CLEAN_ to State.DIRTY_, // check-out, fechamento, uso
                State.INSPECTED_ to State.DIRTY_, // check-out, fechamento, uso
            )
    }

    @Test
    fun `a new room is vacant, dirty, without pending inspection nor guest signal, and not ready`() {
        val room = newRoom()
        assertSoftly {
            room.occupancy shouldBe VACANT
            room.guestCount shouldBe null
            room.housekeepingStatus shouldBe DIRTY
            room.inspectionPending shouldBe false
            room.serviceFlag shouldBe NONE
            room.isReadyForCheckIn shouldBe false
            room.statusChangedAt shouldBe t0
            room.occupancyChangedAt shouldBe t0
        }
    }

    @Test
    fun `every allowed transition moves the room and returns the change of each field`() {
        for ((key, outcome) in machine) {
            val (from, operation) = key
            val to = (outcome as? Moves)?.to ?: continue
            withClue("$from --$operation--> $to") {
                val room = roomIn(from)
                val changes = room.apply(operation)
                room.state() shouldBe to
                room.statusChangedAt shouldBe t1
                val expected =
                    buildList {
                        if (from.status != to.status) {
                            add(RoomStatusFieldChange(HOUSEKEEPING_STATUS, from.status.name, to.status.name))
                        }
                        if (from.pending != to.pending) {
                            add(
                                RoomStatusFieldChange(
                                    INSPECTION_PENDING,
                                    from.pending.toString(),
                                    to.pending.toString(),
                                ),
                            )
                        }
                    }
                changes shouldContainExactly expected
            }
        }
    }

    @Test
    fun `every transition outside SPEC 8_4 is refused with a stable code and changes nothing`() {
        for ((key, outcome) in machine) {
            if (outcome != Refused) continue
            val (from, operation) = key
            withClue("$from --$operation--> recusada") {
                val room = roomIn(from)
                val version = room.statusChangedAt
                val error = shouldThrow<BusinessRuleException> { room.apply(operation) }
                error.code shouldBe ErrorCode("HOUSEKEEPING_TRANSITION_NOT_ALLOWED")
                room.state() shouldBe from
                room.statusChangedAt shouldBe version
            }
        }
    }

    @Test
    fun `repeating the operation of the current state changes nothing`() {
        for ((key, outcome) in machine) {
            if (outcome != Unchanged) continue
            val (from, operation) = key
            withClue("$from --$operation--> sem mudança") {
                val room = roomIn(from)
                val before = room.statusChangedAt
                room.apply(operation).shouldBeEmpty()
                room.state() shouldBe from
                room.statusChangedAt shouldBe before
            }
        }
    }

    @Test
    fun `markClean with inspection leaves it pending, markInspected clears it, rejectInspection dirties it`() {
        val approved = newRoom()
        approved.markClean(requiresInspection = true, now = t1)
        approved.housekeepingStatus shouldBe CLEAN
        approved.inspectionPending shouldBe true
        approved.markInspected(t1)
        approved.housekeepingStatus shouldBe INSPECTED
        approved.inspectionPending shouldBe false

        val rejected = newRoom()
        rejected.markClean(requiresInspection = true, now = t1)
        rejected.rejectInspection(t1)
        rejected.housekeepingStatus shouldBe DIRTY
        rejected.inspectionPending shouldBe false
    }

    // --- Ocupação e número de hóspedes (RF-UH-04, H4) ---------------------------------------------------

    @Test
    fun `markOccupied records the occupancy and the guest count, and a new count only changes the count`() {
        val room = newRoom()
        room.markOccupied(2, t1) shouldContainExactly
            listOf(
                RoomStatusFieldChange(OCCUPANCY, "VACANT", "OCCUPIED"),
                RoomStatusFieldChange(GUEST_COUNT, null, "2"),
            )
        room.occupancy shouldBe OCCUPIED
        room.guestCount shouldBe 2
        room.occupancyChangedAt shouldBe t1
        // O status de governança é outra dimensão: não muda.
        room.housekeepingStatus shouldBe DIRTY

        val later = t1.plusSeconds(60)
        room.markOccupied(3, later) shouldContainExactly listOf(RoomStatusFieldChange(GUEST_COUNT, "2", "3"))
        room.occupancyChangedAt shouldBe later
        room.markOccupied(3, later.plusSeconds(60)).shouldBeEmpty()
        room.occupancyChangedAt shouldBe later
    }

    @Test
    fun `the guest count goes from 1 to 40, and an inactive room is not occupied`() {
        for (count in listOf(0, -1, Room.MAX_GUEST_COUNT + 1)) {
            withClue("$count hóspedes") {
                val error = shouldThrow<InvalidInputException> { newRoom().markOccupied(count, t1) }
                error.code shouldBe ErrorCode("GUEST_COUNT_INVALID")
            }
        }
        newRoom().markOccupied(Room.MAX_GUEST_COUNT, t1)

        val inactive = newRoom().also { it.deactivate(t1) }
        shouldThrow<BusinessRuleException> { inactive.markOccupied(1, t1) }.code shouldBe ErrorCode("ROOM_INACTIVE")
        inactive.occupancy shouldBe VACANT
    }

    @Test
    fun `markVacant clears the guest count and the guest signal, and keeps the housekeeping status`() {
        val room = roomIn(State.INSPECTED_)
        room.markOccupied(2, t0)
        room.setServiceFlag(DO_NOT_DISTURB, t0)

        room.markVacant(RoomStatusSource.MANUAL, t1) shouldContainExactly
            listOf(
                RoomStatusFieldChange(OCCUPANCY, "OCCUPIED", "VACANT"),
                RoomStatusFieldChange(GUEST_COUNT, "2", null),
                RoomStatusFieldChange(SERVICE_FLAG, "DO_NOT_DISTURB", "NONE"),
            )
        room.occupancy shouldBe VACANT
        room.guestCount shouldBe null
        room.serviceFlag shouldBe NONE
        room.housekeepingStatus shouldBe INSPECTED
        room.occupancyChangedAt shouldBe t1
        room.markVacant(RoomStatusSource.MANUAL, t1).shouldBeEmpty()
    }

    @Test
    fun `after a check-out the room is vacant and dirty (RN-09)`() {
        for (state in State.entries) {
            withClue(state) {
                val room = roomIn(state)
                room.markOccupied(1, t0)
                val changes = room.markVacant(RoomStatusSource.CHECK_OUT, t1)
                room.occupancy shouldBe VACANT
                room.housekeepingStatus shouldBe DIRTY
                room.inspectionPending shouldBe false
                changes.map { it.field } shouldBe
                    listOf(OCCUPANCY, GUEST_COUNT) +
                    (if (state.status != DIRTY) listOf(HOUSEKEEPING_STATUS) else emptyList()) +
                    (if (state.pending) listOf(INSPECTION_PENDING) else emptyList())
            }
        }
        // Uma UH já vaga (corrigida à mão) também fica suja no check-out.
        val vacant = roomIn(State.INSPECTED_)
        vacant.markVacant(RoomStatusSource.CHECK_OUT, t1) shouldContainExactly
            listOf(RoomStatusFieldChange(HOUSEKEEPING_STATUS, "INSPECTED", "DIRTY"))
    }

    // --- Sinalização do hóspede (RF-UH-04) ----------------------------------------------------------

    @Test
    fun `the guest signal only applies to an occupied room`() {
        val vacant = newRoom()
        for (flag in listOf(DO_NOT_DISTURB, MAKE_UP_ROOM)) {
            withClue(flag) {
                shouldThrow<BusinessRuleException> { vacant.setServiceFlag(flag, t1) }.code shouldBe
                    ErrorCode("ROOM_NOT_OCCUPIED")
            }
        }
        vacant.setServiceFlag(NONE, t1).shouldBeEmpty()

        val occupied = newRoom().also { it.markOccupied(1, t0) }
        occupied.setServiceFlag(DO_NOT_DISTURB, t1) shouldContainExactly
            listOf(RoomStatusFieldChange(SERVICE_FLAG, "NONE", "DO_NOT_DISTURB"))
        occupied.setServiceFlag(DO_NOT_DISTURB, t1).shouldBeEmpty()
        occupied.setServiceFlag(MAKE_UP_ROOM, t1) shouldContainExactly
            listOf(RoomStatusFieldChange(SERVICE_FLAG, "DO_NOT_DISTURB", "MAKE_UP_ROOM"))
        occupied.serviceFlag shouldBe MAKE_UP_ROOM
        // O status de governança e a sinalização são independentes.
        occupied.markClean(requiresInspection = false, now = t1)
        occupied.serviceFlag shouldBe MAKE_UP_ROOM
    }

    // --- RN-05 ---------------------------------------------------------------------------------------

    @Test
    fun `a room is ready for check-in only when vacant and inspected, or clean without pending inspection (RN-05)`() {
        for (state in State.entries) {
            for (occupied in listOf(false, true)) {
                for (active in listOf(true, false)) {
                    val room = roomIn(state)
                    if (occupied) room.markOccupied(1, t1)
                    if (!active) room.deactivate(t1)
                    val expected =
                        active && !occupied && (state == State.INSPECTED_ || state == State.CLEAN_)
                    withClue("$state, ocupada=$occupied, ativa=$active") {
                        room.isReadyForCheckIn shouldBe expected
                    }
                }
            }
        }
    }

    // --- Apoio ---------------------------------------------------------------------------------------

    private fun newRoom(): Room = Room.create(RoomDetails("101", null, RoomTypeId.new(), emptySet(), null), t0)

    private fun roomIn(state: State): Room {
        val room = newRoom()
        when (state) {
            State.DIRTY_ -> {
                Unit
            }

            State.CLEAN_ -> {
                room.markClean(requiresInspection = false, now = t0)
            }

            State.CLEAN_PENDING -> {
                room.markClean(requiresInspection = true, now = t0)
            }

            State.INSPECTED_ -> {
                room.markClean(requiresInspection = false, now = t0)
                room.markInspected(t0)
            }
        }
        room.state() shouldBe state
        return room
    }

    private fun Room.state(): State =
        State.entries.single {
            it.status == housekeepingStatus &&
                it.pending == inspectionPending
        }

    private fun Room.apply(operation: Operation): List<RoomStatusFieldChange> =
        when (operation) {
            Operation.MARK_DIRTY -> markDirty(t1)
            Operation.MARK_CLEAN -> markClean(requiresInspection = false, now = t1)
            Operation.MARK_CLEAN_WITH_INSPECTION -> markClean(requiresInspection = true, now = t1)
            Operation.MARK_INSPECTED -> markInspected(t1)
            Operation.REJECT_INSPECTION -> rejectInspection(t1)
        }
}
