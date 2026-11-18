package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate

/** O local de uma tarefa: uma UH **ou** uma área comum. */
sealed interface TaskLocation {
    data class Room(
        val id: RoomId,
    ) : TaskLocation

    data class Area(
        val id: AreaId,
    ) : TaskLocation

    companion object {
        /** O local de [roomId] ou de [areaId]; os dois ou nenhum, `400 HOUSEKEEPING_TASK_LOCATION_INVALID`. */
        fun of(
            roomId: RoomId?,
            areaId: AreaId?,
        ): TaskLocation =
            when {
                roomId != null && areaId == null -> {
                    Room(roomId)
                }

                areaId != null && roomId == null -> {
                    Area(areaId)
                }

                else -> {
                    val message = "Informe o local da tarefa: a UH (roomId) ou a área (areaId), um só."
                    val code = HousekeepingErrorCodes.HOUSEKEEPING_TASK_LOCATION_INVALID
                    throw InvalidInputException(
                        code,
                        message,
                        listOf("roomId", "areaId").map { FieldViolation(it, code.value, message) },
                    )
                }
            }
    }
}

/**
 * Tarefa de governança (RF-GOV-02, SPEC §8.2.10): uma limpeza de um tipo num local (UH ou área, exatamente um, que não
 * muda) numa data operacional, com a origem, a prioridade padrão do tipo e os créditos do tipo para o tipo da UH (D2;
 * área ou sem crédito, 0). Nasce `PENDING`; as transições são da E03 e da E04. Há no máximo uma por (local, data,
 * tipo), em qualquer status (D5-E02).
 *
 * [arrivalExpected] é um **retrato do momento da geração** (D8-E02): alocação, cancelamento ou no-show posteriores não
 * o atualizam. [notes] é texto livre da recepção e nunca vai à trilha, a eventos nem a logs (RN-13). Nunca é apagada (o
 * banco recusa).
 */
@Entity
@Table(name = "housekeeping_task", schema = "housekeeping")
@Suppress("LongParameterList") // os campos da tarefa, só pelo create
class HousekeepingTask private constructor(
    @Id
    val id: HousekeepingTaskId,
    @Column(name = "room_id", updatable = false)
    val roomId: RoomId?,
    @Column(name = "area_id", updatable = false)
    val areaId: AreaId?,
    @Column(name = "business_date", nullable = false, updatable = false)
    val businessDate: LocalDate,
    @Column(name = "cleaning_type_id", nullable = false, updatable = false)
    val cleaningTypeId: CleaningTypeId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val source: TaskSource,
    @Column(nullable = false, updatable = false)
    val priority: Int,
    @Column(nullable = false, updatable = false)
    val credits: Int,
    @Column(name = "arrival_expected", nullable = false, updatable = false)
    val arrivalExpected: Boolean,
    @Column(name = "scheduled_cleaning_id", updatable = false)
    val scheduledCleaningId: ScheduledCleaningId?,
    @Column(updatable = false)
    val notes: String?,
    @Column(name = "created_by", updatable = false)
    val createdBy: UserId?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TaskStatus = TaskStatus.PENDING
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    /** A camareira publicada (D12-E03); nula em `PENDING`. */
    @Column(name = "assigned_employee_id")
    var assignedEmployeeId: EmployeeId? = null
        protected set

    /** A posição publicada na ordem de execução da camareira, de 1 em diante; nula sem camareira. */
    @Column(name = "assignment_position")
    var assignmentPosition: Int? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val location: TaskLocation
        get() = roomId?.let { TaskLocation.Room(it) } ?: TaskLocation.Area(checkNotNull(areaId))

    /**
     * Dá a tarefa à camareira [employeeId] na [position] (de 1 em diante) e a deixa `ASSIGNED` (publicação do plano do
     * dia, M6 E03). Só a partir de `PENDING` ou `ASSIGNED`: a tarefa que já andou nunca é tocada (Review Focus 4).
     */
    fun assign(
        employeeId: EmployeeId,
        position: Int,
        now: Instant,
    ) {
        require(position >= 1) { "Posição fora da ordem da camareira: $position" }
        requireNotStarted()
        status = TaskStatus.ASSIGNED
        assignedEmployeeId = employeeId
        assignmentPosition = position
        updatedAt = now
    }

    /** Volta a tarefa a `PENDING`, sem camareira (D7-E03). Só a partir de `PENDING` ou `ASSIGNED`. */
    fun unassign(now: Instant) {
        requireNotStarted()
        status = TaskStatus.PENDING
        assignedEmployeeId = null
        assignmentPosition = null
        updatedAt = now
    }

    private fun requireNotStarted() =
        check(status == TaskStatus.PENDING || status == TaskStatus.ASSIGNED) {
            "A tarefa $id já andou ($status): a distribuição não a toca"
        }

    override fun toString(): String = "HousekeepingTask(id=$id, source=$source, status=$status)"

    companion object {
        const val MAX_NOTES_LENGTH = 500
        private const val NUL = '\u0000'

        /**
         * Uma tarefa nova, `PENDING`, do [type] (a prioridade é a padrão dele) no [location] e na [date], com os
         * [credits] já calculados ([creditsFor]). Só a origem `SCHEDULED` leva a [scheduledCleaningId], e sempre a
         * leva. As referências (local, tipo ativo) e a data são do caso de uso; as [notes] passam por [notes].
         */
        @Suppress("LongParameterList") // os dados de criação da tarefa
        fun create(
            type: CleaningType,
            location: TaskLocation,
            date: LocalDate,
            source: TaskSource,
            arrivalExpected: Boolean,
            credits: Int,
            now: Instant,
            scheduledCleaningId: ScheduledCleaningId? = null,
            notes: String? = null,
            createdBy: UserId? = null,
        ): HousekeepingTask {
            require((source == TaskSource.SCHEDULED) == (scheduledCleaningId != null)) {
                "Só a tarefa de faxina programada leva a faxina, e sempre a leva"
            }
            require(credits >= 0) { "Créditos negativos: $credits" }
            return HousekeepingTask(
                HousekeepingTaskId.new(),
                (location as? TaskLocation.Room)?.id,
                (location as? TaskLocation.Area)?.id,
                date,
                type.id,
                source,
                type.defaultPriority,
                credits,
                arrivalExpected,
                scheduledCleaningId,
                notes(notes),
                createdBy,
                now,
            )
        }

        /**
         * As observações aparadas, ou `null` se vazias; com mais de [MAX_NOTES_LENGTH] caracteres ou com NUL (que o
         * PostgreSQL não guarda), `400 HOUSEKEEPING_DATA_INVALID` no campo `notes`, sem ecoar o texto.
         */
        fun notes(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            HousekeepingRules.require(
                trimmed.codePointCount(0, trimmed.length) <= MAX_NOTES_LENGTH && NUL !in trimmed,
                "notes",
                "Informe as observações com até $MAX_NOTES_LENGTH caracteres.",
            )
            return trimmed
        }

        /** Os créditos do [type] para a UH do tipo [roomTypeId] (D2): sem crédito, ou numa área (`null`), 0. */
        fun creditsFor(
            type: CleaningType,
            roomTypeId: RoomTypeId?,
        ): Int = roomTypeId?.let { id -> type.credits.firstOrNull { it.roomTypeId == id }?.credits } ?: 0
    }
}
