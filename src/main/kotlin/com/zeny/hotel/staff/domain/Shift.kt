package com.zeny.hotel.staff.domain

import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalTime

/**
 * Turno de um setor (RF-PES-02): nome, início e fim, no fuso do hotel, em horas e minutos. Fim antes
 * do início é um turno que atravessa a meia-noite: a entrada da escala pertence à data em que ele
 * começa. Início igual ao fim é recusado (não diz se o turno dura zero ou 24 horas).
 */
@Entity
@Table(name = "shift", schema = "staff")
class Shift private constructor(
    @Id
    val id: ShiftId,
    @Column(name = "department_id", nullable = false, updatable = false)
    val departmentId: DepartmentId,
    name: String,
    start: LocalTime,
    end: LocalTime,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var name: String = StaffRules.required("name", name, MAX_NAME_LENGTH)
        protected set

    @Column(name = "start_time", nullable = false)
    var start: LocalTime = start
        protected set

    @Column(name = "end_time", nullable = false)
    var end: LocalTime = end
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    init {
        validateTimes(start, end)
    }

    /** Se o turno termina no dia seguinte ao início. */
    val crossesMidnight: Boolean get() = end < start

    /** Troca nome e horários; devolve se algo mudou. */
    fun update(
        name: String,
        start: LocalTime,
        end: LocalTime,
        now: Instant,
    ): Boolean {
        val validName = StaffRules.required("name", name, MAX_NAME_LENGTH)
        validateTimes(start, end)
        if (validName == this.name && start == this.start && end == this.end) return false
        this.name = validName
        this.start = start
        this.end = end
        updatedAt = now
        return true
    }

    companion object {
        const val MAX_NAME_LENGTH = 60

        fun create(
            departmentId: DepartmentId,
            name: String,
            start: LocalTime,
            end: LocalTime,
            now: Instant,
        ): Shift = Shift(ShiftId.new(), departmentId, name, start, end, now)

        private fun validateTimes(
            start: LocalTime,
            end: LocalTime,
        ) {
            StaffRules.wholeMinute("start", start)
            StaffRules.wholeMinute("end", end)
            if (start == end) {
                throw StaffRules.invalid(
                    StaffErrorCodes.SHIFT_TIMES_INVALID,
                    "end",
                    "O fim do turno precisa ser diferente do início.",
                )
            }
        }
    }
}
