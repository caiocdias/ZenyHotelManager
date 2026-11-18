package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.domain.Attendant
import com.zeny.hotel.housekeeping.domain.AttendantId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DaySchedule
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.DepartmentSummary
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.EmployeeSummary
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ShiftAssignment
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.ShiftSummary
import com.zeny.hotel.staff.StaffApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * As camareiras do plano (D1-E03), sem Spring: as escaladas na data no setor de governança da carga
 * (`DepartmentKey.HOUSEKEEPING`), só as ativas e sem repetição; sem o setor, a invariante quebrada é uma
 * `IllegalStateException`, sem código no contrato.
 */
class AssignmentPlansServiceTest {
    private val date = LocalDate.of(2030, 5, 6)
    private val housekeeping = DepartmentSummary(DepartmentId.new(), DepartmentKey.HOUSEKEEPING, "Governança")
    private val reception = DepartmentSummary(DepartmentId.new(), DepartmentKey.RECEPTION, "Recepção")

    @Test
    fun `the attendants are the active employees scheduled on the date in the housekeeping department`() {
        val ana = employee("Ana")
        val bia = employee("Bia")
        val inactive = employee("Cida", active = false)
        val staff =
            FakeStaff(listOf(reception, housekeeping), mapOf(housekeeping.id to listOf(ana, bia, inactive, ana)))

        service(staff).scheduledAttendants(date) shouldContainExactly
            listOf(Attendant(AttendantId(ana.id.value), "Ana"), Attendant(AttendantId(bia.id.value), "Bia"))
    }

    @Test
    fun `without the housekeeping department it is an IllegalStateException`() {
        val staff = FakeStaff(listOf(reception), emptyMap())

        shouldThrow<IllegalStateException> { service(staff).scheduledAttendants(date) }.message shouldContain
            "HOUSEKEEPING"
    }

    private fun service(staff: StaffApi) =
        AssignmentPlansService(
            mockk(),
            mockk(),
            mockk(),
            staff,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
        )

    private fun employee(
        name: String,
        active: Boolean = true,
    ) = EmployeeSummary(EmployeeId.new(), name, "Camareira", housekeeping.id, UserId(UUID.randomUUID()), active)

    private inner class FakeStaff(
        private val departments: List<DepartmentSummary>,
        private val schedules: Map<DepartmentId, List<EmployeeSummary>>,
    ) : StaffApi {
        override fun departments() = departments

        override fun daySchedule(
            date: LocalDate,
            departmentId: DepartmentId,
        ): DaySchedule {
            val shift = ShiftSummary(ShiftId.new(), departmentId, "Manhã", LocalTime.of(7, 0), LocalTime.of(15, 0))
            return DaySchedule(
                date,
                departmentId,
                schedules[departmentId].orEmpty().map { ShiftAssignment(it, shift, ScheduleEntryKind.SHIFT) },
            )
        }

        override fun employeeOfUser(userId: UserId): EmployeeSummary? = null

        override fun employees(ids: Collection<EmployeeId>): Map<EmployeeId, EmployeeSummary> = error("fora do teste")
    }
}
