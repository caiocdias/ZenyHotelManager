package com.zeny.hotel.staff

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * API pública do módulo `staff` para os outros módulos (ARCHITECTURE §3.1, RF-PES-04): a escala de
 * uma data por setor, que a governança (M6) e as rotinas de checklist (M2) consomem, os setores e o
 * colaborador vinculado a um usuário. Não checa permissão: quem chama é um caso de uso que já se
 * autorizou.
 */
interface StaffApi {
    /** Os setores, em ordem de criação (os seis da SPEC §5.3 primeiro). */
    fun departments(): List<DepartmentSummary>

    /**
     * Quem trabalha em [date] no setor [departmentId] e em que turno: as entradas da escala com
     * turno (as do padrão e as atribuições e trocas lançadas), sem folgas, faltas e férias, em ordem
     * de início do turno e nome. Um turno que atravessa a meia-noite pertence à data em que começa.
     */
    fun daySchedule(
        date: LocalDate,
        departmentId: DepartmentId,
    ): DaySchedule

    /** O colaborador vinculado ao usuário (H7), ativo ou não, ou `null` se não há. */
    fun employeeOfUser(userId: UserId): EmployeeSummary?
}

/** Id de um setor ([DepartmentSummary]). */
@JvmInline
value class DepartmentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): DepartmentId = DepartmentId(UuidV7.generate())
    }
}

/** Id de um colaborador. */
@JvmInline
value class EmployeeId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): EmployeeId = EmployeeId(UuidV7.generate())
    }
}

/** Id de um turno. */
@JvmInline
value class ShiftId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ShiftId = ShiftId(UuidV7.generate())
    }
}

/**
 * Os seis setores da SPEC §5.3 (RF-PES-01), pré-carregados (H6): a chave identifica o setor para os
 * outros módulos mesmo que o administrador o renomeie. Setores criados depois não têm chave.
 */
enum class DepartmentKey {
    RECEPTION,
    HOUSEKEEPING,
    MAINTENANCE,
    FOOD_AND_BEVERAGE,
    EVENTS,
    ADMINISTRATION,
}

/** Padrão recorrente de escala (RF-PES-03). */
enum class SchedulePatternKind {
    /** 12x36: um dia de trabalho e um de descanso, alternados a partir do início. */
    TWELVE_THIRTY_SIX,

    /** 6x1: seis dias de trabalho e um de folga, em ciclos de sete dias a partir do início. */
    SIX_ON_ONE_OFF,

    /** Fixo: os mesmos dias da semana. */
    FIXED,
}

/** O que a entrada da escala diz do colaborador naquela data (RF-PES-03). */
enum class ScheduleEntryKind(
    /** Se o colaborador trabalha (a entrada tem turno). */
    val working: Boolean,
) {
    /** Trabalha no turno: gerado pelo padrão ou atribuído à mão. */
    SHIFT(true),

    /** Troca: trabalha num turno ou data diferente do padrão. */
    SWAP(true),

    /** Folga. */
    DAY_OFF(false),

    /** Falta. */
    ABSENCE(false),

    /** Férias. */
    VACATION(false),
}

/** De onde veio a entrada da escala. */
enum class ScheduleEntrySource {
    /** Gerada por um padrão: a geração seguinte pode refazê-la ou retirá-la. */
    PATTERN,

    /** Lançada à mão (atribuição ou exceção): prevalece sobre o padrão e a geração nunca a toca. */
    MANUAL,
}

/** Um setor. [key] é nula nos setores criados pelo administrador. */
data class DepartmentSummary(
    val id: DepartmentId,
    val key: DepartmentKey?,
    val name: String,
)

/** Um colaborador como os outros módulos o veem. */
data class EmployeeSummary(
    val id: EmployeeId,
    val name: String,
    val position: String,
    val departmentId: DepartmentId,
    val userId: UserId?,
    val active: Boolean,
)

/** Um turno de um setor; [end] antes de [start] é um turno que termina no dia seguinte. */
data class ShiftSummary(
    val id: ShiftId,
    val departmentId: DepartmentId,
    val name: String,
    val start: LocalTime,
    val end: LocalTime,
) {
    /** Se o turno termina no dia seguinte ao início. */
    val crossesMidnight: Boolean get() = end < start
}

/** Um colaborador escalado num turno numa data ([kind] é [ScheduleEntryKind.SHIFT] ou [ScheduleEntryKind.SWAP]). */
data class ShiftAssignment(
    val employee: EmployeeSummary,
    val shift: ShiftSummary,
    val kind: ScheduleEntryKind,
)

/** A escala de uma data num setor: quem trabalha e em que turno. */
data class DaySchedule(
    val date: LocalDate,
    val departmentId: DepartmentId,
    val assignments: List<ShiftAssignment>,
)
