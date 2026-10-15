package com.zeny.hotel.staff.domain

import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** Os dados cadastrais de um colaborador (RF-PES-01), como chegam para criar ou editar. */
data class EmployeeDetails(
    val name: String,
    val position: String,
    val phone: String?,
    val email: String?,
    val departmentId: DepartmentId,
)

/**
 * Colaborador (RF-PES-01, SPEC §8.2.2): nome, cargo, contato, setor e o vínculo opcional com um
 * usuário (H7; um usuário vincula no máximo um colaborador, o banco garante). Nunca é apagado, só
 * desativado (RF-ACS-12); o banco recusa a exclusão.
 */
@Entity
@Table(name = "employee", schema = "staff")
class Employee private constructor(
    @Id
    val id: EmployeeId,
    details: EmployeeDetails,
    userId: UserId?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(nullable = false)
    var position: String = ""
        protected set

    var phone: String? = null
        protected set

    var email: String? = null
        protected set

    @Column(name = "department_id", nullable = false)
    var departmentId: DepartmentId = details.departmentId
        protected set

    @Column(name = "user_id")
    private var userIdValue: UUID? = userId?.value

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    init {
        apply(details)
    }

    /** O usuário vinculado, se houver. */
    val userId: UserId? get() = userIdValue?.let(::UserId)

    /** Troca os dados cadastrais; devolve se algo mudou. */
    fun update(
        details: EmployeeDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    /** Troca o usuário vinculado (ou o retira, com `null`); devolve se mudou. A validação é do caso de uso. */
    fun linkUser(
        userId: UserId?,
        now: Instant,
    ): Boolean {
        if (userId?.value == userIdValue) return false
        userIdValue = userId?.value
        updatedAt = now
        return true
    }

    /** Desativa; devolve se mudou. O histórico (escala, vínculo) fica. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    private fun apply(details: EmployeeDetails): Boolean {
        val name = StaffRules.required("name", details.name, MAX_NAME_LENGTH)
        val position = StaffRules.required("position", details.position, MAX_POSITION_LENGTH)
        val phone = StaffRules.optional("phone", details.phone, MAX_PHONE_LENGTH)
        val email = StaffRules.optional("email", details.email, MAX_EMAIL_LENGTH)
        val changed =
            name != this.name ||
                position != this.position ||
                phone != this.phone ||
                email != this.email ||
                details.departmentId != departmentId
        this.name = name
        this.position = position
        this.phone = phone
        this.email = email
        departmentId = details.departmentId
        return changed
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_POSITION_LENGTH = 80
        const val MAX_PHONE_LENGTH = 30
        const val MAX_EMAIL_LENGTH = 254

        fun create(
            details: EmployeeDetails,
            userId: UserId?,
            now: Instant,
        ): Employee = Employee(EmployeeId.new(), details, userId, now)
    }
}
