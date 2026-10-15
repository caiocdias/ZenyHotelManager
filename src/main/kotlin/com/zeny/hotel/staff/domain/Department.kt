package com.zeny.hotel.staff.domain

import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentKey
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * Setor (RF-PES-01, H6): os seis da SPEC §5.3 vêm da migração, com a [key] que os identifica para os
 * outros módulos; o administrador os renomeia e cria outros. Setor nunca é apagado (o banco recusa).
 */
@Entity
@Table(name = "department", schema = "staff")
class Department private constructor(
    @Id
    val id: DepartmentId,
    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", updatable = false)
    val key: DepartmentKey?,
    name: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var name: String = StaffRules.required("name", name, MAX_NAME_LENGTH)
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Troca o nome; devolve se mudou. */
    fun rename(
        name: String,
        now: Instant,
    ): Boolean {
        val valid = StaffRules.required("name", name, MAX_NAME_LENGTH)
        if (valid == this.name) return false
        this.name = valid
        updatedAt = now
        return true
    }

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun create(
            name: String,
            now: Instant,
        ): Department = Department(DepartmentId.new(), null, name, now)
    }
}
