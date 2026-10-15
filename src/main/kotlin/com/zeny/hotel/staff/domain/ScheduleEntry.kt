package com.zeny.hotel.staff.domain

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ScheduleEntrySource
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Id de uma entrada da escala. */
@JvmInline
value class ScheduleEntryId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ScheduleEntryId = ScheduleEntryId(UuidV7.generate())
    }
}

/** Uma entrada que um padrão pede para uma data: o colaborador trabalha no turno do padrão. */
data class PlannedEntry(
    val employeeId: EmployeeId,
    val date: LocalDate,
    val shiftId: ShiftId,
    val departmentId: DepartmentId,
    val patternId: SchedulePatternId,
)

/** Uma atribuição ou exceção lançada à mão (RF-PES-03). [shiftId] só no trabalho e na troca, e obrigatório neles. */
data class ManualEntry(
    val kind: ScheduleEntryKind,
    val shiftId: ShiftId?,
    /** O setor do turno, no trabalho; o do colaborador, nas ausências. */
    val departmentId: DepartmentId,
    val note: String?,
) {
    init {
        if (kind.working && shiftId == null) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_ENTRY_INVALID,
                "shiftId",
                "Informe o turno do trabalho ou da troca.",
            )
        }
        if (!kind.working && shiftId != null) {
            throw StaffRules.invalid(
                StaffErrorCodes.SCHEDULE_ENTRY_INVALID,
                "shiftId",
                "Folga, falta e férias não têm turno.",
            )
        }
    }
}

/**
 * Entrada da escala (RF-PES-03, SPEC §8.2.2): o que um colaborador faz numa data. No máximo uma por
 * colaborador e data (o banco garante). As de [ScheduleEntrySource.PATTERN] são geradas por um padrão
 * e a geração seguinte as refaz; as de [ScheduleEntrySource.MANUAL] (atribuição, troca, folga, falta,
 * férias) prevalecem: a geração nunca as sobrescreve nem as retira. Um turno que atravessa a
 * meia-noite pertence à data em que começa.
 */
@Entity
@Table(name = "schedule_entry", schema = "staff")
@Suppress("LongParameterList") // os campos da entrada (SPEC §8.2.2), só pelas fábricas
class ScheduleEntry private constructor(
    @Id
    val id: ScheduleEntryId,
    @Column(name = "employee_id", nullable = false, updatable = false)
    val employeeId: EmployeeId,
    @Column(name = "work_date", nullable = false, updatable = false)
    val workDate: LocalDate,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    kind: ScheduleEntryKind,
    source: ScheduleEntrySource,
    shiftId: ShiftId?,
    departmentId: DepartmentId,
    patternId: SchedulePatternId?,
    note: String?,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: ScheduleEntryKind = kind
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: ScheduleEntrySource = source
        protected set

    @Column(name = "shift_id")
    private var shiftIdValue: UUID? = shiftId?.value

    @Column(name = "department_id", nullable = false)
    var departmentId: DepartmentId = departmentId
        protected set

    @Column(name = "pattern_id")
    private var patternIdValue: UUID? = patternId?.value

    var note: String? = StaffRules.optional("note", note, MAX_NOTE_LENGTH)
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    /** A data da entrada (a do início do turno). */
    val date: LocalDate get() = workDate

    val shiftId: ShiftId? get() = shiftIdValue?.let(::ShiftId)

    val patternId: SchedulePatternId? get() = patternIdValue?.let(::SchedulePatternId)

    val manual: Boolean get() = source == ScheduleEntrySource.MANUAL

    /** Se a entrada gerada já é o que [planned] pede. */
    fun matches(planned: PlannedEntry): Boolean =
        !manual &&
            shiftId == planned.shiftId &&
            departmentId == planned.departmentId &&
            patternId == planned.patternId

    /** Refaz a entrada gerada pelo que o padrão pede agora. Uma entrada lançada à mão nunca é refeita. */
    fun replan(
        planned: PlannedEntry,
        now: Instant,
    ) {
        check(!manual) { "Entrada lançada à mão não é refeita pela geração" }
        require(planned.employeeId == employeeId && planned.date == workDate) { "Outra entrada" }
        shiftIdValue = planned.shiftId.value
        departmentId = planned.departmentId
        patternIdValue = planned.patternId.value
        updatedAt = now
    }

    /** Lança à mão sobre a entrada (gerada ou não): ela passa a prevalecer sobre o padrão. Devolve se mudou. */
    fun overrideWith(
        entry: ManualEntry,
        now: Instant,
    ): Boolean {
        val note = StaffRules.optional("note", entry.note, MAX_NOTE_LENGTH)
        val unchanged =
            manual &&
                kind == entry.kind &&
                shiftId == entry.shiftId &&
                departmentId == entry.departmentId &&
                this.note == note
        if (unchanged) return false
        kind = entry.kind
        source = ScheduleEntrySource.MANUAL
        shiftIdValue = entry.shiftId?.value
        departmentId = entry.departmentId
        patternIdValue = null
        this.note = note
        updatedAt = now
        return true
    }

    companion object {
        const val MAX_NOTE_LENGTH = 200

        fun planned(
            planned: PlannedEntry,
            now: Instant,
        ): ScheduleEntry =
            ScheduleEntry(
                ScheduleEntryId.new(),
                planned.employeeId,
                planned.date,
                now,
                ScheduleEntryKind.SHIFT,
                ScheduleEntrySource.PATTERN,
                planned.shiftId,
                planned.departmentId,
                planned.patternId,
                null,
            )

        fun manual(
            employeeId: EmployeeId,
            date: LocalDate,
            entry: ManualEntry,
            now: Instant,
        ): ScheduleEntry =
            ScheduleEntry(
                ScheduleEntryId.new(),
                employeeId,
                date,
                now,
                entry.kind,
                ScheduleEntrySource.MANUAL,
                entry.shiftId,
                entry.departmentId,
                null,
                entry.note,
            )
    }
}
