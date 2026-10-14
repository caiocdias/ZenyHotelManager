package com.zeny.hotel.iam.domain

import com.zeny.hotel.shared.UserId
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * Usuário do sistema (SPEC §8.2.1, RF-ACS-01). Guarda só os hashes da senha e do PIN
 * (Argon2id, calculados fora do domínio), nunca os valores. Nunca é apagado, só desativado
 * (RF-ACS-12). O controle de tentativas de login (bloqueio progressivo) fica aqui, com o
 * relógio e a política recebidos de fora. As roles atribuídas ([roleIds]) definem as permissões
 * efetivas (RF-ACS-04), calculadas fora do agregado.
 */
@Entity
@Table(name = "user_account", schema = "iam")
class User private constructor(
    @Id
    val id: UserId,
    /** Sempre a forma normalizada ([Logins.normalize]). */
    @Column(nullable = false, unique = true)
    val login: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    name: String,
    passwordHash: String,
    passwordChangeRequired: Boolean,
) {
    @Column(nullable = false)
    var name: String = name
        protected set

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = passwordHash
        protected set

    @Column(name = "pin_hash")
    var pinHash: String? = null
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "password_change_required", nullable = false)
    var passwordChangeRequired: Boolean = passwordChangeRequired
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Column(name = "failed_login_count", nullable = false)
    var failedLoginCount: Int = 0
        protected set

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_role", schema = "iam", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "role_id", nullable = false)
    @Convert(converter = RoleIdConverter::class)
    private val roleIdSet: MutableSet<RoleId> = mutableSetOf()

    /** As roles atribuídas ao usuário (RF-ACS-04). */
    val roleIds: Set<RoleId> get() = roleIdSet

    /** Atribui a role; devolve se ela ainda não estava atribuída. */
    fun assignRole(
        roleId: RoleId,
        now: Instant,
    ): Boolean = roleIdSet.add(roleId).also { if (it) updatedAt = now }

    /** Retira a role; devolve se ela estava atribuída. */
    fun unassignRole(
        roleId: RoleId,
        now: Instant,
    ): Boolean = roleIdSet.remove(roleId).also { if (it) updatedAt = now }

    /** Se o login está bloqueado em [now] (recusado mesmo com a senha certa). */
    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    /** Registra uma senha errada e, a partir do limite da [policy], bloqueia o login por tempo crescente. */
    fun recordFailedLogin(
        now: Instant,
        policy: LockoutPolicy,
    ) {
        failedLoginCount++
        policy.lockoutAfter(failedLoginCount)?.let { lockedUntil = now.plus(it) }
        updatedAt = now
    }

    /** Login bem-sucedido: zera as falhas e o bloqueio. */
    fun recordSuccessfulLogin(now: Instant) {
        if (failedLoginCount != 0 || lockedUntil != null) {
            failedLoginCount = 0
            lockedUntil = null
            updatedAt = now
        }
    }

    /** Troca o hash da senha (por exemplo, para parâmetros do Argon2 mais fortes), sem mudar a senha. */
    fun rehashPassword(
        newHash: String,
        now: Instant,
    ) {
        passwordHash = newHash
        updatedAt = now
    }

    /** Senha nova escolhida pelo próprio usuário: encerra a troca obrigatória. */
    fun changePassword(
        newHash: String,
        now: Instant,
    ) {
        passwordHash = newHash
        passwordChangeRequired = false
        updatedAt = now
    }

    companion object {
        /**
         * Usuário novo e ativo. [login] precisa estar normalizado; [passwordChangeRequired]
         * obriga a trocar a senha no primeiro login (administrador inicial, senha provisória).
         */
        @Suppress("LongParameterList")
        fun create(
            name: String,
            login: String,
            passwordHash: String,
            passwordChangeRequired: Boolean,
            now: Instant,
            id: UserId = UserId.new(),
        ): User {
            require(Logins.normalize(login) == login) { "O login precisa estar normalizado" }
            require(name.isNotBlank()) { "O nome do usuário é obrigatório" }
            return User(
                id = id,
                login = login,
                createdAt = now,
                name = name.trim(),
                passwordHash = passwordHash,
                passwordChangeRequired = passwordChangeRequired,
            )
        }
    }
}
