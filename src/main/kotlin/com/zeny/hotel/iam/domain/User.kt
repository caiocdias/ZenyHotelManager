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
@Suppress("TooManyFunctions") // operações do agregado: login, senha, PIN e aprovações, roles e estado ativo
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

    /** PIN errado seguido nas aprovações (RF-ACS-07); separado do login. */
    @Column(name = "failed_pin_count", nullable = false)
    var failedPinCount: Int = 0
        protected set

    /** Até quando as aprovações deste usuário ficam recusadas, mesmo com o PIN certo. */
    @Column(name = "pin_locked_until")
    var pinLockedUntil: Instant? = null
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

    /** Troca o nome exibido. */
    fun rename(
        name: String,
        now: Instant,
    ) {
        val checked = checkedName(name)
        if (checked != this.name) {
            this.name = checked
            updatedAt = now
        }
    }

    /**
     * Desativa o usuário (RF-ACS-10 e 12): ele não entra mais, perde as permissões e as sessões
     * dele são revogadas fora do agregado. Nunca é apagado. Devolve se mudou.
     */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa o usuário; as sessões revogadas não voltam. Devolve se mudou. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    /**
     * Senha nova definida pelo administrador: é provisória (o usuário a troca no próximo login)
     * e zera o bloqueio por tentativas.
     */
    fun resetPassword(
        newHash: String,
        now: Instant,
    ) {
        passwordHash = newHash
        passwordChangeRequired = true
        failedLoginCount = 0
        lockedUntil = null
        updatedAt = now
    }

    /** PIN novo (hash Argon2id) para as aprovações de superior (RF-ACS-01 e 07); zera o bloqueio do PIN. */
    fun resetPin(
        newHash: String,
        now: Instant,
    ) {
        pinHash = newHash
        failedPinCount = 0
        pinLockedUntil = null
        updatedAt = now
    }

    /** Se as aprovações deste usuário estão bloqueadas em [now] por PIN errado (o login não). */
    fun isPinLocked(now: Instant): Boolean = pinLockedUntil?.isAfter(now) == true

    /**
     * Registra um PIN errado numa aprovação e, a partir do limite da [policy], bloqueia as
     * aprovações deste usuário por tempo crescente. Não mexe no bloqueio do login.
     */
    fun recordFailedPin(
        now: Instant,
        policy: LockoutPolicy,
    ) {
        failedPinCount++
        policy.lockoutAfter(failedPinCount)?.let { pinLockedUntil = now.plus(it) }
        updatedAt = now
    }

    /** PIN certo: zera as falhas e o bloqueio do PIN. */
    fun recordSuccessfulPin(now: Instant) {
        if (failedPinCount != 0 || pinLockedUntil != null) {
            failedPinCount = 0
            pinLockedUntil = null
            updatedAt = now
        }
    }

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
        const val MAX_NAME_LENGTH = 200

        private fun checkedName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH) { "Nome de usuário inválido" }
            return trimmed
        }

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
            return User(
                id = id,
                login = login,
                createdAt = now,
                name = checkedName(name),
                passwordHash = passwordHash,
                passwordChangeRequired = passwordChangeRequired,
            )
        }
    }
}
