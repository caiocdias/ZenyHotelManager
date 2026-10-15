package com.zeny.hotel.staff.application

import com.zeny.hotel.staff.domain.Department
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.ScheduleEntry
import com.zeny.hotel.staff.domain.SchedulePattern
import com.zeny.hotel.staff.domain.Shift

/**
 * Tipos de entidade, ações e retratos do `staff` na trilha de auditoria (RF-ACS-11). O retrato do
 * colaborador leva nome, cargo e contato (dados pessoais que o administrador já vê), nunca dado do
 * usuário vinculado além do id.
 */
internal object StaffAudit {
    const val DEPARTMENT = "staff.department"
    const val EMPLOYEE = "staff.employee"
    const val SHIFT = "staff.shift"
    const val SCHEDULE_PATTERN = "staff.schedule-pattern"
    const val SCHEDULE_ENTRY = "staff.schedule-entry"
    const val SCHEDULE_GENERATION = "staff.schedule-generation"

    const val CREATE_DEPARTMENT = "staff.department.create"
    const val UPDATE_DEPARTMENT = "staff.department.update"
    const val CREATE_EMPLOYEE = "staff.employee.create"
    const val UPDATE_EMPLOYEE = "staff.employee.update"
    const val DEACTIVATE_EMPLOYEE = "staff.employee.deactivate"
    const val REACTIVATE_EMPLOYEE = "staff.employee.reactivate"
    const val CREATE_SHIFT = "staff.shift.create"
    const val UPDATE_SHIFT = "staff.shift.update"
    const val CREATE_PATTERN = "staff.schedule-pattern.create"
    const val UPDATE_PATTERN = "staff.schedule-pattern.update"
    const val DEACTIVATE_PATTERN = "staff.schedule-pattern.deactivate"
    const val GENERATE_SCHEDULE = "staff.schedule.generate"
    const val RECORD_ENTRIES = "staff.schedule-entry.record"
}

internal fun Department.auditState(): Map<String, Any?> = mapOf("key" to key?.name, "name" to name)

internal fun Employee.auditState(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "position" to position,
        "phone" to phone,
        "email" to email,
        "departmentId" to departmentId.toString(),
        "userId" to userId?.toString(),
        "active" to active,
    )

internal fun Shift.auditState(): Map<String, Any?> =
    mapOf(
        "departmentId" to departmentId.toString(),
        "name" to name,
        "start" to start.toString(),
        "end" to end.toString(),
    )

internal fun SchedulePattern.auditState(): Map<String, Any?> =
    mapOf(
        "employeeId" to employeeId.toString(),
        "shiftId" to shiftId.toString(),
        "kind" to kind.name,
        "startsOn" to startsOn.toString(),
        "endsOn" to endsOn?.toString(),
        "weekdays" to weekdays.sorted().map { it.name },
        "active" to active,
    )

internal fun ScheduleEntry.auditState(): Map<String, Any?> =
    mapOf(
        "employeeId" to employeeId.toString(),
        "date" to date.toString(),
        "kind" to kind.name,
        "source" to source.name,
        "shiftId" to shiftId?.toString(),
        "departmentId" to departmentId.toString(),
        "patternId" to patternId?.toString(),
        "note" to note,
    )
