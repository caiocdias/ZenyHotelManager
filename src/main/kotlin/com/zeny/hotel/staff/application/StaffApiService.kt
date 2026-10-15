package com.zeny.hotel.staff.application

import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DaySchedule
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentSummary
import com.zeny.hotel.staff.EmployeeSummary
import com.zeny.hotel.staff.StaffApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A API pública do `staff` ([StaffApi]) para os outros módulos: leituras sem checar permissão. */
@Service
class StaffApiService(
    private val store: StaffStore,
    private val directory: StaffDirectory,
) : StaffApi {
    @Transactional(readOnly = true)
    override fun departments(): List<DepartmentSummary> =
        generateSequence(directory.departments(null, PAGE)) { page ->
            if (page.size < PAGE) null else directory.departments(page.last().id.value, PAGE)
        }.flatten()
            .map { DepartmentSummary(it.id, it.key, it.name) }
            .toList()

    @Transactional(readOnly = true)
    override fun daySchedule(
        date: LocalDate,
        departmentId: DepartmentId,
    ): DaySchedule = DaySchedule(date, departmentId, directory.assignments(date, departmentId))

    @Transactional(readOnly = true)
    override fun employeeOfUser(userId: UserId): EmployeeSummary? =
        store.employeeOfUser(userId)?.let {
            EmployeeSummary(it.id, it.name, it.position, it.departmentId, it.userId, it.active)
        }

    private companion object {
        const val PAGE = 200
    }
}
